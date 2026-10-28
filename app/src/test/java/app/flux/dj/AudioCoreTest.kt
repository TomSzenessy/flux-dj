package app.flux.dj

import app.flux.dj.audio.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AudioCoreTest {
    private fun blind(i: Int): Track {
        val spec = DemoTracks.specs[i]
        val known = DemoTracks.build(spec)
        // re-analyse without the ground truth
        val t = Track(spec.id, spec.title, "", known.pcm)
        Analyzer.waveform(t)
        Analyzer.rhythmAndKey(t)
        return t
    }

    @Test fun bpmAndKeyAreRecoveredBlind() {
        for (i in DemoTracks.specs.indices) {
            val s = DemoTracks.specs[i]
            val t = blind(i)
            println("${s.title}: bpm=${t.bpm} (true ${s.bpm}) key=${t.keyName}/${t.camelot} firstBeat=${t.firstBeat / SR}s")
            assertEquals("bpm ${s.title}", s.bpm, t.bpm, 0.6f)
            assertTrue("first beat near grid ${t.firstBeat}", t.firstBeat / SR < 0.06 || abs(t.firstBeat - t.spb) / SR < 0.06)
            assertTrue("key detected", t.key >= 0)
        }
    }

    @Test fun deckRendersCleanAudio() {
        val t = DemoTracks.build(DemoTracks.specs[0])
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.render(BLOCK, l, r) // apply seek
        d.play()
        var peak = 0f
        repeat(400) { d.render(BLOCK, l, r); for (k in 0 until BLOCK) { assertFalse(l[k].isNaN()); peak = maxOf(peak, abs(l[k])) } }
        assertTrue("audible, peak=$peak", peak > 0.1f)
        val moved = d.pos
        assertEquals("advanced ~2.3 s", 400.0 * BLOCK, moved - t.firstBeat, 3000.0)

        // key lock at +6 %: audio flows, timeline advances 6 % faster
        d.keyLock = true; d.setTempoFader(0.75f) // +6 % of 8 %
        val p0 = d.pos
        peak = 0f
        repeat(300) { d.render(BLOCK, l, r); for (k in 0 until BLOCK) { assertFalse(l[k].isNaN()); peak = maxOf(peak, abs(l[k])) } }
        assertTrue("keylock audible $peak", peak > 0.1f)
        val adv = (d.pos - p0) / (300.0 * BLOCK)
        assertEquals("tempo ratio", 1.06, adv, 0.01)

        // scratch backwards then release
        d.keyLock = false; d.setTempoFader(0f)
        d.scratchBegin(); val sp = d.pos
        for (k in 0 until 30) { d.scratchTo(sp - k * 800.0); d.render(BLOCK, l, r) }
        assertTrue("moved back", d.pos < sp - 5000)
        d.scratchEnd()

        // loop wraps
        d.loopIn = d.pos; d.loopOut = d.pos + 20000.0; d.loopOn = true
        repeat(300) { d.render(BLOCK, l, r) }
        assertTrue("stays inside loop", d.pos < d.loopOut + 2000)
    }

    @Test fun cueBehaviour() {
        val t = DemoTracks.build(DemoTracks.specs[1])
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.render(BLOCK, l, r)
        d.play(); repeat(100) { d.render(BLOCK, l, r) }
        d.cueDown(); d.render(BLOCK, l, r)
        assertFalse(d.playing)
        assertEquals(d.cue, d.pos, 3000.0)
    }
}

class ProfileTest {
    @Test fun profilesDemoTracks() {
        for (s in DemoTracks.specs) {
            val t = DemoTracks.build(s); Analyzer.profile(t)
            println("${s.title}: energy=${t.energy} pattern=${t.pattern} vibe=${t.vibe}")
            assertEquals("4/4 kick", t.pattern)
            assertTrue(t.energy in 1..10)
            assertTrue(t.vibe.isNotEmpty())
        }
    }
}

class FractionalBpmTest {
    @Test fun detectsNonIntegerTempoWithoutDrift() {
        val spec = DemoTracks.Spec("t:frac", "Frac", "", 127.6f, 9, true, 0)
        val known = DemoTracks.build(spec)
        val t = Track(spec.id, spec.title, "", known.pcm)
        Analyzer.waveform(t); Analyzer.rhythmAndKey(t)
        println("fractional: bpm=${t.bpm} (true 127.6) firstBeat=${t.firstBeat / SR}s")
        assertEquals(127.6f, t.bpm, 0.03f)
        // grid must still be on the beat at the END of the track (no drift)
        val lastBeat = Math.floor((t.frames - t.firstBeat) / t.spb) * t.spb + t.firstBeat
        val trueSpb = 60.0 / 127.6 * SR
        val err = Math.abs(lastBeat - Math.round(lastBeat / trueSpb) * trueSpb) / SR
        println("drift at end: ${err * 1000} ms")
        assertTrue("drift ${err * 1000} ms", err < 0.02)
    }
}

