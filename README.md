# FLUX — touch DJ for Android

A two-deck DJ app for Android tablets and phones, built around the feature set of the Pioneer DJ / AlphaTheta DDJ-FLX4 and redesigned for touch. Kotlin + Jetpack Compose, pure-Kotlin audio engine, no third-party libraries.

## Highlights

- **Decks:** touch jog wheels (scratch + rim pitch-bend), CDJ-style cue, sync with phase lock, key lock, slip, quantize, tempo ranges ±6/10/16/50 %, hold-to-nudge.
- **Pads:** hot cues (saved per track), beat loops, loop rolls, beat jump, editable sampler (load a file or capture a loop/bar from a deck), pitch play.
- **Mixer:** trim, 3-band EQ, colour FX (filter / noise / crush / dub echo / space), faders, crossfader curves, LED meters, phase meter.
- **Beat FX:** echo, reverb, flanger, phaser, gate, crush — tempo-synced, assignable to A / B / master.
- **Waveforms:** 3-band scrolling waveforms on a shared, tempo-aware zoom (pinch), beat grid, bar/phrase counter, overview strips.
- **Beat-grid editor:** move, stretch around a pinned beat, set downbeat, fine BPM, tap tempo.
- **Library intelligence:** background analysis of your music (BPM, beat grid, key/Camelot, energy, rhythm pattern, vibe), smart filters and sorting, harmonic/tempo-aware suggestions, headphone preview with scrubbing.
- **Fast loading:** streaming decode (playable in <1 s) and an LRU disk cache of decoded audio (~150 ms reloads).
- **Audio routing:** single output, two devices (e.g. aux + Bluetooth) or split cable (L = cue, R = master), with latency alignment; master recording to WAV.

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
