package app.flux.dj

import app.flux.dj.audio.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * PITCH LOCK must keep the recorded pitch when the tempo moves. The granular engine used to mix
 * two out-of-phase copies, so a 440 Hz tone came out around 424/466 Hz (audibly detuned) while the
 * level pulsed. These tests lock in the measured behaviour.
 */
class PitchLockTest {
    private fun sine(freq: Double, sec: Double): Track {
        val n = (SR * sec).toInt()
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * freq * i / SR) * 12000).toInt().toShort()
            pcm[i * 2] = v; pcm[i * 2 + 1] = v
        }
        return Track("sine", "sine", "", pcm).also { it.bpm = 120f; it.firstBeat = 0.0 }
    }

    /** Dominant frequency (Goertzel sweep) and peak-to-trough level ratio of the rendered output. */
    private fun measure(d: Deck, seconds: Double): Pair<Double, Double> {
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        repeat((SR * 0.5 / BLOCK).toInt()) { d.render(BLOCK, l, r) }   // let the rate settle
        val n = (SR * seconds).toInt()
        val buf = FloatArray(n)
        var w = 0
        while (w < n) {
            d.render(BLOCK, l, r)
            for (k in 0 until minOf(BLOCK, n - w)) buf[w++] = l[k]
        }
        var best = 0.0; var bestF = 0.0
        var f = 400.0
        while (f <= 480.0) {
            val c = 2.0 * cos(2.0 * PI * f / SR)
            var s1 = 0.0; var s2 = 0.0
            for (k in 0 until n) { val s0 = buf[k] + c * s1 - s2; s2 = s1; s1 = s0 }
            val p = s1 * s1 + s2 * s2 - c * s1 * s2
            if (p > best) { best = p; bestF = f }
            f += 0.5
        }
        val win = SR / 50
        var lo = 1e9; var hi = 0.0
        var i = 0
        while (i + win <= n) {
            var s = 0.0
            for (k in 0 until win) s += buf[i + k].toDouble() * buf[i + k]
            val e = sqrt(s / win)
            lo = min(lo, e); hi = max(hi, e)
            i += win
        }
        return bestF to (if (lo > 0) hi / lo else 0.0)
    }

    @Test fun pitchLockKeepsThePitch() {
        for ((tempo, range) in listOf(0.75f to 0.08f, -0.26f to 0.08f, 1f to 0.5f)) {
            val d = Deck(0)
            d.load(sine(440.0, 20.0))
            d.range = range
            d.setTempoFader(tempo)
            d.keyLock = true
            d.play()
            val (freq, am) = measure(d, 4.0)
            assertEquals("pitch lock at ${1 + tempo * range}x", 440.0, freq, 3.0)
            // A coherent crossfade: the level must not pulse at the grain rate (43 Hz).
            assertTrue("no grain-rate level pumping at ${1 + tempo * range}x (am=$am)", am < 1.08)
        }
    }

    /** Without the lock the deck is a turntable: tempo changes the pitch. */
    @Test fun noLockChangesThePitch() {
        val d = Deck(0)
        d.load(sine(440.0, 20.0))
        d.range = 0.08f
        d.setTempoFader(0.75f)
        d.keyLock = false
        d.play()
        val (freq, _) = measure(d, 3.0)
        assertEquals(440.0 * 1.06, freq, 3.0)
    }
}
