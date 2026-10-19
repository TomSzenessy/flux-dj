# FLUX — a touch-first DJ app for Android

Design brief: everything on the Pioneer DJ / AlphaTheta **DDJ-FLX4** (rekordbox + Serato layout), rebuilt for glass.
Minimal at rest, deep on demand. Landscape, tablet-first, phone-capable.

---

## 1. What the DDJ-FLX4 actually does (feature inventory)

| Section | FLX4 hardware | FLUX on touch |
|---|---|---|
| **Jog wheel** | Touch-sensitive platter: scratch (top), pitch-bend (rim), vinyl/CDJ mode | Circular touch platter. Centre = scratch (finger angle drives playhead), rim = pitch-bend. Spins with the track, shows playhead + cue marker |
| **Transport** | Play/Pause, Cue (hold = preview), Shift+Cue = back-to-start | Large Play + Cue, Cue is momentary (CDJ style) |
| **Sync / Master** | Sync (tempo+phase), Master | SYNC button (tap = beat+tempo, long-press = off), automatic master election |
| **Tempo** | 100 mm tempo slider, range ±6/10/16/WIDE, Key Lock (Master Tempo) | Vertical pitch fader, range chip (±6 / ±10 / ±16 / ±50), KEY LOCK toggle, double-tap = reset |
| **Loop** | Loop In/Out, Reloop, ½ / ×2, Auto-beat-loop | LOOP chip row: 1/4 … 32 beats, tap = on/off, ½ ×2 |
| **Hot cues** | 8 performance pads | 8 pads, tap = jump/set, long-press = delete, colour-coded, shown on the waveform |
| **Pad modes** | Hot Cue, Pad FX1, Pad FX2, Beat Jump, Sampler, Keyboard, Beat Loop, Key Shift | **Hot Cue · Loop · Roll · Jump · Sampler · Pitch Play** (the FX/Key modes folded in) — one pad grid, six faces |
| **Mixer** | Trim, 3-band EQ, Colour FX knob, channel fader, CH cue (PFL), VU meters | Trim, Hi/Mid/Lo knobs (with kill on double-tap), Colour knob, vertical fader, headphone-cue button, LED meters |
| **Crossfader** | Crossfader + curve switch | Wide horizontal crossfader, curve: Smooth / Sharp / Cut |
| **Smart CFX** | Noise, Filter, Crush, Dub Echo… | Colour FX: Filter · Noise · Crush · Dub Echo · Space |
| **Beat FX** | Low-cut Echo, Echo, Reverb, Spiral, Flanger, Phaser, Trans…, Level/Depth, beat division | FX sheet: Echo · Reverb · Flanger · Phaser · Gate · Crush; Level/Depth + beat 1/4 … 4; routes to Deck A / B / Master |
| **Headphones** | Headphone cue, Mix (cue↔master), Level | Cue/Master mix knob + level, **separate audio route** |
| **Master** | Master level, Booth, VU | Master level + LED VU |
| **Browse** | Rotary browse + Load A/B | Library sheet: search, load-to-A/B, sorted by compatibility |
| **Record** | rekordbox record | REC (writes master mix to WAV in Music/FLUX) |

## 2. What touch lets us do *better* than the hardware

1. **Waveforms are the hero** — two stacked, colour-coded (low = magenta/red, mid = amber/green, high = cyan/white) scrolling waveforms with beat grid, cue flags and loop region; tap to jump, drag to nudge, pinch-free zoom chips. Overview strip below each for the whole track.
2. **Multi-touch by design** — every control owns its pointers, so you can scratch on one deck while moving the crossfader and holding an EQ kill.
3. **Pads that change face** — one grid for 6 functions removes 20 buttons from the surface.
4. **Intelligence (the "proactive" bit)**
   * Automatic **BPM, beat-grid and musical key** analysis in the background after loading (Camelot notation).
   * **Harmonic + tempo compatibility chips** in the library: tracks are ranked against whatever is playing (green = perfect, amber = ±1 on the wheel/energy lift).
   * **Smart Sync**: when you load onto a stopped deck while the other plays, it auto-matches tempo; tap Play on the beat and it is phase-aligned (quantised start).
   * **Auto-gain**: loudness normalised on analysis so faders start at unity and level-matching is rarely needed.
   * **Transition assist**: one tap `MIX →` runs a beat-matched, EQ-swapped transition to the other deck over 8/16/32 beats (bass swap at the midpoint). Fully manual otherwise.
   * **Cue end warning**: waveform border pulses in the last 30 s; loop-out suggestion on phrase boundaries.
5. **Demo tracks built in** — three synthesised tracks (house / tech / disco) so the app is playable the second it opens, no files needed.
6. **Split-cue made trivial** — see §4.

