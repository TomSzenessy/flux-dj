package app.flux.dj.audio

import kotlin.math.*

/** Stereo RBJ biquad. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var xl1 = 0f; private var xl2 = 0f; private var yl1 = 0f; private var yl2 = 0f
    private var xr1 = 0f; private var xr2 = 0f; private var yr1 = 0f; private var yr2 = 0f
    var bypass = true

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        bypass = false
    }

    fun lowShelf(f: Double, db: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * f / SR; val cw = cos(w); val al = sin(w) / 2 * sqrt(2.0)
        val t = 2 * sqrt(A) * al
        set(A * ((A + 1) - (A - 1) * cw + t), 2 * A * ((A - 1) - (A + 1) * cw), A * ((A + 1) - (A - 1) * cw - t),
            (A + 1) + (A - 1) * cw + t, -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - t)
    }

    fun highShelf(f: Double, db: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * f / SR; val cw = cos(w); val al = sin(w) / 2 * sqrt(2.0)
        val t = 2 * sqrt(A) * al
        set(A * ((A + 1) + (A - 1) * cw + t), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - t),
            (A + 1) - (A - 1) * cw + t, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - t)
    }

    fun peak(f: Double, q: Double, db: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * f / SR; val cw = cos(w); val al = sin(w) / (2 * q)
        set(1 + al * A, -2 * cw, 1 - al * A, 1 + al / A, -2 * cw, 1 - al / A)
    }

    fun lowpass(f: Double, q: Double) {
        val w = 2 * PI * f / SR; val cw = cos(w); val al = sin(w) / (2 * q)
        set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al)
    }

    fun highpass(f: Double, q: Double) {
        val w = 2 * PI * f / SR; val cw = cos(w); val al = sin(w) / (2 * q)
        set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
    }

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        if (bypass) return
        var x1 = xl1; var x2 = xl2; var y1 = yl1; var y2 = yl2
        for (i in 0 until n) {
            val x = l[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y; l[i] = y
        }
        xl1 = x1; xl2 = x2; yl1 = y1; yl2 = y2
        x1 = xr1; x2 = xr2; y1 = yr1; y2 = yr2
        for (i in 0 until n) {
            val x = r[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y; r[i] = y
        }
        xr1 = x1; xr2 = x2; yr1 = y1; yr2 = y2
    }
}

/** Fractional stereo delay line with smoothed delay time (tape-style pitch glide when changed). */
class DelayLine(val size: Int) {
    val l = FloatArray(size); val r = FloatArray(size)
    var w = 0
    fun write(a: Float, b: Float) { l[w] = a; r[w] = b; w = if (w + 1 == size) 0 else w + 1 }
    fun readL(d: Float) = rd(l, d)
    fun readR(d: Float) = rd(r, d)
    private fun rd(buf: FloatArray, d: Float): Float {
        if (size <= 0 || !d.isFinite()) return 0f
        var p = (w - d) % size
        if (p < 0f) p += size
        val i = p.toInt().coerceIn(0, size - 1); val f = p - i
        val i1 = if (i + 1 == size) 0 else i + 1
        return buf[i] * (1 - f) + buf[i1] * f
    }
}

// ---------------------------------------------------------------- Colour FX (per channel)

enum class ColorFx(val label: String) { FILTER("Filter"), NOISE("Noise"), CRUSH("Crush"), ECHO("Dub Echo"), SPACE("Space") }

class ColorFxUnit {
    private val lp = Biquad()
    private val hp = Biquad()
    private val echo = DelayLine(SR * 2)
    private var echoLpL = 0f; private var echoLpR = 0f
    private val rev = Reverb()
    private val tmpL = FloatArray(4096); private val tmpR = FloatArray(4096)
    private var noiseSeed = 22222
    private var holdL = 0f; private var holdR = 0f; private var holdCnt = 0
    private var tail = 0
    private var nLp = 0f
    private var delay = SR * 0.4f

