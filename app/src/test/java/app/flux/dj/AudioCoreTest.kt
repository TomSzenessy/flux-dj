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
     * A master with a rekordbox dynamic grid moves from 160 to 180 BPM mid-track. The follower must
     * keep following the tempo under the master's playhead; matching once drifted away while both
     * decks still displayed 159.2.
     */
    @Test fun syncFollowsADynamicMasterTempo() {
        val masterTrack = track("m", 160f)
        masterTrack.setTempoMap(doubleArrayOf(0.0, 10.0), doubleArrayOf(160.0, 180.0), 1)
        val master = Deck(1).apply { load(masterTrack) }
        val follower = Deck(0).apply { load(track("f", 160f)) }

        master.pos = 15.0 * SR
        var ratio = syncFollowTarget(follower, master, current = 1.0)
        repeat(200) { ratio = slewTo(ratio, syncFollowTarget(follower, master, ratio)) }
        assertEquals("follower plays at the master's tempo", 1.125, ratio, 0.001)
        assertEquals("audible tempo matches", currentBpm(master) * deckRatio(master), currentBpm(follower) * ratio, 0.01)
    }

    /** The ratio may depend on the follower's grid, but its *audible* tempo must not. */
    @Test fun followerStaysAudibleAtTheMasterTempoWhereverItIs() {
        val followerTrack = track("f", 128f)
        followerTrack.setTempoMap(doubleArrayOf(0.0, 2.0, 6.0), doubleArrayOf(128.0, 138.89, 126.0), 1)
        val follower = Deck(0).apply { load(followerTrack) }
        val master = Deck(1).apply { load(track("m", 128f)) }
        for (atSec in listOf(0.0, 3.0, 7.0)) {
            follower.pos = atSec * SR
            val r = syncFollowTarget(follower, master, current = 1.0)
            assertEquals("audible tempo at ${atSec}s", 128.0, currentBpm(follower) * r, 0.01)
        }
    }

    /** A half-time master keeps the beat instead of halving the deck. */
    @Test fun halfTimeMasterKeepsTheBeat() {
        val master = Deck(1).apply { load(track("m", 80f)) }
        val follower = Deck(0).apply { load(track("f", 160f)) }
        assertEquals("folded into the current octave", 1.0, syncFollowTarget(follower, master, current = 1.0), 1e-9)
    }

    @Test fun slewStepsTowardsTheTargetWithoutJumping() {
        assertEquals("one step at most", 0.505, slewTo(0.5, 1.2), 1e-9)
        var r = 0.5
        repeat(200) { r = slewTo(r, 1.0) }
        assertEquals("converges", 1.0, r, 1e-9)
        assertEquals("does not overshoot", 1.0, slewTo(1.0, 1.0), 1e-12)
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

class TransportRegressionTest {
    @Test fun renderNeverIndexesPastItsPcmSnapshot() {
        // The decoder can publish a larger frame count while the deck still holds the old array.
        val t = Track("grow", "grow", "", ShortArray(200))
        t.complete = true
        t.frames = 1000
        t.available = 1000
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.play()
        repeat(10) { d.render(BLOCK, l, r) }
        assertTrue("playhead clamped to the local array", d.pos <= 100.0)
    }

    @Test fun tinyTrackRendersSilenceInsteadOfThrowing() {
        val t = Track("tiny", "tiny", "", ShortArray(8))
        t.frames = 4
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.play()
        d.render(BLOCK, l, r)
        for (k in 0 until BLOCK) { assertEquals(0f, l[k], 0f); assertEquals(0f, r[k], 0f) }
    }

    @Test fun loopPastTheTrackEndKeepsPlaying() {
        val t = Track("end", "end", "", ShortArray(40_000))
        t.bpm = 120f; t.firstBeat = 0.0; t.analyzed = true
        val d = Deck(0); d.load(t)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        d.play(); d.seekNow(19_000.0)
        d.loopSet(4f, 19_000.0)
        assertTrue("loop is active", d.loopOn)
        repeat(80) { d.render(BLOCK, l, r) }
        assertTrue("deck keeps playing", d.playing)
        assertTrue("inside the clamped loop", d.pos >= d.loopIn && d.pos <= d.loopOut + 1)
    }

    @Test fun streamingDurationUsesDecodedFrames() {
        val t = Track("stream", "stream", "", ShortArray(2000))
        t.complete = false
        t.available = 500
        t.frames = 2000
        assertEquals(500.0 / SR, t.durationSec, 1e-9)
        t.complete = true
        assertEquals(2000.0 / SR, t.durationSec, 1e-9)
    }

    @Test fun tempoMapIgnoresInvalidMarkers() {
        val t = Track("map", "map", "", ShortArray(1000))
        t.setTempoMap(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(120.0, 0.0, 128.0), 1)
        assertTrue("finite dominant bpm", t.bpm.isFinite() && t.bpm > 0f)
        assertTrue("finite beat position", t.beatPos(10.0).isFinite())
    }
}

class AnalysisRegressionTest {
    @Test fun silenceHasNoTempoOrKey() {
        val t = Track("silence", "silence", "", ShortArray(SR * 20 * 2))
        Analyzer.waveform(t)
        Analyzer.rhythmAndKey(t)
        Analyzer.profile(t)
        assertEquals("no invented bpm", 0f, t.bpm, 0f)
        assertEquals("no invented key", -1, t.key)
        assertEquals("beatless", "Beatless", t.pattern)
    }

    @Test fun outOfPhaseStereoStillHasAWaveform() {
        val frames = SR * 20
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) { pcm[i * 2] = 12_000; pcm[i * 2 + 1] = -12_000 }
        val t = Track("phase", "phase", "", pcm)
        Analyzer.waveform(t)
        assertTrue("waveform is not flat", t.wave!!.any { (it.toInt() and 255) > 0 })
        assertTrue("rms is audible", t.rms > 0.01f)
    }
}
