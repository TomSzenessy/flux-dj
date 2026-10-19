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
