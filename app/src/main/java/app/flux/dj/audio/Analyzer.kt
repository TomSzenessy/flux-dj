package app.flux.dj.audio

import kotlin.math.*

/** Off-thread track analysis: 3-band waveform, BPM + beat grid, musical key, loudness. */
object Analyzer {

    /** Fast pass (~100 ms): waveform + overview + loudness. */
    fun waveform(t: Track) {
        val n = t.frames
        val bins = n / BIN
        val w = ByteArray(bins * 3)
        val raw = FloatArray(bins * 3)
        var lp1 = 0f; var lp2 = 0f
        val a1 = 1f - exp(-2f * PI.toFloat() * 200f / SRF)
        val a2 = 1f - exp(-2f * PI.toFloat() * 2500f / SRF)
        val pcm = t.pcm
        var sumSq = 0.0
        var maxv = 1e-6f
        var i = 0
        for (b in 0 until bins) {
            var pl = 0f; var pm = 0f; var ph = 0f
            for (k in 0 until BIN) {
                val m = (pcm[i * 2] + pcm[i * 2 + 1]) * (0.5f / 32768f)
                lp1 += a1 * (m - lp1)
                lp2 += a2 * (m - lp2)
                val lo = lp1; val mi = lp2 - lp1; val hi = m - lp2
                val al = abs(lo); val am = abs(mi); val ah = abs(hi)
                if (al > pl) pl = al
                if (am > pm) pm = am
                if (ah > ph) ph = ah
                sumSq += (m * m).toDouble()
                i++
            }
            raw[b * 3] = pl; raw[b * 3 + 1] = pm; raw[b * 3 + 2] = ph
            val tot = pl + pm + ph
            if (tot > maxv) maxv = tot
        }
        val sc = 255f / (maxv * 0.8f)
        for (j in raw.indices) w[j] = (raw[j] * sc).coerceIn(0f, 255f).toInt().toByte()
        t.wave = w
        // overview
        val ov = ByteArray(OV_COLS * 3)
        for (c in 0 until OV_COLS) {
            val b0 = (c.toLong() * bins / OV_COLS).toInt()
            val b1 = max(b0 + 1, ((c + 1).toLong() * bins / OV_COLS).toInt())
            var l = 0; var m = 0; var h = 0
            for (b in b0 until min(b1, bins)) {
                l = max(l, w[b * 3].toInt() and 255); m = max(m, w[b * 3 + 1].toInt() and 255); h = max(h, w[b * 3 + 2].toInt() and 255)
            }
            ov[c * 3] = l.toByte(); ov[c * 3 + 1] = m.toByte(); ov[c * 3 + 2] = h.toByte()
        }
        t.overview = ov
        val rms = sqrt(sumSq / max(1, n)).toFloat()
        t.rms = rms
        t.gain = if (rms > 1e-4f) (0.16f / rms).coerceIn(0.5f, 1.8f) else 1f
    }

    /** Re-run beat detection only (needs just the waveform, not the audio). */
    fun rebeat(t: Track) {
        val w = t.wave ?: return
        val bins = w.size / 3
        if (bins > 441 * 12) detectBpm(t, w, bins)
    }

    /** Slow pass: BPM, first beat, key. */
    fun rhythmAndKey(t: Track, knownBpm: Float = 0f, knownKey: Int = -1) {
        val w = t.wave ?: return
        val bins = w.size / 3
        if (knownBpm > 0) { t.bpm = knownBpm; t.firstBeat = 0.0 }
        else if (bins > 441 * 12) detectBpm(t, w, bins)
        t.key = if (knownKey >= 0) knownKey else detectKey(t)
        t.analyzed = true
    }