    private fun rnd(): Float { noiseSeed = noiseSeed * 1664525 + 1013904223; return (noiseSeed shr 8) / 8388608f - 1f }

    fun process(mode: ColorFx, v: Float, bpm: Float, l: FloatArray, r: FloatArray, n: Int) {
        val d = abs(v - 0.5f) * 2f
        val up = v > 0.5f
        if (d < 0.025f && mode != ColorFx.ECHO && mode != ColorFx.SPACE) { lp.bypass = true; hp.bypass = true; return }
        when (mode) {
            ColorFx.FILTER -> {
                if (up) { lp.bypass = true; hp.highpass(20.0 * 400.0.pow(d.toDouble()), 1.1); hp.process(l, r, n) }
                else { hp.bypass = true; lp.lowpass(20000.0 * 0.008.pow(d.toDouble()), 1.1); lp.process(l, r, n) }
            }
            ColorFx.NOISE -> {
                val amt = d * d * 0.35f
                val a = if (up) 0.9f else 0.12f
                for (i in 0 until n) {
                    val nz = rnd()
                    nLp += a * (nz - nLp)
                    val ns = if (up) nz - nLp else nLp * 2.2f
                    l[i] = l[i] * (1f - d * 0.35f) + ns * amt
                    r[i] = r[i] * (1f - d * 0.35f) + ns * amt
                }
                if (up) { hp.highpass(20.0 * 20.0.pow(d.toDouble()), 0.8); hp.process(l, r, n) } else { lp.lowpass(20000.0 * 0.02.pow(d.toDouble()), 0.8); lp.process(l, r, n) }
            }
            ColorFx.CRUSH -> {
                val hold = 1 + (d * 12).toInt()
                val levels = 2.0.pow((15.0 - d * 9.5)).toFloat()
                for (i in 0 until n) {
                    if (holdCnt <= 0) {
                        holdL = round(l[i] * levels) / levels; holdR = round(r[i] * levels) / levels; holdCnt = hold
                    }
                    holdCnt--
                    l[i] = holdL; r[i] = holdR
                }
            }
            ColorFx.ECHO -> {
                if (n > tmpL.size) return
                val active = d > 0.02f
                if (!active) { tail += n; if (tail > SR * 3) { java.util.Arrays.fill(echo.l, 0f); java.util.Arrays.fill(echo.r, 0f); return } } else tail = 0
                val fade = if (active) 1f else (1f - tail.toFloat() / (SR * 3)).coerceIn(0f, 1f)
                val target = (60f / max(60f, bpm)) * 0.75f * SRF
                val fb = 0.55f
                for (i in 0 until n) {
                    delay += (target - delay) * 0.0006f
                    val dl = echo.readL(delay); val dr = echo.readR(delay)
                    echoLpL += 0.45f * (dl - echoLpL); echoLpR += 0.45f * (dr - echoLpR)
                    val inL = if (active) l[i] * min(1f, d * 1.6f) else 0f
                    val inR = if (active) r[i] * min(1f, d * 1.6f) else 0f
                    echo.write(inL + echoLpL * fb, inR + echoLpR * fb)
                    l[i] += dl * 0.9f * fade; r[i] += dr * 0.9f * fade
                }
            }
            ColorFx.SPACE -> {
                if (n > tmpL.size) return
                val active = d > 0.02f
                if (!active) { tail += n; if (tail > SR * 4) { java.util.Arrays.fill(tmpL, 0f); java.util.Arrays.fill(tmpR, 0f); return } } else tail = 0
                val fade = if (active) 1f else (1f - tail.toFloat() / (SR * 4)).coerceIn(0f, 1f)
                for (i in 0 until n) { tmpL[i] = if (active) l[i] * min(1f, d * 1.4f) else 0f; tmpR[i] = if (active) r[i] * min(1f, d * 1.4f) else 0f }
                rev.process(tmpL, tmpR, n, 0.9f)
                for (i in 0 until n) { l[i] += tmpL[i] * fade; r[i] += tmpR[i] * fade }
            }
        }
    }
}

