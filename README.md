# FLUX — touch DJ for Android

A two-deck DJ app for Android tablets and phones, built around the feature set of the Pioneer DJ / AlphaTheta DDJ-FLX4 and redesigned for touch. Kotlin + Jetpack Compose, pure-Kotlin audio engine, no third-party libraries.

## Highlights

- **Decks:** touch jog wheels (scratch + rim pitch-bend), CDJ-style cue, sync with phase lock, key lock, slip, quantize, tempo ranges ±6/10/16/50 %, hold-to-nudge.
- **Pads:** hot cues (saved per track), beat loops, loop rolls, beat jump, editable sampler (load a file or capture a loop/bar from a deck), pitch play.
- **Mixer:** trim, 3-band EQ, colour FX (filter / noise / crush / dub echo / space), faders, crossfader curves, LED meters, phase meter.
- **Beat FX:** echo, reverb, flanger, phaser, gate, crush — tempo-synced, assignable to A / B / master.
- **Waveforms:** 3-band scrolling waveforms on a shared, tempo-aware zoom (pinch), beat grid, bar/phrase counter, overview strips.
- **Beat-grid editor:** move, stretch around a pinned beat, set downbeat, fine BPM, tap tempo.
- **Library intelligence:** background analysis of your music (BPM, beat grid, key/Camelot, energy, rhythm pattern, vibe), smart filters and sorting, harmonic/tempo-aware suggestions, headphone preview with scrubbing. Tracks covered by a rekordbox `collection.xml` keep those grids and are skipped by local BPM/key analysis, with clear `RB` / `LOCAL` / `✎` provenance badges.
- **Cloud sources:** sign in to Plex (the same library Plexamp sees) and stream the original files, or connect a Google Drive/cloud folder. A `collection.xml` inside that folder is imported automatically and overrides local analysis — except for BPM/grid edits you made by hand. Audiobook/spoken-word Plex libraries are excluded, and a "My collection" switch can limit Plex to tracks that match your `collection.xml`.
- **Fast loading:** streaming decode (playable in <1 s) and an LRU disk cache of decoded audio (~150 ms reloads).
- **Audio routing:** single output, two devices (e.g. aux + Bluetooth) or split cable (L = cue, R = master), with latency alignment; master recording to WAV. With no CUE lit the headphones carry the master mix; switching a CUE on makes them hear only that deck.
- **One timing model:** room, headphones and the picture are all outputs. The microphone measures each real acoustic delay, aligns the faster output to the slower one and holds the waveforms, beat grid, jogs and pulses back to match. Manual nudging is available as a fine-tune, not a requirement.
- **Grid-aware performance:** loop pads start the loop immediately and snap to the nearest beat using the playhead you actually hear/see (output latency compensated), so a tap just after a beat still lands on that beat. Waveform drags snap to the nearest half-beat; the jump pads move by bars (±1/2/4/8) and never leave an active loop — they wrap inside it.

See [PLAN.md](PLAN.md) for the design rationale and [mockup/](mockup/) for the original layout mockup.

## Build

Requires the Android SDK (API 35) and JDK 17.

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (audio engine, BPM/key/grid analysis):

```bash
./gradlew :app:testDebugUnitTest
```

GitHub Actions builds debug + release APKs on every push (`.github/workflows/android.yml`). Download the `flux-apk` artifact from the run, or push a `v*` tag to attach both APKs to a GitHub Release for one-tap installs on other devices.
