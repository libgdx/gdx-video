/*******************************************************************************
 * Copyright 2014 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

#include "VideoDecoder.h"

#include <cmath>
#include <cstring>
#include <iterator>
#include <stdexcept>

#include <pthread.h>

static int readFunction(void* opaque, uint8_t* buffer, int bufferSize)
{
    VideoDecoder* decoder = (VideoDecoder*) opaque;

    //Call implemented function
    return decoder->getFillBufferFunc()(decoder->getCustomFuncData(), buffer, bufferSize);
}

static int64_t seekFunction(void* opaque, int64_t offset, int whence)
{
    VideoDecoder* decoder = (VideoDecoder*) opaque;

    return decoder->getSeekBufferFunc()(decoder->getCustomFuncData(), offset, whence & ~AVSEEK_FORCE);
}

static int interruptFunction(void* opaque)
{
    VideoDecoder* decoder = (VideoDecoder*) opaque;

    return decoder->isInterruptRequested() ? 1 : 0;
}

VideoDecoder::VideoDecoder() : decodeCondvar(decodeMutex) {
    fileLoaded = false;
    videoOutputEnded = false;
    audioOutputEnded = false;
    stopRequested = false;
    rewindRequested = false;
    prefetchRequested = false;
    prefetchPending = false;
    reopening = false;

    avioContext = nullptr;
    formatContext = NULL;
    videoCodecContext = NULL;
    audioCodecContext = NULL;
    audioCodecParameters = NULL;

    videoCodec = NULL;
    audioCodec = NULL;
    swsContext = NULL;
    swrContext = NULL;

    frame = av_frame_alloc();
    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        rgbFrames[i] = av_frame_alloc();
        rgbFrames[i]->pts = 0;
    }
    memset(audioBuffer, 0, VIDEOPLAYER_AUDIO_BUFFER_SIZE);
    audioFrame = av_frame_alloc();
    audioDecodingBuffer = NULL;
    audioDecodedSize = 0;
    audioDecodedUsed = 0;
    currentFrameDisplayed = 0;
    totalFramesBuffered = 0;

    customFuncData = nullptr;
    fillBufferFunc = nullptr;
    seekBufferFunc = nullptr;
    cleanupFunc = nullptr;

    timestampOffset = 0;
    videoFrameRate = av_make_q(0, 1);
    loadedAudioStreamIndex = -1;
    audioDecoderNeedsReopen = true;

    looping = false;
    loopDuration = 0;
    audioCutSamples = 0;
    audioSampleRate = 0;
    pendingAudioLoopMarkers = 0;
    resetLoopState();
}

void VideoDecoder::resetPacketRange() {
    videoPacketsEnd = 0;
    audioPacketsEnd = 0;
    videoPacketsStart = HUGE_VAL;
    audioPacketsStart = HUGE_VAL;
    lastPacketEnd = 0;
    endOfFile = false;
    fileEnded = false;
}

void VideoDecoder::resetLoopState() {
    resetPacketRange();
    videoLoop = 0;
    audioFinalLoop = false;
    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        frameLoops[i] = 0;
    }
    videoLoopPending = false;
    audioLoopPending = false;
    audioLoopSamples = 0;
    audioSilenceSamples = 0;
    audioSkipSamples = 0;
    audioLoop = 0;
    audioLoopDrained = false;
    audioLoopSampleCarry = 0;
    lastAudioPop = av_gettime_relative();
    for(int i = 0; i < VIDEOPLAYER_LOOP_OFFSETS; i++) {
        loopOffsetBase[i] = 0;
    }
}

void VideoDecoder::setLooping(bool looping) {
    this->looping = looping;
}

static bool isLoopMarker(AVPacket* packet) {
    return packet != NULL && packet->stream_index < 0;
}

AVPacket* VideoDecoder::newLoopMarker() {
    AVPacket* marker = av_packet_alloc();
    marker->stream_index = -1;
    return marker;
}

VideoDecoder::~VideoDecoder() {
    // Wake up and stop decoding thread
    decodeMutex.lock();
    stopRequested = true;
    decodeCondvar.signal();
    decodeMutex.unlock();
    join();

    if(!fileLoaded && cleanupFunc != NULL) {
        // Loading failed, so the decoding thread, which cleans up otherwise, never started
        cleanupFunc(customFuncData);
    }

    //Take care of cleanup
    if(swrContext != NULL) {
        swr_free(&swrContext);
    }

    if(swsContext != NULL) {
        sws_freeContext(swsContext);
        swsContext = NULL;
    }

    if(audioCodecContext != NULL) {
        avcodec_free_context(&audioCodecContext);
    }
    avcodec_parameters_free(&audioCodecParameters);
    if(videoCodecContext != NULL) {
        avcodec_free_context(&videoCodecContext);
    }

    av_frame_free(&audioFrame);
    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        av_frame_free(rgbFrames + i);
    }
    av_frame_free(&frame);
    clearPackets();
    avformat_close_input(&formatContext);
    av_freep(&audioDecodingBuffer);
    if(avioContext != nullptr) {
        av_freep(&avioContext->buffer);
        av_freep(&avioContext);
    }
}

void VideoDecoder::loadFile(char* filename, VideoBufferInfo *bufferInfo) {
    if(fileLoaded) {
        logError("[VideoPlayer::loadFile] Tried to load a new file. Ignoring...\n");
        return;
    }
    if(filename == NULL || strcmp(filename, "")==0) {
        logError("[VideoPlayer::loadFile] Invalid arguments supplied!\n");
        throw std::invalid_argument("Filename should not be empty!");
    }

    //Try to open the file
    int err = avformat_open_input(&formatContext, filename, NULL, NULL);
    if(err < 0) {
        char error[1024];
        av_strerror(err, error, 1024);
        logError("[VideoPlayer::loadFile] Error opening file (%s): %s\n", filename, error);
        throw std::runtime_error("Could not open file!");
    }

    if(debugLoggingActive) {
        av_dump_format(formatContext, 0, filename, 0);
    }
    loadContainer(bufferInfo);
}

void VideoDecoder::loadStream(FillFileBufferFunc fillFunc, SeekFileBufferFunc seekFunc, bool seekable, void* funcData, CleanupFunc cleanFunc, VideoBufferInfo* bufferInfo) {
    if(fileLoaded) {
        logError("[VideoPlayer::loadFile] Tried to load a new file. Ignoring...\n");
        if(cleanFunc != NULL) {
            cleanFunc(funcData);
        }
        return;
    }

    if(fillFunc == NULL) {
        logError("[VideoPlayer::loadFile] Invalid arguments supplied!\n");
        throw std::invalid_argument("FillFileBufferFunc should be a valid function");
    }

    fillBufferFunc = fillFunc;
    seekBufferFunc = seekFunc;
    customFuncData = funcData;
    cleanupFunc = cleanFunc;

    uint8_t *avioBuffer = (uint8_t *)av_malloc(CUSTOMIO_BUFFER_SIZE);
    avioContext = avio_alloc_context(avioBuffer, CUSTOMIO_BUFFER_SIZE, 0, (void*)this, &readFunction, NULL, seekFunc != NULL ? &seekFunction : NULL);
    // Without this flag, ffmpeg only seeks when explicitly asked to (e.g. when rewinding)
    avioContext->seekable = seekable ? AVIO_SEEKABLE_NORMAL : 0;

    formatContext = avformat_alloc_context();
    formatContext->pb = avioContext;

    int err = avformat_open_input(&formatContext, "<stream>", NULL, NULL);
    if(err < 0) {
        char error[1024];
        av_strerror(err, error, 1024);
        logError("[VideoPlayer::loadFile] Error opening file: %s\n", error);
        throw std::runtime_error("Could not open file!");
    }
    loadContainer(bufferInfo);
}

void VideoDecoder::loadContainer(VideoBufferInfo* bufferInfo) {
    currentFrameDisplayed = 0;
    totalFramesBuffered = 0;

    if (avformat_find_stream_info(formatContext, NULL) < 0) {
        logError("[VideoPlayer::loadFile] Could not find stream info!\n");
        throw std::runtime_error("Could not find stream info!");
    }

    videoStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, &videoCodec, 0);
    audioStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_AUDIO, -1, -1, &audioCodec, 0);
    loadedAudioStreamIndex = audioStreamIndex;

    if(!videoCodec) {
        logError("[VideoPlayer::loadFile] Could not find video stream!\n");
        throw std::runtime_error("Could not find any video stream");
    } else {
        logDebug("[VideoPlayer::loadFile] video stream found [index=%d]\n", videoStreamIndex);
    }

    if(!audioCodec) {
        logError("[VideoPlayer::loadFile] Could not find audio stream!\n");
    } else {
        logDebug("[VideoPlayer::loadFile] audio stream found [index=%d]\n", audioStreamIndex);
    }

    // Load timeBase
    AVStream *videoStream = formatContext->streams[videoStreamIndex];
    AVRational streamTimeBase = videoStream->time_base;
    timeBase = ((double)streamTimeBase.num / (double)streamTimeBase.den);
    // Kept, as it's only known after avformat_find_stream_info, which is skipped when opening the file again
    videoFrameRate = videoStream->avg_frame_rate;

    // Initialize video decoder
    videoCodecContext = avcodec_alloc_context3(videoCodec);
    avcodec_parameters_to_context(videoCodecContext, videoStream->codecpar);

    if(avcodec_open2(videoCodecContext, videoCodec, NULL) < 0) {
        logError("[VideoPlayer::loadFile] Could not open video decoder!\n");
        throw std::runtime_error("Could not open video decoder!");
    }

    // Initialize audio decoder
    if(audioStreamIndex >= 0) {
        audioCodecContext = avcodec_alloc_context3(audioCodec);

        AVStream *audioStream = formatContext->streams[audioStreamIndex];
        avcodec_parameters_to_context(audioCodecContext, audioStream->codecpar);
        audioCodecParameters = avcodec_parameters_alloc();
        avcodec_parameters_copy(audioCodecParameters, audioStream->codecpar);

        bufferInfo->audioSampleRate = audioCodecContext->sample_rate;
        audioSampleRate = audioCodecContext->sample_rate;

        if(avcodec_open2(audioCodecContext, audioCodec, NULL) < 0) {
            logError("[VideoPlayer::loadFile] Could not open audio decoder!\n");
            throw std::runtime_error("Could not open audio decoder!");
        }
        // Decoders that handle an encoder delay themselves only set that up when being opened
        audioDecoderNeedsReopen = audioCodec->id == AV_CODEC_ID_OPUS || audioCodecContext->delay > 0 ||
                                  audioCodecParameters->initial_padding > 0;
        bufferInfo->audioBuffer = this->audioBuffer;
        bufferInfo->audioBufferSize = VIDEOPLAYER_AUDIO_BUFFER_SIZE;

        // Setup conversion context, which will convert our audio to the right output format
        AVChannelLayout avch = AV_CHANNEL_LAYOUT_STEREO;
        av_channel_layout_copy(&audioChannelLayout, &avch);
        audioSampleFormat = AV_SAMPLE_FMT_S16;
        // The resampler always outputs stereo, whatever the source has
        bufferInfo->audioChannels = audioChannelLayout.nb_channels;

        logDebug("[VideoPlayer::loadFile] Loading audio resampler ...\n");
        swr_alloc_set_opts2(
            &swrContext,
            /* out */ &avch, audioSampleFormat, bufferInfo->audioSampleRate,
            /*  in */ &audioCodecContext->ch_layout, audioCodecContext->sample_fmt, bufferInfo->audioSampleRate,
            0, NULL
        );
        swr_init(swrContext);

        //Calculate how much seconds a single kb block is (1024 bytes): blockSize / bytesPerSample / channels / sampleRate
        secPerKbBlock = 1024.0 / av_get_bytes_per_sample(audioSampleFormat) / (double)audioChannelLayout.nb_channels /
                        (double)audioCodecContext->sample_rate;
    }

    int width = videoCodecContext->width;
    int height = videoCodecContext->height;

    logDebug("[VideoPlayer::loadFile] Loading video scaler ...\n");

    swsContext = sws_getContext(
        width, height, videoCodecContext->pix_fmt, // src
        width, height, AV_PIX_FMT_RGB24,           // dst
        SWS_BILINEAR, NULL, NULL, NULL
    );

    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        AVFrame * frame = rgbFrames[i];
        frame->width = width;
        frame->height = height;
        frame->format = AV_PIX_FMT_RGB24;
        av_frame_get_buffer(frame, 0);
    }

    videoFrameSize = rgbFrames[0]->buf[0]->size;
    int lineSize = rgbFrames[0]->linesize[0];

    bufferInfo->videoBuffer = rgbFrames[0]->data[0];
    bufferInfo->videoBufferSize = videoFrameSize;
    bufferInfo->videoBufferWidth = lineSize / 3;
    bufferInfo->videoWidth = width;
    bufferInfo->videoHeight = height;

    fileLoaded = true;

    logDebug("[VideoPlayer::loadFile] Starting decoding thread ...\n");

    this->start();
}

