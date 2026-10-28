# FLUX app architecture

<!-- index: design | FLUX Android app structure, audio engine seams, and state ownership | Adding a screen, changing audio behaviour, or locating a module. -->
<!-- covers: app/** build.gradle.kts settings.gradle.kts gradle.properties gradlew.bat gradle/** -->

One Android application module (`:app`) in Kotlin. The audio engine is plain
Kotlin/JVM code with no Android UI dependency; the Compose layer observes its
state. There is no second process, no service and no native library.

## Map

| Layer | Package | Responsibility | Entry point |
|---|---|---|---|
| UI | `app.flux.dj.ui` | Compose screens, Canvas instrument widgets, dialogs | `MainScreen` |
| State | `app.flux.dj` | `Controller` (ViewModel): library, analysis cache, imports, suggestions, history, outputs, samples, grid edits | `Controller` |
| Audio | `app.flux.dj.audio` | Mixer thread, decks, decoder, analyser, DSP, synth, microphone alignment | `Engine` |
| Integrations | `app.flux.dj` | rekordbox XML, Plex/Drive clients, MediaStore indexing, hardened XML and transport policy | `Rekordbox`, `Cloud`, `Xml`, `Http`, `Controller` |

## State modules

`Controller` is the observable root; cohesive behaviour lives in extension
modules so one changing fact has one file. Bodies are plain `internal fun
Controller.*`; the Compose layer sees the same calls.

| Module | Owns |
|---|---|
| `Controller.kt` | Snapshot state, construction/init, `poll()`/`needsPolling()`, transport setters, simple actions |
| `LibraryController.kt` | MediaStore library, imports/folders, deck loading/decoding, analysis and caches, suggestions/filters, preview, history, sampler pads |
| `CloudController.kt` | Plex sign-in/sync/pagination, Drive/cloud folder scan, remote cache/download |
| `RekordboxController.kt` | `collection.xml` import, match/apply precedence, duplicate report |
| `OutputController.kt` | Output devices/modes, routing, alignment/mic measurement, output tests |
| `GridController.kt` | Beat-grid editor state and operations |
| `UiModels.kt` | `LibItem`, `HistEntry`, `RefMeta`, `DeckUi`, `ChanUi`, `ViewPrefs`, `DeviceOpt`, media constants |
| `LibraryRanking.kt` | Pure relevance/tempo/source helpers (unit-tested) |
| `Xml.kt`, `Http.kt` | Parser and transport policy |

`project.toml` is the machine-readable source for paths and commands. This table
is the human navigation view, not a second command catalog.

## Seams

- **UI ↔ engine:** the engine exposes volatile state snapshots plus commands; the
  UI polls once per frame and never blocks the render thread. A command that
  allocates runs off the mixer thread. Seeks are published through one atomic
  slot (`Deck.seekReq`) and consumed once per block, so a UI seek cannot be lost.
- **Streaming decode:** a track becomes playable before it is complete. `Track`
  keeps `available` (decoded) separate from `frames`; consumers use
  `decodedFrames`, and the mixer clamps every block to the `pcm` snapshot it
  actually holds.
- **Deck ↔ track:** beat maths lives on `Track` (`beatIndex`, `beatPos`,
  `spbAt`, `bpmAt`), so a single tempo and a rekordbox tempo map behave the same
  to the deck, the UI and sync.
- **Library ↔ sources:** `Controller` normalises MediaStore, imported folders,
  Plex and Drive into one `Track`/library model; source-specific code stays in
  `Cloud`, `Rekordbox` or the SAF helpers.
- **Persistence:** `SharedPreferences` for settings and per-track analysis,
  app-private files for waveform/PCM/history caches. One key format is owned by
  the code that writes it (see `HANDOVER.md` for the recorded layout).
- **Analysis:** decode and analysis run off the main thread; a track becomes
  playable before analysis finishes. rekordbox data is authoritative for grids
  and cues except for explicit hand edits.

## Rules

- No allocations, file I/O, logging or lock acquisition in the mixer render
  loop.
- One owner per fact: tempo/key/cue data has one representation, and UI state is
  derived rather than copied.
- New screens go in `ui/` and take state through parameters; they do not reach
  into Android APIs directly.
- A new audio effect or pad mode implements the existing interfaces in
  `audio/` instead of branching inside the mixer loop.
- Unit-testable logic stays out of Android framework classes; Android-specific
  decoding and audio output are adapted at the edge.
- Untrusted XML goes through `Xml.parse` (no doctype/external entities) and
  token-bearing HTTP through `Http` (https or a private host, same-host
  redirects only); `collection.xml` and Plex responses are size-capped.
- Credentials and library indexes are excluded from Android backup; release
  identity and signing are supplied by the release environment, never a
  per-run debug key.

## Verification

-`./gradlew :app:testDebugUnitTest` covers the audio core, rekordbox parsing,
tempo maps, matching, DSP and transport policy; `:app:lintDebug` is a blocking
gate; `:app:assembleDebug`/`:app:assembleRelease` prove both builds.
The on-device session on the reference Android tablet is the integration oracle.

## Toolchain

AGP 8.13.2, Gradle 8.13 (checksum pinned), Kotlin 2.2.21, Compose BOM
2025.12.01, `compileSdk`/`targetSdk` 36, `minSdk` 26. The release build is
minified and signed only when the release environment supplies the keystore.

## UI single sources

`ui/Theme.kt` owns colours (`C`), radii (`D`) and font sizes (`F`); no widget
carries a raw colour/radius. Static user copy lives in
`app/src/main/res/values/strings.xml`, with dynamic values (track titles, counts,
formatted times) built in code. `app_name` is the manifest label.