## 3. Information design (minimal-first)

Landscape. Dark OLED black, translucent surfaces, one accent colour per deck (Deck A cyan `#22D3EE`, Deck B magenta `#F472B6`), amber for sync/FX, a single typeface (Inter-like, tabular numbers for BPM/time).

```
┌ ≡ library   FLUX      ● REC   124.0 BPM · master A         ⚙ outputs ┐
│ ┌─ DECK A waveform ─────────────────────────────────────────────────┐ │
│ ├─ DECK B waveform ─────────────────────────────────────────────────┤ │
│ DECK A                    MIXER                        DECK B        │
│  [track · key · bpm]      CH A  CH B                    [track …]    │
│  (  jog platter  )        trim, hi/mid/lo, colour       (  jog  )    │
│  CUE  PLAY  SYNC          fader + VU   fader + VU       CUE PLAY SYNC│
│  pads ×8  mode ▸          ───── crossfader ─────        pads ×8      │
│  loop chips  pitch fader  FX · cue · master             pitch fader  │
└───────────────────────────────────────────────────────────────────────┘
```

Progressive disclosure: **Library**, **FX** and **Outputs** are sheets that slide over; nothing else is hidden. No menus, no tabs on the performance surface.

### Modular surface (added on request)
Every region is a panel you can switch off from **VIEW** in the top bar; the rest reflows into the freed space and the choice is remembered: Waveforms · Track overview · Jog wheels · Performance pads · Loop controls · EQ & knobs · Level meters · Mixer. Four one-tap layouts: **Full**, **Mix** (no jogs/pads), **Scratch** (jogs + mixer only), **Minimal** (waveforms + faders).

## 4. Audio routing — the "two sets of ears" problem

Android exposes `AudioTrack.setPreferredDevice()`, so FLUX opens **two independent output streams**:

* **Master** → speaker / aux / USB / Bluetooth speaker.
* **Cue (headphones)** → wired phones / USB / second Bluetooth device.

Modes (Outputs sheet, auto-suggested from the connected devices):
1. **Dual device** — master and cue on separate devices (best: aux/USB for master, Bluetooth for cue, or reverse).
2. **Split cable** — one stereo jack with a splitter: **L = cue, R = master** (mono-summed). Works with any phone with a headphone-splitter cable or USB-C dongle.
3. **Single** — master only, with cue-on-screen (meters) fallback.

Caveats we surface honestly in the UI: Android allows only one A2DP Bluetooth sink at a time on most phones (a second BT device needs a phone with *Dual Audio* / LE Audio); Bluetooth adds 100–250 ms, so a **cue-latency trim** slider keeps headphone and master aligned.

## 5. Audio engine (performance)

Pure Kotlin/JVM DSP, zero allocations in the render loop, no NDK dependency.

* One real-time mixer thread (URGENT_AUDIO priority), 256-frame blocks @ 44.1 kHz, `PERFORMANCE_MODE_LOW_LATENCY` AudioTracks, float pipeline, soft-clip master.
* Each deck: whole-track PCM in memory (16-bit), cubic-interpolated variable-rate reader (vinyl), optional **key-lock** (granular overlap-add time-stretch), scratch smoothing, slip mode, quantised loops/cues.
* Channel strip: trim → 3-band isolator EQ (biquads) → colour FX (filter/noise/crush/echo) → channel fader → crossfader curve → FX bus → master.
* Beat FX: tempo-synced echo, reverb (Schroeder), flanger, phaser, gate, bit-crush.
* Sampler: 8 synthesised one-shots (kick, snare, clap, hat, perc, horn, siren, riser).
* Analysis (off-thread): decode with `MediaCodec`, resample to 44.1 kHz, 3-band waveform envelope, onset-autocorrelation BPM + grid phase, chroma → key (Krumhansl), RMS loudness.
* UI polls engine state once per frame; engine never waits on the UI.

## 6. Tech stack

Kotlin · Jetpack Compose (Canvas for waveforms/jog/meters) · Material3 only for sheets · AudioTrack/MediaCodec/MediaStore · minSdk 26 · targetSdk 35 · no third-party libraries.

## 7. Milestones

1. Plan ✅  2. HTML mockup → iterate  3. Project scaffold + theme  4. Engine (decode, playback, mixer, FX, outputs)  5. Analysis (BPM/key/waveform) + demo tracks  6. UI (controls, decks, mixer, sheets)  7. Build, run on emulator, fix, polish.

## 8. Out of scope for v1 (documented, not forgotten)

Streaming services, MIDI hardware mapping, Link/Ableton sync, stems separation, video, cloud sync.
