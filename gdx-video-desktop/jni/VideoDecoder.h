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


#pragma once

extern "C"
{
//This makes certain C libraries usable for ffmpeg
#define __STDC_CONSTANT_MACROS
//Include ffmpeg headers
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
}

#include "Utilities.h"
#include "Thread.hpp"
#include "Mutex.hpp"
#include "CondVar.hpp"

#include <list>

//Should always be bigger then 1! If not, the buffer will never be filled, because the buffer will never be completely full.
//It will always have 1 single empty element, which is used as protection for faster synchronization.
#define VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES 4
#define VIDEOPLAYER_AUDIO_BUFFER_SIZE 1024

struct VideoBufferInfo {
    void* videoBuffer;
    void* audioBuffer;
    int videoBufferSize;
    int videoBufferWidth;
    int videoWidth;
    int videoHeight;
    int audioBufferSize;
    int audioChannels;
    int audioSampleRate;
};

/**
 * @brief The VideoPlayer class is the base class which will handle everything needed to play a videofile.
 * 
 * @author Rob Bogie <rob.bogie@codepoke.net>
 */
class VideoDecoder : private Thread{
public:
    /**
     * @brief VideoPlayer Default constructor
     */
    VideoDecoder();
    /**
     * @brief ~VideoPlayer Destructor
     */
    virtual ~VideoDecoder();

    /**
     * @brief loadFile This function will load the given file, and creates a buffer which will be put in the pointer.
     * @param filename The filename of the file to load
     * @param bufferPointer A reference to a void* which will then be filled with the buffer address.
     * @return The size of the buffer
     */
    void loadFile(char* filename, VideoBufferInfo* bufferInfo);
    /**
     * @brief fillBufferWithNextFrame This function will fill the buffers with the data of the next available frame
     * @return Whether a new frame was available
     */
    u_int8_t* nextVideoFrame();
    /**
     * @brief updateAudioBuffer This function will fill the audio buffers with the next amount of data.
     * @return
     */
    void updateAudioBuffer();

    /**
     * @brief getCurrentFrameTimestamp    This function will return the latest available Presentation TimeStamp. This is the
     *                              timestamp that the current frame should be shown.
     * @return The pts of the currently buffered frame (The one that can be read from the buffer at THIS moment)
     */
    double getCurrentFrameTimestamp();
    /**
     * @brief getVideoFrameSize This method returns the size in bytes of each aquired videoFrame.
     * @return The size in bytes of a single video frame
     */
    int getVideoFrameSize();

    /**
     * @brief isBuffered Returns whether the frame buffer is full
     * @return Whether the framebuffer is full.
     */
    bool isBuffered();
    bool hasFrameBuffered();
private:
    int decodeAudio(void* audioBuffer, int buf_samples);

    AVChannelLayout audioChannelLayout;
    AVSampleFormat audioSampleFormat;

    bool readPacket();

    /**
     * @brief run Implements the Threads run method, and will fill videobuffers
     */
    virtual void run();

    /**
     * @brief loadContainer Used by the loadFile functions, which setup a single input method, and then call
     * this to load the shared info.
     */
    void loadContainer(VideoBufferInfo* bufferInfo);
private:
    // Parsing
    AVFormatContext* formatContext;

    // Video decoding
    AVCodecContext* videoCodecContext;
    int videoStreamIndex;
    const AVCodec* videoCodec;
    AVFrame* frame;
    // Video scaling / reformatting to RGB
    struct SwsContext* swsContext;

    // Audio decoding
    AVCodecContext* audioCodecContext;
    int audioStreamIndex;
    const AVCodec* audioCodec;
    AVFrame* audioFrame;
    // Audio resampling
    SwrContext* swrContext;

    /// Size of an RGB video frame buffer, in bytes
    int videoFrameSize;

    /// RGB Frame ring buffer
    AVFrame* rgbFrames[VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES];

    /// currently displayed frame = tail of ring buffer
    int currentFrameDisplayed;
    int getReadIndex();
    /// total number of buffered frames = head of ring buffer
    int totalFramesBuffered;
    int getWriteIndex();
    /// number of frames currently buffered ahead
    int getNumBuffered();

    // decode mutex and condvar, signaled to wake up the decoding thread
    Mutex decodeMutex;
    CondVar decodeCondvar;

    bool videoOutputEnded;
    std::list<AVPacket *> videoPackets;

    char audioBuffer[VIDEOPLAYER_AUDIO_BUFFER_SIZE];
    uint8_t * audioDecodingBuffer;
    int audioDecodedSize;
    int audioDecodedUsed;
    bool audioOutputEnded;
    std::list<AVPacket *> audioPackets;
    Mutex packetMutex;
    double secPerKbBlock;

    bool fileLoaded;
    double timeBase;
    double timestampOffset;
};
