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

package com.badlogic.gdx.video.test.gwt;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;
import com.badlogic.gdx.video.test.GdxVideoTest;

/** Launches the GWT application. */
public class GwtLauncher extends GwtApplication {
	//// USE THIS CODE FOR A FIXED SIZE APPLICATION
	@Override
	public GwtApplicationConfiguration getConfig () {
		return new GwtApplicationConfiguration(640, 480);
	}
	//// END CODE FOR FIXED SIZE APPLICATION

	//// UNCOMMENT THIS CODE FOR A RESIZABLE APPLICATION
	// PADDING is to avoid scrolling in iframes, set to 20 if you have problems
	// private static final int PADDING = 0;
	//
	// @Override
	// public GwtApplicationConfiguration getConfig() {
	// int w = Window.getClientWidth() - PADDING;
	// int h = Window.getClientHeight() - PADDING;
	// GwtApplicationConfiguration cfg = new GwtApplicationConfiguration(w, h);
	// Window.enableScrolling(false);
	// Window.setMargin("0");
	// Window.addResizeHandler(new ResizeListener());
	// cfg.preferFlash = false;
	// return cfg;
	// }
	//
	// class ResizeListener implements ResizeHandler {
	// @Override
	// public void onResize(ResizeEvent event) {
	// if (Gdx.graphics.isFullscreen()) return;
	// int width = event.getWidth() - PADDING;
	// int height = event.getHeight() - PADDING;
	// getRootPanel().setWidth("" + width + "px");
	// getRootPanel().setHeight("" + height + "px");
	// getApplicationListener().resize(width, height);
	// Gdx.graphics.setWindowedMode(width, height);
	// }
	// }
	//// END OF CODE FOR RESIZABLE APPLICATION

	@Override
	public ApplicationListener createApplicationListener () {
		return new GdxVideoTest();
	}
}
