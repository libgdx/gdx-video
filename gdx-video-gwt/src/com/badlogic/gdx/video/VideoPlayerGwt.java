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
import com.badlogic.gdx.backends.gwt.GwtFileHandle;
import com.badlogic.gdx.backends.gwt.GwtGL20;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.utils.Null;
import com.google.gwt.media.client.Video;

public class VideoPlayerGwt extends AbstractVideoPlayer implements VideoPlayer {
	private FileHandle currentFile;
	private final Video v = Video.createIfSupported();
	private Texture frame;
	private int width, height;
	private VideoSizeListener videoSizeListener;

	public VideoPlayerGwt () {
	}

	@Override
	public boolean play (FileHandle file) {
		currentFile = file;
		if (v != null) {
			v.setSrc(((GwtFileHandle)file).getAssetUrl());
			v.play();
			return true;
		}
		return false;
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
				((GwtGL20)Gdx.gl).gl.texImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE,
					v.getVideoElement());
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
		return v != null && v.getBuffered().length() > 0;
	}

	@Override
	public void pause () {
		if (v != null) v.pause();
	}

	@Override
	public void resume () {
		if (v != null && (v.getCurrentTime() == 0 || v.getCurrentTime() < v.getDuration())) v.play();
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

	//@off
	private native void setEndedCaller(CompletionListener listener) /*-{
		var video = this.@com.badlogic.gdx.video.VideoPlayerGwt::v;
		var videoElement = video.@com.google.gwt.media.client.Video::getVideoElement()();
		var videoPlayer = this;
		videoElement.onended = function () {
			listener.@com.badlogic.gdx.video.VideoPlayer.CompletionListener::onCompletionListener(Lcom/badlogic/gdx/files/FileHandle;)(videoPlayer.@com.badlogic.gdx.video.VideoPlayerGwt::currentFile);
		};
	}-*/;
	//@on

	@Override
	public void setOnCompletionListener (CompletionListener listener) {
		if (v != null) setEndedCaller(listener);
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
	public float getVolume () {
		return v != null ? (float)v.getVolume() : 0;
	}

	@Override
	public void setLooping (boolean looping) {
		v.setLoop(looping);
	}

	@Override
	public boolean isLooping () {
		return v.isLoop();
	}

	@Override
	public void setVolume (float volume) {
		if (v != null) v.setVolume(volume);
	}
}
