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

package com.badlogic.gdx.video.test.teavm;

import com.github.xpenatan.gdx.backends.teavm.TeaApplicationConfiguration;
import com.github.xpenatan.gdx.backends.teavm.TeaApplication;
import com.badlogic.gdx.video.test.GdxVideoTest;

/** Launches the TeaVM/HTML application. */
public class TeaVMLauncher {
	public static void main (String[] args) {
		TeaApplicationConfiguration config = new TeaApplicationConfiguration("canvas");
		//// If width and height are each greater than 0, then the app will use a fixed size.
		// config.width = 640;
		// config.height = 480;
		//// If width and height are both 0, then the app will use all available space.
		// config.width = 0;
		// config.height = 0;
		//// If width and height are both -1, then the app will fill the canvas size.
		config.width = -1;
		config.height = -1;
		new TeaApplication(new GdxVideoTest(), config);
	}
}