// ---------------------------------------------------------------- Reverb (Freeverb-style)

class Reverb {
    private val combLen = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491)
    private val apLen = intArrayOf(556, 441, 341)
    private val cL = Array(6) { FloatArray(combLen[it]) }
    private val cR = Array(6) { FloatArray(combLen[it] + 23) }
    private val cLi = IntArray(6); private val cRi = IntArray(6)
    private val cLs = FloatArray(6); private val cRs = FloatArray(6)
    private val aL = Array(3) { FloatArray(apLen[it]) }
    private val aR = Array(3) { FloatArray(apLen[it] + 23) }
    private val aLi = IntArray(3); private val aRi = IntArray(3)

    /** In-place; result is wet-only. */
    fun process(l: FloatArray, r: FloatArray, n: Int, room: Float) {
        val fb = 0.72f + room * 0.2f
        val damp = 0.35f
        // Keep the wet gain roughly independent of feedback: six combs each with 1/(1-fb) gain.
        val inGain = (1f - fb) / 6f
        for (i in 0 until n) {
            val inp = (l[i] + r[i]) * 0.5f * 0.2f * inGain
            var oL = 0f; var oR = 0f
            for (c in 0 until 6) {
                val bl = cL[c]; val bi = cLi[c]; val y = bl[bi]
                cLs[c] = y * (1 - damp) + cLs[c] * damp
                bl[bi] = inp + cLs[c] * fb; cLi[c] = if (bi + 1 == bl.size) 0 else bi + 1
                oL += y
                val br = cR[c]; val bj = cRi[c]; val y2 = br[bj]
                cRs[c] = y2 * (1 - damp) + cRs[c] * damp
                br[bj] = inp + cRs[c] * fb; cRi[c] = if (bj + 1 == br.size) 0 else bj + 1
                oR += y2
            }
            for (a in 0 until 3) {
                val bl = aL[a]; val bi = aLi[a]; val z = bl[bi]
                val o1 = -oL + z; bl[bi] = oL + z * 0.5f; aLi[a] = if (bi + 1 == bl.size) 0 else bi + 1; oL = o1
                val br = aR[a]; val bj = aRi[a]; val z2 = br[bj]
                val o2 = -oR + z2; br[bj] = oR + z2 * 0.5f; aRi[a] = if (bj + 1 == br.size) 0 else bj + 1; oR = o2
            }
            l[i] = oL * 0.9f; r[i] = oR * 0.9f
        }
    }
}

// ---------------------------------------------------------------- Beat FX (shared bus)

enum class BeatFx(val label: String, val insert: Boolean) {
    ECHO("Echo", false), REVERB("Reverb", false), FLANGER("Flanger", false),
    PHASER("Phaser", false), GATE("Gate", true), CRUSH("Crush", true)
}

class BeatFxBus {
    private val echo = DelayLine(SR * 3)
    private var eDelay = SR * 0.4f
    private var eLpL = 0f; private var eLpR = 0f
    private val rev = Reverb()
    private val fl = DelayLine(2048)
    private var flFbL = 0f; private var flFbR = 0f
    private var lfo = 0.0
    private val apL = FloatArray(4); private val apR = FloatArray(4)
    private var gateEnv = 1f
    private var holdL = 0f; private var holdR = 0f; private var holdC = 0
    private var gPhase = 0.0

