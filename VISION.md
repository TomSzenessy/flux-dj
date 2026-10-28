# Project vision

<!-- index: operate | Accepted product direction, constraints, and success evidence | Starting a project or making a high-impact scope decision. -->

Status: accepted
Project: flux-dj
Owner: TomSzenessy
Date: 2026-10-28

## Outcome

FLUX is a two-deck DJ app for Android tablets and phones that puts the DDJ-FLX4
performance surface on glass. It is for DJs who want to practise, prepare a set
or play a small room with a tablet instead of a controller: full jog, cue, sync,
loop, pad, mixer and FX behaviour, with the waveforms as the primary instrument
rather than a small status display.

Success looks like a DJ completing a 30-minute on-device mix using only FLUX —
load, beat-match or sync, EQ, a loop, a hot cue and a transition — with no
audible glitch and without touching a laptop.

## Users and context

- The owner, practising or playing at home and on the move.
- DJs who already know rekordbox/Serato conventions and expect cues, beat grids
  and hot cues to survive the move to a tablet.
- Context: landscape tablet or phone, headphones plus a speaker or PA, music
  already on the device or in a Plex/Drive library, rekordbox `collection.xml`
  available for grids and cues.
- Today the same job needs a laptop plus a controller or a desktop-only app.

## Platforms and constraints

- Android only, `minSdk 26`, `targetSdk 35`, landscape-first, tablet and phone.
- Kotlin, Jetpack Compose and a pure-Kotlin audio engine; no third-party
  libraries, no NDK.
- Audio must run from a real-time thread with no allocations in the render path;
  the UI never blocks playback.
- Works offline with local MediaStore audio; Plex and Google Drive are optional
  read-only sources. Credentials stay in private shared preferences.
- No account of our own, no analytics, no telemetry, no cloud upload of the
  user's library.
- Build and unit tests run with JDK 17 and the Android SDK 35; the on-device
  reference is a reference Android tablet (Android 13).

## Non-goals

- Streaming-service catalogues beyond the existing Plex/Drive read-only sources.
- MIDI/HID hardware mapping, Ableton Link, stems separation, video.
- Cloud sync of the library between devices in this version.
- iOS, desktop or web builds.
- A public hosted service or user accounts.

## Success evidence

- `./gradlew :app:testDebugUnitTest` passes, including the audio-core,
  rekordbox and tempo-map tests.
- `./gradlew :app:assembleDebug` produces an installable APK.
- A judged on-device session on the reference Android tablet exercises load, sync, EQ,
  loop, hot cue, beat FX and a transition without a glitch; `HANDOVER.md`
  records what was observed and what remains unverified.
