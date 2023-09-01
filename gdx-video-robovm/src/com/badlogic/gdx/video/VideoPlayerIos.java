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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import org.robovm.apple.avfoundation.AVAsset;
import org.robovm.apple.avfoundation.AVAssetTrack;
import org.robovm.apple.avfoundation.AVMediaType;
import org.robovm.apple.avfoundation.AVPlayer;
import org.robovm.apple.avfoundation.AVPlayerItem;
import org.robovm.apple.avfoundation.AVPlayerItemVideoOutput;
import org.robovm.apple.avfoundation.AVPlayerStatus;
import org.robovm.apple.coreaudio.AudioStreamBasicDescription;
import org.robovm.apple.coremedia.CMAudioFormatDescription;
import org.robovm.apple.coremedia.CMTime;
import org.robovm.apple.coremedia.CMVideoCodecType;
import org.robovm.apple.coremedia.CMVideoDimensions;
import org.robovm.apple.coremedia.CMVideoFormatDescription;
import org.robovm.apple.corevideo.CVImageBuffer;
import org.robovm.apple.corevideo.CVPixelBuffer;
import org.robovm.apple.corevideo.CVPixelBufferAttributes;
import org.robovm.apple.corevideo.CVPixelBufferLockFlags;
import org.robovm.apple.corevideo.CVPixelFormatType;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSError;
import org.robovm.apple.foundation.NSKeyValueChangeInfo;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.foundation.NSURL;
import org.robovm.objc.block.VoidBlock1;
import org.robovm.objc.block.VoidBlock2;
import org.robovm.objc.block.VoidBooleanBlock;
import org.robovm.rt.bro.NativeObject;
import org.robovm.rt.bro.ptr.BytePtr;

import java.nio.ByteBuffer;
import java.util.List;

/** iOS implementation of the VideoPlayer class.
 *
 * @author Maximilian Wende &lt;dasisdormax@mailbox.org&gt; */
public class VideoPlayerIos extends AbstractVideoPlayer implements VideoPlayer {

	protected FileHandle file;
	protected AVAsset asset;
	AVPlayerItem playerItem;
	AVPlayer player;
	private boolean playerIsReady;
	private boolean playerIsPrerolled;
	private boolean pauseRequested;
	private boolean isPlaying;
	private boolean isLooping;

	/* --- Video --- */
	AVAssetTrack videoTrack;
	CMVideoFormatDescription videoFormat;
	CMVideoDimensions videoDimensions;
	AVPlayerItemVideoOutput videoOutput;
	private Texture texture;

	/* --- Audio --- */
	AVAssetTrack audioTrack;
	CMAudioFormatDescription audioFormat;

	/* --- Callbacks --- */
	private VideoSizeListener videoSizeListener;
	private CompletionListener completionListener;

	protected AVPlayerItemVideoOutput createVideoOutput () {
		CVPixelBufferAttributes attributes = new CVPixelBufferAttributes();
		attributes.setPixelFormatType(CVPixelFormatType._24RGB);
		return new AVPlayerItemVideoOutput(attributes);
	}

	protected void onVideoTrackLoaded (AVAssetTrack track) {
		List<? extends NativeObject> formatDescriptions = track.getFormatDescriptions();
		videoTrack = track;
		videoFormat = formatDescriptions.get(0).as(CMVideoFormatDescription.class);
		videoDimensions = videoFormat.getDimensions();
		if (videoSizeListener != null) {
			videoSizeListener.onVideoSize(videoDimensions.getWidth(), videoDimensions.getHeight());
		}
		String message = "Track " + track.getTrackID() + ": Video, ";
		message += CMVideoCodecType.valueOf(videoFormat.getMediaSubType());
		message += " @ " + getVideoWidth() + "x" + getVideoHeight();
		Gdx.app.debug("VideoPlayer", message);
	}

	protected void onAudioTrackLoaded (AVAssetTrack track) {
		List<? extends NativeObject> formatDescriptions = track.getFormatDescriptions();
		audioTrack = track;
		audioFormat = formatDescriptions.get(0).as(CMAudioFormatDescription.class);
		AudioStreamBasicDescription asbd = audioFormat.getFormatList().getASBD();
		String message = "Track " + track.getTrackID() + ": Audio, ";
		message += asbd.getFormat() + " @ " + (asbd.getSampleRate() / 1000) + "kHz";
		Gdx.app.debug("VideoPlayer", message);
	}

	private void onPlayerReady () {
		// Preroll player
		player.prerollAtRate(1.0f, new VoidBooleanBlock() {
			@Override
			public void invoke (boolean success) {
				if (!success) return;
				onPlayerPrerolled();
			}
		});
	}

	private void onPlayerPrerolled () {
		playerIsPrerolled = true;
		if (!pauseRequested) {
			resume();
		}
	}