int VideoDecoder::getReadIndex() {
    return currentFrameDisplayed % VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES;
}

int VideoDecoder::getWriteIndex() {
    return (totalFramesBuffered + 1) % VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES;
}

int VideoDecoder::getNumBuffered() {
    __sync_synchronize();
    return totalFramesBuffered - currentFrameDisplayed;
}

u_int8_t* VideoDecoder::nextVideoFrame() {
    if(!hasFrameBuffered()) {
        if(videoOutputEnded || totalFramesBuffered == 0) return NULL;
        logDebug("[VideoPlayer::nextVideoFrame] no new frame available yet!\n");
    } else {
        decodeMutex.lock();
        __sync_add_and_fetch(&currentFrameDisplayed, 1);
        decodeCondvar.signal();
        decodeMutex.unlock();
    }

    return rgbFrames[getReadIndex()]->data[0];
}

void VideoDecoder::updateAudioBuffer() {
    audioMutex.lock();
    int sizeLeft = VIDEOPLAYER_AUDIO_BUFFER_SIZE;

    // Try getting enough data, to fill the buffer.
    while(sizeLeft > 0) {
        //If there is no decoded data left, decode new frame
        if(audioDecodedUsed >= audioDecodedSize) {
            audioDecodedUsed = 0;

            int buf_samples = 64000; // enough, hopefully
            if(audioDecodingBuffer == NULL) {
                av_samples_alloc(
                    &audioDecodingBuffer, NULL,
                    audioChannelLayout.nb_channels, buf_samples, audioSampleFormat,
                    1
                );
            }
            int size = decodeAudio(audioDecodingBuffer, buf_samples);
            if(size <= 0) {
                //Play silence
                audioDecodedSize = 1024;
                memset(audioDecodingBuffer, 0, audioDecodedSize);

                //Set an offset for the video, so that audio won't be behind
                if(!audioOutputEnded) {
                    timestampOffset += secPerKbBlock;
                }
            } else {
                audioDecodedSize = size;
            }
        }

        //Copy data into the buffer
        int lengthToCopy = audioDecodedSize - audioDecodedUsed;
        //Make sure we never copy more than the buffer
        if(lengthToCopy > sizeLeft) {
            lengthToCopy = sizeLeft;
        }

        memcpy(audioBuffer + (VIDEOPLAYER_AUDIO_BUFFER_SIZE - sizeLeft), audioDecodingBuffer + audioDecodedUsed, lengthToCopy);
        sizeLeft -= lengthToCopy;
        audioDecodedUsed += lengthToCopy;
    }
    audioMutex.unlock();
}

