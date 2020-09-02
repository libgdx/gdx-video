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
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.files.FileHandle;
import com.google.gwt.event.dom.client.EndedEvent;
import com.google.gwt.event.dom.client.EndedHandler;
import com.google.gwt.media.client.Video;

import java.io.FileNotFoundException;

public class VideoPlayerGwt implements VideoPlayer {
	FileHandle currentFile;
	Video v;

	@Override
	public boolean play (FileHandle file) throws FileNotFoundException {
		if (!file.exists()) throw new FileNotFoundException();
		currentFile = file;
		v = Video.createIfSupported();
		if (v != null) {
			v.setSrc(((GwtApplication)Gdx.app).getPreloaderBaseURL() + file.path());
			v.play();
		}
		return false;
	}

	@Override
	public boolean render () {
		return false;
	}

	@Override
	public boolean isBuffered () {
		return false;
	}

	@Override
	public void resize (int width, int height) {
		if (v != null) v.setPixelSize(width, height);
	}

	@Override
	public void pause () {
		v.pause();
	}

	@Override
	public void resume () {
		v.play();
	}

	@Override
	public void stop () {
		v.pause();
		v = null;
	}

	@Override
	public void setOnVideoSizeListener (VideoSizeListener listener) {

	}

	@Override
	public void setOnCompletionListener (CompletionListener listener) {
		v.addEndedHandler(new EndedHandler() {
			@Override
			public void onEnded (EndedEvent endedEvent) {
				listener.onCompletionListener(currentFile);
			}
		});
	}

	@Override
	public int getVideoWidth () {
		return v.getVideoWidth();
	}

	@Override
	public int getVideoHeight () {
		return v.getVideoHeight();
	}

	@Override
	public boolean isPlaying () {
		return !v.isPaused();
	}

	@Override
	public int getCurrentTimestamp () {
		return (int)(v.getCurrentTime() * 1000);
	}

	@Override
	public void dispose () {

	}

	@Override
	public float getVolume () {
		return (float)v.getVolume();
	}

	@Override
	public void setVolume (float volume) {
		v.setVolume(volume);
	}
}
