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

static int readFunction(void* opaque, uint8_t* buffer, int bufferSize)
{
    VideoDecoder* decoder = (VideoDecoder*) opaque;

    //Call implemented function
    return decoder->getFillFileBufferFunc()(decoder->getCustomFileBufferFuncData(), buffer, bufferSize);
}

VideoDecoder::VideoDecoder() : videoBufferMutex(true), videoBufferConditional(videoBufferMutex), listMutex(true){
    fileLoaded = false;
    videoOutputEnded = false;

    formatContext = NULL;
    videoCodecContext = NULL;
    audioCodecContext = NULL;
    avioContext = NULL;
    avioBuffer = NULL;

    videoCodec = NULL;
    audioCodec = NULL;
    swsContext = NULL;
    frame = av_frame_alloc();
    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        rgbFrames[i] = av_frame_alloc();
    }
    audioFrame = av_frame_alloc();
    audioDecodedSize = 0;
    audioDecodedUsed = 0;

    videoCurrentBufferIndex = 0;
    videoNumFrameBuffered = 0;
    timestampOffset = -1;
}

VideoDecoder::~VideoDecoder() {
    //Take care of cleanup
    if(swrContext !=  NULL) {
        swr_free(&swrContext);
    }

    if(swsContext != NULL) {
        sws_freeContext(swsContext);
    }

    if(audioCodecContext != NULL) {
        avcodec_close(audioCodecContext);
    }
    if(videoCodecContext != NULL) {
        avcodec_close(videoCodecContext);
    }

    av_frame_free(&audioFrame);
    for(int i = 0; i < VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES; i++) {
        av_frame_free(rgbFrames + i);
    }
    av_frame_free(&frame);
    avformat_close_input(&formatContext);

    if(avioContext != NULL) {
        av_free(avioContext);
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

    if(debugLoggingActive) {
        //Print all available information about the streams inside of the file.
        av_dump_format(formatContext, 0, filename, 0);
    }

    //Try to open the file
    int err = avformat_open_input(&formatContext, filename, NULL, NULL);
    if(err < 0) {
        char error[1024];
        av_strerror(err, error, 1024);
        logError("[VideoPlayer::loadFile] Error opening file (%s): %s\n", filename, error);
        throw std::runtime_error("Could not open file!");
    }
    loadContainer(bufferInfo);
}

void VideoDecoder::loadFile(FillFileBufferFunc func, void* funcData, CleanupFunc cleanupFunc, VideoBufferInfo* bufferInfo) {
    if(fileLoaded) {
        logError("[VideoPlayer::loadFile] Tried to load a new file. Ignoring...\n");
        return;
    }

    if(func == NULL) {
        logError("[VideoPlayer::loadFile] Invalid arguments supplied!\n");
        throw std::invalid_argument("FillFileBufferFunc should be a valid function");
    }

    fillFileBufferFunc = func;
    customFileBufferFuncData = funcData;
    this->cleanupFunc = cleanupFunc;

    avioBuffer = (u_int8_t*)av_malloc(CUSTOMIO_BUFFER_SIZE);
    avioContext = avio_alloc_context(avioBuffer, CUSTOMIO_BUFFER_SIZE, 0, (void*)this, &readFunction, NULL, NULL);

    formatContext = avformat_alloc_context();
    formatContext->pb = avioContext;

    int err = avformat_open_input(&formatContext, "dummyFileName", NULL, NULL);if(err < 0) {
        char error[1024];
        av_strerror(err, error, 1024);
        logError("[VideoPlayer::loadFile] Error opening file: %s\n", error);
        throw std::runtime_error("Could not open file!");
    }
    loadContainer(bufferInfo);
}

void VideoDecoder::loadContainer(VideoBufferInfo* bufferInfo) {
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

    AVDictionary* codecOptions = NULL;

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

        if(avcodec_open2(audioCodecContext, audioCodec, &codecOptions) < 0) {
            logError("[VideoPlayer::loadFile] Could not open audio decoder!\n");
            throw std::runtime_error("Could not open audio decoder!");
        }
        bufferInfo->audioBuffer = this->audioBuffer;
        bufferInfo->audioBufferSize = VIDEOPLAYER_AUDIO_BUFFER_SIZE;

        int channelLayout = 0;
        switch(bufferInfo->audioChannels) {
        case 1:
            channelLayout = AV_CH_LAYOUT_MONO;
            break;
        case 2:
            channelLayout = AV_CH_LAYOUT_STEREO;
            break;
        default:
            logError("[VideoPlayer::loadFile] InputFile has unsupported number of audiochannels!");
            throw std::runtime_error("InputFile has unsupported number of audiochannels!");
        }

        //Setup conversion context, which will convert our audio to the right output format
        swrContext = swr_alloc_set_opts(NULL, AV_CH_LAYOUT_STEREO, AV_SAMPLE_FMT_S16, bufferInfo->audioSampleRate, channelLayout, AV_SAMPLE_FMT_FLTP, bufferInfo->audioSampleRate, 0, NULL);
        swr_init(swrContext);

        //Calculate how much seconds a single kb block is (1024 bytes): blockSize / bytesPerSample / channels / sampleRate
        secPerKbBlock = 1024.0 / 1 / (double)bufferInfo->audioChannels / (double)audioCodecContext->sample_rate;
    }

    int width = videoCodecContext->width;
    int height = videoCodecContext->height;

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

    bufferInfo->videoBuffer = rgbFrames[0]->data[0];
    bufferInfo->videoBufferSize = videoFrameSize;
    bufferInfo->videoWidth = width;
    bufferInfo->videoHeight = height;

    fileLoaded = true;

    //start filling up the buffer (Start seperate thread)
    this->start();
    videoBufferMutex.lock();
    while(videoNumFrameBuffered < (VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES - 1)) {
        videoBufferConditional.wait();
        logDebug("[VideoPlayer::loadFile] Waiting for buffer to fill: %d\n", videoNumFrameBuffered);
    }
    videoBufferMutex.unlock();
}

