package app.flux.dj.audio

import kotlin.math.*
import kotlin.random.Random

/** Procedural one-shots (used for the sampler and for the built-in demo tracks). */
object Synth {
    private fun n(sec: Float) = (sec * SR).toInt()
    private fun midiHz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    fun kick(): FloatArray {
        val len = n(0.5f); val o = FloatArray(len)
        var ph = 0.0
        for (i in 0 until len) {
            val t = i / SRF
            val f = 42.0 + 120.0 * exp(-t * 32.0)
            ph += 2 * PI * f / SR
            val env = exp(-t * 6.5)
            val click = if (i < 90) (1f - i / 90f) * 0.5f else 0f
            o[i] = (sin(ph) * env * 1.1 + click).toFloat().coerceIn(-1f, 1f)
        }
        return o
    }

    fun snare(): FloatArray {
        val r = Random(3); val len = n(0.3f); val o = FloatArray(len)
        var ph = 0.0; var prev = 0f
        for (i in 0 until len) {
            val t = i / SRF
            ph += 2 * PI * (190.0 - 60 * t) / SR
            val nz = r.nextFloat() * 2 - 1
            val hp = nz - prev * 0.6f; prev = nz
            o[i] = (hp * exp(-t * 16.0) * 0.8 + sin(ph) * exp(-t * 28.0) * 0.6).toFloat()
        }
        return o
    }

    fun clap(): FloatArray {
        val r = Random(5); val len = n(0.28f); val o = FloatArray(len)
        var prev = 0f
        for (i in 0 until len) {
            val t = i / SRF
            val nz = r.nextFloat() * 2 - 1
            val hp = nz - prev * 0.5f; prev = nz
            val burst = (0..2).sumOf { k -> if (t >= k * 0.011f) exp(-(t - k * 0.011f) * 140.0) else 0.0 }
            val tail = exp(-t * 20.0) * 0.55
            o[i] = (hp * (burst * 0.5 + tail) * 0.9).toFloat()
        }
        return o
    }

    fun hat(open: Boolean): FloatArray {
        val r = Random(if (open) 7 else 9); val len = n(if (open) 0.32f else 0.07f); val o = FloatArray(len)
        var p1 = 0f; var p2 = 0f
        val dec = if (open) 11.0 else 70.0
        for (i in 0 until len) {
            val t = i / SRF
            val nz = r.nextFloat() * 2 - 1
            val hp = nz - p1 * 0.95f + p2 * 0.0f; p1 = nz * 0.5f + p1 * 0.5f; p2 = p1
            o[i] = (hp * exp(-t * dec) * 0.45).toFloat()
        }
        return o
    }

    fun tom(): FloatArray {
        val len = n(0.4f); val o = FloatArray(len); var ph = 0.0
        for (i in 0 until len) {
            val t = i / SRF
            ph += 2 * PI * (110.0 + 90 * exp(-t * 18.0)) / SR
            o[i] = (sin(ph) * exp(-t * 8.0)).toFloat()
        }
        return o
    }

    fun horn(): FloatArray {
        val len = n(1.0f); val o = FloatArray(len)
        val fs = doubleArrayOf(440.0, 554.4, 659.3, 880.0)
        val ph = DoubleArray(4)
        for (i in 0 until len) {
            val t = i / SRF
            val vib = 1.0 + 0.012 * sin(2 * PI * 5.5 * t)
            var s = 0.0
            for (k in fs.indices) {
                ph[k] += fs[k] * vib / SR
                val saw = 2 * (ph[k] - floor(ph[k] + 0.5))
                s += saw * (if (k == 3) 0.12 else 0.26)
            }
            val env = min(1.0, t / 0.015) * (if (t > 0.8) (1.0 - (t - 0.8) / 0.2) else 1.0)
            o[i] = tanh(s * 1.6 * env).toFloat() * 0.8f
        }
        return o
    }

    fun siren(): FloatArray {
        val len = n(1.4f); val o = FloatArray(len); var ph = 0.0
        for (i in 0 until len) {
            val t = i / SRF
            val f = 900.0 + 500.0 * sin(2 * PI * 2.2 * t)
            ph += 2 * PI * f / SR
            val env = min(1.0, t / 0.02) * (if (t > 1.0) (1.0 - (t - 1.0) / 0.4) else 1.0)
            o[i] = (sin(ph) * 0.7 * env).toFloat()
        }
        return o
    }

    fun riser(sec: Float = 1.6f): FloatArray {
        val r = Random(11); val len = n(sec); val o = FloatArray(len)
        var lp = 0f
        for (i in 0 until len) {
            val p = i / len.toFloat()
            val nz = r.nextFloat() * 2 - 1
            val a = 0.02f + 0.9f * p * p
            lp += a * (nz - lp)
            o[i] = (nz - lp * 0.0f).let { lp * (0.5f + 2f * p) } * p
        }
        return o
    }

