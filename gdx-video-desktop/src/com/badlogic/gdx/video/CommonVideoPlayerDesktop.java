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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Null;
import com.badlogic.gdx.video.VideoDecoder.VideoDecoderBuffers;

/** Desktop implementation of the VideoPlayer
 *
 * @author Rob Bogie rob.bogie@codepoke.net */
abstract public class CommonVideoPlayerDesktop extends AbstractVideoPlayer {
	VideoDecoder decoder;
	Texture texture;
	DecoderMusic audio;
	long startTime = 0;
	long lastFrameID = 0;
	/** Doubles, as the timestamps keep increasing while looping */
	double targetPosition = 0;

	boolean paused = false;
	boolean looping = false;
	boolean isFirstFrame = true;
	/** Set when the end was reached without looping */
	boolean completed = false;
	/** How often the video has looped before the displayed frame */
	int loop = 0;
	/** Position of the displayed frame within the video, in milliseconds */
	int shownTimestamp = 0;
	/** Frame taken from the decoder, but not shown yet, as it isn't due yet. If playback completed because looping got disabled,
	 * it's the first frame of the next loop, which is shown if playback continues with looping enabled again. */
	ByteBuffer pendingFrame;
	/** How many frames the decoder had moved on when the last one was taken, to tell repeated frames apart */
	int framesDisplayed = 0;

	int currentVideoWidth, currentVideoHeight;
	int videoBufferWidth;
	VideoSizeListener sizeListener;
	CompletionListener completionListener;
	FileHandle currentFile;

	/** Only set if the file can't be accessed directly, e.g. when it is inside a jar */
	InputStream inputStream;
	ReadableByteChannel fileChannel;
	long filePosition;

	boolean playing = false;

	public CommonVideoPlayerDesktop () {
	}

	/** @return The music playing the decoder's audio, or null if audio isn't available */
	abstract DecoderMusic createMusic (VideoDecoder decoder, ByteBuffer audioBuffer, int audioChannels, int sampleRate);

	private int getTextureWidth () {
		return videoBufferWidth;
	}

	private int getTextureHeight () {
		return currentVideoHeight;
	}

