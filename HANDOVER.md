# FLUX — handover

State as of 27 Oct 2026. Repo: `github.com/TomSzenessy/flux-dj` (private), single branch `main`.
Test device: Xiaomi Pad 5 (`nabu`, Android 13), via `adb -s a6103e95`.

## What it is

A two-deck touch DJ app for Android tablets, modelled on the Pioneer DDJ-FLX4. Kotlin + Jetpack Compose, a
pure-Kotlin audio engine, no third-party libraries. Landscape only. See `README.md` for the feature list and
`PLAN.md` for the original design brief.

## Build, install, test

```bash
export JAVA_HOME=/Users/tom/Library/Java/JavaVirtualMachines/corretto-17.0.16/Contents/Home   # JDK 17 (Gradle rejects JDK 26)
./gradlew :app:assembleDebug
adb -s a6103e95 install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

- `local.properties` points at `~/Library/Android/sdk` (not committed).
- `RekordboxTest` reads `~/Documents/collection.xml` and is skipped if it's missing (the library itself is never committed).
- `TabletMatchTest` is a dry run against a MediaStore dump of the tablet. It only runs with `FLUX_MS_DUMP=<file>`; produce the dump with
  `adb shell content query --uri content://media/external/audio/media --projection _id:title:artist:duration:_display_name:is_music`.
- Installing restarts the app, so don't install while someone is mixing on the tablet.

## Code map (`app/src/main/java/app/flux/dj/`)

| File | Role |
|---|---|
| `audio/Engine.kt` | Mixer thread (256-frame blocks, 44.1 kHz float): channel strips, EQ, colour FX, beat FX bus, sampler, crossfader, sync/phase lock, transition assist, library preview voice, alignment loop, output routing (single / two devices / split cable), output volume, latency measurement, recorder. |
| `audio/Deck.kt` | One deck: variable-rate reader (Hermite), key lock (granular OLA), scratch, slip, loops/rolls, hot cues, CDJ cue, silent search, `jumpTo`. Beat maths goes through `Track.beatIndex/beatPos`. |
| `audio/Track.kt` | Decoded PCM plus analysis. **Tempo map**: a single tempo (`bpm` + `firstBeat`), or rekordbox-style markers fitted into continuous segments (`setTempoMap`, `beatIndex`, `beatPos`, `spbAt`, `bpmAt`). Beat index 0 is always a downbeat. |
| `audio/Decoder.kt` | MediaCodec decode to 44.1 kHz stereo PCM16. Streaming (`onStream` hands over a playable track after about 1 s) and a "window" mode used for quick analysis. |
| `audio/Analyzer.kt` | 3-band waveform, BPM (refined to 0.01 over the whole track), beat phase, key (Krumhansl), energy / pattern / vibe. |
| `audio/Dsp.kt`, `audio/Synth.kt` | Biquads, FX, reverb; demo tracks and sampler one-shots (`SampleBank`). |
| `Rekordbox.kt` | `collection.xml` parser (tracks, TEMPO markers, keys, hot and memory cues, playlist tree) and matcher (file name → title+artist+length → "Artist - Title" → containment with equal length). |
| `Controller.kt` | ViewModel holding all UI state and app logic: library (MediaStore, imports, folders, demos), indexing, caches, suggestions/sorting/filters, removal, rekordbox apply, grid editor, history, preview, outputs, samples. **Large (1500 lines); the first candidate to split.** |
| `ui/*` | Compose UI: `MainScreen` (layout and dialogs), `DeckPanel`, `MixerPanel`, `Waveform` (scrolling waveform, overview, grid-edit bar), `Jog`, `Panels` (library, FX, outputs, view, history, playlist picker), `Align` (align-by-ear screens), `Controls` (knob, faders, pads). |

## Persistence (on the tablet)

- `shared_prefs/flux.xml`: UI and settings, output devices (by stable `type|address` key), imports list, hidden tracks, last-loaded tracks, sample names, `rb_import_time`.
- `shared_prefs/flux_analysis.xml`, per track id (`ms:<mediastore id>`, `imp:<uri>`):
  - `<id>` = `bpm|key|firstBeat|energy|pattern|vibe|source`, where source is `2` (FLUX analysis), `u:<ms>` (hand edit) or `rb:<ms>` (rekordbox). `u` and `rb` are **protected**: the indexer and re-analysis never overwrite them, and a hand edit newer than the rekordbox import wins.
  - `tmap:<id>`: tempo map `sec:bpm,…;battito`.
  - `cues:<id>`: `main,hc0..hc7` in frames (-1 = empty).
  - `rbApplied:<id>`: which import was applied.
- `files/wave/*.bin`: cached waveforms. `cache/pcm/*.pcm`: LRU cache of decoded audio (1.5 GB cap).
- `files/rekordbox.xml`: copy of the last imported collection. `files/history.tsv`: set history. `files/samples/pad*.pcm`: custom pads.

## Current state (verified on the tablet unless noted)

- rekordbox import: 173 of 232 tracks matched (the rest aren't in `Music/dj songs` any more). 18 playlists, 177 hot cues, 80 tempo maps (25 with real tempo changes).
- Two outputs: Bluetooth + built-in speaker work as separate outputs. Bluetooth + Bluetooth is impossible on Android, and the OUT panel only offers combinations that work.
- Measured speaker output delay is about 139 ms, compensated in the visuals.
- **Not verified on the device**:
  - the playlist picker UI and loading a tempo-map track on a deck (the logic is unit-tested against the real XML);
  - tap-to-jump on the waveform and CUE-restarts-loop (unit-tested, built after the last install, **not yet installed**);
  - USB-C audio output;
  - Share in History.

## Known gaps and next steps

1. **Install the latest build** (`f7cb519` + this commit) and check the playlist picker, a tempo-map track (e.g. *Axel F*, 387 markers), tap-to-jump and CUE in a loop.
2. **Plex connector** (agreed next feature): plex.tv PIN sign-in, library sections, playlists and collections, stream original files through the existing decode/cache path, two-way playlist sync.
3. **Analysis sync across devices**: a sync file in a user-chosen folder (Drive, Nextcloud, Syncthing), or write rekordbox XML back out so edits round-trip to rekordbox.
4. rekordbox details not imported yet: hot-cue colours and names, saved loops (`Type=4`), ratings, colours, comments.
5. Grid editor on tempo-map tracks: tempo edits flatten the map to the tempo at the playhead (RESET restores it). Per-segment editing doesn't exist yet.
6. Split `Controller.kt` (library, rekordbox, grid, outputs) before it grows further.
7. Ideas raised earlier but not built: headphone pre-listen inside playlists, Deezer/MusicBrainz metadata.
