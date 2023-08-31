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

#include <cstring>
#include <stdexcept>

#include <pthread.h>

VideoDecoder::VideoDecoder() : decodeCondvar(decodeMutex) {
    fileLoaded = false;
    videoOutputEnded = false;
    audioOutputEnded = false;

    formatContext = NULL;
    videoCodecContext = NULL;
    audioCodecContext = NULL;

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

    timestampOffset = 0;
}

VideoDecoder::~VideoDecoder() {
    // Wake up and stop decoding thread
    decodeMutex.lock();
    videoOutputEnded = true;
    audioOutputEnded = true;
    decodeCondvar.signal();
    decodeMutex.unlock();
    join();

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
    if(videoCodecContext != NULL) {
        avcodec_free_context(&videoCodecContext);
    }

    av_frame_free(&audioFrame);
    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        av_frame_free(rgbFrames + i);
    }
    av_frame_free(&frame);
    avformat_close_input(&formatContext);
    av_freep(&audioDecodingBuffer);
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

void VideoDecoder::loadContainer(VideoBufferInfo* bufferInfo) {
    currentFrameDisplayed = 0;
    totalFramesBuffered = 0;

    if (avformat_find_stream_info(formatContext, NULL) < 0) {
        logError("[VideoPlayer::loadFile] Could not find stream info!\n");
        throw std::runtime_error("Could not find stream info!");
    }

    videoStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, &videoCodec, 0);
    audioStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_AUDIO, -1, -1, &audioCodec, 0);

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

        bufferInfo->audioChannels = audioCodecContext->ch_layout.nb_channels;
        bufferInfo->audioSampleRate = audioCodecContext->sample_rate;

        if(avcodec_open2(audioCodecContext, audioCodec, NULL) < 0) {
            logError("[VideoPlayer::loadFile] Could not open audio decoder!\n");
            throw std::runtime_error("Could not open audio decoder!");
        }
        bufferInfo->audioBuffer = this->audioBuffer;
        bufferInfo->audioBufferSize = VIDEOPLAYER_AUDIO_BUFFER_SIZE;

        // Setup conversion context, which will convert our audio to the right output format
        AVChannelLayout avch = AV_CHANNEL_LAYOUT_STEREO;
        av_channel_layout_copy(&audioChannelLayout, &avch);
        audioSampleFormat = AV_SAMPLE_FMT_S16;

        logDebug("[VideoPlayer::loadFile] Loading audio resampler ...\n");
        swr_alloc_set_opts2(
            &swrContext,
            /* out */ &avch, audioSampleFormat, bufferInfo->audioSampleRate,
            /*  in */ &audioCodecContext->ch_layout, audioCodecContext->sample_fmt, bufferInfo->audioSampleRate,
            0, NULL
        );
        swr_init(swrContext);

        //Calculate how much seconds a single kb block is (1024 bytes): blockSize / bytesPerSample / channels / sampleRate
        secPerKbBlock = 1024.0 / 1 / (double)bufferInfo->audioChannels / (double)audioCodecContext->sample_rate;
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
}

int VideoDecoder::decodeAudio(void* decodingBuffer, int buf_samples) {
    while(!audioOutputEnded) {
        int ret = avcodec_receive_frame(audioCodecContext, audioFrame);
        if(ret == AVERROR(EAGAIN)) {
            // Get a new packet and send to decoder
            bool check = true;
            AVPacket * audioPacket = NULL;

            while(!audioPacket && check) {
                packetMutex.lock();
                if(audioPackets.empty()) {
                    check = readPacket();
                } else {
                    audioPacket = audioPackets.back();
                    audioPackets.pop_back();
                }
                packetMutex.unlock();
            }

            avcodec_send_packet(audioCodecContext, audioPacket);
            av_packet_free(&audioPacket);
            continue;
        }
        if(ret != 0) {
            // Either decoding error or EOF
            logDebug("[VideoDecoder::run] Finished decoding the audio stream.\n");
            audioOutputEnded = true;
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
    return rgbFrames[getReadIndex()]->pts * timeBase + timestampOffset;
}

bool VideoDecoder::readPacket() {
    AVPacket *packet = av_packet_alloc();
    if(av_read_frame(formatContext, packet) >= 0) {
        if(packet->stream_index==videoStreamIndex) {
            videoPackets.push_front(packet);
        } else if(packet->stream_index == audioStreamIndex) {
            audioPackets.push_front(packet);
        } else {
            av_packet_free(&packet);
        }
        return true;
    } else {
        logDebug("[VideoDecoder::readPacket] No more packets available.\n");
        return false;
    }
}

void VideoDecoder::run() {
    decodeMutex.lock();
    while(true) {
        while(!isBuffered() && !videoOutputEnded) {
            decodeMutex.unlock();

            int ret = avcodec_receive_frame(videoCodecContext, frame);
            if(ret == AVERROR(EAGAIN)) {
                // Get a new packet and send to decoder
                bool check = true;
                AVPacket * videoPacket = NULL;

                while(!videoPacket && check) {
                    packetMutex.lock();
                    if(videoPackets.empty()) {
                        check = readPacket();
                    } else {
                        videoPacket = videoPackets.back();
                        videoPackets.pop_back();
                    }
                    packetMutex.unlock();
                }

                avcodec_send_packet(videoCodecContext, videoPacket);
                av_packet_free(&videoPacket);
                continue;
            }
            if(ret != 0) {
                // Either decoding error or EOF
                logDebug("[VideoDecoder::run] Finished decoding the video stream.\n");
                videoOutputEnded = true;
                break;
            }

            // Got a new frame, so scale it into an RGB frame
            AVFrame * dst = rgbFrames[getWriteIndex()];
            sws_scale(
                swsContext,
                (uint8_t const * const *)frame->data, frame->linesize, 0, videoCodecContext->height,
                dst->data, dst->linesize
            );
            dst->pts = frame->pts;

            //Atomic increment of totalFramesBuffered
            __sync_add_and_fetch(&totalFramesBuffered, 1);

            decodeMutex.lock();
        }
        if(videoOutputEnded) break;
        // Wait for signal that new frames are requested
        decodeCondvar.wait();
    }
    decodeMutex.unlock();
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