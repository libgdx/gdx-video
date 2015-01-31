# GDX-Video
A LibGDX cross platform video rendering extension

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

Gdx-video is available in maven with Official builds and Snapshot builds.  You can find them at the following repositories:

#### Repositories

* **Official**  For official releases, use https://oss.sonatype.org/content/repositories/releases
* **Snapshots** For snapshot builds, use https://oss.sonatype.org/content/repositories/snapshots

#### Gradle dependency declarations
Core:
```groovy
compile "com.badlogicgames.gdxvideo:gdx-video:0.0.1"
```
Desktop:
```groovy
compile "com.badlogicgames.gdxvideo:gdx-video-desktop:0.0.1"
compile "com.badlogicgames.gdxvideo:gdx-video-platform:0.0.1:natives-desktop"
```
Android:
```groovy
compile "com.badlogicgames.gdxvideo:gdx-video-android:0.0.1"
```
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
  <artifactId>gdx-video-desktop</artifactId>
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
