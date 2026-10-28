package app.flux.dj

import app.flux.dj.audio.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DspTest {
    @Test fun alignDelayClampsToItsCapacity() {
        val d = AlignDelay(100)
        d.frames = 10_000
        assertEquals(100, d.frames)
        val l = FloatArray(BLOCK); val r = FloatArray(BLOCK)
        for (i in 0 until BLOCK) { l[i] = 1f; r[i] = 1f }
        d.process(l, r, BLOCK)
        for (i in 0 until BLOCK) { assertTrue(l[i].isFinite()); assertTrue(r[i].isFinite()) }
    }

    @Test fun delayLineHandlesOversizedDelayWithoutThrowing() {
        val d = DelayLine(64)
        for (i in 0 until 64) { d.write(i.toFloat(), -i.toFloat()) }
        assertTrue(d.readL(10_000f).isFinite())
        assertTrue(d.readR(-3f).isFinite())
    }

    @Test fun reverbWetGainStaysBounded() {
        val rev = Reverb()
        val l = FloatArray(512); val r = FloatArray(512)
        l[0] = 1f; r[0] = 1f
        rev.process(l, r, 512, 1f)
        var peak = 0f
        for (i in 0 until 512) { assertTrue(l[i].isFinite()); peak = maxOf(peak, abs(l[i]), abs(r[i])) }
        assertTrue("wet peak $peak stays near unity", peak < 5f)
    }

    @Test fun phaserDepthChangesTheSound() {
        val dry = FloatArray(BLOCK) { if (it == 0) 1f else 0f }
        val aL = dry.copyOf(); val aR = dry.copyOf(); val bL = dry.copyOf(); val bR = dry.copyOf()
        val oaL = FloatArray(BLOCK); val oaR = FloatArray(BLOCK)
        val obL = FloatArray(BLOCK); val obR = FloatArray(BLOCK)
        val busA = BeatFxBus(); val busB = BeatFxBus()
        busA.process(BeatFx.PHASER, aL, aR, oaL, oaR, BLOCK, 22050f, 1f, 0f)
        busB.process(BeatFx.PHASER, bL, bR, obL, obR, BLOCK, 22050f, 1f, 1f)
        var diff = 0f
        for (i in 0 until BLOCK) diff += abs(oaL[i] - obL[i])
        assertTrue("depth changes the phaser output", diff > 1e-4f)
    }
}
