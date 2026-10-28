# FLUX — touch DJ for Android

A two-deck DJ app for Android tablets and phones, built around the feature set of
the Pioneer DJ / AlphaTheta DDJ-FLX4 and redesigned for touch. Kotlin + Jetpack
Compose with a pure-Kotlin audio engine; AndroidX and coroutines are the only
third-party dependencies (no audio, HTTP, JSON or image libraries).

## Highlights

- **Decks:** touch jog wheels (scratch + rim pitch-bend), CDJ-style cue, sync
  with phase lock, key lock, slip, quantize, tempo ranges ±6/10/16/50 %,
  hold-to-nudge.
- **Pads:** hot cues (saved per track), beat loops, loop rolls, beat jump,
  editable sampler (load a file or capture a loop/bar from a deck), key shift
  (−4…+3 semitones, labelled **KEY**).
- **Mixer:** trim, 3-band EQ, colour FX (filter / noise / crush / dub echo /
  space), faders, crossfader curves, LED meters, phase meter.
- **Beat FX:** echo, reverb, flanger, phaser, gate, crush — tempo-synced,
  assignable to A / B / master.
- **Waveforms:** 3-band scrolling waveforms on a shared, tempo-aware zoom
  (pinch), beat grid, bar/phrase counter, overview strips.
- **Beat-grid editor:** move, stretch around a pinned beat, set downbeat, fine
  BPM, tap tempo.
- **Library intelligence:** background analysis (BPM, beat grid, key/Camelot,
  energy, rhythm pattern, vibe), smart filters and sorting, harmonic/tempo-aware
  suggestions, headphone preview with scrubbing. Tracks covered by a rekordbox
  `collection.xml` keep those grids and skip local BPM/key analysis, with `RB` /
  `LOCAL` / `✎` provenance badges.
- **Cloud sources:** sign in to Plex and stream the original files, or connect a
  Google Drive/cloud folder. A `collection.xml` in that folder is imported and
  overrides local analysis — except for BPM/grid/cues you edited by hand.
  Audiobook/spoken-word Plex libraries are excluded; "My collection" limits Plex
  to matches in your `collection.xml`.
- **Fast loading:** streaming decode (playable after about one second) and an
  LRU disk cache of decoded audio. Reload timing is not benchmarked; expect a
  fraction of a second once the PCM cache is warm.
- **Audio routing:** single output, two devices (e.g. aux + Bluetooth) or split
  cable (L = cue, R = master), with latency alignment; master recording to WAV
  (`Music/FLUX` on API 29+; app-specific music folder below). With no CUE lit
  the headphones carry the master mix; switching a CUE on makes them hear only
  that deck.
- **One timing model:** room, headphones and the picture are all outputs. The
  microphone measures each real acoustic delay, aligns the faster output to the
  slower one and holds the waveforms, beat grid, jogs and pulses back to match.
  Manual nudging is a fine-tune, not a requirement.
- **Grid-aware performance:** loop pads start immediately and snap to the
  nearest beat using the playhead you hear/see (output latency compensated).
  Waveform drags snap to the nearest half-beat; jump pads move by bars
  (±1/2/4/8) and wrap inside an active loop.

See [PLAN.md](PLAN.md) for the original design brief (historical),
[docs/architecture/app.md](docs/architecture/app.md) for the code map and
[docs/design.md](docs/design.md) for the visual rules. Live work is tracked in
GitHub Issues.

## Build and test

Requires JDK 17 and the Android SDK (API 36).

```bash
./gradlew :app:testDebugUnitTest    # unit tests (audio core, DSP, rekordbox, transport policy)
./gradlew :app:lintDebug            # blocking Android lint gate
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # minified release APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is unsigned unless `FLUX_KEYSTORE`, `FLUX_KEYSTORE_PASSWORD`,
`FLUX_KEY_ALIAS` and `FLUX_KEY_PASSWORD` are supplied; CI then publishes only the
signed `app-release.apk` for a `v*` tag. The version comes from the tag
(`v1.2.3` → 1.2.3, code 10203), not a hardcoded value.

GitHub Actions runs tests, lint and debug/release builds on every push
(`.github/workflows/android.yml`). The debug APK is a workflow artifact, never a
release asset.

## Repository

The repository also carries the AI_TEMPLATE agent kit: `AGENTS.md`,
`project.toml`, `make start`/`make done`, and the canonical issue contract in
[docs/ISSUE_TEMPLATE.md](docs/ISSUE_TEMPLATE.md). Agent sessions start with
`make start` and file work as GitHub Issues.

<!-- repoctl:project-readme -->
> Project initialized: **flux-dj** (`android`). Agents start with [`AGENTS.md`](./AGENTS.md): `make start`, `make next`, `make done`.
