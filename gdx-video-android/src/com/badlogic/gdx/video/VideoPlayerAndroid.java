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
import android.util.Log;
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
import com.badlogic.gdx.utils.Null;

import java.io.FileNotFoundException;
import java.io.IOException;

/** Android implementation of the VideoPlayer class.
 *
 * @author Rob Bogie &lt;rob.bogie@codepoke.net&gt; */
public class VideoPlayerAndroid extends AbstractVideoPlayer implements VideoPlayer, OnFrameAvailableListener {
	//@off
	String vertexShaderCode =
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

	String fragmentShaderCode =
			"#extension GL_OES_EGL_image_external : require\n" +
					"uniform samplerExternalOES u_sampler0;" +
					"varying highp vec2 v_texCoord0;" +
					"void main()                 \n" +
					"{                           \n" +
					"  gl_FragColor = texture2D(u_sampler0, v_texCoord0);    \n" +
					"}";
	//@on

	private final ShaderProgram shader = new ShaderProgram(vertexShaderCode, fragmentShaderCode);
	private Matrix4 transform;
	private final int[] textures = new int[1];
	private SurfaceTexture videoTexture;
	private FrameBuffer fbo;
	private Texture frame;
	private ImmediateModeRenderer20 renderer;

	private MediaPlayer player;
	private boolean prepared = false;
	/** Whether the video was requested to stay paused after loading
	 *
	 * To achieve this and still load the first video frame properly, we play the video muted until the first frame is available,
	 * and then pause. */
	private boolean pauseRequested = false;
	private boolean frameAvailable = false;
	/** If the external should be drawn to the fbo and make it available thru {@link #getTexture()} */
	public boolean renderToFbo = true;

	VideoSizeListener sizeListener;
	CompletionListener completionListener;
	private float currentVolume = 1.0f;

	/** Used for sending mediaplayer tasks to the Main Looper */
	private static Handler handler;

	/** Lock used for waiting if the player was not yet created. */
	final Object lock = new Object();

	public VideoPlayerAndroid () {
		setupRenderTexture();
		initializeMediaPlayer();
	}

	private void initializeMediaPlayer () {
		if (handler == null) handler = new Handler(Looper.getMainLooper());

		handler.post(new Runnable() {
			@Override
			public void run () {
				synchronized (lock) {
					player = new MediaPlayer();
					lock.notify();
				}
			}
		});
	}

	@Override
	public boolean play (final FileHandle file) throws FileNotFoundException {
		if (!file.exists()) {
			throw new FileNotFoundException("Could not find file: " + file.path());
		}
		prepared = false;

		// Wait for the player to be created. (If the Looper thread is busy,
		if (player == null) {
			synchronized (lock) {
				while (player == null) {
					try {
						lock.wait();
					} catch (InterruptedException e) {
						return false;
					}
				}
			}
		}

		player.reset();

		player.setOnPreparedListener(new OnPreparedListener() {
			@Override
			public void onPrepared (MediaPlayer mp) {
				prepared = true;
				if (sizeListener != null) {
					sizeListener.onVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
				}
				if (fbo != null && (fbo.getWidth() != mp.getVideoWidth() || fbo.getHeight() != mp.getVideoHeight())) {
					fbo.dispose();
					fbo = null;
				}
				mp.start();
				if (pauseRequested) {
					mp.pause();
				}
			}
		});
		player.setOnErrorListener(new OnErrorListener() {
			@Override
			public boolean onError (MediaPlayer mp, int what, int extra) {
				Log.e("VideoPlayer", String.format("Error occured: %d, %d\n", what, extra));
				return false;
			}
		});

		player.setOnCompletionListener(new OnCompletionListener() {
			@Override
			public void onCompletion (MediaPlayer mp) {
				if (isLooping()) return;
				prepared = false;
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
			} else {
				player.setDataSource(file.file().getAbsolutePath());
			}
			player.setSurface(new Surface(videoTexture));
			player.prepareAsync();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return true;
	}

	/** Get external texture directly without framebuffer
	 * @return texture handle to be used with external OES target, -1 if texture is not available */
	public int getTextureExternal () {
		if (prepared) {
			return textures[0];
		}
		return -1;
	}

	@Override
	public boolean update () {
		synchronized (this) {
			if (frameAvailable) {
				frameAvailable = false;
				videoTexture.updateTexImage();
				if (renderToFbo) {
					if (fbo == null) {
						fbo = new FrameBuffer(Pixmap.Format.RGB888, player.getVideoWidth(), player.getVideoHeight(), false);
						frame = fbo.getColorBufferTexture();
						frame.setFilter(minFilter, magFilter);
					}
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
				}
				return true;
			}
		}
		return false;
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
		if (player != null && player.isPlaying()) {
			player.stop();
		}
		prepared = false;
	}

	private void setupRenderTexture () {
		renderer = new ImmediateModeRenderer20(4, false, false, 1);
		renderer.setShader(shader);
		transform = new Matrix4().setToOrtho2D(0, 0, 1, 1);

		videoTexture = new SurfaceTexture(textures[0]);
		videoTexture.setOnFrameAvailableListener(this);
	}

	@Override
	public void onFrameAvailable (SurfaceTexture surfaceTexture) {
		synchronized (this) {
			frameAvailable = true;
		}
	}

	@Override
	public void pause () {
		// If it is running
		if (prepared) {
			player.pause();
		} else {
			pauseRequested = true;
		}
	}

	@Override
	public void resume () {
		// If it is running
		if (prepared) {
			player.start();
		}
		pauseRequested = false;
	}

	@Override
	public void dispose () {
		stop();
		if (player != null) player.release();

		videoTexture.detachFromGLContext();

		GLES20.glDeleteTextures(1, textures, 0);

		if (fbo != null) fbo.dispose();
		shader.dispose();
		renderer.dispose();
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
		return player.getVideoWidth();
	}

	@Override
	public int getVideoHeight () {
		return player.getVideoHeight();
	}

	@Override
	public boolean isPlaying () {
		return player.isPlaying();
	}

	@Override
	public void setVolume (float volume) {
		currentVolume = volume;
		player.setVolume(volume, volume);
	}

	@Override
	public float getVolume () {
		return currentVolume;
	}

	@Override
	public void setLooping (boolean looping) {
		player.setLooping(looping);
	}

	@Override
	public boolean isLooping () {
		return player.isLooping();
	}

	@Override
	public int getCurrentTimestamp () {
		return player.getCurrentPosition();
	}
}
