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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALMusic;

import java.nio.ByteBuffer;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/** The RawMusic class extends OpenAlMusic, and retrieves it's audio from a VideoDecoder instance.
 *
 * @author Rob Bogie <rob.bogie@codepoke.net> */
class RawMusic extends OpenALMusic implements DecoderMusic {
	DecoderAudioBuffer audioBuffer;

	public RawMusic (VideoDecoder decoder, ByteBuffer buffer, int channels, int sampleRate) {
		super((OpenALLwjgl3Audio)Gdx.audio, null);
		audioBuffer = new DecoderAudioBuffer(decoder, buffer, channels, sampleRate);
		setup(channels, 16, sampleRate);
	}

	@Override
	public int read (byte[] buffer) {
		return audioBuffer.read(buffer);
	}

	@Override
	public void reset () {
		audioBuffer.reset();
	}

	@Override
	public double getPlayedSeconds () {
		int sourceId = getSourceId();
		if (sourceId == -1) return 0;
		return audioBuffer.getPlayedSeconds(AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED),
			AL10.alGetSourcef(sourceId, AL11.AL_SEC_OFFSET));
	}

}
