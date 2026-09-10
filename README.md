# DEPRECATED

**This repository is no longer maintained or consumed.** As of 2026-09-10 the player source was
vendored into the `losikov/react-native-track-player` fork
(branch `dev-android-auto-turbo-media3`, `android/src/main/java/com/doublesymmetry/kotlinaudio/`)
and is being migrated there from ExoPlayer 2.19.1 to AndroidX Media3. The JitPack artifact built
from this repo (last pin: `c98a0dd28492489b3ad52bf431312e79e2f096ab`) stays available for rollback
only. Do not open PRs or push fixes here; make them in the RNTP fork.

---

# KotlinAudio

[![](https://jitpack.io/v/doublesymmetry/KotlinAudio.svg)](https://jitpack.io/#doublesymmetry/KotlinAudio)

KotlinAudio is an Android audio player written in Kotlin, making it simpler to work with audio playback from streams and files.

Inspired by [SwiftAudioEx](https://github.com/doublesymmetry/SwiftAudioEx). Our aim is to have feature parity with the iOS equivalent.

<div align="left" valign="middle">
<a href="https://runblaze.dev">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://www.runblaze.dev/logo_dark.png">
   <img align="right" src="https://www.runblaze.dev/logo_light.png" height="102px"/>
 </picture>
</a>

<br style="display: none;"/>

_[Blaze](https://runblaze.dev) sponsors KotlinAudio by providing super fast Apple Silicon based macOS Github Action Runners. Use the discount code `RNTP50` at checkout to get 50% off your first year._

</div>

## Example

To see the audio player in action, run the example project!
To run the example project, clone the repo, then open in Android Studio.
Choose "kotlin-audio-example" in the run target and run it in a simulator
(or on an actual device).

## Requirements

minSDK 21

## Installation

### Gradle

```gradle
implementation 'com.github.doublesymmetry:kotlinaudio:v2.0.0'
```

## Usage

### AudioPlayer

To get started playing some audio:

```swift
let player = AudioPlayer()
let audioItem = DefaultAudioItem(audioUrl: "someUrl", type: MediaType.DEFAULT)
player.load(item: audioItem, playWhenReady: true) // Load the item and start playing when the player is ready.
```

To listen for events in the `AudioPlayer`, subscribe to events found in the `event` property of the `AudioPlayer`.
To subscribe to an event:

```kotlin
// jetpack compose
val state = player.event.stateChange.collectAsState(initial = AudioPlayerState.IDLE)

// normal
player.event.stateChange.collect {}
```

#### QueuedAudioPlayer

The `QueuedAudioPlayer` is a subclass of `AudioPlayer` that maintains a queue of audio tracks.

```swift
let player = QueuedAudioPlayer()
let audioItem = DefaultAudioItem(audioUrl: "someUrl", type: MediaType.DEFAULT)
player.add(item: audioItem, playWhenReady: true) // Since this is the first item, we can supply playWhenReady: true to immedietaly start playing when the item is loaded.
```

When a track is done playing, the player will load the next track and update the queue.

##### Navigating the queue

All `AudioItem`s are stored in either `previousItems` or `nextItems`, which refers to items that come prior to the `currentItem` and after, respectively. The queue is navigated with:

```swift
player.next() // Increments the queue, and loads the next item.
player.previous() // Decrements the queue, and loads the previous item.
player.jumpToItem(index:) // Jumps to a certain item and loads that item.
```

##### Manipulating the queue

```swift
 player.remove(index:) // Remove a specific item from the queue.
 player.removeUpcomingItems() // Remove all items in nextItems.
```

## Tests

Instrumented tests need a running emulator or device:

```bash
./gradlew :kotlin-audio:connectedDebugAndroidTest
# one class:
./gradlew :kotlin-audio:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.doublesymmetry.kotlinaudio.CbrMp3SeekAccuracyTest
```

`CbrMp3SeekAccuracyTest` guards the constant-bitrate MP3 seeking in
`players/components/CbrMp3Extractor.kt`. It plays a committed 5-minute CBR fixture through the same
extractor stack `BaseAudioPlayer` uses, with a `DataSource.Factory` that records every byte offset
requested, and asserts each seek fetches within one MPEG frame of `firstAudioFrame + t * bitrate / 8`.
Measured on an API 30 emulator: within 33–309 bytes with the extractor, and up to 25465 bytes
(1.59 s of audio) without it.

Two notes on the build, both fixed here rather than worked around in the tests:

- `com.android.tools:r8:8.2.42` is pinned on the buildscript classpath. The D8 that ships with AGP
  7.3.1 refuses to dex a `PermittedSubclasses` attribute ("Sealed classes are not supported as
  program classes"), which Kotlin 1.7.10 at `jvmTarget = 17` emits for every `sealed class` in
  `models/` — so this project could not dex its own library for instrumentation. Nothing about the
  published AAR changes; its `classes.jar` is dexed by the consuming app.
- `AudioPlayerTest` and `QueuedAudioPlayerTest` now pass a `mediaSessionCallback`
  (`utils/NoopMediaSessionCallback`). They had not been updated when the argument became required,
  and the whole `androidTest` source set failed to compile without it.

## License

KotinAudio is available under the MIT license. See the LICENSE file for more info.