    /** Energy (1..10), rhythmic pattern and a vibe label, derived from the waveform + tempo. */
    fun profile(t: Track) {
        val w = t.wave ?: return
        val bins = w.size / 3
        if (bins < 441 * 6) return
        var sl = 0L; var sm = 0L; var sh = 0L
        for (b in 0 until bins) { sl += w[b * 3].toInt() and 255; sm += w[b * 3 + 1].toInt() and 255; sh += w[b * 3 + 2].toInt() and 255 }
        val tot = (sl + sm + sh).toFloat().coerceAtLeast(1f)
        val highRatio = sh / tot
        val rmsN = ((t.rms - 0.05f) / 0.2f).coerceIn(0f, 1f)
        val bpmN = if (t.bpm > 0) ((t.bpm - 80f) / 90f).coerceIn(0f, 1f) else 0f
        t.energy = (1 + 9 * (0.5f * rmsN + 0.3f * bpmN + 0.2f * (highRatio * 3f).coerceIn(0f, 1f))).roundToInt().coerceIn(1, 10)
        var four = false
        if (t.bpm > 0) {
            // kick regularity: low-band onsets on the beat vs off the beat
            val period = 441.0 * 60.0 / t.bpm
            val lo = FloatArray(bins) { (w[it * 3].toInt() and 255).toFloat() }
            val fl = onset(lo, 12)
            var n = 0; var hit = 0
            val sorted = fl.copyOf(); java.util.Arrays.sort(sorted)
            val thr = sorted[(sorted.size * 0.985f).toInt().coerceAtMost(sorted.size - 1)] * 0.4f
            val start = bins / 3
            val fbBin = t.firstBeat / BIN
            var p = fbBin + Math.ceil((start - fbBin) / period) * period
            while (p + period < bins - 4 && n < 240) {
                val i = p.toInt()
                val m = max(max(fl[i], fl[i + 1]), max(max(fl[i + 2], fl[i + 3]), if (i > 1) max(fl[i - 1], fl[i - 2]) else 0f))
                if (m > thr && thr > 0f) hit++
                p += period; n++
            }
            four = n > 8 && hit.toFloat() / n >= 0.78f
            t.pattern = if (four) "4/4 kick" else "Broken beat"
        } else t.pattern = "Beatless"
        val bpm = t.bpm
        t.vibe = when {
            t.bpm <= 0 -> "Ambient"
            bpm < 96 -> if (t.energy <= 4) "Chill" else "Groove"
            bpm < 116 -> "Groove"
            bpm < 135 -> if (four) "House" else "Breaks"
            bpm < 153 -> if (four) "Techno" else "Breaks"
            else -> "Drum & Bass"
        }
    }

    private fun detectBpm(t: Track, w: ByteArray, bins: Int) {
        // analysis window: up to 75 s from the middle
        val winBins = min(bins, 441 * 75)
        val s0 = (bins - winBins) / 2
        val e = FloatArray(winBins)
        val lowE = FloatArray(winBins)
        for (i in 0 until winBins) {
            val b = (s0 + i) * 3
            val lo = (w[b].toInt() and 255).toFloat()
            val mi = (w[b + 1].toInt() and 255).toFloat()
            val hi = (w[b + 2].toInt() and 255).toFloat()
            e[i] = lo * 1.0f + mi * 0.7f + hi * 0.5f
            lowE[i] = lo
        }
        val flux = onset(e, 12)
        val lowFlux = onset(lowE, 12)
        val minLag = 441f * 60f / 180f
        val maxLag = 441f * 60f / 70f
        var bestLag = 0f; var best = -1f
        var lag = minLag
        val scores = HashMap<Int, Float>()
        while (lag <= maxLag) {
            val s = acf(flux, lag) + 0.5f * acf(flux, lag * 2f)
            scores[(lag * 10).toInt()] = s
            lag += 0.1f
        }
        // prefer tempos in 90..150 by gentle weighting
        for ((k, s) in scores) {
            val l = k / 10f
            val bpm = 441f * 60f / l
            val wgt = if (bpm in 88f..150f) 1f else 0.85f
            if (s * wgt > best) { best = s * wgt; bestLag = l }
        }
        val coarse = 441f * 60f / bestLag
        // Refine over the WHOLE track: a tempo that is 0.1 BPM off drifts a full beat in ~4 minutes,
        // so pick the tempo + phase whose grid lands on the most kicks from start to end.
        val lf = onset(FloatArray(bins) { (w[it * 3].toInt() and 255).toFloat() }, 12)
        var bestB = coarse; var bestO = 0; var bestS = -1f
        var cand = coarse - 0.3f
        while (cand <= coarse + 0.3001f) {
            val p = 441.0 * 60.0 / cand
            val nPh = p.toInt()
            for (o in 0 until nPh) {
                var sc = 0f
                var q = o + 0.5
                while (q < bins) { sc += lf[q.toInt()]; q += p }
                if (sc > bestS) { bestS = sc; bestB = cand; bestO = o }
            }
            cand += 0.01f
        }
        var bpm = (bestB * 100f).roundToInt() / 100f
        val r = bpm.roundToInt().toFloat()
        if (abs(bpm - r) < 0.04f) bpm = r
        val period = 441.0 * 60.0 / bpm
        var fb = bestO.toDouble()
        fb -= floor(fb / period) * period
        t.bpm = bpm
        t.firstBeat = max(0.0, (fb - 1.0) * BIN)
    }

