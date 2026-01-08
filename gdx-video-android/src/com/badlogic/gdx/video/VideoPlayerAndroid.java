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

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplicationBase;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Null;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.IntBuffer;

/** Android implementation of the VideoPlayer class.
 *
 * @author Rob Bogie &lt;rob.bogie@codepoke.net&gt; */
public class VideoPlayerAndroid extends AbstractVideoPlayer implements VideoPlayer, OnFrameAvailableListener {
	//@off
	static final String vertexShaderCode =
			"#define highp\n" +
					"attribute highp vec4 a_position; \n" +
					"attribute highp vec2 a_texCoord0;" +
					"uniform highp mat4 u_projModelView;" +
					"varying highp vec2 v_texCoord0;" +
					"void main() \n" +
					"{ \n" +
					" gl_Position = u_projModelView * a_position; \n" +
					" v_texCoord0 = a_texCoord0;\n" +
					"} \n";

	static final String fragmentShaderCode =
			"#extension GL_OES_EGL_image_external : require\n" +
					"uniform samplerExternalOES u_sampler0;" +
					"varying highp vec2 v_texCoord0;" +
					"void main()                 \n" +
					"{                           \n" +
					"  gl_FragColor = texture2D(u_sampler0, v_texCoord0);    \n" +
					"}";
	//@on

	private void setupStep () {
		if (stopped || setupRenderer()) return;
		queueSetupStep();
	}

	private boolean setupRenderer () {
		if (surface != null) {
			return true;
		}
		if (renderer == null) {
			shader = new ShaderProgram(vertexShaderCode, fragmentShaderCode);
			renderer = new ImmediateModeRenderer20(4, false, false, 1, shader);
			transform = new Matrix4().setToOrtho2D(0, 0, 1, 1);
		} else if (initialized) {
			videoTexture.setOnFrameAvailableListener(this);
			surface = new Surface(videoTexture);
			player.setSurface(surface);
		}
		return false;
	}

