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

import com.badlogic.gdx.utils.SharedLibraryLoader;

/** This class manages the loading of the native libraries that wrap FFMpeg. It allows changing the path from which it loads the
 * libraries, and defaults to loading the file from jar containing the class.
 *
 * @author Rob Bogie rob.bogie@codepoke.net */
public class FfMpeg {
	public static final String NATIVE_LIBRARY_NAME = "gdx-video-desktop";

	private static boolean loaded = false;
	private static String libraryPath;

	/** This will set the path in which it tries to find the native library.
	 *
	 * @param path The path on which the library can be found. If it is null or an empty string, the default location will be used.
	 *           This is usually a SteamJavaNatives folder inside the jar. */
	public static void setLibraryFilePath (String path) {
		libraryPath = path;
	}

	/** This method will load the libraries from the path given with setLibraryFilePath.
	 *
	 * @return whether loading was successful */
	public static boolean loadLibraries () {
		if (loaded) {
			return true;
		}

		SharedLibraryLoader libLoader;
		if (libraryPath == null) {
			libLoader = new SharedLibraryLoader();
		} else {
			libLoader = new SharedLibraryLoader(libraryPath);
		}

		try {
			libLoader.load(NATIVE_LIBRARY_NAME);
		} catch (Exception e) {
			System.out.println(e.getMessage());
			e.printStackTrace();
			loaded = false;
			return false;
		}
		loaded = true;
		return true;
	}

	/** This tells whether the native libraries are already loaded.
	 *
	 * @return Whether the native libraries are already loaded. */
	public static boolean isLoaded () {
		return loaded;
	}

	public static void setDebugLogging (boolean debugLogging) {
		if (!loaded) {
			if (!loadLibraries()) {
				return;
			}
		}
		setDebugLoggingNative(debugLogging);
	}

	/*
	 * Native functions
	 * @off
	 */

	/*JNI
		#include "Utilities.h"
	 */

	 /**
	  * This function can be used to turn on/off debug logging of the native code
	  *
	  * @param debugLogging whether logging should be turned on or off
	  */
	 private native static void setDebugLoggingNative (boolean debugLogging);/*
		debug(debugLogging);
	 */
}