    private fun onset(e: FloatArray, avgN: Int): FloatArray {
        val f = FloatArray(e.size)
        var acc = 0f
        for (i in e.indices) {
            val avg = if (i >= avgN) acc / avgN else if (i > 0) acc / i else 0f
            f[i] = max(0f, e[i] - avg)
            acc += e[i]
            if (i >= avgN) acc -= e[i - avgN]
        }
        return f
    }

    private fun acf(f: FloatArray, lag: Float): Float {
        val li = lag.toInt(); val fr = lag - li
        var s = 0f
        val n = f.size - li - 2
        if (n <= 0) return 0f
        for (i in 0 until n) s += f[i] * (f[i + li] * (1 - fr) + f[i + li + 1] * fr)
        return s / n
    }

    // Krumhansl-Schmuckler profiles
    private val MAJ = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MIN = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    private fun detectKey(t: Track): Int {
        // decimate x4 -> 11025 Hz, up to 60 s from the middle
        val dr = SR / 4
        val total = t.frames / 4
        val len = min(total, dr * 60)
        val start = (total - len) / 2
        val x = FloatArray(len)
        for (i in 0 until len) {
            val b = (start + i) * 4
            var s = 0
            for (k in 0 until 4) s += t.pcm[(b + k) * 2] + t.pcm[(b + k) * 2 + 1]
            x[i] = s / (8f * 32768f)
        }
        val chroma = DoubleArray(12)
        val frame = 4096
        val coef = DoubleArray(48) { 2.0 * cos(2.0 * PI * 440.0 * 2.0.pow((36 + it - 69) / 12.0) / dr) }
        val win = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * PI * it / frame)).toFloat() }
        var f0 = 0
        val buf = FloatArray(frame)
        var hop = frame * 2 // skip half the frames for speed
        while (f0 + frame <= len) {
            var energy = 0f
            for (i in 0 until frame) { buf[i] = x[f0 + i] * win[i]; energy += buf[i] * buf[i] }
            if (energy > 1e-3f) {
                for (n in 0 until 48) {
                    val c = coef[n]
                    var s1 = 0.0; var s2 = 0.0
                    for (i in 0 until frame) { val s0 = buf[i] + c * s1 - s2; s2 = s1; s1 = s0 }
                    val p = s1 * s1 + s2 * s2 - c * s1 * s2
                    chroma[(36 + n) % 12] += sqrt(max(0.0, p))
                }
            }
            f0 += hop
        }
        var best = -1.0; var bk = -1
        for (k in 0 until 12) {
            var cM = 0.0; var cm = 0.0
            val mm = MAJ.average(); val mn = MIN.average(); val cmean = chroma.average()
            var nM = 0.0; var nm = 0.0; var nc = 0.0
            for (i in 0 until 12) {
                val c = chroma[(i + k) % 12] - cmean
                cM += c * (MAJ[i] - mm); cm += c * (MIN[i] - mn)
                nM += (MAJ[i] - mm).pow(2); nm += (MIN[i] - mn).pow(2); nc += c * c
            }
            val rM = cM / sqrt(nM * nc + 1e-9); val rm = cm / sqrt(nm * nc + 1e-9)
            if (rM > best) { best = rM; bk = k }
            if (rm > best) { best = rm; bk = k + 12 }
        }
        return bk
    }
}
