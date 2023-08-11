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
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.utils.Null;

import org.robovm.apple.avfoundation.AVAsset;
import org.robovm.apple.avfoundation.AVAssetReader;
import org.robovm.apple.avfoundation.AVAssetReaderTrackOutput;
import org.robovm.apple.avfoundation.AVAssetTrack;
import org.robovm.apple.avfoundation.AVAudioSettings;
import org.robovm.apple.avfoundation.AVMediaType;
import org.robovm.apple.avfoundation.AVPixelAspectRatio;
import org.robovm.apple.avfoundation.AVPixelBufferAttributes;
import org.robovm.apple.avfoundation.AVPlayer;
import org.robovm.apple.avfoundation.AVPlayerItem;
import org.robovm.apple.avfoundation.AVPlayerItemOutput;
import org.robovm.apple.avfoundation.AVPlayerItemTrack;
import org.robovm.apple.avfoundation.AVPlayerItemVideoOutput;
import org.robovm.apple.avfoundation.AVPlayerStatus;
import org.robovm.apple.coreaudio.AudioChannelLayout;
import org.robovm.apple.coreaudio.AudioChannelLayoutTag;
import org.robovm.apple.coreaudio.AudioFormat;
import org.robovm.apple.coreaudio.AudioStreamBasicDescription;
import org.robovm.apple.coremedia.CMAudioFormatDescription;
import org.robovm.apple.coremedia.CMFormatDescription;
import org.robovm.apple.coremedia.CMTime;
import org.robovm.apple.coremedia.CMVideoCodecType;
import org.robovm.apple.coremedia.CMVideoDimensions;
import org.robovm.apple.coremedia.CMVideoFormatDescription;
import org.robovm.apple.corevideo.CVImageBuffer;
import org.robovm.apple.corevideo.CVMetalTexture;
import org.robovm.apple.corevideo.CVOpenGLESTexture;
import org.robovm.apple.corevideo.CVPixelBuffer;
import org.robovm.apple.corevideo.CVPixelBufferAttributes;
import org.robovm.apple.corevideo.CVPixelBufferLockFlags;
import org.robovm.apple.corevideo.CVPixelFormatType;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSError;
import org.robovm.apple.foundation.NSErrorException;
import org.robovm.apple.foundation.NSKeyValueChangeInfo;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.foundation.NSURL;
import org.robovm.apple.photos.PHAssetMediaSubtype;
import org.robovm.objc.block.VoidBlock1;
import org.robovm.objc.block.VoidBlock2;
import org.robovm.objc.block.VoidBooleanBlock;
import org.robovm.rt.bro.NativeObject;
import org.robovm.rt.bro.ptr.BytePtr;

import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/** iOS implementation of the VideoPlayer class.
 *
 * @author Maximilian Wende &lt;dasisdormax@mailbox.org&gt; */
public class VideoPlayerIos implements VideoPlayer {

	protected FileHandle file;
	protected AVAsset asset;
	AVPlayerItem playerItem;
	AVPlayer player;
	private boolean playerIsReady;
	private boolean playerIsPrerolled;
	private boolean pauseRequested;
	private boolean isPlaying;

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

	protected AVPlayerItemVideoOutput createVideoOutput() {
		CVPixelBufferAttributes attributes = new CVPixelBufferAttributes();
		attributes.setPixelFormatType(CVPixelFormatType._24RGB);
		return new AVPlayerItemVideoOutput(attributes);
	}

	protected void loadTracks() {
		// Get and print track infos
		for(AVPlayerItemTrack itemTrack : playerItem.getTracks()) {
			AVAssetTrack track = itemTrack.getAssetTrack();
			String mediaType = track.getMediaType();
			System.out.print("Track " + track.getTrackID() + ": ");
			List<? extends NativeObject> formatDescriptions = track.getFormatDescriptions();
			if (mediaType.equals(AVMediaType.Audio.value().toString())) {
				audioTrack = track;
				audioFormat = formatDescriptions.get(0).as(CMAudioFormatDescription.class);
				AudioStreamBasicDescription asbd = audioFormat.getFormatList().getASBD();
				System.out.print("Audio, " + asbd.getFormat() + "@" + (asbd.getSampleRate() / 1000) + "kHz");
			} else if (mediaType.equals(AVMediaType.Video.value().toString())) {
				videoTrack = track;
				videoFormat = formatDescriptions.get(0).as(CMVideoFormatDescription.class);
				videoDimensions = videoFormat.getDimensions();
				if(videoSizeListener != null) {
					videoSizeListener.onVideoSize(videoDimensions.getWidth(), videoDimensions.getHeight());
				}
				System.out.print("Video, " + CMVideoCodecType.valueOf(videoFormat.getMediaSubType())
						+ "@" + getVideoWidth() + "x" + getVideoHeight());
			} else {
				System.out.print("Other (" + mediaType + ")");
			}
			System.out.println();
		}
	}

