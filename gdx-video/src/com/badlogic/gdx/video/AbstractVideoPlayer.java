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

package com.badlogic.gdx.video;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;

public abstract class AbstractVideoPlayer implements VideoPlayer {
	protected TextureFilter minFilter = TextureFilter.Linear;
	protected TextureFilter magFilter = TextureFilter.Linear;

	@Override
	public void setFilter (TextureFilter minFilter, TextureFilter magFilter) {
		if (this.minFilter == minFilter && this.magFilter == magFilter) return;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		Texture texture = getTexture();
		if (texture == null) return;
		texture.setFilter(minFilter, magFilter);
	}
}
