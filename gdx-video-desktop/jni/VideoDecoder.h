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
#include <libavutil/time.h>
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
#define CUSTOMIO_BUFFER_SIZE 16384
//When looping, audio that is longer than the video by less than this (in seconds) is treated as encoder padding, and is
//cut so the next loop starts right after the video ends
#define VIDEOPLAYER_MAX_AUDIO_PADDING 0.05
//When looping, the decoding thread keeps at least this many audio packets queued, so it reaches the end of the file and
//starts the next loop before the audio needs it
#define VIDEOPLAYER_AUDIO_PREFETCH_PACKETS 64
//Limit of queued video packets when reading ahead for the audio
#define VIDEOPLAYER_MAX_VIDEO_PACKETS 256
//When looping, the decoding thread is woken up to read ahead once less than this many seconds are queued for the audio
#define VIDEOPLAYER_AUDIO_PREFETCH_TIME 0.5
//Limit of queued audio packets. More are only queued if nothing plays the audio, so the oldest ones are dropped then.
#define VIDEOPLAYER_MAX_AUDIO_PACKETS 4096
//Microseconds without the audio taking a packet, after which nothing is assumed to play the audio
#define VIDEOPLAYER_AUDIO_STALL_TIME 5000000
//Number of loops whose start offset is remembered, as shown frames may lag behind the audio by some loops
#define VIDEOPLAYER_LOOP_OFFSETS 64

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
 *  The FillFileBufferFunc function will give a pointer to some data you gave to it, a buffer, and an integer representing
 *  the buffer's size. The function needs to return the amount of data that is filled into the buffer.
 */
typedef int (*FillFileBufferFunc)(void*, uint8_t*, int);
/**
 *  The SeekFileBufferFunc function will give a pointer to some data you gave to it, an offset and a whence value
 *  (SEEK_SET, AVSEEK_SIZE, or SEEK_END if AVSEEK_SIZE failed). The function needs to return the new position (or the
 *  size for AVSEEK_SIZE), or a negative value on failure.
 */
typedef int64_t (*SeekFileBufferFunc)(void*, int64_t, int);
typedef void (*CleanupFunc)(void*);

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
     * @brief loadFile This function starts loading the given file, and creates the video and audio buffers.
     * @param filename The filename of the file to load
     * @param bufferInfo A reference to a VideoBufferInfo, which will then be filled with the buffer addresses.
     * @return The size of the buffer
     */
    void loadFile(char* filename, VideoBufferInfo* bufferInfo);

    /**
     * @brief loadFile This function starts loading video from the given stream, and creates the video and audio buffers.
     * @param fillFunc The function to call to fill the I/O buffers
     * @param seekFunc An optional function to call to seek within the stream
     * @param seekable Whether seeking is cheap, so the demuxer may seek freely. Otherwise seekFunc is only used when
     *                 seeking is explicitly requested (e.g. by rewind)
     * @param funcData Custom data to pass to the fillFunc as first parameter
     * @param cleanFunc An optional function to call when the decoding stops
     * @param bufferInfo A reference to a VideoBufferInfo, which will then be filled with the buffer addresses.
     * @return The size of the buffer
     */
    void loadStream(FillFileBufferFunc fillFunc, SeekFileBufferFunc seekFunc, bool seekable, void* funcData, CleanupFunc cleanFunc, VideoBufferInfo* bufferInfo);

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
     * @brief rewind Goes back to the start of the video and resets all decoding state, so playback can start over
     *               without reloading the file. Decoders and buffers are kept.
     * @return Whether the rewind succeeded. If not, the decoder is in an undefined state and should be reloaded.
     */
    bool rewind();

    /**
     * @brief setLooping Sets whether the video starts over when reaching the end. The decoder then continues seamlessly
     *                   with the start of the video, and the timestamps keep increasing by the loop duration.
     */
    void setLooping(bool looping);

    /**
     * @brief disableAudio Stops queueing audio, when nothing is going to play it.
     */
    void disableAudio();

    /**
     * @brief getCurrentFrameTimestamp    This function will return the latest available Presentation TimeStamp. This is the
     *                              timestamp that the current frame should be shown.
     * @return The pts of the currently buffered frame (The one that can be read from the buffer at THIS moment)
     */
    double getCurrentFrameTimestamp();
    /**
     * @brief getCurrentFramePosition Returns the position of the current frame within the current loop, i.e. the
     *                                timestamp without the offset added by looping.
     * @return The position in seconds
     */
    double getCurrentFramePosition();
    /**
     * @brief getCurrentFrameLoop Returns how often the video has looped before the current frame.
     * @return The loop index of the current frame
     */
    int getCurrentFrameLoop();
    /**
     * @brief getVideoFrameSize This method returns the size in bytes of each aquired videoFrame.
     * @return The size in bytes of a single video frame
     */
    int getVideoFrameSize();

    void *getCustomFuncData() const { return customFuncData; }
    FillFileBufferFunc getFillBufferFunc() const { return fillBufferFunc; }
    SeekFileBufferFunc getSeekBufferFunc() const { return seekBufferFunc; }
    /// Whether opening the file again should be aborted, as the decoder is disposed or rewound
    bool isInterruptRequested() const { return stopRequested || rewindRequested; }

    /**
     * @brief isBuffered Returns whether the frame buffer is full
     * @return Whether the framebuffer is full.
     */
    bool isBuffered();
    bool hasFrameBuffered();
    /**
     * @brief getFramesDisplayed Returns how many frames nextVideoFrame() has moved on, so a repeated frame can be told apart
     */
    int getFramesDisplayed() { return currentFrameDisplayed; }
