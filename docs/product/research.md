# Product research record

<!-- index: operate | Comparable products, user feedback, and design references behind FLUX | Before a scope or UX decision that leans on an outside product. -->

FLUX was built from a known hardware surface (DDJ-FLX4) rather than a blank
page. This record keeps the outside references that shaped it, so a later
session can check a claim instead of re-deriving it.

## Comparable products

| Reference | What it establishes | Link |
|---|---|---|
| Pioneer DJ DDJ-FLX4 | The control inventory FLUX reproduces on glass: touch jog (scratch/pitch-bend), CDJ cue, sync/master, tempo ranges and key lock, loop and pad modes, 3-band EQ, colour FX, beat FX, channel cue | <https://www.pioneerdj.com/en/product/controller/ddj-flx4/black/overview/> |
| rekordbox | The library data model FLUX imports: `collection.xml`, TEMPO markers, hot/memory cues, playlists, key and BPM | <https://rekordbox.com/en/> |
| Serato DJ | Comparison point for pad modes and library conventions; confirms the one-grid-many-modes pattern DJs already know | <https://serato.com/dj> |
| djay for Android | The closest touch-first Android competitor; its Play listing and reviews set the expectation for tablet layout, streaming sources and onboarding | <https://www.algoriddim.com/djay-android>, <https://play.google.com/store/apps/details?id=com.algoriddim.djay_free> |

## User feedback themes

The recurring complaints about tablet DJ apps are consistent across store
listings and DJ forums: latency that makes cueing unusable, no real headphone
cue path on Android, libraries that do not import rekordbox grids/cues, and
interfaces that hide the waveform behind menus. FLUX answers these directly:
a low-latency engine, two independent outputs with measured alignment, a
rekordbox import that preserves grids and cues, and a performance surface that
never hides the waveform. Store listings were read as directional evidence, not
quoted statistics; no review text is reproduced here.

## Design and platform references

| Reference | What it establishes | Link |
|---|---|---|
| Android `AudioTrack` | Low-latency output, buffer sizing, preferred device routing | <https://developer.android.com/reference/android/media/AudioTrack> |
| Android `MediaCodec` | Hardware decode of the user's own files | <https://developer.android.com/reference/android/media/MediaCodec> |
| Android `MediaStore` | Local library discovery without a file browser | <https://developer.android.com/reference/android/provider/MediaStore> |
| Android `AudioRecord` | Microphone capture for acoustic delay measurement | <https://developer.android.com/reference/android/media/AudioRecord> |
| Jetpack Compose | Declarative UI and Canvas for the instrument widgets | <https://developer.android.com/develop/ui/compose> |

## What this changed

- The DDJ-FLX4 inventory became the feature checklist in `PLAN.md` and
  `README.md`.
- rekordbox became the import format instead of a proprietary library file.
- The waveform-first layout and the two-output routing are deliberate answers
  to the feedback themes above, not decoration.
