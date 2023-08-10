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

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Null;

import org.robovm.apple.avfoundation.AVAsset;
import org.robovm.apple.avfoundation.AVAssetReader;
import org.robovm.apple.avfoundation.AVAssetReaderTrackOutput;
import org.robovm.apple.avfoundation.AVAssetTrack;
import org.robovm.apple.avfoundation.AVAudioSettings;
import org.robovm.apple.avfoundation.AVMediaType;
import org.robovm.apple.avfoundation.AVPixelAspectRatio;
import org.robovm.apple.avfoundation.AVPixelBufferAttributes;
import org.robovm.apple.coreaudio.AudioChannelLayout;
import org.robovm.apple.coreaudio.AudioChannelLayoutTag;
import org.robovm.apple.coreaudio.AudioFormat;
import org.robovm.apple.coreaudio.AudioStreamBasicDescription;
import org.robovm.apple.coremedia.CMAudioFormatDescription;
import org.robovm.apple.coremedia.CMFormatDescription;
import org.robovm.apple.coremedia.CMVideoCodecType;
import org.robovm.apple.coremedia.CMVideoDimensions;
import org.robovm.apple.coremedia.CMVideoFormatDescription;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSError;
import org.robovm.apple.foundation.NSErrorException;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.foundation.NSURL;
import org.robovm.apple.photos.PHAssetMediaSubtype;
import org.robovm.objc.block.VoidBlock2;
import org.robovm.rt.bro.NativeObject;

import java.io.FileNotFoundException;
import java.util.List;

/** iOS implementation of the VideoPlayer class.
 *
 * @author Maximilian Wende &lt;dasisdormax@mailbox.org&gt; */
public class VideoPlayerIos implements VideoPlayer {

	AVAsset asset;
	AVAssetReader assetReader;

	/* --- Video --- */
	AVAssetTrack videoTrack;
	CMVideoFormatDescription videoFormat;
	CMVideoDimensions videoDimensions;
	AVAssetReaderTrackOutput videoOutput;

	/* --- Audio --- */
	AVAssetTrack audioTrack;
	CMAudioFormatDescription audioFormat;
	AVAssetReaderTrackOutput audioOutput;

	/* --- Callbacks --- */
	VideoSizeListener videoSizeListener;
	CompletionListener completionListener;

	private void loadAudioTrack(AVAssetTrack track) {
		List<? extends NativeObject> formatDescriptions = track.getFormatDescriptions();
		audioTrack = track;
		audioFormat = formatDescriptions.get(0).as(CMAudioFormatDescription.class);

		AVAudioSettings settings = new AVAudioSettings();
		settings.setFormat(AudioFormat.LinearPCM);
		settings.setNumberOfChannels(2);
		audioOutput = new AVAssetReaderTrackOutput(track, settings);
	}

	private void loadVideoTrack(AVAssetTrack track) {
		videoTrack = track;
		List<? extends NativeObject> formatDescriptions = track.getFormatDescriptions();
		videoFormat = formatDescriptions.get(0).as(CMVideoFormatDescription.class);

		videoDimensions = videoFormat.getDimensions();
		if(videoSizeListener != null) {
			videoSizeListener.onVideoSize(videoDimensions.getWidth(), videoDimensions.getHeight());
		}

		AVPixelBufferAttributes attributes = new AVPixelBufferAttributes();
		attributes.setCompatibleWithMetal(true);
		videoOutput = new AVAssetReaderTrackOutput(track, attributes);
		assetReader.addOutput(videoOutput);
	}

	@Override
	public boolean play(FileHandle file) {
		if(!file.exists()) return false;
		NSURL fileUrl = new NSURL(file.file());
		asset = new AVAsset(fileUrl);
		NSArray<? extends AVAssetTrack> tracks = asset.getTracks();
		try {
			assetReader = new AVAssetReader(asset);
		} catch (NSErrorException e) {
			throw new RuntimeException(e);
		}
		for(AVAssetTrack track : tracks) {
			String mediaType = track.getMediaType();
			System.out.print("Track " + track.getTrackID() + ": ");
			if(mediaType.equals(AVMediaType.Audio.value().toString())) {
				loadAudioTrack(track);
				AudioStreamBasicDescription asbd = audioFormat.getFormatList().getASBD();
				System.out.print("Audio, " + asbd.getFormat() + "@" + (asbd.getSampleRate() / 1000) + "kHz");
			} else if(mediaType.equals(AVMediaType.Video.value().toString())) {
				loadVideoTrack(track);
				System.out.print(
					"Video, " + CMVideoCodecType.valueOf(videoFormat.getMediaSubType()) +
					"@" + getVideoWidth() + "x" + getVideoHeight()
				);
			} else {
				System.out.print("Other (" + mediaType + ")");
			}
			System.out.println();
		}

		return assetReader.startReading();
	}



	@Override
	public boolean update() {

		return true;
	}

	@Override
	public Texture getTexture() {
		return null;
	}

	@Override
	public boolean isBuffered() {
		if(videoTrack == null) return false;
		return true;
	}

	@Override
	public void pause() {

	}

	@Override
	public void resume() {

	}

	@Override
	public void stop() {

	}

	@Override
	public void setOnVideoSizeListener(VideoSizeListener listener) {
		videoSizeListener = listener;
	}

	@Override
	public void setOnCompletionListener(CompletionListener listener) {

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
		return false;
	}

	@Override
	public int getCurrentTimestamp() {
		return 0;
	}

	@Override
	public void dispose() {

	}

	@Override
	public void setVolume(float volume) {

	}

	@Override
	public float getVolume() {
		return 0;
	}

	@Override
	public void setLooping(boolean looping) {

	}

	@Override
	public boolean isLooping() {
		return false;
	}
}
