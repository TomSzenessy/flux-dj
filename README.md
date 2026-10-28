# FLUX — touch DJ for Android

FLUX is an open-source, two-deck DJ app for Android tablets and phones. It brings
the DDJ-FLX4-style performance surface to glass: jog wheels, CDJ cue, sync, loops,
hot cues, a three-band mixer, beat FX and scrolling waveforms, driven by a
pure-Kotlin audio engine.

Built with Kotlin and Jetpack Compose. AndroidX and Kotlin coroutines are the
only third-party dependencies; there is no vendor audio, HTTP, JSON or image
library.

**Status:** active development. The audio engine, library, rekordbox import and
Plex/Drive sources are implemented; hardware-specific routing and a few UX
checks still need a real device. See [Limitations](#limitations) and the
[development guide](docs/development.md).

## Features

- **Decks:** touch jog (scratch + rim pitch-bend), CDJ-style cue, sync with
  phase lock, key lock, slip, quantize, tempo ranges ±6/10/16/50 %, hold-to-nudge.
- **Pads:** 8 hot cues, beat loops, loop rolls, beat jump, an editable sampler,
  and key shift (−4…+3 semitones).
- **Mixer:** trim, 3-band EQ, colour FX (filter / noise / crush / dub echo /
  space), faders, crossfader curves, LED meters and a phase meter.
- **Beat FX:** echo, reverb, flanger, phaser, gate and crush, tempo-synced and
  assignable to deck A, deck B or master.
- **Waveforms:** 3-band scrolling waveforms, beat grid, bar/phrase counter,
  overview strips, pinch zoom and latency-compensated playheads.
- **Beat-grid editor:** move, stretch around a pinned beat, set a downbeat, fine
  BPM, tap tempo.
- **Library intelligence:** background BPM/beat-grid/key/Camelot analysis,
  energy and vibe profiling, harmonic/tempo suggestions, filters, sorting and
  headphone preview.
- **rekordbox:** import `collection.xml` and keep its grids, keys, hot cues and
  playlists. Hand edits win over a later import.
- **Cloud sources:** sign in to Plex and stream the original files, or pick a
  Google Drive/cloud folder. A `collection.xml` in that folder is imported too.
- **Audio routing:** one output, two devices (e.g. aux + Bluetooth) or a split
  cable (L = cue, R = master), with measured latency alignment. Record the
  master mix to WAV.

## Requirements

- JDK 17
- Android SDK **API 36** (build tools 36); `compileSdk`/`targetSdk` are 36,
  `minSdk` is 26
- An Android device or emulator on Android 8.0+ (landscape recommended)

Android Studio is optional; the Gradle wrapper is the source of truth.

## Build and run

```bash
git clone https://github.com/TomSzenessy/flux-dj.git
cd flux-dj

# Point Gradle at the SDK if it is not in ANDROID_HOME/ANDROID_SDK_ROOT:
# echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:testDebugUnitTest    # JVM tests (audio, DSP, rekordbox, transport)
./gradlew :app:lintDebug            # blocking Android lint gate
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A release build is minified and **unsigned** unless you provide
`FLUX_KEYSTORE`, `FLUX_KEYSTORE_PASSWORD`, `FLUX_KEY_ALIAS` and
`FLUX_KEY_PASSWORD`. The version is derived from a `vX.Y.Z` tag (for example
`v1.2.3` → `1.2.3`, version code `10203`). See
[docs/development.md](docs/development.md) for the full setup, device-test and
release notes.

## Project layout

| Path | What lives there |
|---|---|
| `app/src/main/java/app/flux/dj/` | Android app; `audio/` is the mixer, decks, decoder, analyser and DSP |
| `app/src/test/` | Hermetic JVM tests and a synthetic `collection.xml` fixture |
| `docs/architecture/app.md` | Module map and audio/state seams |
| `docs/design.md` | Visual rules, tokens, accessibility and compact layout |
| `docs/development.md` | Build, test, device verification, release signing |
| `docs/` | Product and engineering docs, including the AI_TEMPLATE agent kit |
| `PLAN.md` | Historical design brief (not current status) |

Live work and design discussions are in GitHub Issues.

## Privacy and permissions

FLUX is local-first. It has no account system, telemetry or analytics, and does
not upload your library. The app requests:

- media read access (to index audio on the device),
- `INTERNET` (Plex/Drive streaming only),
- `RECORD_AUDIO` (optional acoustic alignment),
- `BLUETOOTH_CONNECT` (optional output-device names, requested from Outputs).

A Plex token, if you sign in, is kept in app-private preferences and excluded
from Android cloud backup and device transfer. Streamed/downloaded audio and
decoded PCM live in the app cache and can be cleared from the Outputs sheet.
See [`SECURITY.md`](SECURITY.md) for reporting and
[`docs/security.md`](docs/security.md) for the engineering baseline.

## Contributing

Issues and pull requests are welcome. Start with
[`CONTRIBUTING.md`](CONTRIBUTING.md); the repository uses an issue-backed
write-ahead record and a small `make` gate from the included
[AI_TEMPLATE](https://github.com/TomSzenessy/AI_TEMPLATE) kit.

For a quick contribution you only need the Gradle commands above. For larger
changes, run `make check` (repository structure/docs) and `make done` (local
verification) if Python 3.11+ is available.

## Limitations

- Validated on an Android 13 tablet; USB-C routing, split-cable output,
  Bluetooth combinations and acoustic latency still need broader device
  testing.
- The 12-minute decoder cap is deliberate; longer files are truncated with a
  notice.
- Plex playlists/library-section selection and rekordbox writing-back are not
  implemented yet.

## License and trademarks

FLUX is released under the [MIT License](LICENSE).

FLUX is an independent project. It is **not affiliated with, endorsed by or
sponsored by** Pioneer DJ, AlphaTheta, rekordbox, Serato, Plex or Google. Product
names and trademarks belong to their respective owners and are used only to
describe compatibility or the hardware layout that inspired the interface.

<!-- repoctl:project-readme -->
> Project initialized: **flux-dj** (`android`). Agents start with [`AGENTS.md`](./AGENTS.md): `make start`, `make next`, `make done`.
