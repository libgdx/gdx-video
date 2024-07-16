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

import static com.badlogic.gdx.Application.LOG_DEBUG;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

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
	long lastFrameID = 0;
	float targetPosition = 0;

	boolean paused = false;
	boolean looping = false;
	boolean isFirstFrame = true;

	int currentVideoWidth, currentVideoHeight;
	int videoBufferWidth;
	VideoSizeListener sizeListener;
	CompletionListener completionListener;
	FileHandle currentFile;

	BufferedInputStream inputStream;
	ReadableByteChannel fileChannel;

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

		if (!FfMpeg.isLoaded()) {
			FfMpeg.loadLibraries();
		}

		if (decoder != null) {
			// Do all the cleanup
			stop();
		}

		VideoDecoder.setDebug(Gdx.app.getLogLevel() >= LOG_DEBUG);

		currentFile = file;
		inputStream = file.read(256 * 1024);
		fileChannel = Channels.newChannel(inputStream);

		isFirstFrame = true;
		decoder = new VideoDecoder();
		VideoDecoderBuffers buffers;
		try {
			buffers = decoder.loadStream(new VideoDecoder.VideoFileReader() {
				@Override
				public int fillBuffer (ByteBuffer buffer) {
					return readFileContents(buffer);
				}
			});

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

	/** Called by jni to fill in the file buffer.
	 *
	 * @param buffer The buffer that needs to be filled
	 * @return The amount that has been filled into the buffer. */
	private int readFileContents (ByteBuffer buffer) {
		try {
			buffer.rewind();
			return fileChannel.read(buffer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean update () {
		if (decoder != null && (!paused || isFirstFrame) && playing) {
			if (!paused && startTime == 0) {
				// Since startTime is 0, this means that we should now display the first frame of the video, and set the
				// time.
				startTime = System.currentTimeMillis();
				targetPosition = 0;
				if (audio != null) {
					audio.play();
				}
			}

			boolean newFrame = false;

			long currentFrameID = Gdx.graphics.getFrameId();
			if (currentFrameID != lastFrameID) {
				lastFrameID = Gdx.graphics.getFrameId();
				// Update video position
				if (audio != null) {
					targetPosition = audio.getPosition();
				} else {
					float delta = Gdx.graphics.getDeltaTime();
					if (delta < 0.25f) {
						targetPosition += delta;
					}
				}
			}

			float currentPosition = isFirstFrame ? -1 : (float)decoder.getCurrentFrameTimestamp();

			while (currentPosition <= targetPosition) {
				ByteBuffer videoData = decoder.nextVideoFrame();
				if (videoData != null) {
					float newPosition = (float)decoder.getCurrentFrameTimestamp();
					if (newPosition == currentPosition) {
						// A frame was repeated (not loaded fast enough)
						break;
					}
					currentPosition = newPosition;
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
						float volume = getVolume();
						play(currentFile);
						setVolume(volume);
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
		if (inputStream != null) {
			try {
				inputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			inputStream = null;
		}

		startTime = 0;
		isFirstFrame = true;
	}

	@Override
	public void pause () {
		if (!paused) {
			paused = true;
			if (audio != null) {
				audio.pause();
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
		if (audio != null) audio.setVolume(volume);
	}

	@Override
	public float getVolume () {
		if (audio == null) return 0;
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