int VideoDecoder::decodeAudio(void* decodingBuffer, int buf_samples) {
    int bytesPerSample = av_get_bytes_per_sample(audioSampleFormat) * audioChannelLayout.nb_channels;
    while(!audioOutputEnded) {
        if(audioSilenceSamples > 0) {
            int samples = (int)FFMIN(audioSilenceSamples, (int64_t)buf_samples);
            audioSilenceSamples -= samples;
            memset(decodingBuffer, 0, samples * bytesPerSample);
            return samples * bytesPerSample;
        }

        int ret = avcodec_receive_frame(audioCodecContext, audioFrame);
        if(ret == AVERROR(EAGAIN)) {
            // Get a new packet and send to decoder
            bool check = true;
            bool waitForLoop = false;
            AVPacket * audioPacket = NULL;

            while(!audioPacket && check) {
                packetMutex.lock();
                if(audioPackets.empty()) {
                    // Usually the decoding thread has read ahead already, so this only reads from the file when it fell
                    // behind, or playback is starting
                    check = readPacket(false);
                    // The decoding thread opens the file again for the next loop
                    waitForLoop = !check && canLoopAtEnd();
                } else {
                    audioPacket = audioPackets.back();
                    audioPackets.pop_back();
                    lastAudioPop = av_gettime_relative();
                    if(isLoopMarker(audioPacket)) {
                        pendingAudioLoopMarkers--;
                    } else {
                        double end = getPacketTime(audioPacket, true);
                        // Only wake up the decoding thread once the queue got shorter or the file isn't read far enough
                        // ahead anymore, it then reads a batch
                        if(needsPrefetch() &&
                           (endOfFile || audioPackets.size() < VIDEOPLAYER_AUDIO_PREFETCH_PACKETS / 2 ||
                            lastPacketEnd < end + VIDEOPLAYER_AUDIO_PREFETCH_TIME)) {
                            requestPrefetch();
                        }
                        // Whether this is the end of the loop has to be known before audio beyond the end of the video
                        // would be decoded. Usually the decoding thread has read far enough ahead already, otherwise the
                        // little that's missing is read here.
                        while(needsAudioLookahead(end) && readPacket(false)) {}
                    }
                }
                // No loop follows once the file ended or looping got disabled, unless the audio is still in an earlier
                // one or the next one is already being opened. A popped marker is followed by the rest of its loop.
                bool nextLoopFollows = pendingAudioLoopMarkers > 0 || reopening || (looping && !fileEnded);
                audioFinalLoop = !nextLoopFollows && !isLoopMarker(audioPacket);
                packetMutex.unlock();
            }

            if(waitForLoop) {
                if(!audioLoopDrained) {
                    // All packets of this loop have been sent, so the rest of it is taken out of the decoder while
                    // the next loop is being opened. Otherwise silence would be played before it.
                    audioLoopDrained = true;
                    audioLoopPending = true;
                    avcodec_send_packet(audioCodecContext, NULL);
                    continue;
                }
                // Play silence until the next loop is available
                return 0;
            }
            if(isLoopMarker(audioPacket)) {
                av_packet_free(&audioPacket);
                if(audioLoopDrained) {
                    // The decoder has been drained and restarted already
                    audioLoopDrained = false;
                    enterNextAudioLoop();
                    continue;
                }
                // Get the remaining audio of this loop out of the decoder first
                audioLoopPending = true;
            }
            avcodec_send_packet(audioCodecContext, audioPacket);
            av_packet_free(&audioPacket);
            continue;
        }
        if(ret == AVERROR_EOF && audioLoopPending) {
            audioLoopPending = false;
            if(!audioLoopDrained) {
                // Otherwise that's done once the marker of the next loop arrives
                enterNextAudioLoop();
            }
            if(!restartAudioDecoder()) {
                endAudioOutput();
                continue;
            }
            // Make the audio of each loop exactly as long as the loop, so it stays in sync with the video
            // The fraction of a sample left by rounding is carried over, so it doesn't add up over many loops
            double loopSamples = loopDuration * audioSampleRate + audioLoopSampleCarry;
            int64_t roundedLoopSamples = llround(loopSamples);
            audioLoopSampleCarry = loopSamples - roundedLoopSamples;
            int64_t missing = roundedLoopSamples - audioLoopSamples;
            if(missing > 0) {
                audioSilenceSamples = missing;
            } else {
                audioSkipSamples = -missing;
            }
            audioLoopSamples = 0;
            continue;
        }
        if(ret != 0) {
            // Either decoding error or EOF
            logDebug("[VideoDecoder::run] Finished decoding the audio stream.\n");
            endAudioOutput();
            continue;
        }

        // got frame
        int max_samples = swr_get_out_samples(swrContext, audioFrame->nb_samples);
        int out_samples = 0;
        if(max_samples > 0) {
            out_samples = swr_convert(
                swrContext,
                (u_int8_t**)&decodingBuffer, buf_samples,
                (uint8_t const **)audioFrame->extended_data, audioFrame->nb_samples // in
            );
        }

        // The audio is only cut when the next loop follows right after it
        if(audioCutSamples > 0 && !audioFinalLoop) {
            if(audioLoopSamples + out_samples > audioCutSamples) {
                out_samples = (int)FFMAX(0, audioCutSamples - audioLoopSamples);
                if(out_samples == 0) {
                    continue;
                }
            }
        }
        audioLoopSamples += out_samples;
        if(audioSkipSamples > 0) {
            int skip = (int)FFMIN(audioSkipSamples, (int64_t)out_samples);
            audioSkipSamples -= skip;
            out_samples -= skip;
            memmove(decodingBuffer, (uint8_t*)decodingBuffer + skip * bytesPerSample, out_samples * bytesPerSample);
            if(out_samples == 0) {
                continue;
            }
        }

        int size = av_samples_get_buffer_size(
            NULL, audioChannelLayout.nb_channels, out_samples, audioSampleFormat,
            1
        );

        //Return copied size
        return size;
    }
    return 0;
}

