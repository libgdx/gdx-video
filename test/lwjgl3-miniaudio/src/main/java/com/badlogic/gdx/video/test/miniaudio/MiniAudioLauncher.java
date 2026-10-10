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

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/** Launches the desktop (LWJGL3) application without OpenAL, the video's audio is played by MiniAudio. */
public class MiniAudioLauncher {
	public static void main (String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication () {
		return new Lwjgl3Application(new MiniAudioVideoTest(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration () {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setTitle("test-miniaudio");
		configuration.setWindowedMode(640, 480);
		// The point of this example: no OpenAL, Gdx.audio is a mock.
		configuration.disableAudio(true);
		return configuration;
	}
}
