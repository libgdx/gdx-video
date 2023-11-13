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

package com.badlogic.gdx.video;

import java.nio.ByteBuffer;

import com.badlogic.gdx.utils.Disposable;

/** This class is a java wrapper used on the background, which communicates through jni to the actual video decoder.
 *
 * @author Rob Bogie rob.bogie@codepoke.net */
public class VideoDecoder implements Disposable {
	/** This value should not be used or altered in any way. It is used to store the pointer to the native object, for which this
	 * object is a wrapper. */
	private long nativePointer;

	interface VideoFileReader {
		@SuppressWarnings("unused")
		int fillBuffer (ByteBuffer buffer);
	}

	public static class VideoDecoderBuffers {
		private final ByteBuffer videoBuffer;
		private final ByteBuffer audioBuffer;
		private final int videoBufferWidth;
		private final int videoWidth;
		private final int videoHeight;
		private final int audioChannels;
		private final int audioSampleRate;

		// If constructor parameters are changed, please also update the native code to call the new constructor!
		private VideoDecoderBuffers (ByteBuffer videoBuffer, ByteBuffer audioBuffer, int videoBufferWidth, int videoWidth,
			int videoHeight, int audioChannels, int audioSampleRate) {
			this.videoBuffer = videoBuffer;
			this.audioBuffer = audioBuffer;
			this.videoBufferWidth = videoBufferWidth;
			this.videoWidth = videoWidth;
			this.videoHeight = videoHeight;
			this.audioChannels = audioChannels;
			this.audioSampleRate = audioSampleRate;
		}

		/** @return The audiobuffer */
		public ByteBuffer getAudioBuffer () {
			return audioBuffer;
		}

		/** @return The videobuffer */
		public ByteBuffer getVideoBuffer () {
			return videoBuffer;
		}

		/** @return The amount of audio channels */
		public int getAudioChannels () {
			return audioChannels;
		}

		/** @return The audio's samplerate */
		public int getAudioSampleRate () {
			return audioSampleRate;
		}

		/** @return The number of pixels per row in the decoding buffer, may be larger than the video width */
		public int getVideoBufferWidth () {
			return videoBufferWidth;
		}

		/** @return The height of the video */
		public int getVideoHeight () {
			return videoHeight;
		}

		/** @return The width of the video */
		public int getVideoWidth () {
			return videoWidth;
		}
	}

	/** Constructs a VideoDecoder */
	public VideoDecoder () {
		if (!FfMpeg.isLoaded()) throw new IllegalStateException("The native libraries are not yet loaded!");
		nativePointer = init();
	}

	/** This will close the VideoDecoder, and with it cleanup everything. */
	public void close () {
		disposeNative();
		nativePointer = 0;
	}

	/** Calls close */
	@Override
	public void dispose () {
		close();
	}

	/*
	 * Native functions
	 *
	 * @off
	 */

	/*JNI
	 	#include "VideoDecoder.h"
	 	#include "Utilities.h"

	 	#include <stdexcept>

	 	JavaVM* jvm = NULL;
	 	JavaVMAttachArgs attachArgs;

	 	struct FFmpegFillBufferData {
            jobject objectToCall;
            jmethodID methodToCall;
        };

        static JNIEnv * attachThread() {
        	JNIEnv * env;
			int getEnvStat = jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
			if (getEnvStat == JNI_EDETACHED) {
				if (jvm->AttachCurrentThread((void **)&env, (void *)&attachArgs) != 0) {
					logError("Failed to attach\n");
					return NULL;
				}
			} else if (getEnvStat == JNI_EVERSION) {
				logError("Unsupported version\n");
				return NULL;
			}
			return env;
        }

		static int ffmpegFillBuffer(void* data, u_int8_t* buffer, int bufferSize) {
			FFmpegFillBufferData* customData = (FFmpegFillBufferData*)data;
			JNIEnv * env = attachThread();
			if(env == NULL) return 0;
			jint bytes = env->CallIntMethod(customData->objectToCall, customData->methodToCall, env->NewDirectByteBuffer(buffer, bufferSize));
			if (env->ExceptionCheck()) {
				env->ExceptionDescribe();
			}
			if(bytes != bufferSize)
				logDebug("[VideoPlayer::fillBuffer] AVIO buffer not filled (%d/%d bytes).\n", bytes, bufferSize);
			return bytes;
		}

		static void ffmpegDataCleanup(void* data) {
			FFmpegFillBufferData* customData = (FFmpegFillBufferData*)data;
			JNIEnv * env = attachThread();
			if(env == NULL) return;
			env->DeleteGlobalRef(customData->objectToCall);
			jvm->DetachCurrentThread();
            memset(customData, 0, sizeof(FFmpegFillBufferData));
		}
	 */

	/** Creates an instance on the native side.
	 *
	 * @return A raw pointer to the native instance */
	private native long init ();/*

		if(jvm == NULL) {
            env->GetJavaVM(&jvm);
        }

        attachArgs.version = JNI_VERSION_1_6;
		attachArgs.name = (char *)"FFMpegInternalThread";
		attachArgs.group = NULL;

		VideoDecoder* pointer = new VideoDecoder();
		return (jlong)pointer;
											 */