double VideoDecoder::getCurrentFrameTimestamp() {
    int index = getReadIndex();
    return rgbFrames[index]->pts * timeBase + frameLoops[index] * loopDuration + timestampOffset;
}

void VideoDecoder::enterNextAudioLoop() {
    // The silence played until here belongs to the loop that ended, so the next one starts at 0 again
    int nextLoop = audioLoop + 1;
    loopOffsetBase[nextLoop % VIDEOPLAYER_LOOP_OFFSETS] = timestampOffset;
    __sync_synchronize();
    audioLoop = nextLoop;
}

double VideoDecoder::getCurrentFramePosition() {
    int index = getReadIndex();
    int loop = frameLoops[index];
    int currentAudioLoop = audioLoop;
    __sync_synchronize();
    // Only the offset added during the frame's loop counts. If the audio isn't in that loop yet (or there is no audio),
    // nothing has been added during it.
    double loopOffset = 0;
    if(loop == currentAudioLoop) {
        loopOffset = timestampOffset - loopOffsetBase[loop % VIDEOPLAYER_LOOP_OFFSETS];
    } else if(loop < currentAudioLoop && currentAudioLoop - loop < VIDEOPLAYER_LOOP_OFFSETS) {
        // The audio is in a later loop already, so the offset of the frame's loop is final
        loopOffset = loopOffsetBase[(loop + 1) % VIDEOPLAYER_LOOP_OFFSETS] - loopOffsetBase[loop % VIDEOPLAYER_LOOP_OFFSETS];
    }
    return rgbFrames[index]->pts * timeBase + loopOffset;
}

