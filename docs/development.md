# Development guide

<!-- index: operate | Build, test, device verification, and release setup | Setting up a development environment, testing on a device, or preparing a release. -->

This is the practical guide for working on FLUX. The product feature list lives in
[`../README.md`](../README.md); module ownership and seams in
[`architecture/app.md`](./architecture/app.md); visual rules in
[`design.md`](./design.md); security reporting in [`../SECURITY.md`](../SECURITY.md).

## Environment

- **JDK 17** (Gradle rejects newer major JDKs on the current AGP).
- **Android SDK 36** with build tools 36. `compileSdk`/`targetSdk` are 36,
  `minSdk` is 26.
- Gradle is provided by the wrapper (`./gradlew`), pinned to 8.13 with a
  distribution checksum.
- An Android device on Android 8.0+ with developer options/USB debugging, or an
  emulator. A landscape tablet is the reference form factor.

Point Gradle at the SDK with `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or a
`local.properties` file (git-ignored):

```properties
sdk.dir=/path/to/Android/sdk
```

## Build and test

```bash
./gradlew :app:testDebugUnitTest    # hermetic JVM suite
./gradlew :app:lintDebug            # Android lint; errors block
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # minified APK (unsigned unless configured)
```

The JVM suite covers the audio core, DSP, tempo maps, rekordbox parsing and
matching, transport policy and library ranking. It needs no device and no
private library: `app/src/test/resources/collection.xml` is synthetic.

Useful focused commands:

```bash
./gradlew :app:testDebugUnitTest --tests 'app.flux.dj.AudioCoreTest'
./gradlew :app:testDebugUnitTest --tests 'app.flux.dj.RekordboxTest'
```

## Install and run on a device

```bash
adb devices
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s FLUX FLUX-SYNC FLUX-STATE FLUX-WAVE
```

Installing restarts the app; do not install while a set is playing. The app is
landscape-first and requests media access on first library use. Microphone
alignment and Bluetooth device names are optional and requested only when used.

## Put a rekordbox library on the device

`scripts/sync-music.sh` copies the music folder (and any `collection.xml` in it)
to a connected device, incrementally, and asks the media scanner to index it:

```bash
scripts/sync-music.sh                       # ~/Music/dj songs → /sdcard/Music/dj songs
scripts/sync-music.sh "/path/to/folder"     # any folder
XML=~/Downloads/collection.xml scripts/sync-music.sh   # push a newer XML as collection.xml
ANDROID_SERIAL=a6103e95 scripts/sync-music.sh          # several devices attached
```

`adb push --sync` sends only files whose mtime/size differ, so a repeat run is
cheap. Keep the destination under `/sdcard/Music` and add it once in FLUX
(Library → ＋ Folder → Music → the folder): the same tree then survives app
restarts, and because the folder holds `collection.xml`, one pick imports the
audio and the rekordbox grids, cues and playlists together. The XML path in the
collection is the desktop's, so matching is by file name/title/artist — the same
matcher the import report describes.

## What to test on a device

The JVM suite cannot prove audio routing, acoustic timing or the UI. For a change
that touches them, check:

1. Load a local track on both decks; play, cue, scratch, loop and hot-cue it.
2. Switch output modes (single / two devices / split) and confirm each device
   plays what the Outputs sheet says.
3. Run timing/alignment with the microphone and confirm the waveform, grid and
   pluck stay with the sound; then nudge it manually.
4. Import a `collection.xml`, load a tempo-map track and confirm the grid/cues.
5. Sign in to Plex and play a streamed track; test a Drive folder with
   `collection.xml`.
6. Rotate/restart the app and confirm last-loaded tracks, cues and settings
   survive.

Record what was actually observed and what remains unverified in the issue or
PR. A hardware check that was not run stays an open gate, not a pass.

## Release signing

The release build is **unsigned** unless all four variables are present:

```bash
export FLUX_KEYSTORE=/absolute/path/to/release.jks
export FLUX_KEYSTORE_PASSWORD=...
export FLUX_KEY_ALIAS=...
export FLUX_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

The version comes from a `vX.Y.Z` tag or `FLUX_VERSION`; no hard-coded version.
Only the signed `app-release.apk` is eligible for a tagged GitHub Release; the
debug APK is an internal workflow artifact. Never commit a keystore or its
passwords.

## Project structure

```
app/src/main/java/app/flux/dj/
  audio/        Engine, Deck, Track, Decoder, Analyzer, DSP, Synth, MicAlign
  ui/           Compose screens, Canvas widgets, Theme tokens
  Controller.kt observable state root, poll/init
  LibraryController.kt, CloudController.kt, RekordboxController.kt,
  OutputController.kt, GridController.kt
  Rekordbox.kt, Cloud.kt, Xml.kt, Http.kt
app/src/test/   JVM tests + synthetic collection.xml
docs/           product, architecture, design, security and kit docs
```

The repository also carries the
[AI_TEMPLATE](https://github.com/TomSzenessy/AI_TEMPLATE) agent kit. You do not
need it to build the app, but `AGENTS.md`, `project.toml`, `make check` and the
issue contract explain the repository rules. If Python 3.11+ is installed:

```bash
make start      # session brief (kit)
make check      # docs/issue/manifest structure
make done       # full local gate (tests, lint, docs)
```

`make done` may stop on an external CI gate when GitHub Actions is unavailable;
the Gradle commands above are the app-level source of truth.

## Code rules

- **Real-time audio:** no allocations, file I/O, logging or blocking locks in
  `Engine`/`Deck`/`Dsp` render paths; use the control thread for those. See
  `.agents/rules/audio-render-loop.md`.
- **One owner per fact:** tempo/grid/cue data has one representation; persistence
  keys have one owning controller module.
- **UI:** 48 dp touch targets, content descriptions/Role semantics, no raw
  colours/radii outside `Theme.kt`, static copy in `strings.xml`.
- **Docs:** a behavior change updates its covering document; `make check`
  enforces doc-code bindings.

## Troubleshooting

- **Gradle can't find the SDK:** set `ANDROID_HOME` or `local.properties`.
- **`./gradlew` permission denied:** `chmod +x gradlew`.
- **No sound on a device:** check the Outputs sheet; Android may route media to
  a connected Bluetooth device with its own volume. Try "Two devices" or
  disconnect Bluetooth once.
- **A track stops at 12 minutes:** the decoder cap is deliberate; the app shows a
  truncation notice.
- **Plex sign-in fails:** check the network and that the server advertises a
  reachable connection; the app refuses public plain-HTTP and cross-host
  redirects that would leak the token.
