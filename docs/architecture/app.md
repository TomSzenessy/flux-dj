# FLUX app architecture

<!-- index: design | FLUX Android app structure, audio engine seams, and state ownership | Adding a screen, changing audio behaviour, or locating a module. -->
<!-- covers: app/** -->

One Android application module (`:app`) in Kotlin. The audio engine is plain
Kotlin/JVM code with no Android UI dependency; the Compose layer observes its
state. There is no second process, no service and no native library.

## Map

| Layer | Package | Responsibility | Entry point |
|---|---|---|---|
| UI | `app.flux.dj.ui` | Compose screens, Canvas instrument widgets, dialogs | `MainScreen` |
| State | `app.flux.dj` | `Controller` (ViewModel): library, analysis cache, imports, suggestions, history, outputs, samples, grid edits | `Controller` |
| Audio | `app.flux.dj.audio` | Mixer thread, decks, decoder, analyser, DSP, synth, microphone alignment | `Engine` |
| Integrations | `app.flux.dj` | rekordbox XML, Plex/Drive clients, MediaStore indexing | `Rekordbox`, `Cloud`, `Controller` |

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

## Verification

`./gradlew :app:testDebugUnitTest` covers the audio core, rekordbox parsing,
tempo maps and matching; `./gradlew :app:assembleDebug` proves the app builds.
The on-device session on the reference Android tablet is the integration oracle.
