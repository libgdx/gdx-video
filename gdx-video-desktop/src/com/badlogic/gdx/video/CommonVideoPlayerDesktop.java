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

import java.io.FileNotFoundException;
import java.nio.ByteBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Null;
import com.badlogic.gdx.video.VideoDecoder.VideoDecoderBuffers;

/** Desktop implementation of the VideoPlayer
 *
 * @author Rob Bogie rob.bogie@codepoke.net */
abstract public class CommonVideoPlayerDesktop extends AbstractVideoPlayer implements VideoPlayer {
	VideoDecoder decoder;
	Texture texture;
	Music audio;
	long startTime = 0;
	boolean showAlreadyDecodedFrame = false;

	boolean paused = false;
	boolean looping = false;
	boolean isFirstFrame = true;
	long timeBeforePause = 0;

	int currentVideoWidth, currentVideoHeight;
	int videoBufferWidth;
	VideoSizeListener sizeListener;
	CompletionListener completionListener;
	FileHandle currentFile;

	boolean playing = false;

	public CommonVideoPlayerDesktop () {
	}

	abstract Music createMusic (VideoDecoder decoder, ByteBuffer audioBuffer, int audioChannels, int sampleRate);

	private int getTextureWidth () {
		return videoBufferWidth;
	}

	private int getTextureHeight () {
		return currentVideoHeight;
	}

	@Override
	public boolean play (FileHandle file) throws FileNotFoundException {
		if (file == null) {
			return false;
		}
		if (!file.exists()) {
			throw new FileNotFoundException("Could not find file: " + file.path());
		}

		currentFile = file;

		if (!FfMpeg.isLoaded()) {
			FfMpeg.loadLibraries();
		}

		if (decoder != null) {
			// Do all the cleanup
			stop();
		}

		isFirstFrame = true;
		decoder = new VideoDecoder();
		VideoDecoderBuffers buffers;
		try {
			buffers = decoder.loadFile(file.path());

			if (buffers != null) {
				ByteBuffer audioBuffer = buffers.getAudioBuffer();
				if (audioBuffer != null) {
					if (audio != null) audio.dispose();
					audio = createMusic(decoder, audioBuffer, buffers.getAudioChannels(), buffers.getAudioSampleRate());
				}
				currentVideoWidth = buffers.getVideoWidth();
				currentVideoHeight = buffers.getVideoHeight();
				videoBufferWidth = buffers.getVideoBufferWidth();
				if (texture != null && (texture.getWidth() != getTextureWidth() || texture.getHeight() != getTextureHeight())) {
					texture.dispose();
					texture = null;
				}
			} else {
				return false;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

		if (sizeListener != null) {
			sizeListener.onVideoSize(currentVideoWidth, currentVideoHeight);
		}

		playing = true;
		return true;
	}

	@Override
	public boolean update () {
		if (decoder != null && (!paused || isFirstFrame) && playing) {
			if (!paused && startTime == 0) {
				// Since startTime is 0, this means that we should now display the first frame of the video, and set the
				// time.
				startTime = System.currentTimeMillis();
				if (audio != null) {
					audio.play();
				}
			}

			boolean newFrame = false;
			if (!showAlreadyDecodedFrame) {
				ByteBuffer videoData = decoder.nextVideoFrame();
				if (videoData != null) {
					if (texture == null) {
						texture = new Texture(getTextureWidth(), getTextureHeight(), Format.RGB888);
						texture.setFilter(minFilter, magFilter);
					}
					texture.bind();
					Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, getTextureWidth(), getTextureHeight(), 0, GL20.GL_RGB,
						GL20.GL_UNSIGNED_BYTE, videoData);
					newFrame = true;
				} else if (isFirstFrame) {
					return false;
				} else if (looping) {
					try {
						// NOTE: this just creates a new decoder instead of reusing the existing one.
						play(currentFile);
					} catch (FileNotFoundException e) {
						throw new RuntimeException(e);
					}
					return false;
				} else {
					playing = false;
					if (completionListener != null) {
						completionListener.onCompletionListener(currentFile);
					}
					return false;
				}
			}

			isFirstFrame = false;
			long currentVideoTime = System.currentTimeMillis() - startTime;
			long millisecondsAhead = (long)getCurrentTimestamp() - currentVideoTime;
			showAlreadyDecodedFrame = millisecondsAhead > 20;
			return newFrame;
		}
		return false;
	}

	@Override
	@Null
	public Texture getTexture () {
		return texture;
	}

	/** Will return whether the buffer is filled. At the time of writing, the buffer used can store 10 frames of video. You can
	 * find the value in jni/VideoDecoder.h
	 *
	 * @return whether buffer is filled. */
	@Override
	public boolean isBuffered () {
		if (decoder != null) {
			return decoder.isBuffered();
		}
		return false;
	}

	@Override
	public void stop () {
		playing = false;

		if (audio != null) {
			audio.dispose();
			audio = null;
		}
		if (decoder != null) {
			decoder.dispose();
			decoder = null;
		}

		startTime = 0;
		showAlreadyDecodedFrame = false;
		isFirstFrame = true;
	}

	@Override
	public void pause () {
		if (!paused) {
			paused = true;
			if (audio != null) {
				audio.pause();
			}
			if (startTime != 0L) {
				timeBeforePause = System.currentTimeMillis() - startTime;
			} else {
				timeBeforePause = 0L;
			}
		}
	}

	@Override
	public void resume () {
		if (paused) {
			paused = false;
			if (audio != null) {
				audio.play();
			}
			startTime = System.currentTimeMillis() - timeBeforePause;
		}
	}

	@Override
	public void dispose () {
		stop();
		if (texture != null) {
			texture.dispose();
			texture = null;
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
		return currentVideoWidth;
	}

	@Override
	public int getVideoHeight () {
		return currentVideoHeight;
	}

	@Override
	public boolean isPlaying () {
		return playing;
	}

	@Override
	public void setVolume (float volume) {
		audio.setVolume(volume);
	}

	@Override
	public float getVolume () {
		return audio.getVolume();
	}

	@Override
	public void setLooping (boolean looping) {
		this.looping = looping;
	}

	@Override
	public boolean isLooping () {
		return looping;
	}

	@Override
	public int getCurrentTimestamp () {
		return (int)(decoder.getCurrentFrameTimestamp() * 1000);
	}

}