int VideoDecoder::getCurrentFrameLoop() {
    return frameLoops[getReadIndex()];
}

bool VideoDecoder::readPacket(bool allowReopen) {
    if(formatContext == NULL || reopening || fileEnded) {
        return false;
    }
    AVPacket *packet = av_packet_alloc();
    int ret = av_read_frame(formatContext, packet);
    if(ret >= 0) {
        bool hasTimestamp = packet->pts != AV_NOPTS_VALUE || packet->dts != AV_NOPTS_VALUE;
        if(hasTimestamp && (packet->stream_index == videoStreamIndex || packet->stream_index == audioStreamIndex)) {
            lastPacketEnd = getPacketTime(packet, true);
        }
        // Packets without a timestamp don't tell where the streams start or end
        if(packet->stream_index==videoStreamIndex) {
            if(hasTimestamp) {
                videoPacketsStart = FFMIN(videoPacketsStart, getPacketTime(packet, false));
                videoPacketsEnd = FFMAX(videoPacketsEnd, lastPacketEnd);
            }
            videoPackets.push_front(packet);
        } else if(packet->stream_index == audioStreamIndex) {
            if(hasTimestamp) {
                audioPacketsStart = FFMIN(audioPacketsStart, getPacketTime(packet, false));
                audioPacketsEnd = FFMAX(audioPacketsEnd, lastPacketEnd);
            }
            queueAudioPacket(packet);
        } else {
            av_packet_free(&packet);
        }
        return true;
    }
    av_packet_free(&packet);
    endOfFile = true;

    AVIOContext* pb = formatContext->pb;
    if(ret != AVERROR_EOF && !(pb != NULL && avio_feof(pb) && pb->error == 0)) {
        // A read error isn't the end of the video, so it must not start the next loop. Playback ends here.
        char error[1024];
        av_strerror(ret, error, 1024);
        logError("[VideoDecoder::readPacket] Error reading packet: %s\n", error);
        fileEnded = true;
        return false;
    }

    // Decided once, as looping may be toggled meanwhile
    bool loop = canLoopAtEnd();
    if(loop && loopDuration <= 0) {
        // The whole file has been read, so the duration of a loop is known now. It's fixed from here on, so the
        // timestamps of the frames don't change. The first frame of the next loop follows right after the end of
        // the longer stream.
        double loopStart = FFMAX(0, FFMIN(videoPacketsStart, audioPacketsStart));
        double loopEnd = videoPacketsEnd;
        if(audioSampleRate > 0 && audioPacketsEnd > 0) {
            if(audioPacketsEnd < videoPacketsEnd + VIDEOPLAYER_MAX_AUDIO_PADDING) {
                // The decoded audio starts with the first audio packet, so the end of the video is relative to that
                audioCutSamples = llround((videoPacketsEnd - FFMAX(0, audioPacketsStart)) * audioSampleRate);
            } else {
                loopEnd = audioPacketsEnd;
            }
        }
        loopDuration = loopEnd - loopStart;
    }

    // When looping, continue with the start of the file. The decoders find a marker in their queues where the next
    // loop starts.
    if(!loop) {
        // Remembered, so audio and video both end here, even if looping gets enabled before the other one gets here
        fileEnded = true;
        logDebug("[VideoDecoder::readPacket] No more packets available.\n");
        return false;
    }
    if(!allowReopen) {
        // Opening the file again is left to the decoding thread, so the thread playing the audio isn't blocked by it
        requestPrefetch();
        return false;
    }
    // Opening the file again can take a while, so the packets are unlocked meanwhile. The audio can continue with the
    // packets that are already queued, nothing reads from the file until it's done.
    reopening = true;
    packetMutex.unlock();
    AVFormatContext* newContext = openContainerAgain();
    packetMutex.lock();
    reopening = false;
    if(!swapContainer(newContext)) {
        return false;
    }
    logDebug("[VideoDecoder::readPacket] Reached the end, looping.\n");
    resetPacketRange();
    videoPackets.push_front(newLoopMarker());
    if(audioStreamIndex >= 0 && !audioOutputEnded) {
        audioPackets.push_front(newLoopMarker());
        pendingAudioLoopMarkers++;
    }
    return true;
}