	@Override
	public boolean load (FileHandle file) throws FileNotFoundException {
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
		openFile(true);

		isFirstFrame = true;
		completed = false;
		resetShownFrame();
		decoder = new VideoDecoder();
		// Set before the decoding thread starts, short videos may reach their end right away
		decoder.setLooping(looping);
		VideoDecoderBuffers buffers;
		try {
			buffers = decoder.loadStream(new VideoDecoder.VideoFileReader() {
				@Override
				public int fillBuffer (ByteBuffer buffer) {
					return readFileContents(buffer);
				}

				@Override
				public long seek (long offset, int whence) {
					return seekFileContents(offset, whence);
				}
			}, fileChannel instanceof FileChannel);

			if (buffers != null) {
				ByteBuffer audioBuffer = buffers.getAudioBuffer();
				if (audioBuffer != null) {
					audio = createMusic(decoder, audioBuffer, buffers.getAudioChannels(), buffers.getAudioSampleRate());
					if (audio == null) {
						// Otherwise the decoder would keep the audio it reads ahead, as nothing reads it
						decoder.disableAudio();
					}
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
			Gdx.app.error("gdx-video", "Error loading video", e);
			return false;
		}

		if (sizeListener != null) {
			sizeListener.onVideoSize(currentVideoWidth, currentVideoHeight);
		}
		return true;
	}

	@Override
	public void play () {
		playing = true;
		if (paused) {
			paused = false;
			if (audio != null) {
				audio.play();
			}
		}
		if (completed) {
			completed = false;
			if (pendingFrame != null && looping) {
				// The decoder is already in the next loop, so continue there instead of starting over
				if (audio != null) {
					audio.play();
				}
			} else {
				// update() starts the audio again
				rewind();
			}
		}
	}

	/** @param direct Whether the file may be read directly. Otherwise it's read as a stream. */
	private void openFile (boolean direct) {
		closeFile();
		InputStream stream = currentFile.read();
		if (direct && stream.getClass() == FileInputStream.class) {
			fileChannel = ((FileInputStream)stream).getChannel();
		} else {
			inputStream = new BufferedInputStream(stream, 256 * 1024);
			fileChannel = Channels.newChannel(inputStream);
		}
		filePosition = 0;
	}

	private void closeFile () {
		try {
			if (fileChannel != null) {
				fileChannel.close();
			}
		} catch (IOException e) {
			Gdx.app.error("gdx-video", "Error closing file", e);
		}
		fileChannel = null;
		inputStream = null;
	}

	/** Called by jni to fill in the file buffer.
	 *
	 * @param buffer The buffer that needs to be filled
	 * @return The amount that has been filled into the buffer. */
	private int readFileContents (ByteBuffer buffer) {
		try {
			if (fileChannel == null) {
				// Reopening the file failed
				return -1;
			}
			buffer.rewind();
			int read = fileChannel.read(buffer);
			if (read > 0) {
				filePosition += read;
			}
			return read;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Called by jni to seek within the file. Seeking backwards in a stream reopens it.
	 *
	 * @return The new position, the file size or a negative value on failure. */
	private long seekFileContents (long offset, int whence) {
		try {
			FileChannel seekableChannel = fileChannel instanceof FileChannel ? (FileChannel)fileChannel : null;
			if (whence == VideoDecoder.SEEK_SIZE) {
				return seekableChannel != null ? seekableChannel.size() : -1;
			}

			long target;
			switch (whence) {
			case VideoDecoder.SEEK_SET:
				target = offset;
				break;
			case VideoDecoder.SEEK_END:
				if (seekableChannel == null) return -1;
				target = seekableChannel.size() + offset;
				break;
			default:
				return -1;
			}
			if (target < 0) return -1;

			if (seekableChannel != null) {
				seekableChannel.position(target);
			} else {
				if (target < filePosition) {
					// Stays a stream, as the decoder expects seeking to be expensive
					openFile(false);
				}
				while (filePosition < target) {
					long skipped = inputStream.skip(target - filePosition);
					if (skipped <= 0) {
						// skip() may make no progress without having reached the end
						if (inputStream.read() < 0) return -1;
						skipped = 1;
					}
					filePosition += skipped;
				}
			}
			filePosition = target;
			return target;
		} catch (IOException | RuntimeException e) {
			// Called from the decoding thread, so nothing would handle the exception. FileHandle.read() throws a
			// GdxRuntimeException.
			Gdx.app.error("gdx-video", "Error seeking in file", e);
			return -1;
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
					targetPosition = audio.getPlayedSeconds();
				} else {
					float delta = Gdx.graphics.getDeltaTime();
					if (delta < 0.25f) {
						targetPosition += delta;
					}
				}
			}

			while (true) {
				if (pendingFrame == null) {
					ByteBuffer videoData = decoder.nextVideoFrame();
					if (videoData == null) {
						if (isFirstFrame) {
							return false;
						}
						// The video ended. The audio may be longer, so playback ends with it, while the last frame is still shown.
						if (!isShownLoopPlayed()) {
							break;
						}
						if (looping) {
							// The decoder couldn't loop by itself
							rewind();
						} else {
							complete();
						}
						return newFrame;
					}
					int newFramesDisplayed = decoder.getFramesDisplayed();
					if (newFramesDisplayed == framesDisplayed) {
						// A frame was repeated (not loaded fast enough). Not told by the timestamp, which may not change
						// either after a long time of looping, due to its precision.
						break;
					}
					framesDisplayed = newFramesDisplayed;
					// It stays the decoder's current frame until the next one is taken, so it can be shown later
					pendingFrame = videoData;
				}

				int frameLoop = decoder.getCurrentFrameLoop();
				if (frameLoop != loop && !looping) {
					// The decoder already continued with the next loop before looping got disabled. Playback ends with the
					// audio of the shown loop, which may be longer than its video. The frame is kept, in case playback
					// continues with looping enabled again.
					if (isShownLoopPlayed()) {
						complete();
						return newFrame;
					}
					break;
				}
				// The first frame is shown right away, the others once they are due. Otherwise e.g. the first frame of the
				// next loop would be shown while the audio of the previous one is still playing.
				if (!isFirstFrame && decoder.getCurrentFrameTimestamp() > targetPosition) {
					break;
				}
				showFrame(pendingFrame, frameLoop);
				pendingFrame = null;
				newFrame = true;
				isFirstFrame = false;
			}
			return newFrame;
		}
		return false;
	}

	/** Uploads the decoder's current frame, which videoData points to.
	 *
	 * @param frameLoop The loop the frame is in */
	private void showFrame (ByteBuffer videoData, int frameLoop) {
		loop = frameLoop;
		shownTimestamp = (int)(decoder.getCurrentFramePosition() * 1000);
		if (texture == null) {
			texture = new Texture(getTextureWidth(), getTextureHeight(), Format.RGB888);
			texture.setFilter(minFilter, magFilter);
		}
		texture.bind();
		Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, getTextureWidth(), getTextureHeight(), 0, GL20.GL_RGB,
			GL20.GL_UNSIGNED_BYTE, videoData);
	}

	/** @return Whether playback reached the end of the shown frame's loop, which is the end of the audio if it's longer than the
	 *         video */
	private boolean isShownLoopPlayed () {
		return targetPosition >= decoder.getLoopEnd(loop);
	}

	private void resetShownFrame () {
		framesDisplayed = 0;
		loop = 0;
		shownTimestamp = 0;
		pendingFrame = null;
	}

	private void complete () {
		playing = false;
		completed = true;
		if (audio != null) {
			// Otherwise it keeps playing what the decoder outputs after the end, e.g. the next loop
			audio.pause();
		}
		if (completionListener != null) {
			completionListener.onCompletionListener(currentFile);
		}
	}

	/** Restarts the video from the beginning, preferably without reloading it. */
	private void rewind () {
		if (decoder.rewind()) {
			isFirstFrame = true;
			targetPosition = 0;
			resetShownFrame();
			// Like after loading, update() starts the clock and the audio again
			startTime = 0;
			if (audio != null) {
				// Drops the queued audio, and the audio read from the decoder before rewinding (by resetting it). Not
				// refilled here, as the decoding thread hasn't read anything yet. update() plays it again.
				audio.stop();
			}
		} else {
			resetVideo();
		}
	}

	private void resetVideo () {
		try {
			// NOTE: this just creates a new decoder instead of reusing the existing one.
			float volume = getVolume();
			load(currentFile);
			play();
			setVolume(volume);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
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
		closeFile();

		startTime = 0;
		isFirstFrame = true;
		completed = false;
		resetShownFrame();
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
		if (decoder != null) {
			play();
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
		return playing && !paused;
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
		if (decoder != null) {
			decoder.setLooping(looping);
		}
	}

	@Override
	public boolean isLooping () {
		return looping;
	}

	@Override
	public int getCurrentTimestamp () {
		// Of the shown frame, as the decoder may already be at the next one, e.g. when the next loop started after looping
		// got disabled. Like before looping was done by the decoder, it starts at 0 with every loop.
		return shownTimestamp;
	}

}
