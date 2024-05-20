/*******************************************************************************
 * Copyright 2023 See AUTHORS file.
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

package com.badlogic.gdx.video.scenes.scene2d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.video.VideoPlayer;

/** A simple actor that allows you to integrate a video in a 2D scene.
 * <p>
 * The actor takes care of updating and displaying the video on each frame. The creation, video loading, playback control, and
 * disposing of the {@link VideoPlayer} is left to the user.
 * <p>
 * The actor must be removed before disposing the VideoPlayer and must not be used again after. */
public class VideoActor extends Actor {
	/** Creates a new video actor that displays the video from the provided player.
	 *
	 * @param player The video player that this actor uses. Must not be null! */
	public VideoActor (VideoPlayer player) {
		if (player == null) throw new GdxRuntimeException("VideoActor: player must not be null!");
		this.player = player;
	}

	private final VideoPlayer player;

	@Override
	public void act (float delta) {
		super.act(delta);
		player.update();
	}

	@Override
	public void draw (Batch batch, float parentAlpha) {
		Texture texture = player.getTexture();
		if (texture == null) return;
		Color color = getColor();
		batch.setColor(color.r, color.g, color.b, color.a * parentAlpha);
		batch.draw(texture, getX(), getY(), getWidth(), getHeight(), 0, 0, player.getVideoWidth(), player.getVideoHeight(), false,
			false);
	}

	public VideoPlayer getVideoPlayer () {
		return player;
	}
}