void VideoDecoder::queueAudioPacket(AVPacket* packet) {
    if(audioOutputEnded) {
        // Nothing decodes the audio anymore
        av_packet_free(&packet);
        return;
    }
    audioPackets.push_front(packet);
    if(audioPackets.size() > VIDEOPLAYER_MAX_AUDIO_PACKETS &&
       av_gettime_relative() - lastAudioPop > VIDEOPLAYER_AUDIO_STALL_TIME) {
        // Nothing seems to play the audio (e.g. there is no audio device), so the oldest packet is dropped. Otherwise
        // the queue would grow for as long as the video plays. Loop markers are kept, the loops are counted by them.
        for(std::list<AVPacket*>::reverse_iterator it = audioPackets.rbegin(); it != audioPackets.rend(); ++it) {
            if(!isLoopMarker(*it)) {
                AVPacket* oldest = *it;
                audioPackets.erase(std::next(it).base());
                av_packet_free(&oldest);
                break;
            }
        }
    }
}

void VideoDecoder::disableAudio() {
    packetMutex.lock();
    // Packets of the audio stream are dropped from now on
    audioStreamIndex = -1;
    clearAudioPackets();
    packetMutex.unlock();
}

bool VideoDecoder::canLoopAtEnd() {
    // Once reopening the file started, the next loop follows, even if looping gets disabled meanwhile
    return reopening ||
           (!fileEnded && looping && videoPacketsEnd > 0 && avioContext != nullptr && formatContext != NULL);
}

void VideoDecoder::endAudioOutput() {
    // Set while holding the packets, so the decoding thread doesn't queue anything for the audio anymore
    packetMutex.lock();
    audioOutputEnded = true;
    clearAudioPackets();
    packetMutex.unlock();
}

double VideoDecoder::getPacketTime(AVPacket* packet, bool end) {
    if(formatContext == NULL) {
        // The container got closed, as reopening it for the next loop failed
        return 0;
    }
    AVStream* stream = formatContext->streams[packet->stream_index];
    int64_t timestamp = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
    if(timestamp == AV_NOPTS_VALUE) {
        return 0;
    }
    if(!end) {
        return timestamp * av_q2d(stream->time_base);
    }
    int64_t duration = packet->duration;
    // The parameters from loading the file are used, as they may be missing after opening it again for the next loop
    if(duration <= 0 && stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
        if(audioCodecParameters != NULL && audioCodecParameters->frame_size > 0 && audioCodecParameters->sample_rate > 0) {
            duration = av_rescale_q(audioCodecParameters->frame_size, av_make_q(1, audioCodecParameters->sample_rate),
                                    stream->time_base);
        }
    } else if(duration <= 0 && videoFrameRate.num > 0) {
        duration = av_rescale_q(1, av_inv_q(videoFrameRate), stream->time_base);
    }
    return (timestamp + duration) * av_q2d(stream->time_base);
}

bool VideoDecoder::needsAudioLookahead(double poppedPacketEnd) {
    if(!looping || formatContext == NULL || endOfFile || fileEnded || reopening ||
       audioPackets.size() >= VIDEOPLAYER_AUDIO_PREFETCH_PACKETS || pendingAudioLoopMarkers > 0) {
        return false;
    }
    // Only a short time of the file has to be read ahead, which is enough to know whether this is the end of the loop.
    // Packets are interleaved by time, so this doesn't need much even if there is no more audio.
    return lastPacketEnd < poppedPacketEnd + VIDEOPLAYER_MAX_AUDIO_PADDING;
}