class TransportTest {
    @Test fun cueInsideLoopRestartsLoop() {
        val t = DemoTracks.build(DemoTracks.specs[0])
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.render(BLOCK, l, r); d.play(); repeat(200) { d.render(BLOCK, l, r) }
        d.loopSet(4f); repeat(50) { d.render(BLOCK, l, r) }
        d.cueDown(); d.render(BLOCK, l, r)
        assertTrue("still playing", d.playing)
        assertEquals(d.loopIn, d.pos, 600.0)
    }

    @Test fun loopPadStartsAndSnapsToNearestGridPoint() {
        val t = DemoTracks.build(DemoTracks.specs[0])
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.render(BLOCK, l, r); d.play(); repeat(120) { d.render(BLOCK, l, r) }
        val spb = t.spb
        val grid = t.firstBeat + Math.floor((d.pos - t.firstBeat) / spb) * spb
        // press a fraction of a beat AFTER the grid point — it must snap back onto that beat
        d.loopSet(4f, grid + spb * 0.12)
        assertTrue("loop started by the pad", d.loopOn)
        assertEquals("loop-in on the nearest beat", grid, d.loopIn, 40.0)
        assertEquals("loop length", grid + spb * 4, d.loopOut, 60.0)
        // and just before the next beat it snaps forward to that beat
        d.loopSet(1f, grid + spb * 0.88)
        assertEquals("nearest beat ahead", grid + spb, d.loopIn, 40.0)
    }

    @Test fun hotCueJumpsAndStartsLatching() {
        val t = DemoTracks.build(DemoTracks.specs[0])
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.render(BLOCK, l, r); d.play(); repeat(200) { d.render(BLOCK, l, r) }
        d.hotCueDown(0)                       // store a cue at the playhead
        assertTrue("cue stored", d.hotCues[0] >= 0)
        d.pause(); d.render(BLOCK, l, r)
        d.hotCueDown(0)                       // tap the stored cue on a stopped deck
        assertTrue("hot cue starts the deck", d.playing)
        assertFalse("hot cue latches instead of previewing", d.previewing)
        d.render(BLOCK, l, r)
        assertEquals("jumped to the cue", d.hotCues[0], d.pos, 2000.0)
        d.hotCueUp(0)                         // release the pad
        assertTrue("keeps playing after release", d.playing)
        repeat(100) { d.render(BLOCK, l, r) }
        assertTrue("audio flows on from the cue", d.pos > d.hotCues[0])
    }

    @Test fun jumpStaysInsideLoop() {
        val t = DemoTracks.build(DemoTracks.specs[0])
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.render(BLOCK, l, r); d.play(); d.loopSet(4f)
        val len = d.loopOut - d.loopIn
        d.jumpTo(d.loopOut + SR * 10.0); d.render(BLOCK, l, r)
        assertTrue("loop stays on", d.loopOn)
        assertTrue("inside loop", d.pos >= d.loopIn && d.pos < d.loopOut)
        assertTrue("wrapped by loop length", d.pos - d.loopIn < len)
    }
}

class SyncTest {
    private fun track(id: String, dominant: Float): Track {
        val t = Track(id, id, "", ShortArray(SR * 8 * 2))
        t.bpm = dominant
        return t
    }

    /**
     * A rekordbox dynamic grid says ~138.9 BPM under the playhead while the deck displays 128.01.
     * Sync must match what both decks show, and must not depend on where the playheads are — the old
     * code used the grid segment, so every SYNC press re-pitched the deck.
     */
    @Test fun syncMatchesTheDisplayedTempoNotTheGridSegment() {
        val masterTrack = track("m", 128.01f)
        masterTrack.setTempoMap(doubleArrayOf(0.0, 1.296, 3.28), doubleArrayOf(138.89, 120.97, 138.89), 1)
        masterTrack.bpm = 128.01f                       // what the deck shows (dominant tempo)

        val follower = Deck(0).apply { load(track("f", 128f)) }
        val master = Deck(1).apply { load(masterTrack); tempo = 0.6f; range = 0.08f; effRatio = 1.048 }

        assertEquals("grid segment under the playhead", 138.89f, masterTrack.bpmAt(0.0), 1f)
        val r = syncTargetNow(follower, master)
        assertEquals("matches the displayed BPM", 1.0481, r, 0.0005)

        // moving either playhead must not change the match
        follower.pos = 20.0 * SR
        master.pos = 40.0 * SR
        assertEquals("same match anywhere in the track", r, syncTargetNow(follower, master), 1e-9)
    }

    @Test fun syncOffKeepsTheMatchedSpeed() {
        val d = Deck(0).apply { load(track("f", 128f)); range = 0.08f }
        d.syncOn = true
        d.syncRatio = 1.137
        releaseSync(d)
        assertFalse("sync released", d.syncOn)
        assertEquals("fader carries the matched speed", 1.137, 1.0 + d.tempo * d.range, 1e-6)
        assertTrue("range widened so the fader can hold it", d.range >= 0.137f)
    }

    @Test fun halfDoubleTempoIsFolded() {
        assertEquals("half-time master", 1.0, syncMatchRatio(87.0, 174.0, 1.0), 1e-9)
        assertEquals("regular match", 1.28, syncMatchRatio(100.0, 128.0, 1.0), 1e-6)
        assertEquals("unknown bpm is neutral", 1.0, syncMatchRatio(0.0, 128.0, 1.0), 1e-9)
    }
}