private:
    int decodeAudio(void* audioBuffer, int buf_samples);

    AVChannelLayout audioChannelLayout;
    AVSampleFormat audioSampleFormat;

    /// Reads the next packet into the queues. At the end of the file, the file is only opened again for the next loop
    /// if allowReopen is set, otherwise the decoding thread is asked to do it.
    bool readPacket(bool allowReopen);
    void queueAudioPacket(AVPacket* packet);
    double getPacketTime(AVPacket* packet, bool end);
    bool needsAudioLookahead(double poppedPacketEnd);
    /// Whether the end of the file is going to be followed by the next loop
    bool canLoopAtEnd();
    bool needsPrefetch();
    void requestPrefetch();
    void prefetchPackets();
    AVPacket* newLoopMarker();
    void decodeVideoFrame();
    void clearPackets();
    void clearAudioPackets();
    /// Replaces the container with the given one, which may be NULL. Returns whether there is a container now.
    bool swapContainer(AVFormatContext* newContext);
    /// Opens the file again from the start. Returns NULL on failure.
    AVFormatContext* openContainerAgain();
    bool hasSameStreams(AVFormatContext* context);
    bool isSameStream(AVFormatContext* context, int streamIndex, AVMediaType type, AVCodecID codecId);
    /// Stops decoding audio, and queueing packets for it
    void endAudioOutput();

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
    // Custom / Streaming I/O
    void* customFuncData;
    FillFileBufferFunc fillBufferFunc;
    SeekFileBufferFunc seekBufferFunc;
    CleanupFunc cleanupFunc;
    AVIOContext* avioContext;

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
    /// Copy of the audio stream parameters, to be able to open the audio decoder again
    AVCodecParameters* audioCodecParameters;
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
    // set to stop the decoding thread
    volatile bool stopRequested;
    // set while rewind() waits for the decoding thread
    volatile bool rewindRequested;
    // set to make the decoding thread read packets ahead for the audio
    bool prefetchRequested;
    // held while the video / audio decoders are being used
    Mutex videoMutex;
    Mutex audioMutex;

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
    AVRational videoFrameRate;
    /// Index of the audio stream when loading, which unlike audioStreamIndex doesn't change
    int loadedAudioStreamIndex;
    double timestampOffset;

    // Looping
    volatile bool looping;
    /// Duration of one loop in seconds, from the start of the earliest to the end of the longest stream. Known once the
    /// end of the file has been reached.
    double loopDuration;
    /// End time of the latest video / audio packet read, in seconds
    double videoPacketsEnd;
    double audioPacketsEnd;
    /// Start time of the earliest video / audio packet read in this loop, in seconds
    double videoPacketsStart;
    double audioPacketsStart;
    /// End time of the packet read last, in seconds
    double lastPacketEnd;
    /// Whether the end of the file has been reached, and the next read starts the next loop
    bool endOfFile;
    /// Set when the end of the file has been reached without looping, so playback ends there
    bool fileEnded;
    /// Set while the file is opened again for the next loop, without holding packetMutex
    bool reopening;
    /// Set while the decoding thread has been asked to read packets ahead, but hasn't started yet. Guarded by
    /// packetMutex, unlike prefetchRequested.
    bool prefetchPending;
    void resetPacketRange();
    /// Number of loop markers in the audio queue
    int pendingAudioLoopMarkers;
    /// If the audio is only longer than the video by encoder padding, it's cut after this many samples of each loop, at
    /// the end of the video. 0 if it isn't cut.
    int64_t audioCutSamples;
    /// Loop the video decoder is in
    int videoLoop;
    /// Whether the audio decoder is in the last loop, so no loop follows
    bool audioFinalLoop;
    /// Loop of each frame in the ring buffer
    int frameLoops[VIDEOPLAYER_VIDEO_NUM_BUFFERED_FRAMES];
    /// Set while a decoder is drained at the end of a loop
    bool videoLoopPending;
    bool audioLoopPending;
    int audioSampleRate;
    /// Number of audio samples output in the current loop
    int64_t audioLoopSamples;
    /// Silence still to output, so the audio of a loop is as long as the loop
    int64_t audioSilenceSamples;
    /// Samples to drop, because the audio of the previous loop was longer than the loop
    int64_t audioSkipSamples;
    /// Loop the audio output is in
    volatile int audioLoop;
    /// timestampOffset when the audio started each loop, indexed by loop % VIDEOPLAYER_LOOP_OFFSETS
    double loopOffsetBase[VIDEOPLAYER_LOOP_OFFSETS];
    /// Set when the audio decoder has been drained at the end of the file, before the marker of the next loop arrived
    bool audioLoopDrained;
    /// Fraction of a sample the audio of the previous loop was too long, due to rounding to whole samples
    double audioLoopSampleCarry;
    /// av_gettime_relative() when the audio took the last packet
    int64_t lastAudioPop;
    /// Starts counting the offset of the next audio loop
    void enterNextAudioLoop();
    void resetLoopState();
    bool reopenAudioDecoder();
    /// Resets the audio decoder for the next loop, by reopening it only if necessary
    bool restartAudioDecoder();
    /// Whether the audio decoder has to be reopened for the next loop, as flushing it doesn't restore the handling of
    /// the encoder delay
    bool audioDecoderNeedsReopen;
};