bool VideoDecoder::needsPrefetch() {
    // Read ahead for the audio in any case, so it rarely has to read from the file itself
    if(formatContext == NULL || audioStreamIndex < 0 || audioOutputEnded || fileEnded || reopening) {
        return false;
    }
    // At the end of the file, reading starts the next loop (or ends the file), which the audio may be waiting for.
    // Otherwise, only a limited amount is read ahead.
    return endOfFile || (audioPackets.size() < VIDEOPLAYER_AUDIO_PREFETCH_PACKETS &&
                         videoPackets.size() < VIDEOPLAYER_MAX_VIDEO_PACKETS);
}

void VideoDecoder::requestPrefetch() {
    if(prefetchPending) {
        // The decoding thread hasn't got to it yet
        return;
    }
    prefetchPending = true;
    decodeMutex.lock();
    prefetchRequested = true;
    decodeCondvar.signal();
    decodeMutex.unlock();
}

void VideoDecoder::prefetchPackets() {
    // Reads until the next loop has started, if the end of the file is near. The packets are locked per packet, so the
    // audio doesn't wait for the whole batch.
    packetMutex.lock();
    prefetchPending = false;
    packetMutex.unlock();
    bool read = true;
    // Stops early when the decoder is disposed or rewound, which wait for this
    while(read && !stopRequested && !rewindRequested) {
        packetMutex.lock();
        read = needsPrefetch() && readPacket(true);
        packetMutex.unlock();
    }
}

void VideoDecoder::decodeVideoFrame() {
    int ret = avcodec_receive_frame(videoCodecContext, frame);
    if(ret == AVERROR(EAGAIN)) {
        // Get a new packet and send to decoder
        bool check = true;
        AVPacket * videoPacket = NULL;

        while(!videoPacket && check) {
            packetMutex.lock();
            if(videoPackets.empty()) {
                check = readPacket(true);
            } else {
                videoPacket = videoPackets.back();
                videoPackets.pop_back();
            }
            packetMutex.unlock();
        }

        if(isLoopMarker(videoPacket)) {
            // Get the remaining frames of this loop out of the decoder first. If playback doesn't continue, the
            // video ends here.
            videoLoopPending = true;
            av_packet_free(&videoPacket);
        }
        avcodec_send_packet(videoCodecContext, videoPacket);
        av_packet_free(&videoPacket);
        return;
    }
    if(ret == AVERROR_EOF && videoLoopPending) {
        videoLoopPending = false;
        avcodec_flush_buffers(videoCodecContext);
        videoLoop++;
        return;
    }
    if(ret != 0) {
        // Either decoding error or EOF
        logDebug("[VideoDecoder::run] Finished decoding the video stream.\n");
        videoOutputEnded = true;
        return;
    }

    // Got a new frame, so scale it into an RGB frame
    int writeIndex = getWriteIndex();
    AVFrame * dst = rgbFrames[writeIndex];
    sws_scale(
        swsContext,
        (uint8_t const * const *)frame->data, frame->linesize, 0, videoCodecContext->height,
        dst->data, dst->linesize
    );
    dst->pts = frame->pts;
    frameLoops[writeIndex] = videoLoop;

    //Atomic increment of totalFramesBuffered
    __sync_add_and_fetch(&totalFramesBuffered, 1);
}

void VideoDecoder::run() {
    decodeMutex.lock();
    while(!stopRequested) {
        bool prefetch = prefetchRequested;
        prefetchRequested = false;
        bool decode = !isBuffered() && !videoOutputEnded;
        if(!prefetch && !decode) {
            // Wait for signal that new frames or packets are requested, the video got rewound or decoding should stop
            decodeCondvar.wait();
            continue;
        }
        decodeMutex.unlock();

        if(prefetch) {
            // The video lock keeps rewind() from touching the file while it's being opened again
            videoMutex.lock();
            prefetchPackets();
            videoMutex.unlock();
        }
        if(decode) {
            videoMutex.lock();
            decodeVideoFrame();
            videoMutex.unlock();
        }

        decodeMutex.lock();
    }
    decodeMutex.unlock();

    if(cleanupFunc != NULL) {
        cleanupFunc(customFuncData);
    }
}

void VideoDecoder::clearPackets() {
    for(AVPacket * packet : videoPackets) {
        av_packet_free(&packet);
    }
    videoPackets.clear();
    clearAudioPackets();
}

void VideoDecoder::clearAudioPackets() {
    for(AVPacket * packet : audioPackets) {
        av_packet_free(&packet);
    }
    audioPackets.clear();
    pendingAudioLoopMarkers = 0;
}

bool VideoDecoder::swapContainer(AVFormatContext* newContext) {
    avformat_close_input(&formatContext);
    formatContext = newContext;
    return formatContext != NULL;
}

bool VideoDecoder::rewind() {
    if(!fileLoaded || avioContext == nullptr) {
        // Opened by filename, which can't be opened again. The decoder stays as it is, it has to be reloaded.
        return false;
    }

    // Make sure neither the decoding thread nor the audio is using the decoders while resetting them. The decoding thread
    // is asked to stop reading ahead / opening the file again, as that would be thrown away anyway.
    rewindRequested = true;
    videoMutex.lock();
    rewindRequested = false;
    audioMutex.lock();
    packetMutex.lock();

    clearPackets();
    bool success = swapContainer(openContainerAgain());
    if(!success) {
        // Make sure nothing touches the demuxer anymore, the decoder has to be reloaded
        audioOutputEnded = true;
        videoOutputEnded = true;
    } else {
        avcodec_flush_buffers(videoCodecContext);
        audioDecodedSize = 0;
        audioDecodedUsed = 0;
        // Nothing to reset if the audio is disabled
        audioOutputEnded = audioCodecContext != NULL && audioStreamIndex >= 0 && !restartAudioDecoder();
        timestampOffset = 0;
        resetLoopState();

        decodeMutex.lock();
        currentFrameDisplayed = 0;
        totalFramesBuffered = 0;
        videoOutputEnded = false;
        decodeCondvar.signal();
        decodeMutex.unlock();
    }

    packetMutex.unlock();
    audioMutex.unlock();
    videoMutex.unlock();
    return success;
}