	private void onPlayerReady () {
		// Preroll player
		player.prerollAtRate(1.0f, new VoidBooleanBlock() {
			@Override
			public void invoke(boolean success) {
				if(!success) return;
				onPlayerPrerolled();
			}
		});
	}

	private void onPlayerPrerolled() {
		loadTracks();
		playerIsPrerolled = true;
		if(!pauseRequested) {
			resume();
		}
	}

	@Override
	public boolean play(FileHandle file) {
		dispose();

		if(!file.exists()) return false;
		this.file = file;
		NSURL fileUrl = new NSURL(file.file());
		asset = new AVAsset(fileUrl);
		playerItem = new AVPlayerItem(asset);
		player = new AVPlayer(playerItem);

		player.addKeyValueObserver("status", new NSObject.NSKeyValueObserver() {
			@Override
			public void observeValue(String keyPath, NSObject object, NSKeyValueChangeInfo change) {
				AVPlayer player = object.as(AVPlayer.class);
				System.out.println("Player status is " + player.getStatus() + ".");
				if(!playerIsReady && player.getStatus() == AVPlayerStatus.ReadyToPlay) {
					playerIsReady = true;
					onPlayerReady();
				}
			}
		});

		AVPlayerItem.Notifications.observeDidPlayToEndTime(playerItem, new VoidBlock1<AVPlayerItem>() {
			@Override
			public void invoke(AVPlayerItem avPlayerItem) {
				if(completionListener != null) {
					completionListener.onCompletionListener(VideoPlayerIos.this.file);
				}
			}
		});

		videoOutput = createVideoOutput();
		playerItem.addOutput(videoOutput);

		return true;
	}

	protected void updateTextureFromBuffer(CVImageBuffer buffer) {
		CVPixelBuffer pixelBuffer = buffer.as(CVPixelBuffer.class);
		texture.bind();
		pixelBuffer.lockBaseAddress(CVPixelBufferLockFlags.ReadOnly);
		ByteBuffer bytes = pixelBuffer.getBaseAddress().as(BytePtr.class).asByteBuffer((int)pixelBuffer.getDataSize());
		Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, getVideoWidth(), getVideoHeight(),
				0, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE, bytes);
		pixelBuffer.unlockBaseAddress(CVPixelBufferLockFlags.ReadOnly);
	}

	@Override
	public boolean update() {
		if(player == null) return false;
		CMTime position = player.getCurrentTime();
		if(!videoOutput.hasNewPixelBufferForItemTime(position)) return false;
		// TODO: use CVOpenGLESTexture or CVMetalTexture directly to reduce
		//       the number of copies required
		CVImageBuffer buffer = videoOutput.getPixelBufferForItemTime(position, null);
		if(buffer == null) {
			return false;
		}
		if(texture == null) {
			texture = new Texture(getVideoWidth(), getVideoHeight(), Pixmap.Format.RGB888);
		}
		updateTextureFromBuffer(buffer);
		return true;
	}

	@Override
	public Texture getTexture() {
		return texture;
	}

	@Override
	public boolean isBuffered() {
		return playerIsPrerolled;
	}

	@Override
	public void pause() {
		if(!playerIsPrerolled) {
			pauseRequested = true;
		} else {
			player.pause();
			isPlaying = false;
		}
	}

	@Override
	public void resume() {
		pauseRequested = false;
		if(playerIsPrerolled) {
			player.play();
			isPlaying = true;
		}
	}

	@Override
	public void stop() {
		if(player != null) {
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
	public void setOnVideoSizeListener(VideoSizeListener listener) {
		videoSizeListener = listener;
	}

	@Override
	public void setOnCompletionListener(final CompletionListener listener) {
		completionListener = listener;
	}

	@Override
	public int getVideoWidth() {
		if(videoDimensions == null) return 0;
		return videoDimensions.getWidth();
	}

	@Override
	public int getVideoHeight() {
		if(videoDimensions == null) return 0;
		return videoDimensions.getHeight();
	}

	@Override
	public boolean isPlaying() {
		return isPlaying;
	}

	@Override
	public int getCurrentTimestamp() {
		if(player == null) return 0;
		return (int)(player.getCurrentTime().getSeconds() * 1000);
	}

	@Override
	public void dispose() {
		stop();
		if(texture != null) {
			texture.dispose();
		}
		texture = null;
	}

	@Override
	public void setVolume(float volume) {
		if(player == null) return;
		player.setVolume(volume);
	}

	@Override
	public float getVolume() {
		if(player == null) return 0f;
		return player.getVolume();
	}

	@Override
	public void setLooping(boolean looping) {
		// TODO: not supported
	}

	@Override
	public boolean isLooping() {
		return false;
	}
}
