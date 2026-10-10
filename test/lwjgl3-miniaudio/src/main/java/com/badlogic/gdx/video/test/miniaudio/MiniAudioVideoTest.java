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

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.video.VideoPlayer;
import com.badlogic.gdx.video.scenes.scene2d.VideoActor;

import games.rednblack.miniaudio.MiniAudio;
import games.rednblack.miniaudio.config.MAContextConfiguration;
import games.rednblack.miniaudio.config.MAEngineConfiguration;

import java.io.FileNotFoundException;

/** {@link com.badlogic.gdx.ApplicationListener} that plays a video with its audio going through MiniAudio instead of OpenAL. */
public class MiniAudioVideoTest extends ApplicationAdapter {
	/** MiniAudio plays an audio buffer at the engine rate, so the engine is created with a fixed, known rate that
	 * {@link MiniAudioVideoMusic} can compensate against. */
	private static final int ENGINE_SAMPLE_RATE = 48000;

	private SpriteBatch batch;
	private MiniAudio miniAudio;
	private VideoPlayer videoPlayer;
	private VideoActor videoActor;

	@Override
	public void create () {
		Gdx.app.setLogLevel(Application.LOG_DEBUG);
		batch = new SpriteBatch();

		MAEngineConfiguration engineConfiguration = new MAEngineConfiguration();
		engineConfiguration.sampleRate = ENGINE_SAMPLE_RATE;
		miniAudio = new MiniAudio(new MAContextConfiguration(), engineConfiguration);

		videoPlayer = new MiniAudioVideoPlayer(miniAudio, ENGINE_SAMPLE_RATE);
		videoPlayer.setOnCompletionListener(file -> Gdx.app.log("VideoTest", file.name() + " fully played."));
		videoPlayer.setOnVideoSizeListener(
			(width, height) -> Gdx.app.log("VideoTest", "The video has a size of " + width + "x" + height + "."));

		videoActor = new VideoActor(videoPlayer);
		videoActor.setBounds(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		try {
			videoPlayer.load(getVideoFile());
			videoPlayer.play();
		} catch (FileNotFoundException e) {
			Gdx.app.error("gdx-video", "Oh no!");
		}
	}

	@Override
	public void render () {
		if (Gdx.input.justTouched()) {
			pauseOrPlayVideo();
		}

		Gdx.gl.glClearColor(0, 0, 0, 0);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

		videoActor.act(Gdx.graphics.getDeltaTime());

		batch.begin();
		videoActor.draw(batch, 1f);
		batch.end();
	}

	@Override
	public void pause () {
		videoPlayer.pause();
	}

	@Override
	public void resume () {
		videoPlayer.play();
	}

	@Override
	public void dispose () {
		videoPlayer.dispose();
		miniAudio.dispose();
		batch.dispose();
	}

	private void pauseOrPlayVideo () {
		if (videoPlayer.isPlaying()) {
			videoPlayer.pause();
		} else {
			videoPlayer.play();
		}
	}

	private FileHandle getVideoFile () {
		return Gdx.files.internal("libGDX - It's Good For You!.webm");
	}
}