bool VideoDecoder::restartAudioDecoder() {
    if(audioDecoderNeedsReopen) {
        return reopenAudioDecoder();
    }
    // Cheaper than reopening, the resampler doesn't buffer anything as the sample rate isn't changed
    avcodec_flush_buffers(audioCodecContext);
    return true;
}

bool VideoDecoder::reopenAudioDecoder() {
    // Flushing isn't enough: some decoders only set up the handling of the encoder delay (e.g. the opus pre-skip)
    // when being opened
    avcodec_free_context(&audioCodecContext);
    audioCodecContext = avcodec_alloc_context3(audioCodec);
    avcodec_parameters_to_context(audioCodecContext, audioCodecParameters);
    if(avcodec_open2(audioCodecContext, audioCodec, NULL) < 0) {
        logError("[VideoDecoder::reopenAudioDecoder] Could not open audio decoder!\n");
        return false;
    }
    // Drop samples still buffered in the resampler
    swr_init(swrContext);
    return true;
}

AVFormatContext* VideoDecoder::openContainerAgain() {
    // Seeking doesn't always return to the exact start (e.g. matroska drops the audio before the first video
    // keyframe, so encoder delay / pre-skip samples get lost), so the container is opened again instead.
    // The I/O context and the decoders are kept. The current container must not be read from meanwhile, as it
    // shares the I/O context.
    if(avioContext == nullptr) {
        // Opened by filename, can't be reopened here
        return NULL;
    }
    avioContext->error = 0;
    int64_t pos = avio_seek(avioContext, 0, SEEK_SET);
    if(pos < 0) {
        logError("[VideoDecoder::openContainerAgain] Could not seek to start of file\n");
        return NULL;
    }

    AVFormatContext* context = avformat_alloc_context();
    context->pb = avioContext;
    // Probing the streams of some formats reads a lot, so the decoder can be disposed or rewound meanwhile
    context->interrupt_callback.callback = &interruptFunction;
    context->interrupt_callback.opaque = this;
    int err = avformat_open_input(&context, "<stream>", NULL, NULL);
    if(err < 0) {
        char error[1024];
        av_strerror(err, error, 1024);
        logError("[VideoDecoder::openContainerAgain] Error reopening file: %s\n", error);
        return NULL;
    }
    // avformat_find_stream_info is skipped if possible, as it decodes frames to probe the streams, which is expensive.
    // The decoders are already set up, so the streams only need to be the same as before.
    if(!hasSameStreams(context) && (context->ctx_flags & AVFMTCTX_NOHEADER)) {
        // Containers without a header (e.g. MPEG-TS) only create their streams while reading packets
        if(avformat_find_stream_info(context, NULL) < 0) {
            logError("[VideoDecoder::openContainerAgain] Could not find stream info after reopening file\n");
            avformat_close_input(&context);
            return NULL;
        }
    }
    if(!hasSameStreams(context)) {
        logError("[VideoDecoder::openContainerAgain] Streams changed after reopening file\n");
        // Make sure nothing reads from the unexpected streams
        avformat_close_input(&context);
        return NULL;
    }
    // Otherwise a rewind could abort reading a packet, which would be taken as a read error
    context->interrupt_callback.callback = NULL;
    context->interrupt_callback.opaque = NULL;
    return context;
}

bool VideoDecoder::hasSameStreams(AVFormatContext* context) {
    // Only compares against data that doesn't change after loading, as the audio decoder may be reopened meanwhile
    // audioStreamIndex isn't used, as disableAudio() may change it while the file is opened without holding the packets
    return isSameStream(context, videoStreamIndex, AVMEDIA_TYPE_VIDEO, videoCodec->id) &&
           (loadedAudioStreamIndex < 0 ||
            isSameStream(context, loadedAudioStreamIndex, AVMEDIA_TYPE_AUDIO, audioCodec->id));
}

bool VideoDecoder::isSameStream(AVFormatContext* context, int streamIndex, AVMediaType type, AVCodecID codecId) {
    if(streamIndex >= (int)context->nb_streams) {
        return false;
    }
    AVCodecParameters* codecpar = context->streams[streamIndex]->codecpar;
    return codecpar->codec_type == type && codecpar->codec_id == codecId;
}

int VideoDecoder::getVideoFrameSize()
{
    return videoFrameSize;
}

bool VideoDecoder::hasFrameBuffered() {
    return getNumBuffered() >= 1;
}

bool VideoDecoder::isBuffered() {
    return (getNumBuffered() == VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES - 1) || (videoOutputEnded && hasFrameBuffered());
}