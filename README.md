# GDX-Video

![GitHub Workflow Status (master)](https://img.shields.io/github/workflow/status/libgdx/gdx-video/Publish%20Snapshot/master?label=master)

[![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/com.badlogicgames.gdx-video/gdx-video?nexusVersion=2&server=https%3A%2F%2Foss.sonatype.org&label=release)](https://search.maven.org/artifact/com.badlogicgames.gdx-video/gdx-video)
[![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/com.badlogicgames.gdx-video/gdx-video?server=https%3A%2F%2Foss.sonatype.org&label=snapshot)](https://oss.sonatype.org/#nexus-search;gav~com.badlogicgames.gdx-video~gdx-video~~~~kw,versionexpand)

A libGDX cross platform video rendering extension

## Contents
* [Getting Started](#getting-started)
  * [Maven Repositories](#repositories)
  * [Using with Gradle](#gradle-dependency-declarations)
  * [Using with Maven](#maven-dependency-declarations)
* [Issues](#issues)
* [Contributing](#contributing)
  * [Working from source](#working-from-source)
* [Licensing](#licensing)

## Getting Started

Gdx-video is currently available in maven with official builds and snapshot builds. You can find them at the following repositories:

#### Repositories

* **Official**  For official releases, use https://oss.sonatype.org/content/repositories/releases
* **Snapshots** For snapshot builds, use https://oss.sonatype.org/content/repositories/snapshots

#### Gradle dependency declarations
Don't forget to replace _0.0.1_ with the current version!
##### Core:
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video:0.0.1"
```
##### Desktop:
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video-lwjgl:0.0.1"
```
or
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video-lwjgl3:0.0.1"
```

##### Android:
```groovy
implementation "com.badlogicgames.gdx-video:gdx-video-android:0.0.1"
```

##### Html:

```groovy
implementation "com.badlogicgames.gdx-video:gdx-video:0.0.1:sources"
implementation "com.badlogicgames.gdx-video:gdx-video-gwt:0.0.1"
implementation "com.badlogicgames.gdx-video:gdx-video-gwt:0.0.1:sources"
```
You also need to add the following file to your GdxDefinition.gwt.xml in your html project:
`````xml
<inherits name="com.badlogic.gdx.video.gdx_video_gwt" />
`````
#### Maven
Don't forget to replace _0.0.1_ with the current version!

Core:
```xml
<dependency>
  <groupId>com.badlogicgames.gdx-video</groupId>
  <artifactId>gdx-video</artifactId>
  <version>0.0.1</version>
</dependency>
```
Desktop:
```xml
<dependency>
  <groupId>com.badlogicgames.gdx-video</groupId>
  <artifactId>gdx-video-lwjgl</artifactId>
  <version>0.0.1</version>
</dependency>
```
or
```xml
<dependency>
  <groupId>com.badlogicgames.gdx-video</groupId>
  <artifactId>gdx-video-lwjgl3</artifactId>
  <version>0.0.1</version>
</dependency>
```
Android:
```xml
<dependency>
  <groupId>com.badlogicgames.gdx-video</groupId>
  <artifactId>gdx-video-android</artifactId>
  <version>0.0.1</version>
</dependency>
```
## Issues


## Contributing

### Building from source
To build from source, clone or download this repository, then open it in Android Studio. Perform the following command to compile and upload the library in your local repository:

    gradlew clean uploadArchives -PLOCAL=true

See `build.gradle` file for current version to use in your dependencies.

## Licensing
The project is licensed under the Apache 2 License, meaning you can use it free of charge, without strings attached in commercial and non-commercial projects. We love to get (non-mandatory) credit in case you release a game or app using this project!
