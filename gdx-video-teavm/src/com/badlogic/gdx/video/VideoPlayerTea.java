/*******************************************************************************
 * Copyright 2025 See AUTHORS file.
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
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Null;
import com.github.xpenatan.gdx.backends.teavm.TeaGL20;
import com.github.xpenatan.gdx.backends.teavm.assetloader.AssetInstance;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLVideoElement;

import java.io.FileNotFoundException;

public class VideoPlayerTea extends AbstractVideoPlayer implements VideoPlayer {
	private static final HTMLDocument document = Window.current().getDocument();
	private FileHandle currentFile;
	private final HTMLVideoElement v;
	private Texture frame;
	private int width, height;
	private VideoSizeListener videoSizeListener;

	public VideoPlayerTea () {
		v = document.createElement("video").cast();
	}

	@Override
	public boolean load (FileHandle file) throws FileNotFoundException {
		currentFile = file;
		if (v != null) {
			v.setSrc(AssetInstance.getLoaderInstance().getAssetUrl() + file.path());
			v.load();
			return true;
		}
		return false;
	}

	@Override
	public void play () {
		if (v != null) {
			v.play();
		}
	}

	@Override
	public boolean update () {
		if (v != null) {
			if (v.getVideoHeight() != height || v.getVideoWidth() != width) {
				height = v.getVideoHeight();
				width = v.getVideoWidth();

				if (videoSizeListener != null) videoSizeListener.onVideoSize(width, height);
			}
			if ((!v.isPaused() || v.getCurrentTime() == 0) && isBuffered() && width * height > 0) {
				if (frame != null && (frame.getWidth() != width || frame.getHeight() != height)) {
					frame.dispose();
					frame = null;
				}
				if (frame == null) {
					frame = new Texture(width, height, Pixmap.Format.RGB888);
					frame.setFilter(minFilter, magFilter);
				}
				frame.bind();
				((TeaGL20)Gdx.gl).gl.texImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE, v);
				return true;
			}
		}
		return false;
	}

	@Override
	@Null
	public Texture getTexture () {
		return frame;
	}

	@Override
	public boolean isBuffered () {
		return v != null && v.getBuffered().getLength() > 0;
	}

	@Override
	public void pause () {
		if (v != null) v.pause();
	}

	@Override
	public void resume () {
		play();
	}

	@Override
	public void stop () {
		if (v != null) {
			v.pause();
			v.setSrc("");
			v.load();
		}
	}

	@Override
	public void setOnVideoSizeListener (VideoSizeListener listener) {
		videoSizeListener = listener;
	}

	@Override
	public void setOnCompletionListener (CompletionListener listener) {
		v.onEvent("end", e -> {
			if (listener != null) {
				listener.onCompletionListener(currentFile);
			}
		});
	}

	@Override
	public int getVideoWidth () {
		return width;
	}

	@Override
	public int getVideoHeight () {
		return height;
	}

	@Override
	public boolean isPlaying () {
		return v != null && !v.isPaused();
	}

	@Override
	public int getCurrentTimestamp () {
		return v != null ? (int)(v.getCurrentTime() * 1000) : 0;
	}

	@Override
	public void dispose () {
		if (frame != null) frame.dispose();
	}

	@Override
	public void setVolume (float volume) {
		if (v != null) v.setVolume(volume);
	}

	@Override
	public float getVolume () {
		return v != null ? v.getVolume() : 0;
	}

	@Override
	public void setLooping (boolean looping) {
		v.setLoop(looping);
	}

	@Override
	public boolean isLooping () {
		return v.isLoop();
	}
}
