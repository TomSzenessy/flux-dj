# Stack decision record

<!-- index: operate | Framework/toolchain decision and rationale | Choosing a project stack or replacing an assumed tool. -->

Status: accepted
Project: flux-dj
Owner: TomSzenessy
Date: 2026-10-28

## Decision

- **Language:** Kotlin 2.0.21 (JVM target 17), Gradle 8.11.1 with the Kotlin DSL.
- **UI:** Jetpack Compose (BOM 2024.10.01) and Material 3 for sheets; Canvas for
  waveforms, jog wheels and meters.
- **Audio:** Android `AudioTrack` in `PERFORMANCE_MODE_LOW_LATENCY`, 44.1 kHz
  float, 256-frame blocks, one real-time mixer thread; `MediaCodec` for decode;
  `MediaStore` for the local library; `AudioRecord` for microphone alignment.
- **Cloud:** Plex via `HttpURLConnection` and `org.json`/XML, Google Drive via
  the Storage Access Framework. No vendor SDK.
- **Persistence:** `SharedPreferences` for settings and per-track analysis,
  app-private files for waveform, PCM and history caches.
- **Build and test:** JDK 17, Android SDK 35 (`compileSdk`/`targetSdk` 35,
  `minSdk` 26), JUnit 4 unit tests on the JVM, GitHub Actions for debug and
  release APKs.
- **Dependencies:** AndroidX and Kotlin coroutines only; no third-party audio,
  HTTP, JSON or image libraries.

## Why this, and what else was considered

- **Native Android + Compose** keeps the whole audio path on one thread with
  predictable latency and gives direct access to `AudioTrack`, `MediaCodec`,
  `MediaStore` and `AudioRecord`. A cross-platform toolkit (Flutter, React
  Native) adds a bridge in the audio path and cannot open two independent
  output streams as directly.
- **Pure Kotlin/JVM DSP** avoids an NDK toolchain and keeps the engine unit
  testable on the JVM; the expensive part (decode) already uses the platform
  codec.
- **No third-party libraries** was chosen deliberately: the app is a long-lived
  personal tool, and every dependency is a supply-chain and upgrade cost. The
  cost is hand-written biquads, reverb, XML parsing and HTTP, which the test
  suite covers.
- **SharedPreferences + private files** beat a database for the current data
  shape (a few thousand keyed records); a database was rejected as unnecessary
  complexity until history or analysis queries grow.
- **minSdk 26** reaches the reference tablet and phones while allowing modern
  audio APIs; raising it further would drop devices the owner uses.

## Verification and rollback

- `./gradlew :app:testDebugUnitTest` runs the audio-core, rekordbox and
  tempo-map tests.
- `./gradlew :app:assembleDebug` builds the installable APK; GitHub Actions runs
  debug and release builds on every push.
- A judged on-device session on the reference Android tablet is the final oracle.
- Rollback: the stack is the app itself, so reverting is a `git revert` of the
  change; dependency versions are pinned in the root `build.gradle.kts` and the
  Gradle wrapper (`gradle/wrapper/gradle-wrapper.properties`) is committed.
