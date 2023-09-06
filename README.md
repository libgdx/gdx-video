# GDX-Video

![GitHub Workflow Status (master)](https://img.shields.io/github/actions/workflow/status/libgdx/gdx-video/publish_snapshot.yml?branch=master)

[![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/com.badlogicgames.gdx-video/gdx-video?nexusVersion=2&server=https%3A%2F%2Foss.sonatype.org&label=release)](https://search.maven.org/artifact/com.badlogicgames.gdx-video/gdx-video)
[![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/com.badlogicgames.gdx-video/gdx-video?server=https%3A%2F%2Foss.sonatype.org&label=snapshot)](https://oss.sonatype.org/#nexus-search;gav~com.badlogicgames.gdx-video~gdx-video~~~~kw,versionexpand)

A libGDX cross platform video rendering extension

## Contents
* [Getting Started](#getting-started)
  * [Repositories](#repositories)
  * [Using with Gradle](#gradle-dependency-declarations)
* [Playing video](#playing-video)
* [Encoding recommendations](#encoding-recommendations)
  * [File format and codec](#file-format-and-codec)
  * [Resolution and framerate](#resolution-and-framerate)
* [Contributing](#contributing)
  * [Building from source](#building-from-source)
  * [Cross-compilation on macOS](#cross-compilation-on-macos)
* [Licensing](#licensing)

## Getting Started

Gdx-video is currently available in maven with official builds and snapshot builds. You can find them at the following repositories:

### Repositories

* **Official**  For official releases, use https://oss.sonatype.org/content/repositories/releases
* **Snapshots** For snapshot builds, use https://oss.sonatype.org/content/repositories/snapshots

### Gradle dependency declarations
##### Core:
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video:$gdxVideoVersion"
```

##### Desktop:
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video-lwjgl3:$gdxVideoVersion"
// or (when using legacy LWJGL2):
implementation "com.badlogicgames.gdx-video:gdx-video-lwjgl:$gdxVideoVersion"
```

##### Android:
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video-android:$gdxVideoVersion"
```

##### iOS (with RoboVM):
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video-robovm:$gdxVideoVersion"
```

##### Html:

```groovy
implementation "com.badlogicgames.gdx-video:gdx-video:$gdxVideoVersion:sources"
implementation "com.badlogicgames.gdx-video:gdx-video-gwt:$gdxVideoVersion"
implementation "com.badlogicgames.gdx-video:gdx-video-gwt:$gdxVideoVersion:sources"
```
You also need to add the following file to your GdxDefinition.gwt.xml in your html project:
`````xml
<inherits name="com.badlogic.gdx.video.gdx_video_gwt" />
`````

## Playing video

First, get a video player for the current platform using

```java
VideoPlayer player = VideoPlayerCreator.createVideoPlayer();
```

Then, open a video from your game's assets.

```java
FileHandle file = Gdx.files.internal("video.webm");
player.play(file);
```

The file gets loaded and starts playing as soon as the first frames
are decoded. Note that loading a file ahead of time is not supported
yet. As a workaround, try to `pause()` the video once it has
started playing and `resume()` playback later.

Once the video has fully loaded, you may retrieve additional
information about the file.

```java
if(player.isBuffered()) {
    int videoWidth = player.getVideoWidth();
    int videoHeight = player.getVideoHeight();
}
```

On each frame, call the `update()` function to acquire new video frames
and keep the decoder running. You may then retrieve the frame using `getTexture()`,
but note that the texture may be larger than the video itself. The provided
`VideoActor` takes care of both updating and drawing when using Scene2D.

Once you are done playing, remember to `dispose()` the video player.

## Encoding recommendations

Depending on the devices your game targets, you may need to encode
your videos with multiple formats and resolutions.

See the following tables for a rough overview, but remember to test
your game on real devices.

### File format and codec

| Format                 | Desktop  | Android  | iOS     | Web |
|------------------------|----------|----------|---------|-----|
| MP4 (H.264/AVC + AAC)  | ❌ *      | ✅ *      | ✅       | ⚠️  |
| MP4 (H.265/HEVC + AAC) | ❌ *      | ⚠️ > 5.0 | ⚠️ > 11 | ⚠️  |
| WebM (VP8 + Vorbis)    | ✅        | ✅        | ❌       | ✅   |
| WebM (VP9 + Opus)      | ✅        | ⚠️ > 5.0 | ❌       | ❔   |
| MKV (AV1 + Opus)       | ✅        | ⚠️ > 10  | ❌       | ⚠️  |

#### Additional notes

**Desktop:** Additional formats and codecs can be enabled when compiling 
gdx-video yourself. See the file [gdx-video-desktop/build.gradle](gdx-video-desktop/build.gradle).

**iOS**: H.265 support notes from apple: <https://support.apple.com/de-de/HT207022>

**Android**: See the following webpage for officially supported media formats: 
<https://developer.android.com/guide/topics/media/platform/supported-formats>.
Note that this support table is not always accurate, especially for devices
and emulator images without Google Play Services. When in doubt, use VP8, VP9
and Vorbis.

### Resolution and framerate

Even when the format is officially supported, not all devices
have hardware acceleration for video decoding. Many video
codecs specify the decoding capabilities as "levels" of bitrate,
video resolution and framerate. Trying to play a video above the
device's capabilities may cause increased power usage, frame drops,
visual or audio artifacts or even game crashes.

#### Examples (using H.264 levels):

| Level | (Mobile) device category | Example 1            | Example 2           |
|-------|--------------------------|----------------------|---------------------|
| 3     | Low end                  | 720 x  480 @  30fps  | -                   |
| 3.1   |                          | 720 x  480 @  60fps  | 1280 x  720 @ 30fps |
| 4     | Budget / mid range       | 1280 x  720 @  60fps | 1920 x 1080 @ 30fps |
| 4.2   |                          | 1280 x  720 @ 120fps | 1920 x 1080 @ 60fps |
| 5.1   | High performance         | 1920 x 1080 @ 120fps | 3840 x 2160 @ 30fps |

#### Possible approaches:

- Use a lower resolution as 'baseline' that works on all target devices
- Ship your videos in multiple resolutions and guess the best one for the
  device (e.g. based on OS version, RAM size or display resolution)

## Contributing

### Building from source

To build from source, clone or download this repository, then open it in Android Studio
and perform a gradle sync. If you get any *ZipFile* errors, watch the logs above and
install the remaining Android SDK components through the SDK manager.

When building for desktop, build the native components using the Gradle tasks
`:gdx-video-desktop:buildFFmpeg{platform}{arch}`
and `:gdx-video-desktop:jnigenBuild{platform}{arch}`.

Perform the following command to compile and upload the library in your local repository:

    ./gradlew publishToMavenLocal

See `build.gradle` file for current version to use in your dependencies.

### Cross-compilation on MacOS

Install the cross compilers using [homebrew](https://brew.sh) with the commands
~~~
brew install mingw-w64 nasm
brew tap messense/macos-cross-toolchains
brew install i686-unknown-linux-gnu x86_64-unknown-linux-gnu x86_64-unknown-linux-gnu arm-unknown-linux-gnueabihf aarch64-unknown-linux-gnu
~~~

## Licensing
The project is licensed under the Apache 2 License, meaning you can use it free of charge, without strings attached in commercial and non-commercial projects. We love to get (non-mandatory) credit in case you release a game or app using this project!