u_int8_t* VideoDecoder::nextVideoFrame() {
    videoBufferMutex.lock();

    if(videoNumFrameBuffered < 1 && !videoOutputEnded) {
        logDebug("[VideoPlayer::nextVideoFrame] no new frame available yet!\n");
        return rgbFrames[videoCurrentBufferIndex]->data[0];
    }

    if(!videoOutputEnded) {
        int readingIndex = videoCurrentBufferIndex;
        logDebug("[VideoPlayer::nextVideoFrame] last returned buffer points to index %d\n", readingIndex);
        videoCurrentBufferIndex = (readingIndex + 1) % VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES;
        videoNumFrameBuffered--;
        videoBufferConditional.signal();
        videoBufferMutex.unlock();
        return rgbFrames[readingIndex]->data[0];
    }

    videoBufferMutex.unlock();
    return NULL;
}

void VideoDecoder::updateAudioBuffer() {
    int sizeLeft = VIDEOPLAYER_AUDIO_BUFFER_SIZE;

    // Try getting enough data, to fill the buffer.
    while(sizeLeft > 0) {
        //If there is no decoded data left, decode new frame
        if(audioDecodedUsed >= audioDecodedSize) {
            audioDecodedUsed = 0;

            // FIXME: audioBuffer should be created with av_samples_alloc
            int buf_samples = VIDEOPLAYER_AUDIO_BUFFER_SIZE / 5;
            int size = decodeAudio(audioDecodingBuffer, buf_samples);
            if(size < 0) {
                logDebug("[VideoPlayer::updateAudioBuffer] Could not decode more frames!\n");

                //Play silence
                audioDecodedSize = 1024;
                memset(audioDecodingBuffer, 0, audioDecodedSize);

                //Set an offset for the video, so that audio won't be behind
                timestampOffset += secPerKbBlock;
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

int VideoDecoder::decodeAudio(void* audioBuffer, int buf_samples) {
    while(true) {
        listMutex.lock();
        int ret = avcodec_receive_frame(audioCodecContext, audioFrame);
        if(ret == AVERROR(EAGAIN)) {
            // Get a new packet and send to decoder
            while(audioPackets.empty() && readPacket());

            AVPacket * audioPacket = NULL;
            if(!audioPackets.empty()) {
                audioPacket = audioPackets.back();
                audioPackets.pop_back();
            }
            avcodec_send_packet(audioCodecContext, audioPacket);
            av_packet_free(&audioPacket);
            continue;
        }
        if(ret != 0) {
            // Either decoding error or EOF
            listMutex.unlock();
            return 0;
        }

        if(audioPackets.empty()) {
            //Keep reading packets until no more can be read, or an audio packet is found.
            while(audioPackets.empty() && readPacket());
            if(audioPackets.empty()) {
                listMutex.unlock();
                return -1;
            }
        }

        listMutex.unlock();

        // got frame
        int max_samples = swr_get_out_samples(swrContext, audioFrame->nb_samples);
        int out_samples = 0;
        if(max_samples > 0) {
            out_samples = swr_convert(
                swrContext,
                (u_int8_t**)&audioBuffer, buf_samples,
                (uint8_t const **)audioFrame->extended_data, audioFrame->nb_samples // in
            );
        }

        int size = av_samples_get_buffer_size(NULL, 2, out_samples, AV_SAMPLE_FMT_S16, 0);

        //Return copied size
        return size;
    }
}

double VideoDecoder::getCurrentFrameTimestamp() {
    //Since the nextVideoFrame function already upped the variable, undo the effect (ugly, but effective, may be refactored later).
    int index = videoCurrentBufferIndex - 1;
    if(index < 0) {
        index = VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES - 1;
    }
    logDebug("[VideoPlayer::nextVideoFrame] last returned timestamp is of index %d\n", index);
    return videoTimestamps[index];// + ((timestampOffset) > 0 ? timestampOffset : 0);
}

bool VideoDecoder::readPacket() {
    AVPacket *packet = av_packet_alloc();
    if(av_read_frame(formatContext, packet) >= 0) {
        if(packet->stream_index==videoStreamIndex) {
            //queue video packet for handling later
            videoBufferMutex.lock();
            videoPackets.push_front(packet);
            videoBufferMutex.unlock();
        } else if(packet->stream_index == audioStreamIndex) {
            //queue audio packet for handling later
            listMutex.lock();
            audioPackets.push_front(packet);
            listMutex.unlock();
        } else {
            av_packet_free(&packet);
        }
        return true;
    } else {
        return false;
    }
}

void VideoDecoder::run() {
    videoBufferMutex.lock();
    while(!videoOutputEnded) {
        while(videoNumFrameBuffered < (VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES - 1) && !videoOutputEnded) {
            int ret = avcodec_receive_frame(videoCodecContext, frame);
            if(ret == AVERROR(EAGAIN)) {
                // Get a new packet and send to decoder
                while(videoPackets.empty() && readPacket());

                AVPacket * videoPacket = NULL;
                if(!videoPackets.empty()) {
                    videoPacket = videoPackets.back();
                    videoPackets.pop_back();
                }
                avcodec_send_packet(videoCodecContext, videoPacket);
                av_packet_free(&videoPacket);
                continue;
            }
            if(ret != 0) {
                // Either decoding error or EOF
                videoOutputEnded = true;
                break;
            }

            videoBufferMutex.unlock();

            // Got a new frame, so scale it into an RGB frame
            int indexToWrite = (videoCurrentBufferIndex + videoNumFrameBuffered) % VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES;

            AVFrame * dst = rgbFrames[indexToWrite];
            sws_scale(
                swsContext,
                (uint8_t const * const *)frame->data, frame->linesize, 0, videoCodecContext->height,
                dst->data, dst->linesize
            );

            videoTimestamps[indexToWrite] = (double)frame->pts * timeBase;

            //Atomic increment of videoNumFrameBuffered
            __sync_add_and_fetch(&videoNumFrameBuffered, 1);

            if(videoBufferMutex.trylock() == 0) {
                // Send signal that new frames are available
                videoBufferConditional.signal();
                videoBufferMutex.unlock();
            }

            videoBufferMutex.lock();
        }
        // Wait for signal that new frames are requested
        videoBufferConditional.wait();
    }
    videoBufferMutex.unlock();

    if(cleanupFunc != NULL) {
        cleanupFunc(this->customFileBufferFuncData);
    }
}

int VideoDecoder::getVideoFrameSize()
{
    return videoFrameSize;
}

bool VideoDecoder::isBuffered() {
    return videoNumFrameBuffered == (VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES - 1);
}


FillFileBufferFunc VideoDecoder::getFillFileBufferFunc() const
{
    return fillFileBufferFunc;
}

void *VideoDecoder::getCustomFileBufferFuncData() const
{
    return customFileBufferFuncData;
}
