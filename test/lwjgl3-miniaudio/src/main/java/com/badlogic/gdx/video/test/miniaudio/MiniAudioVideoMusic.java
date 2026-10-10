/*******************************************************************************
 * Copyright 2026 See AUTHORS file.
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

package com.badlogic.gdx.video.test.miniaudio;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.utils.TimeUtils;
import com.badlogic.gdx.video.VideoDecoder;

import games.rednblack.miniaudio.MAAudioBuffer;
import games.rednblack.miniaudio.MASound;
import games.rednblack.miniaudio.MiniAudio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** The MiniAudio counterpart of the OpenAL based RawMusic: a {@link Music} implementation that retrieves its audio from a
 * {@link VideoDecoder} instance and plays it through a MiniAudio engine.
 *
 * gdx-miniaudio has no streaming buffer, so streaming is built from two fixed size {@link MAAudioBuffer}s chained in a circle:
 * while one buffer is being played the other one is refilled with freshly decoded samples. MiniAudio rewinds a chained data
 * source to its start on every transition, which makes the circle seamless. {@link #update()} must be called once per render
 * frame to keep the buffers filled, see {@link MiniAudioVideoPlayer#update()}. */
public class MiniAudioVideoMusic implements Music {
	/** Frames per buffer (~0.37s at 44.1kHz). A refill may be up to one buffer late before stale samples become audible, so this
	 * must comfortably outlast the time between two {@link #update()} calls. */
	private static final int BUFFER_FRAMES = 16384;
	/** Converts a signed 16 bit sample (-32768 to 32767) from the decoder to the -1 to 1 float range of the MiniAudio buffers. */
	private static final float SHORT_TO_FLOAT = 1 / 32768f;

	private final VideoDecoder decoder;
	private final ShortBuffer backBuffer;
	private final int sampleRate;
	private final MAAudioBuffer headBuffer;
	private final MAAudioBuffer tailBuffer;
	private final MASound sound;
	private final float[] refillSamples;

	private boolean prefilled;
	private boolean playing;
	private boolean disposed;
	private float volume;

	private int completedBuffers;
	private boolean headBufferActive;
	private long anchorFrames;
	private long anchorNanos;

	public MiniAudioVideoMusic (MiniAudio miniAudio, VideoDecoder decoder, ByteBuffer audioBuffer, int channels, int sampleRate,
		int engineSampleRate) {
		this.decoder = decoder;
		this.sampleRate = sampleRate;

		volume = 1;
		headBufferActive = true;

		// The decoder writes interleaved signed 16 bit samples in native byte order.
		backBuffer = audioBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
		backBuffer.position(backBuffer.limit());
		refillSamples = new float[BUFFER_FRAMES * channels];

		headBuffer = miniAudio.createAudioBuffer(BUFFER_FRAMES, channels);
		tailBuffer = miniAudio.createAudioBuffer(BUFFER_FRAMES, channels);
		miniAudio.chainDataSources(headBuffer, tailBuffer);
		miniAudio.chainDataSources(tailBuffer, headBuffer);
		sound = miniAudio.createSound(headBuffer);
		// Sounds are spatialized by default, video audio is not positional.
		sound.setSpatialization(false);

		// An MAAudioBuffer carries no sample rate and is played at the engine rate. When the rates differ, the resampling
		// pitch corrects the playback speed.
		if (sampleRate != engineSampleRate) {
			sound.setPitch((float)sampleRate / engineSampleRate);
		}
	}

	/** Refills the circle of buffers with decoded audio and keeps the playback clock anchored. Must be called once per render
	 * frame while the video is playing. */
	public void update () {
		if (disposed || !playing) return;

		// The sound cursor tracks the head buffer only: it advances while the head buffer is being played and freezes at
		// BUFFER_FRAMES while the tail buffer is being played. A change of the active buffer means the previous one finished
		// and can be refilled.
		int cursor = sound.getCursorPCMPosition();
		boolean headActive = cursor < BUFFER_FRAMES;
		if (headActive != headBufferActive) {
			headBufferActive = headActive;
			completedBuffers++;
			fillNextBuffer(headActive ? tailBuffer : headBuffer);
		}

		if (headActive) {
			// Only the head buffer reports an exact playback position, re-anchor the clock whenever it is available.
			anchorFrames = (long)completedBuffers * BUFFER_FRAMES + cursor;
			anchorNanos = TimeUtils.nanoTime();
		}
	}

	@Override
	public void play () {
		if (disposed || playing) return;
		if (!prefilled) {
			// Prime both buffers so playback starts without a gap.
			prefilled = true;
			fillNextBuffer(headBuffer);
			fillNextBuffer(tailBuffer);
		}
		playing = true;
		anchorNanos = TimeUtils.nanoTime();
		sound.play();
	}

	@Override
	public void pause () {
		if (disposed || !playing) return;
		// Freeze the playback clock at the extrapolated position, play() continues from it.
		anchorFrames = getPositionFrames();
		playing = false;
		sound.pause();
	}

	@Override
	public void stop () {
		if (disposed) return;
		// The video player never stops its music: it disposes it and creates a new one when the video restarts.
		playing = false;
		sound.stop();
	}

	@Override
	public boolean isPlaying () {
		return playing;
	}

	@Override
	public void setLooping (boolean isLooping) {
		// Looping is handled by the video player, which reloads the stream and creates a new music instance.
	}

	@Override
	public boolean isLooping () {
		return false;
	}

	@Override
	public void setVolume (float volume) {
		this.volume = volume;
		if (!disposed) sound.setVolume(volume);
	}

	@Override
	public float getVolume () {
		return volume;
	}

	@Override
	public void setPan (float pan, float volume) {
		if (!disposed) sound.setPan(pan);
		setVolume(volume);
	}

	@Override
	public void setPosition (float position) {
		// Seeking is not supported, the decoder only moves forward.
	}

	/** The decoded audio playback position in seconds. The video player uses this as the clock for audio/video synchronization. */
	@Override
	public float getPosition () {
		return getPositionFrames() / (float)sampleRate;
	}

	@Override
	public void setOnCompletionListener (OnCompletionListener listener) {
		// The decoded stream has no end signal: at the end of the video the decoder produces silence until disposed.
	}

	@Override
	public void dispose () {
		if (disposed) return;
		disposed = true;
		playing = false;
		sound.dispose();
		headBuffer.dispose();
		tailBuffer.dispose();
	}

	private void fillNextBuffer (MAAudioBuffer buffer) {
		int offset = 0;
		while (offset < refillSamples.length) {
			if (!backBuffer.hasRemaining()) {
				// Same contract as RawMusic: rewind and let the decoder fill the whole buffer again. At the end of the stream
				// the decoder fills silence, so this never blocks.
				backBuffer.rewind();
				decoder.updateAudioBuffer();
			}
			int count = Math.min(backBuffer.remaining(), refillSamples.length - offset);
			for (int i = 0; i < count; i++) {
				refillSamples[offset++] = backBuffer.get() * SHORT_TO_FLOAT;
			}
		}
		buffer.write(refillSamples);
	}

	private long getPositionFrames () {
		if (!playing) return anchorFrames;
		// While the tail buffer is being played the cursor is frozen, extrapolate from the last known anchor instead.
		long elapsedNanos = TimeUtils.nanoTime() - anchorNanos;
		return anchorFrames + elapsedNanos * sampleRate / 1000000000L;
	}
}
