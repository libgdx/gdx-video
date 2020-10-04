# GDX-Video

![GitHub Workflow Status (master)](https://img.shields.io/github/workflow/status/libgdx/gdx-video/Java%20CI%20with%20Gradle/master?label=master)

A libGDX cross platform video rendering extension

## Contents
* [Getting Started] (#getting-started)
  * [Maven Repositories] (#repositories)
  * [Using with Gradle] (#gradle-dependency-declarations)
  * [Using with Maven] (#maven-dependency-declarations)
* [Issues] (#issues)
* [Contributing] (#contributing)
  * [Working from source] (#working-from-source)
* [Licensing] (#licensing)

## Getting Started

Gdx-video is available in maven with Official builds and Snapshot builds. You can find them at the following repositories:

#### Repositories

* **Official**  For official releases, use https://oss.sonatype.org/content/repositories/releases
* **Snapshots** For snapshot builds, use https://oss.sonatype.org/content/repositories/snapshots

#### Gradle dependency declarations
##### Core:
```groovy
implementation "com.badlogicgames.gdxvideo:gdx-video:0.0.1"
```
##### Desktop:
```groovy
implementation "com.badlogicgames.gdxvideo:gdx-video-lwjgl:0.0.1"
implementation "com.badlogicgames.gdxvideo:gdx-video-platform:0.0.1:natives-desktop"
```
or
```groovy
implementation "com.badlogicgames.gdxvideo:gdx-video-lwjgl3:0.0.1"
implementation "com.badlogicgames.gdxvideo:gdx-video-platform:0.0.1:natives-desktop"
```

##### Android:
```groovy
implementation "com.badlogicgames.gdxvideo:gdx-video-android:0.0.1"
```

##### Html:

```groovy
implementation "com.badlogicgames.gdxvideo:gdx-video:0.0.1:sources"
implementation "com.badlogicgames.gdxvideo:gdx-video-gwt:0.0.1"
implementation "com.badlogicgames.gdxvideo:gdx-video-gwt:0.0.1:sources"
```
You also need to add the following file to your GdxDefinition.gwt.xml in your html project:
`````xml
<inherits name="com.badlogic.gdx.video.gdx_video_gwt" />
`````
#### Maven
Core:
```xml
<dependency>
  <groupId>com.badlogicgames.gdxvideo</groupId>
  <artifactId>gdx-video</artifactId>
  <version>0.0.1</version>
</dependency>
```
Desktop:
```xml
<dependency>
  <groupId>com.badlogicgames.gdxvideo</groupId>
  <artifactId>gdx-video-lwjgl</artifactId>
  <version>0.0.1</version>
</dependency>
<dependency>
  <groupId>com.badlogicgames.gdxvideo</groupId>
  <artifactId>gdx-video-platform</artifactId>
  <version>0.0.1</version>
  <classifier>natives-desktop</classifier>
</dependency>
```
Android:
```xml
<dependency>
  <groupId>com.badlogicgames.gdxvideo</groupId>
  <artifactId>gdx-video-android</artifactId>
  <version>0.0.1</version>
</dependency>
```
## Issues


## Contributing

### Working from source

## Licensing
The project is licensed under the Apache 2 License, meaning you can use it free of charge, without strings attached in commercial and non-commercial projects. We love to get (non-mandatory) credit in case you release a game or app using this project!
