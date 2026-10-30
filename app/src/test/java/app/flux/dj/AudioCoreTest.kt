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
     * The match is made where the master is *now*: a rekordbox dynamic grid can hold 160 BPM for
     * three minutes and 180 for two. Matching the track average (159.2) left the follower 3 % fast
     * while both decks displayed the same number, so the beats faded apart.
     */
    @Test fun syncMatchesTheSectionUnderTheMastersPlayhead() {
        val masterTrack = track("m", 159.2f)
        masterTrack.setTempoMap(doubleArrayOf(0.0, 0.4, 10.0), doubleArrayOf(159.03, 159.22, 180.0), 1)
        val follower = Deck(0).apply { load(track("f", 160f)) }
        val master = Deck(1).apply { load(masterTrack) }

        master.pos = 0.2 * SR
        val atStart = syncTargetNow(follower, master)
        assertEquals("matches the section being played, not the average",
            currentBpm(master) / currentBpm(follower), atStart, 1e-9)

        master.pos = 15.0 * SR
        assertEquals("the last section is 180 BPM", 180.0, currentBpm(master), 0.5)
        assertEquals("a press there matches that section", 180.0 / 160.0, syncTargetNow(follower, master), 1e-6)
    }

    /** A rekordbox-style dynamic grid: one marker per beat wobbling ±0.1 BPM, 160 then 180 BPM. */
    private fun dynamicMaster(): Track {
        val beats = 400
        val secs = DoubleArray(beats); val bpms = DoubleArray(beats)
        var t = 0.0
        for (b in 0 until beats) {
            bpms[b] = (if (b < 200) 160.0 else 180.0) + if (b % 2 == 0) 0.1 else -0.1
            secs[b] = t; t += 60.0 / bpms[b]
        }
        return track("m", 160f).apply { setTempoMap(secs, bpms, 1) }
    }

    /** Play both decks for [seconds] the way the mixer does; returns the largest beat error after 1 s. */
    private fun playSynced(f: Deck, m: Deck, seconds: Double, each: (Double) -> Unit = {}): Double {
        val n = 256
        var worst = 0.0
        var at = 0.0
        while (at < seconds) {
            syncStep(f, m, 0.0)
            f.effRatio = f.syncRatio; m.effRatio = deckRatio(m)
            f.pos += f.syncRatio * (1.0 + f.bend) * f.phaseAdj * n
            m.pos += deckRatio(m) * (1.0 + m.bend) * n
            at += n.toDouble() / SR
            each(at)
            val e = abs(syncPhaseError(m.track!!.beatIndex(m.pos), f.track!!.beatIndex(f.pos), f.syncFold, f.syncOffset))
            if (at > 1.0) worst = maxOf(worst, e)
        }
        return worst
    }

    private fun engaged(f: Deck, m: Deck) {
        f.syncOn = true
        f.syncRatio = syncTargetNow(f, m)
        f.syncFold = syncFold(currentBpm(f), currentBpm(m), deckRatio(m), f.syncRatio)
        f.playing = true; m.playing = true
    }

    /**
     * The drift bug: sync matched the tempo once and held it, so a dynamic grid's next section (or a
     * BPM a hundredth off) slid the beats apart over a long mix. The lock keeps them within 1 %.
     */
    @Test fun syncStaysOnTheBeatThroughADynamicGrid() {
        val master = Deck(1).apply { load(dynamicMaster()) }
        val follower = Deck(0).apply { load(track("f", 159.97f)) }
        engaged(follower, master)

        val heldOnce = follower.syncRatio
        val oneMatchDrift = (master.track!!.beatIndex(90.0 * SR) - 90.0 * SR * heldOnce / follower.track!!.spb)
        assertTrue("a ratio held from the first beat drifts by whole beats", abs(oneMatchDrift) > 2.0)

        val worst = playSynced(follower, master, 90.0) {
            assertEquals("never bends audibly", 1.0, follower.phaseAdj, 0.0101)
            assertFalse("no re-align jumps while just playing", follower.needAlign)
        }
        assertTrue("beats stay together (worst ${"%.4f".format(worst)} beat)", worst < 0.01)
    }

    @Test fun syncFollowsTheMastersFaderWithoutSeeking() {
        val master = Deck(1).apply { load(track("m", 128f)) }
        val follower = Deck(0).apply { load(track("f", 124f)) }
        engaged(follower, master)
        val worst = playSynced(follower, master, 20.0) { at -> if (at > 5.0) master.tempo = 0.5f }
        assertEquals("speed follows the master's fader", 128.0 * 1.04 / 124.0, follower.syncRatio, 1e-9)
        assertFalse(follower.needAlign)
        assertTrue("beats stay together (worst $worst)", worst < 0.01)
    }

    @Test fun nudgingWhileSyncedHoldsTheNewOffset() {
        val master = Deck(1).apply { load(track("m", 128f)) }
        val follower = Deck(0).apply { load(track("f", 128f)) }
        engaged(follower, master)
        playSynced(follower, master, 1.0)
        playSynced(follower, master, 0.5) { follower.bend = 0.04f }
        follower.bend = 0f
        val held = follower.syncOffset
        assertTrue("the nudge moved the offset", abs(held) > 0.01)
        playSynced(follower, master, 10.0)
        assertEquals("sync keeps the nudge instead of pulling it back", held, follower.syncOffset, 1e-9)
        assertEquals(1.0, follower.phaseAdj, 1e-4)
    }

    @Test fun halfTimeSyncLocksEveryOtherBeat() {
        val master = Deck(1).apply { load(track("m", 174f)) }
        val follower = Deck(0).apply { load(track("f", 86.5f)) }
        engaged(follower, master)
        assertEquals("two master beats per follower beat", 2.0, follower.syncFold, 0.0)
        assertTrue(playSynced(follower, master, 30.0) < 0.01)
    }

    @Test fun phaseErrorWrapsToHalfABeat() {
        assertEquals(0.1, syncPhaseError(10.1, 9.0, 1.0, 0.0), 1e-9)
        assertEquals(-0.1, syncPhaseError(9.9, 9.0, 1.0, 0.0), 1e-9)
        assertEquals(-0.25, syncPhaseError(12.75, 6.0, 2.0, 0.0), 1e-9)
    }

    @Test fun shownBpmIgnoresPerBeatWobble() {
        val t = dynamicMaster()
        assertEquals(160.0, t.shownBpm(t.beatPos(50.3)), 0.03)
        assertEquals(180.0, t.shownBpm(t.beatPos(300.0)), 0.03)
        assertNotEquals("the raw grid wobbles", t.bpmAt(t.beatPos(50.3)).toDouble(), t.bpmAt(t.beatPos(51.3)).toDouble(), 0.1)
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