    /** Punchy sub-bass note. */
    fun bass(midi: Int, sec: Float, saw: Boolean): FloatArray {
        val len = n(sec); val o = FloatArray(len); val f = midiHz(midi); var ph = 0.0; var lp = 0f
        for (i in 0 until len) {
            val t = i / SRF
            ph += f / SR
            val s = if (saw) (2 * (ph - floor(ph + 0.5))).toFloat() else tanh(sin(2 * PI * ph) * 2.2).toFloat()
            val cut = if (saw) 0.06f + 0.12f * exp(-t * 14f) else 1f
            lp += cut * (s - lp)
            val env = min(1f, i / 120f) * min(1f, (len - i) / 400f) * exp(-t * 2.2f)
            o[i] = lp * env * 0.9f
        }
        return o
    }

    /** Detuned saw chord with soft low-pass. */
    fun chord(midis: IntArray, sec: Float, bright: Float = 0.12f): FloatArray {
        val len = n(sec); val o = FloatArray(len)
        val ph = DoubleArray(midis.size * 3)
        var lp = 0f
        for (i in 0 until len) {
            val t = i / SRF
            var s = 0.0
            for (k in midis.indices) for (d in 0..2) {
                val f = midiHz(midis[k]) * (1.0 + (d - 1) * 0.004)
                val idx = k * 3 + d
                ph[idx] += f / SR
                s += 2 * (ph[idx] - floor(ph[idx] + 0.5))
            }
            lp += bright * (s.toFloat() - lp)
            val env = min(1f, t / 0.03f) * min(1f, (sec - t) / 0.12f) * (0.55f + 0.45f * exp(-t * 3.0f))
            o[i] = lp * env * 0.11f
        }
        return o
    }
}

object DemoTracks {
    class Spec(val id: String, val title: String, val artist: String, val bpm: Float, val rootPc: Int, val minor: Boolean, val style: Int)

    val specs = listOf(
        Spec("demo:1", "Sunset Drive", "FLUX Demo", 122f, 9, true, 0),   // Am  8A
        Spec("demo:2", "Neon Pulse", "FLUX Demo", 126f, 4, true, 1),     // Em  9A
        Spec("demo:3", "Velvet Hours", "FLUX Demo", 128f, 2, true, 2),   // Dm  7A
    )

