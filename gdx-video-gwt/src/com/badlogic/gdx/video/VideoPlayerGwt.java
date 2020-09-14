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
import com.badlogic.gdx.backends.gwt.GwtFileHandle;
import com.badlogic.gdx.backends.gwt.GwtGL20;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.google.gwt.event.dom.client.EndedEvent;
import com.google.gwt.event.dom.client.EndedHandler;
import com.google.gwt.media.client.Video;

import java.io.FileNotFoundException;

public class VideoPlayerGwt implements VideoPlayer {
	//@off
	private static final String vertexShader = "attribute vec4 a_position;    \n" +
			"attribute vec2 a_texCoord0;\n" +
			"uniform mat4 u_worldView;\n" +
			"varying vec2 v_texCoords;" +
			"void main()                  \n" +
			"{                            \n" +
			"   v_texCoords = a_texCoord0; \n" +
			"   gl_Position =  u_worldView * a_position;  \n" +
			"}                            \n";
	private static final String fragmentShader = "#ifdef GL_ES\n" +
			"#define LOWP lowp\n" +
			"#define MED mediump\n" +
			"#define HIGH highp\n" +
			"precision highp float;\n" +
			"#else\n" +
			"#define MED\n" +
			"#define LOWP\n" +
			"#define HIGH\n" +
			"#endif\n" +
			"varying vec2 v_texCoords;\n" +
			"uniform sampler2D u_texture;\n" +
			"void main()                                  \n" +
			"{                                            \n" +
			"  gl_FragColor = texture2D(u_texture, v_texCoords);\n" +
			"}";
	// @on
	Viewport viewport;
	Camera cam;
	Mesh mesh;
	private boolean customMesh = false;
	ShaderProgram shader = new ShaderProgram(vertexShader, fragmentShader);
	private FileHandle currentFile;
	private final Video v = Video.createIfSupported();
	private Texture frame;
	private int width, height;
	private int primitiveType = GL20.GL_TRIANGLES;
	private VideoSizeListener videoSizeListener;

	public VideoPlayerGwt () {
		this(new FitViewport(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
	}

	public VideoPlayerGwt (Viewport viewport) {
		this.viewport = viewport;

		mesh = new Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.TexCoords(0));
		mesh.setIndices(new short[] {0, 1, 2, 2, 3, 0});

		cam = viewport.getCamera();
	}

	public VideoPlayerGwt (Camera cam, Mesh mesh, int primitiveType) {
		this.cam = cam;
		this.mesh = mesh;
		this.primitiveType = primitiveType;
		customMesh = true;
	}

	@Override
	public boolean play (FileHandle file) throws FileNotFoundException {
		if (!file.exists()) throw new FileNotFoundException();
		currentFile = file;
		if (v != null) {
			v.setSrc(((GwtFileHandle) file).getAssetUrl());
			v.play();
		}
		return true;
	}

	@Override
	public boolean render () {
		if (v != null) {
			if (v.getVideoHeight() != height || v.getVideoWidth() != width) {
				height = v.getVideoHeight();
				width = v.getVideoWidth();

				float x = -width / 2f;
				float y = -height / 2f;

				//@off
				if (!customMesh)
					mesh.setVertices(
							new float[]{x, y, 0, 0, 1, x + width, y, 0, 1, 1, x + width, y + height, 0, 1, 0, x, y + height, 0, 0, 0});
				//@on

				if (viewport != null) viewport.setWorldSize(width, height);
				if (videoSizeListener != null) videoSizeListener.onVideoSize(width, height);
			}
			if (!v.isPaused() && isBuffered() && v.getVideoWidth() > 0 && v.getVideoHeight() > 0) {
				if (frame == null) frame = new Texture(width, height, Pixmap.Format.RGB888);
				frame.bind();
				((GwtGL20)Gdx.gl).gl.texImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE,
					v.getVideoElement());
				shader.bind();
				shader.setUniformMatrix("u_worldView", cam.combined);
				shader.setUniformi("u_texture", 0);
				mesh.render(shader, primitiveType);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isBuffered () {
		return v != null && v.getBuffered().length() > 0;
	}

	@Override
	public void resize (int width, int height) {
		if (!customMesh) viewport.update(width, height);
	}

	@Override
	public void pause () {
		if (v != null) v.pause();
	}

	@Override
	public void resume () {
		if (v != null && v.getCurrentTime() > 0 && v.getCurrentTime() < v.getDuration()) v.play();
	}

	@Override
	public void stop () {
		if (v != null) {
			v.pause();
			v.setSrc("");
			v.load();
		}
	}

	@Override
	public void setOnVideoSizeListener (VideoSizeListener listener) {
		videoSizeListener = listener;
	}

	//@off
	private native void setEndedCaller(CompletionListener listener) /*-{
		var video = this.@com.badlogic.gdx.video.VideoPlayerGwt::v;
		var videoElement = video.@com.google.gwt.media.client.Video::getVideoElement()();
		var videoPlayer = this;
		videoElement.onended = function () {
			listener.@com.badlogic.gdx.video.VideoPlayer.CompletionListener::onCompletionListener(Lcom/badlogic/gdx/files/FileHandle;)(videoPlayer.@com.badlogic.gdx.video.VideoPlayerGwt::currentFile);
		};
	}-*/;
	//@on

	@Override
	public void setOnCompletionListener (CompletionListener listener) {
		if (v != null) setEndedCaller(listener);
	}

	@Override
	public int getVideoWidth () {
		return width;
	}

	@Override
	public int getVideoHeight () {
		return height;
	}

	@Override
	public boolean isPlaying () {
		return v != null && !v.isPaused();
	}

	@Override
	public int getCurrentTimestamp () {
		return v != null ? (int)(v.getCurrentTime() * 1000) : 0;
	}

	@Override
	public void dispose () {
		if (frame != null) frame.dispose();
		shader.dispose();
	}

	@Override
	public float getVolume () {
		return v != null ? (float)v.getVolume() : 0;
	}

	@Override
	public void setVolume (float volume) {
		if (v != null) v.setVolume(volume);
	}
}
