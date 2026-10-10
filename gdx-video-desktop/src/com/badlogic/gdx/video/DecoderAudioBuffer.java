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

/** Reads the audio of a {@link VideoDecoder}, for the {@link DecoderMusic} of each backend. */
class DecoderAudioBuffer {
	private final VideoDecoder decoder;
	private final ByteBuffer backBuffer;
	private final int bytesPerSecond;
	/** Bytes handed out since the last reset, and the size of the latest read */
	private long bytesRead;
	private int lastReadSize;

	/** @param channels Number of channels of the 16 bit audio */
	DecoderAudioBuffer (VideoDecoder decoder, ByteBuffer buffer, int channels, int sampleRate) {
		this.decoder = decoder;
		backBuffer = buffer;
		backBuffer.position(backBuffer.limit());
		bytesPerSecond = 2 * channels * sampleRate;
	}

	/** Fills the whole buffer with audio from the decoder.
	 *
	 * @return The number of bytes filled in */
	int read (byte[] buffer) {
		int sizeNeeded = buffer.length;
		int currentIndex = 0;

		while (sizeNeeded > 0) {
			if (backBuffer.remaining() > 0) {
				int numBytes = Math.min(backBuffer.remaining(), sizeNeeded);
				backBuffer.get(buffer, currentIndex, numBytes);
				currentIndex += numBytes;
				sizeNeeded -= numBytes;
			} else {
				// We need to fill the buffer;
				backBuffer.rewind();
				decoder.updateAudioBuffer();
			}
		}

		bytesRead += buffer.length;
		lastReadSize = buffer.length;
		return buffer.length;
	}

	/** Drops the remaining audio, e.g. when the decoder got rewound */
	void reset () {
		backBuffer.position(backBuffer.limit());
		bytesRead = 0;
		lastReadSize = 0;
	}

	/** Calculates the played position exactly, unlike OpenALMusic.getPosition(), which adds up floats and so drifts after playing
	 * for a long time. OpenALMusic reads one whole buffer per OpenAL buffer it queues.
	 *
	 * @param queuedBuffers Number of OpenAL buffers queued
	 * @param queueOffset Position within the queued buffers in seconds
	 * @return The position in seconds since the last reset */
	double getPlayedSeconds (int queuedBuffers, float queueOffset) {
		long queuedStart = bytesRead - (long)queuedBuffers * lastReadSize;
		return Math.max(0, queuedStart) / (double)bytesPerSecond + queueOffset;
	}
}
