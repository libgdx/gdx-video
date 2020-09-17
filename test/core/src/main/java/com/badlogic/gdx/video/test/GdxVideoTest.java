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

package com.badlogic.gdx.video.test;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.video.VideoPlayer;
import com.badlogic.gdx.video.VideoPlayerCreator;

import java.io.FileNotFoundException;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class GdxVideoTest extends ApplicationAdapter {
	SpriteBatch batch;
	OrthographicCamera camera;
	VideoPlayer videoPlayer;

	@Override
	public void create () {
		Gdx.app.setLogLevel(Application.LOG_DEBUG);
		batch = new SpriteBatch();
		camera = new OrthographicCamera();
		videoPlayer = VideoPlayerCreator.createVideoPlayer();
		videoPlayer.setOnCompletionListener(new VideoPlayer.CompletionListener() {
			@Override
			public void onCompletionListener (FileHandle file) {
				Gdx.app.log("VideoTest", file.name() + " fully played.");
			}
		});
		videoPlayer.setOnVideoSizeListener(new VideoPlayer.VideoSizeListener() {
			@Override
			public void onVideoSize (float width, float height) {
				Gdx.app.log("VideoTest", "The video has a size of " + width + "x" + height + ".");
			}
		});
	}

	@Override
	public void render () {
		if (Gdx.input.justTouched()) {
			try {
				videoPlayer.play(Gdx.files.internal("libGDX - It's Good For You!.webm"));
			} catch (FileNotFoundException e) {
				System.out.println("Oh no!");
			}
		}

		Gdx.gl.glClearColor(0, 0, 0, 0);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		videoPlayer.update();
		batch.begin();
		Texture frame = videoPlayer.getTexture();
		if (frame != null) batch.draw(frame, 0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		batch.end();
	}

	@Override
	public void pause () {
		videoPlayer.pause();
	}

	@Override
	public void resume () {
		videoPlayer.resume();
	}

	@Override
	public void dispose () {
		videoPlayer.dispose();
	}
}
