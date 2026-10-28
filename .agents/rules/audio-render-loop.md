---
name: audio-render-loop
description: "No allocation, file I/O, logging or blocking locks in the mixer render loop; a command that needs them runs on the control thread; when touching audio/Engine.kt or audio/Deck.kt"
scope: app/src/main/java/app/flux/dj/audio/**
---

# Audio render loop

The mixer runs on a `THREAD_PRIORITY_URGENT_AUDIO` thread. A GC pause from one
allocation or a lock/IO stall causes an audible dropout, and the project has
already paid for `Pair`/`FloatArray` allocations in `Engine.render` and a
`ShortArray` per recorder block (issues #16/#17).

- No `new` arrays/objects, `String.format`, boxed collections, file I/O or
  logging inside `Engine.render`, `Deck.render` or `Dsp` processing.
- Preallocate scratch buffers as fields; write results into caller-provided
  buffers.
- Move buffer resizing, string formatting, disk and network work to
  `Engine.control` or another non-real-time thread.
- A seek/loop hand-off across threads uses one atomic exchange, never a pair of
  volatile writes.
- Run `./gradlew :app:testDebugUnitTest` after touching the audio core; add a
  regression when the change is about timing or state.
