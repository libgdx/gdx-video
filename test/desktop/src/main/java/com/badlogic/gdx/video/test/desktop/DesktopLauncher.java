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

package com.badlogic.gdx.video.test.desktop;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.badlogic.gdx.video.test.GdxVideoTest;

/** Launches the desktop (LWJGL) application. */
public class DesktopLauncher {
	public static void main (String[] args) {
		createApplication();
	}

	private static LwjglApplication createApplication () {
		return new LwjglApplication(new GdxVideoTest(), getDefaultConfiguration());
	}

	private static LwjglApplicationConfiguration getDefaultConfiguration () {
		LwjglApplicationConfiguration configuration = new LwjglApplicationConfiguration();
		configuration.title = "test";
		configuration.width = 640;
		configuration.height = 480;
		//// This prevents a confusing error that would appear after exiting normally.
		configuration.forceExit = false;

		return configuration;
	}
}