	private void queueSetupStep () {
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run () {
				setupStep();
			}
		});
	}

	private ShaderProgram shader;
	private Matrix4 transform;
	private IntBuffer textureBuffer;
	private SurfaceTexture videoTexture;
	private Surface surface;
	private FrameBuffer fbo;
	private Texture frame;
	private ImmediateModeRenderer20 renderer;

	private MediaPlayer player;
	private volatile boolean initialized = false;
	private boolean prepared = false;
	private boolean stopped = false;
	private boolean playRequested = false;
	private volatile boolean frameAvailable = false;
	/** If the external should be drawn to the fbo and make it available thru {@link #getTexture()} */
	public boolean renderToFbo = true;

	VideoSizeListener sizeListener;
	CompletionListener completionListener;

	private boolean currentLooping = false;
	private float currentVolume = 1.0f;

	/** Used for sending mediaplayer tasks to the Main Looper */
	private static Handler handler;

	public VideoPlayerAndroid () {
		initializeMediaPlayer();
		queueSetupStep();
	}

	private void initializeMediaPlayer () {
		if (handler == null) handler = new Handler(Looper.getMainLooper());

		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run () {
				textureBuffer = BufferUtils.newIntBuffer(1);
				Gdx.gl.glGenTextures(1, textureBuffer);
				final int textureID = textureBuffer.get(0);

				Gdx.gl.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID);

				handler.post(new Runnable() {
					@Override
					public void run () {
						player = new MediaPlayer();
						videoTexture = new SurfaceTexture(textureID);
						initialized = true;
					}
				});
			}
		});
	}

	private void playInternal (final FileHandle file) {
		prepared = false;
		frame = null;
		stopped = false;

		// If we haven't finished loading the media player yet, wait without blocking.
		if (!initialized) {
			Gdx.app.postRunnable(new Runnable() {
				@Override
				public void run () {
					if (stopped) return;
					playInternal(file);
				}
			});
			return;
		}

		player.reset();

		player.setOnPreparedListener(new OnPreparedListener() {
			@Override
			public void onPrepared (MediaPlayer mp) {
				if (sizeListener != null) {
					sizeListener.onVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
				}

				Gdx.app.postRunnable(new Runnable() {
					@Override
					public void run () {
						if (fbo != null && (fbo.getWidth() != player.getVideoWidth() || fbo.getHeight() != player.getVideoHeight())) {
							fbo.dispose();
							fbo = null;
						}
						if (fbo == null) {
							fbo = new FrameBuffer(Pixmap.Format.RGB888, player.getVideoWidth(), player.getVideoHeight(), false);
						}
						prepared = true;
						player.setVolume(currentVolume, currentVolume);
						player.setLooping(currentLooping);
						if (playRequested) {
							player.start();
						}
					}
				});
			}
		});
		player.setOnErrorListener(new OnErrorListener() {
			@Override
			public boolean onError (MediaPlayer mp, int what, int extra) {
				Gdx.app.error("gdx-video", String.format("Error occurred: %d, %d\n", what, extra));
				return false;
			}
		});

		player.setOnCompletionListener(new OnCompletionListener() {
			@Override
			public void onCompletion (MediaPlayer mp) {
				if (isLooping()) return;
				if (completionListener != null) {
					completionListener.onCompletionListener(file);
				}
			}
		});

		try {
			if (file.type() == FileType.Classpath || (file.type() == FileType.Internal && !file.file().exists())) {
				AssetManager assets = ((AndroidApplicationBase)Gdx.app).getContext().getAssets();
				AssetFileDescriptor descriptor = assets.openFd(file.path());
				player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
				descriptor.close();
			} else {
				player.setDataSource(file.file().getAbsolutePath());
			}
			player.prepareAsync();
		} catch (IOException e) {
			Gdx.app.error("gdx-video", "Error loading video file: " + file.path(), e);
		}
	}

	@Override
	public boolean load (FileHandle file) throws FileNotFoundException {
		if (!file.exists()) {
			throw new FileNotFoundException("Could not find file: " + file.path());
		}

		playInternal(file);
		return true;
	}

	@Override
	public void play () {
		if (prepared) {
			player.start();
		}
		playRequested = true;
	}

	/** Get external texture directly without framebuffer
	 * @return texture handle to be used with external OES target, -1 if texture is not available */
	public int getTextureExternal () {
		if (prepared) {
			return textureBuffer.get(0);
		}
		return -1;
	}

	@Override
	public boolean update () {
		if (surface == null || !frameAvailable || (fbo == null && renderToFbo)) return false;

		frameAvailable = false;
		videoTexture.updateTexImage();

		if (!renderToFbo) return true;

		fbo.begin();
		shader.bind();

		Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
		Gdx.gl.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, getTextureExternal());
		shader.setUniformi("u_sampler0", 0);
		renderer.begin(transform, GL20.GL_TRIANGLE_STRIP);
		renderer.texCoord(0, 0);
		renderer.vertex(0, 0, 0);
		renderer.texCoord(1, 0);
		renderer.vertex(1, 0, 0);
		renderer.texCoord(0, 1);
		renderer.vertex(0, 1, 0);
		renderer.texCoord(1, 1);
		renderer.vertex(1, 1, 0);
		renderer.end();
		fbo.end();

		Texture colorBufferTexture = fbo.getColorBufferTexture();
		if (frame != colorBufferTexture) {
			frame = colorBufferTexture;
			frame.setFilter(minFilter, magFilter);
		}

		return true;
	}

	@Override
	@Null
	public Texture getTexture () {
		return frame;
	}

	/** For android, this will return whether the prepareAsync method of the android MediaPlayer is done with preparing.
	 *
	 * @return whether the buffer is filled. */
	@Override
	public boolean isBuffered () {
		return prepared;
	}

	@Override
	public void stop () {
		if (player != null && isPlaying()) {
			player.stop();
			player.reset();
		}
		stopped = true;
		prepared = false;
	}

	@Override
	public void onFrameAvailable (SurfaceTexture surfaceTexture) {
		frameAvailable = true;
	}

	@Override
	public void pause () {
		// If it is running
		if (isPlaying()) {
			player.pause();
		}
		playRequested = false;
	}

	@Override
	public void resume () {
		play();
	}

	@Override
	public void dispose () {
		stop();
		surface = null;
		handler.post(new Runnable() {
			@Override
			public void run () {
				player.release();
				player = null;
			}
		});

		if (videoTexture != null) {
			videoTexture.detachFromGLContext();
			GLES20.glDeleteTextures(1, textureBuffer);
		}

		if (fbo != null) fbo.dispose();
		fbo = null;
		frame = null;
		if (renderer != null) {
			renderer.dispose();
			shader.dispose();
		}
	}

	@Override
	public void setOnVideoSizeListener (VideoSizeListener listener) {
		sizeListener = listener;
	}

	@Override
	public void setOnCompletionListener (CompletionListener listener) {
		completionListener = listener;
	}

	@Override
	public int getVideoWidth () {
		if (!prepared) {
			return 0;
		}
		return player.getVideoWidth();
	}

	@Override
	public int getVideoHeight () {
		if (!prepared) {
			return 0;
		}
		return player.getVideoHeight();
	}

	@Override
	public boolean isPlaying () {
		if (!prepared) {
			return false;
		}
		return player.isPlaying();
	}

	@Override
	public void setVolume (float volume) {
		currentVolume = volume;
		if (prepared) {
			player.setVolume(volume, volume);
		}
	}

	@Override
	public float getVolume () {
		return currentVolume;
	}

	@Override
	public void setLooping (boolean looping) {
		currentLooping = looping;
		if (prepared) {
			player.setLooping(looping);
		}
	}

	@Override
	public boolean isLooping () {
		return currentLooping;
	}

	@Override
	public int getCurrentTimestamp () {
		if (!prepared) {
			return 0;
		}
		return player.getCurrentPosition();
	}
}
