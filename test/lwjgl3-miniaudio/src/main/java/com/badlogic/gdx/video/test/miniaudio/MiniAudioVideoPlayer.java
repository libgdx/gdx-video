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
import com.badlogic.gdx.video.CommonVideoPlayerDesktop;
import com.badlogic.gdx.video.VideoDecoder;

import games.rednblack.miniaudio.MiniAudio;

import java.nio.ByteBuffer;

/** Desktop implementation of the VideoPlayer that plays the video's audio through MiniAudio instead of OpenAL, as an example of
 * the {@link CommonVideoPlayerDesktop#createMusic} extension point for projects that do not use the default audio backend. */
public class MiniAudioVideoPlayer extends CommonVideoPlayerDesktop {
	private final MiniAudio miniAudio;
	private final int engineSampleRate;
	private MiniAudioVideoMusic music;

	public MiniAudioVideoPlayer (MiniAudio miniAudio, int engineSampleRate) {
		this.miniAudio = miniAudio;
		this.engineSampleRate = engineSampleRate;
	}

	@Override
	public Music createMusic (VideoDecoder decoder, ByteBuffer audioBuffer, int audioChannels, int sampleRate) {
		music = new MiniAudioVideoMusic(miniAudio, decoder, audioBuffer, audioChannels, sampleRate, engineSampleRate);
		return music;
	}

	@Override
	public boolean update () {
		// MiniAudio does not pull from the decoder the way OpenAL music does, it is fed once per render frame instead.
		if (music != null) music.update();
		return super.update();
	}
}