	@Override
	public boolean play (FileHandle file) {
		dispose();

		if (!file.exists()) return false;

		Gdx.app.debug("VideoPlayer", "Loading file " + file.path() + " ...");
		this.file = file;
		NSURL fileUrl = new NSURL(file.file());
		asset = new AVAsset(fileUrl);
		playerItem = new AVPlayerItem(asset);
		player = new AVPlayer(playerItem);

		asset.loadTracksWithMediaType(AVMediaType.Video.toString(), new VoidBlock2<NSArray<?>, NSError>() {
			@Override
			public void invoke (NSArray<?> nsObjects, NSError nsError) {
				for (NSObject obj : nsObjects) {
					AVAssetTrack track = obj.as(AVAssetTrack.class);
					onVideoTrackLoaded(track);
				}
			}
		});

		asset.loadTracksWithMediaType(AVMediaType.Audio.toString(), new VoidBlock2<NSArray<?>, NSError>() {
			@Override
			public void invoke (NSArray<?> nsObjects, NSError nsError) {
				for (NSObject obj : nsObjects) {
					AVAssetTrack track = obj.as(AVAssetTrack.class);
					onAudioTrackLoaded(track);
				}
			}
		});

		player.addKeyValueObserver("status", new NSObject.NSKeyValueObserver() {
			@Override
			public void observeValue (String keyPath, NSObject object, NSKeyValueChangeInfo change) {
				AVPlayer player = object.as(AVPlayer.class);
				String message = "Player status is now " + player.getStatus() + ".";
				Gdx.app.debug("VideoPlayer", message);
				if (!playerIsReady && player.getStatus() == AVPlayerStatus.ReadyToPlay) {
					playerIsReady = true;
					onPlayerReady();
				}
			}
		});

		AVPlayerItem.Notifications.observeDidPlayToEndTime(playerItem, new VoidBlock1<AVPlayerItem>() {
			@Override
			public void invoke (AVPlayerItem avPlayerItem) {
				if (isLooping) {
					player.seekToTime(CMTime.Zero());
					player.play();
				} else if (completionListener != null) {
					completionListener.onCompletionListener(VideoPlayerIos.this.file);
				}
			}
		});

		videoOutput = createVideoOutput();
		playerItem.addOutput(videoOutput);

		return true;
	}

	protected void updateTextureFromBuffer (CVImageBuffer buffer) {
		CVPixelBuffer pixelBuffer = buffer.as(CVPixelBuffer.class);
		long bpr = pixelBuffer.getBytesPerRow();
		int texWidth = (int)bpr / 3;
		int texHeight = (int)(pixelBuffer.getDataSize() / bpr);
		if (texture == null) {
			texture = new Texture(texWidth, texHeight, Pixmap.Format.RGB888);
			texture.setFilter(minFilter, magFilter);
		}
		texture.bind();
		pixelBuffer.lockBaseAddress(CVPixelBufferLockFlags.ReadOnly);
		ByteBuffer bytes = pixelBuffer.getBaseAddress().as(BytePtr.class).asByteBuffer((int)pixelBuffer.getDataSize());
		Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, texWidth, texHeight, 0, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE, bytes);
		pixelBuffer.unlockBaseAddress(CVPixelBufferLockFlags.ReadOnly);
	}

	@Override
	public boolean update () {
		if (player == null) return false;
		CMTime position = player.getCurrentTime();
		if (!videoOutput.hasNewPixelBufferForItemTime(position)) return false;
		CVImageBuffer buffer = videoOutput.getPixelBufferForItemTime(position, null);
		if (buffer == null) {
			return false;
		}
		updateTextureFromBuffer(buffer);
		return true;
	}

	@Override
	public Texture getTexture () {
		return texture;
	}

	@Override
	public boolean isBuffered () {
		return playerIsPrerolled;
	}

	@Override
	public void pause () {
		if (!playerIsPrerolled) {
			pauseRequested = true;
		} else {
			player.pause();
			isPlaying = false;
		}
	}

	@Override
	public void resume () {
		pauseRequested = false;
		if (playerIsPrerolled) {
			player.play();
			isPlaying = true;
		}
	}

	@Override
	public void stop () {
		if (player != null) {
			player.cancelPendingPrerolls();
			pause();
			playerItem.removeOutput(videoOutput);
		}

		playerIsReady = false;
		playerIsPrerolled = false;
		pauseRequested = false;

		audioTrack = null;
		audioFormat = null;

		videoOutput = null;
		videoFormat = null;
		videoDimensions = null;
		videoTrack = null;

		playerItem = null;
		player = null;
		asset = null;
		file = null;
	}

	@Override
	public void setOnVideoSizeListener (VideoSizeListener listener) {
		videoSizeListener = listener;
	}

	@Override
	public void setOnCompletionListener (final CompletionListener listener) {
		completionListener = listener;
	}

	@Override
	public int getVideoWidth () {
		if (videoDimensions == null) return 0;
		return videoDimensions.getWidth();
	}

	@Override
	public int getVideoHeight () {
		if (videoDimensions == null) return 0;
		return videoDimensions.getHeight();
	}

	@Override
	public boolean isPlaying () {
		return isPlaying;
	}

	@Override
	public int getCurrentTimestamp () {
		if (player == null) return 0;
		return (int)(player.getCurrentTime().getSeconds() * 1000);
	}

	@Override
	public void dispose () {
		stop();
		if (texture != null) {
			texture.dispose();
		}
		texture = null;
	}

	@Override
	public void setVolume (float volume) {
		if (player == null) return;
		player.setVolume(volume);
	}

	@Override
	public float getVolume () {
		if (player == null) return 0f;
		return player.getVolume();
	}

	@Override
	public void setLooping (boolean looping) {
		isLooping = looping;
	}

	@Override
	public boolean isLooping () {
		return isLooping;
	}
}
