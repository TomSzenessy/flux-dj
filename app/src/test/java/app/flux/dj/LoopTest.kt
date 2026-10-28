package app.flux.dj

import app.flux.dj.audio.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LOOP toggle and the beat-jump "move the loop" behaviour. */
class LoopTest {
    private fun deck(): Deck {
        val n = SR * 30
        val pcm = ShortArray(n * 2) { (if (it % 2 == 0) 4000 else -4000).toShort() }
        val t = Track("loop", "loop", "", pcm).also { it.bpm = 120f; it.firstBeat = 0.0 }
        return Deck(0).also { it.load(t); it.seekNow(SR * 4.0) }
    }

    @Test fun loopOffOnReentersTheSameWindow() {
        val d = deck()
        d.loopSet(4f, SR * 4.0)
        val i0 = d.loopIn
        val o0 = d.loopOut
        assertTrue("loop is on", d.loopOn)

        d.loopToggle()                 // off
        d.seekNow(SR * 12.0)           // let it run somewhere else
        d.loopToggle()                 // on again

        assertTrue("reloop is on", d.loopOn)
        assertEquals("loop-in did not move", i0, d.loopIn, 0.5)
        assertEquals("loop-out did not move", o0, d.loopOut, 0.5)
    }

    @Test fun loopToggleWithoutAStoredLoopStartsOneHere() {
        val d = deck()
        assertFalse(d.loopOn)
        d.loopToggle(SR * 6.0)
        assertTrue(d.loopOn)
        assertEquals("starts on the beat under the playhead", 0.0, d.loopIn % (SR * 0.5), 1.0)
    }

    @Test fun movingTheLoopKeepsItsLengthAndPhase() {
        val d = deck()
        d.loopSet(4f, SR * 4.0)
        val len = d.loopOut - d.loopIn
        val at = d.loopIn + len / 3
        d.seekNow(at)

        val beat = d.spbHere()
        val target = d.loopMove(beat, at)

        assertEquals("length", len, d.loopOut - d.loopIn, 0.5)
        assertEquals("window moved by one beat", d.loopIn, (at - len / 3) + beat, 1.0)
        assertEquals("playhead stays at the same point in the loop", d.loopIn + len / 3, target, 1.0)
    }

    @Test fun movingTheLoopStopsAtTheTrackEdges() {
        val d = deck()
        d.loopSet(4f, SR * 4.0)
        val len = d.loopOut - d.loopIn
        d.loopMove(-SR * 60.0, d.loopIn)
        assertEquals("clamped at the start", 0.0, d.loopIn, 0.5)
        d.loopMove(SR * 60.0, d.loopIn)
        assertTrue("clamped before the end", d.loopOut <= d.track!!.frames + 0.5)
        assertEquals("length survives clamping", len, d.loopOut - d.loopIn, 0.5)
    }
}