	/** This will load a video for playback from an I/O Stream
	 *
	 * @param reader A VideoFileReader that is used to fill the FFmpeg IO buffers.
	 * @return A VideoDecoderBuffers object which contains all the information that may be needed about the video.
	 * @throws IllegalArgumentException When the filename is invalid.
	 * @throws Exception                Runtime exceptions in c++, which can have different causes.
	 */
	public native VideoDecoderBuffers loadStream (VideoFileReader reader)
			throws IllegalArgumentException, Exception;/*
		VideoDecoder* pointer = getClassPointer<VideoDecoder>(env, object);
		try {
			VideoBufferInfo bufferInfo;
            memset(&bufferInfo, 0, sizeof(VideoBufferInfo));
            FFmpegFillBufferData* data = new FFmpegFillBufferData();
            memset(data, 0, sizeof(FFmpegFillBufferData));
            data->objectToCall = env->NewGlobalRef(reader);
            jclass clazz = env->GetObjectClass(data->objectToCall);
            data->methodToCall = env->GetMethodID(clazz, "fillBuffer", "(Ljava/nio/ByteBuffer;)I");
            if(data->methodToCall == NULL) {
                delete data;
                throw std::invalid_argument("Supplied method name invalid! Is it having the correct signature?");
            }
            pointer->loadStream(ffmpegFillBuffer, data, ffmpegDataCleanup, &bufferInfo);
            jobject videoBuffer = NULL;
            jobject audioBuffer = NULL;
            if(bufferInfo.videoBuffer != NULL && bufferInfo.videoBufferSize > 0) {
                videoBuffer = env->NewDirectByteBuffer(bufferInfo.videoBuffer, bufferInfo.videoBufferSize);
            }
            if(bufferInfo.audioBuffer != NULL && bufferInfo.audioBufferSize > 0) {
                audioBuffer = env->NewDirectByteBuffer(bufferInfo.audioBuffer, bufferInfo.audioBufferSize);
            }

            jclass cls = env->FindClass("com/badlogic/gdx/video/VideoDecoder$VideoDecoderBuffers");
            if(cls == NULL) {
                logError("[wrapped_Java_com_badlogic_gdx_videoVideoDecoder_loadFile] Could not find VideoDecoderBuffers class");
                return NULL;
            }
            jmethodID constructor = env->GetMethodID(cls, "<init>", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIII)V");
            return env->NewObject(cls, constructor, videoBuffer, audioBuffer, bufferInfo.videoBufferWidth, bufferInfo.videoWidth, bufferInfo.videoHeight, bufferInfo.audioChannels, bufferInfo.audioSampleRate);
		} catch(std::runtime_error e) {
			logDebug("Caught exception \n");
			jclass clazz = env->FindClass("java/lang/Exception");
			if(clazz == 0) { //Something went horribly wrong here...
				return 0;
			}
			env->ThrowNew(clazz, e.what());
		} catch(std::invalid_argument e) {
			jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
			if(clazz == 0) { //Something went horribly wrong here...
				return 0;
			}
			env->ThrowNew(clazz, e.what());
		}
		return 0;
						 */

	/** This will return a ByteBuffer pointing to the next videoframe. This bytebuffer contains a single frame in RGB888.
	 *
	 * @return A ByteBuffer pointing to the next frame. */
	public native ByteBuffer nextVideoFrame ();/*
		VideoDecoder* pointer = getClassPointer<VideoDecoder>(env, object);
		u_int8_t* buffer = pointer->nextVideoFrame();

		return (buffer == NULL) ? NULL : env->NewDirectByteBuffer(buffer, pointer->getVideoFrameSize());
																 */

	/** This will fill the ByteBuffer for the audio (The one gotten from VideoDecoderBuffers object retrieved from loadFile) with
	 * new audio. */
	public native void updateAudioBuffer ();/*
		VideoDecoder* pointer = getClassPointer<VideoDecoder>(env, object);
		pointer->updateAudioBuffer();
															 */

	/** This gets the timestamp of the current displaying frame (The one that you got last by calling nextVideoFrame). The
	 * timestamp is in seconds, and can be total nonsense if you never called nextVideoFrame. It is being corrected when the audio
	 * couldn't keep up.
	 *
	 * @return The timestamp in seconds. */
	public native double getCurrentFrameTimestamp ();/*
		VideoDecoder* pointer = getClassPointer<VideoDecoder>(env, object);
		return pointer->getCurrentFrameTimestamp();
																		 */

	/** Disposes the native object. */
	private native void disposeNative ();/*
		VideoDecoder* pointer = getClassPointer<VideoDecoder>(env, object);
		delete pointer;
														 */

	/** @return Whether the buffer is completely filled. */
	public native boolean isBuffered ();/*
		VideoDecoder* pointer = getClassPointer<VideoDecoder>(env, object);
		return pointer->isBuffered();
													 */

	public static native void setDebug(boolean enableDebug);/*
		debug(enableDebug);
		*/
}