    /** in/out are separate arrays; out is wet-only for send effects, processed signal for insert effects. */
    fun process(fx: BeatFx, inL: FloatArray, inR: FloatArray, oL: FloatArray, oR: FloatArray, n: Int, beatFrames: Float, div: Float, depth: Float) {
        when (fx) {
            BeatFx.ECHO -> {
                val target = (beatFrames * div).coerceIn(300f, SR * 2.9f)
                val fb = 0.35f + 0.5f * depth
                for (i in 0 until n) {
                    eDelay += (target - eDelay) * 0.0008f
                    val dl = echo.readL(eDelay); val dr = echo.readR(eDelay)
                    eLpL += 0.5f * (dl - eLpL); eLpR += 0.5f * (dr - eLpR)
                    echo.write(inL[i] + eLpL * fb, inR[i] + eLpR * fb)
                    oL[i] = dl; oR[i] = dr
                }
            }
            BeatFx.REVERB -> {
                for (i in 0 until n) { oL[i] = inL[i]; oR[i] = inR[i] }
                rev.process(oL, oR, n, 0.3f + 0.7f * depth)
            }
            BeatFx.FLANGER -> {
                val inc = 2 * PI / (beatFrames * 4.0 * div.coerceAtLeast(0.25f) * 2)
                val fb = 0.2f + 0.6f * depth
                for (i in 0 until n) {
                    lfo += inc; if (lfo > 2 * PI) lfo -= 2 * PI
                    val d = (SR * 0.0008f + (0.5f + 0.5f * sin(lfo).toFloat()) * SR * 0.004f)
                    val dl = fl.readL(d); val dr = fl.readR(d)
                    fl.write(inL[i] + flFbL * fb, inR[i] + flFbR * fb)
                    flFbL = dl; flFbR = dr
                    oL[i] = dl; oR[i] = dr
                }
            }
            BeatFx.PHASER -> {
                val inc = 2 * PI / (beatFrames * 8.0 * div.coerceAtLeast(0.25f))
                val wet = (0.35f + 0.65f * depth).coerceIn(0f, 1f)
                for (i in 0 until n) {
                    lfo += inc; if (lfo > 2 * PI) lfo -= 2 * PI
                    val f = 300.0 * 10.0.pow(0.5 * (0.5 + 0.5 * sin(lfo))) * (0.5 + depth)
                    val k = ((tan(PI * f / SR) - 1) / (tan(PI * f / SR) + 1)).toFloat()
                    var xl = inL[i]; var xr = inR[i]
                    for (s in 0 until 4) {
                        val yl = k * xl + apL[s]; apL[s] = xl - k * yl; xl = yl
                        val yr = k * xr + apR[s]; apR[s] = xr - k * yr; xr = yr
                    }
                    oL[i] = inL[i] * (1f - wet) + xl * wet
                    oR[i] = inR[i] * (1f - wet) + xr * wet
                }
            }
            BeatFx.GATE -> {
                val period = beatFrames * div.coerceAtLeast(0.125f) * 2
                val duty = 0.7f - 0.5f * depth
                for (i in 0 until n) {
                    gPhase += 1.0 / period; if (gPhase >= 1.0) gPhase -= 1.0
                    val t = if (gPhase < duty) 1f else 0.04f
                    gateEnv += (t - gateEnv) * 0.01f
                    oL[i] = inL[i] * gateEnv; oR[i] = inR[i] * gateEnv
                }
            }
            BeatFx.CRUSH -> {
                val hold = 1 + (depth * 20).toInt()
                val levels = 2.0.pow(14.0 - depth * 9.0).toFloat()
                for (i in 0 until n) {
                    if (holdC <= 0) { holdL = round(inL[i] * levels) / levels; holdR = round(inR[i] * levels) / levels; holdC = hold }
                    holdC--
                    oL[i] = holdL; oR[i] = holdR
                }
            }
        }
    }
}

/** Simple fixed-size stereo delay used to align master and cue outputs. */
class AlignDelay(maxFrames: Int) {
    private val cap = maxFrames.coerceAtLeast(1)
    private val d = DelayLine(cap + 2)
    var frames: Int = 0
        set(value) { field = value.coerceIn(0, cap) }
    fun process(l: FloatArray, r: FloatArray, n: Int) {
        if (frames <= 0) { for (i in 0 until n) d.write(l[i], r[i]); return }
        val f = frames.toFloat()
        for (i in 0 until n) {
            d.write(l[i], r[i])
            l[i] = d.readL(f); r[i] = d.readR(f)
        }
    }
}
