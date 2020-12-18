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

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Null;
import com.badlogic.gdx.utils.reflect.ClassReflection;
import com.badlogic.gdx.utils.reflect.ReflectionException;

/** This class is used to provide a way of creating a VideoPlayer, without knowing the platform the program is running on. This
 * has to be extended for each supported platform.
 *
 * @author Rob Bogie rob.bogie@codepoke.net */
public class VideoPlayerCreator {
	private static Class<? extends VideoPlayer> videoPlayerClass;

	/** Creates a VideoPlayer.
	 *
	 * @return A new instance of VideoPlayer */
	@Null
	public static VideoPlayer createVideoPlayer () {
		initialize();
		if (videoPlayerClass == null) return new VideoPlayerStub();
		try {
			return ClassReflection.newInstance(videoPlayerClass);
		} catch (ReflectionException e) {
			e.printStackTrace();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static void initialize () {
		if (videoPlayerClass != null) return;

		String className = null;
		ApplicationType type = Gdx.app.getType();

		if (type == ApplicationType.Android) {
			if (Gdx.app.getVersion() >= 12) {
				className = "com.badlogic.gdx.video.VideoPlayerAndroid";
			} else {
				Gdx.app.log("Gdx-Video", "VideoPlayer can't be used on android < API level 12");
			}
		} else if (type == ApplicationType.Desktop) {
			className = "com.badlogic.gdx.video.VideoPlayerDesktop";
		} else if (type == ApplicationType.WebGL) {
			className = "com.badlogic.gdx.video.VideoPlayerGwt";
		} else {
			Gdx.app.log("Gdx-Video", "Platform is not supported by the Gdx Video Extension");
		}

		try {
			videoPlayerClass = ClassReflection.forName(className);
		} catch (ReflectionException e) {
			e.printStackTrace();
		}
	}
}
