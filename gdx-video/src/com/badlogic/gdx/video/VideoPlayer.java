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

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Null;

/** The VideoPlayer will play a video on any given mesh, using textures. It can be reused, but can only play one video at the
 * time.
 *
 * @author Rob Bogie rob.bogie@codepoke.net */
public interface VideoPlayer extends Disposable {
	interface VideoSizeListener {
		void onVideoSize (float width, float height);
	}

	interface CompletionListener {
		void onCompletionListener (FileHandle file);
	}

	/** This function will prepare the VideoPlayer to play the given file. If a video is already played, it will be stopped, and
	 * the new video will be loaded. The video starts playing as soon as it is loaded.
	 *
	 * @throws FileNotFoundException if the file does not exist
	 * @param file The file containing the video which should be played.
	 * @return Whether loading the file was successful. */
	boolean play (FileHandle file) throws FileNotFoundException;

	/** This function needs to be called every frame, so that the player can update all the buffers and you can draw the frame.
	 * Normal use case is to start rendering after {@link #isBuffered()} returns true.
	 * @return if a new frame is available as texture */
	boolean update ();

	/** @return The current video frame. Null if video was not started yet */
	@Null
	Texture getTexture ();

	/** Whether the buffer containing the video is completely filled. The size of the buffer is platform specific, and cannot
	 * necessarily be depended upon. Review the documentation per platform for specifics.
	 *
	 * @return buffer completely filled or not. */
	boolean isBuffered ();

	/** This pauses the video, and should be called when the app is paused, to prevent the video from playing while being swapped
	 * away. */
	void pause ();

	/** This resumes the video after it is paused. */
	void resume ();

	/** This will stop playing the file, and implicitly clears all buffers and invalidate resources used. */
	void stop ();

	/** This will set a listener for whenever the video size of a file is known (after calling play). This is needed since the size
	 * of the video is not directly known after using the play method.
	 *
	 * @param listener The listener to set */
	void setOnVideoSizeListener (VideoSizeListener listener);

	/** This will set a listener for when the video is done playing. The listener will be called every time a video is done
	 * playing.
	 *
	 * @param listener The listener to set */
	void setOnCompletionListener (CompletionListener listener);

	/** This will return the width of the currently playing video.
	 *
	 * This function cannot be called until the {@link VideoSizeListener} has been called for the currently playing video. If this
	 * callback has not been set, a good alternative is to wait until the {@link #isBuffered} function returns true, which
	 * guarantees the availability of the videoSize.
	 *
	 * @return the width of the video */
	int getVideoWidth ();

	/** This will return the height of the currently playing video.
	 *
	 * This function cannot be called until the {@link VideoSizeListener} has been called for the currently playing video. If this
	 * callback has not been set, a good alternative is to wait until the {@link #isBuffered} function returns true, which
	 * guarantees the availability of the videoSize.
	 *
	 * @return the height of the video */
	int getVideoHeight ();

	/** Whether the video is playing or not.
	 *
	 * @return whether the video is still playing */
	boolean isPlaying ();

	/** This will return the the time passed.
	 *
	 * @return the time elapsed in milliseconds */
	int getCurrentTimestamp ();

	/** Disposes the VideoPlayer and ensures all buffers and resources are invalidated and disposed. */
	@Override
	void dispose ();

	/** This will update the volume of the audio associated with the currently playing video.
	 * @param volume The new volume value in range from 0.0 (mute) to 1.0 (maximum) */
	void setVolume (float volume);

	/** This will return the volume of the audio associated with the currently playing video.
	 * @return The volume of the audio in range from 0.0 (mute) to 1.0 (maximum) */
	float getVolume ();

	void setLooping (boolean looping);

	boolean isLooping ();

	/** This sets the texture filtering used for displaying the video on screen.
	 * @see Texture#setFilter(TextureFilter minFilter, TextureFilter magFilter) */
	void setFilter (TextureFilter minFilter, TextureFilter magFilter);
}