    fun build(s: Spec): Track {
        val bars = 56
        val spb = 60.0 / s.bpm * SR
        val spBar = spb * 4
        val total = (bars * spBar).toInt() + SR
        val L = FloatArray(total); val R = FloatArray(total)
        fun put(src: FloatArray, at: Double, g: Float, pan: Float = 0f) {
            val st = at.toInt(); val gl = g * (1f - max(0f, pan)); val gr = g * (1f + min(0f, pan))
            val m = min(src.size, total - st)
            for (i in 0 until m) { L[st + i] += src[i] * gl; R[st + i] += src[i] * gr }
        }
        val kick = Synth.kick(); val clap = Synth.clap(); val snare = Synth.snare()
        val hatC = Synth.hat(false); val hatO = Synth.hat(true)
        val riser = Synth.riser((spb * 16 / SR).toFloat())
        val prog = intArrayOf(0, 8, 3, 10) // i VI III VII
        val third = if (s.minor) 3 else 4
        val bassCache = HashMap<Int, FloatArray>()
        fun bassNote(m: Int, steps: Float) = bassCache.getOrPut(m * 100 + (steps * 10).toInt()) {
            Synth.bass(m, (spb / 4 * steps / SR).toFloat(), s.style == 1)
        }
        val chordCache = HashMap<Int, FloatArray>()
        fun chordFor(rootMidi: Int, bars: Float, bright: Float) = chordCache.getOrPut(rootMidi * 10 + bars.toInt() * 3 + (bright * 100).toInt()) {
            Synth.chord(intArrayOf(rootMidi, rootMidi + third, rootMidi + 7, rootMidi + 12 + (if (s.style == 2) 3 else 0)), (spBar * bars / SR).toFloat(), bright)
        }
        for (bar in 0 until bars) {
            val at0 = bar * spBar
            val sec = when { bar < 8 -> 0; bar < 16 -> 1; bar < 32 -> 2; bar < 40 -> 3; bar < 56 - 8 -> 2; else -> 4 }
            val root = prog[(bar / 2) % 4]
            val bassMidi = 36 + s.rootPc % 12 + root - (if (s.rootPc + root >= 12 + 3) 12 else 0)
            val rootMidi = 57 + (s.rootPc + root) % 12
            for (beat in 0 until 4) {
                val at = at0 + beat * spb
                if (sec != 3) put(kick, at, if (sec == 0 && bar < 2) 0.9f else 1.0f)
                if (sec == 3 && bar >= 38 && beat == 0) put(kick, at, 0.8f)
                if (sec in 1..2 && (beat == 1 || beat == 3)) put(if (s.style == 2) snare else clap, at, 0.7f)
                // off-beat open hat
                if (sec != 3 || bar >= 36) put(hatO, at + spb / 2, 0.30f, 0.15f)
                if (sec == 2 || sec == 4) for (k in 0..3) if (k % 2 == 1) put(hatC, at + k * spb / 4, 0.17f + 0.05f * (k % 3), -0.2f)
                if (sec == 0 && bar >= 4) put(hatC, at + spb / 4 * 3, 0.15f)
                // bass
                if (sec == 1 || sec == 2 || sec == 4) {
                    when (s.style) {
                        0 -> put(bassNote(bassMidi, 1.7f), at + spb / 2, 0.62f)
                        1 -> for (k in 0..3) if (!(k == 0)) put(bassNote(bassMidi + (if (k == 3 && beat % 2 == 1) 12 else 0), 0.9f), at + k * spb / 4, 0.55f)
                        else -> {
                            put(bassNote(bassMidi, 1.8f), at + spb / 2, 0.6f)
                            put(bassNote(bassMidi + 12, 1.0f), at + spb * 3 / 4, 0.4f)
                        }
                    }
                }
            }
            // chords: every bar in drops, 1 bar pads in break
            if (sec == 2) { if (s.style != 1 || bar % 2 == 0) put(chordFor(rootMidi, 1f, 0.12f), at0 + (if (s.style == 0) spb / 2 else 0.0), 0.8f, 0.1f) }
            if (sec == 3) put(chordFor(rootMidi, 1f, 0.05f), at0, 0.9f, -0.1f)
            if (sec == 1 && bar >= 12) put(chordFor(rootMidi, 1f, 0.04f + 0.01f * (bar - 12)), at0, 0.55f)
            // fills
            if (bar == 7 || bar == 15 || bar == 39 || bar == 47) for (k in 0 until 8) put(snare, at0 + spBar - spb + k * spb / 8, 0.25f + 0.1f * k)
            if (bar == 12 || bar == 36) put(riser, at0, 0.5f)
        }
        // normalise
        var peak = 1e-6f
        for (i in 0 until total) peak = max(peak, max(abs(L[i]), abs(R[i])))
        val g = 0.92f / peak
        val pcm = ShortArray(total * 2)
        for (i in 0 until total) {
            pcm[i * 2] = (L[i] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            pcm[i * 2 + 1] = (R[i] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val t = Track(s.id, s.title, s.artist, pcm)
        Analyzer.waveform(t)
        Analyzer.rhythmAndKey(t, s.bpm, s.rootPc + if (s.minor) 12 else 0)
        return t
    }
}

/** Sampler bank used by the SAMPLE pad mode. */
object SampleBank {
    val defaults = arrayOf("KICK", "SNARE", "CLAP", "HAT", "TOM", "HORN", "SIREN", "RISER")
    val names = defaults.copyOf()
    private val builtIn: Array<FloatArray> by lazy {
        arrayOf(Synth.kick(), Synth.snare(), Synth.clap(), Synth.hat(true), Synth.tom(), Synth.horn(), Synth.siren(), Synth.riser(1.5f))
    }
    /** Mono float one-shots; entries are swapped atomically when the user replaces a pad. */
    val data: Array<FloatArray> by lazy { builtIn.copyOf() }

    fun set(i: Int, samples: FloatArray, name: String) { data[i] = samples; names[i] = name }
    fun reset(i: Int) { data[i] = builtIn[i]; names[i] = defaults[i] }

    /** Mono, trimmed of leading silence, capped at [maxSec], short fades to avoid clicks. */
    fun fromStereo(pcm: ShortArray, startFrame: Int, endFrame: Int, maxSec: Float = 10f, trim: Boolean = true): FloatArray {
        var s = startFrame.coerceAtLeast(0)
        val e0 = minOf(endFrame, pcm.size / 2)
        if (trim) while (s < e0 && kotlin.math.abs(pcm[s * 2] + pcm[s * 2 + 1]) < 400) s++
        val e = minOf(e0, s + (maxSec * SR).toInt())
        val n = (e - s).coerceAtLeast(0)
        val out = FloatArray(n) { (pcm[(s + it) * 2] + pcm[(s + it) * 2 + 1]) / 65536f }
        val f = minOf(96, n / 4)
        for (k in 0 until f) { out[k] *= k / f.toFloat(); out[n - 1 - k] *= k / f.toFloat() }
        return out
    }
}
