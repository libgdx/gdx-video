/*******************************************************************************
 * Copyright 2024 See AUTHORS file.
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

package com.badlogic.gdx.video.assets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.video.VideoPlayer;
import com.badlogic.gdx.video.VideoPlayerCreator;

import java.io.FileNotFoundException;

public class VideoLoader extends AsynchronousAssetLoader<VideoPlayer, VideoLoader.VideoParameter> {

	public VideoLoader (FileHandleResolver resolver) {
		super(resolver);
	}

	private VideoPlayer videoPlayer;

	@Override
	public void loadAsync (AssetManager manager, String fileName, FileHandle file, VideoParameter parameter) {
		videoPlayer = null;
		videoPlayer = VideoPlayerCreator.createVideoPlayer();
		videoPlayer.setFilter(parameter.minFilter, parameter.magFilter);
		videoPlayer.setLooping(parameter.looping);
		videoPlayer.setVolume(parameter.volume);
		try {
			videoPlayer.load(file);
		} catch (FileNotFoundException ignored) {
			Gdx.app.error("gdx-video", "Video file " + fileName + " not found");
		}
	}

	@Override
	public VideoPlayer loadSync (AssetManager manager, String fileName, FileHandle file, VideoParameter parameter) {
		VideoPlayer player = this.videoPlayer;
		this.videoPlayer = null;
		return player;
	}

	@Override
	public Array<AssetDescriptor> getDependencies (String fileName, FileHandle file, VideoParameter parameter) {
		return null;
	}

	public static class VideoParameter extends AssetLoaderParameters<VideoPlayer> {
		public TextureFilter minFilter, magFilter;
		public boolean looping;
		public float volume = 1;
	}

}
