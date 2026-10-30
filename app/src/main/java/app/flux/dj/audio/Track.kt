package app.flux.dj.audio

const val SR = 44100
const val SRF = 44100f
const val BIN = 100 // frames per waveform bin (441 bins / second)

/** A fully decoded track (interleaved 16-bit stereo @ 44.1 kHz) plus everything we learn about it. */
class Track(
    val id: String,
    val title: String,
    val artist: String,
    pcm: ShortArray,
) {
    /** May be swapped for a bigger array while a streaming decode is still running. */
    @Volatile var pcm: ShortArray = pcm
    @Volatile var frames: Int = pcm.size / 2
    /** Frames decoded so far (== frames once [complete]). */
    @Volatile var available: Int = pcm.size / 2
    @Volatile var complete: Boolean = true
    /** Set when the decoder hit its length cap before the file ended. */
    @Volatile var truncated: Boolean = false
    /** Frames that are actually decoded: [frames] once complete, [available] while streaming. */
    val decodedFrames: Int get() = if (complete) frames else minOf(available, pcm.size / 2)
    val durationSec: Double get() = decodedFrames / SR.toDouble()

    @Volatile var bpm: Float = 0f
    @Volatile var firstBeat: Double = 0.0   // frames
    @Volatile var key: Int = -1             // 0..23 : pc (0..11) + 12 if minor
    @Volatile var gain: Float = 1f          // loudness normalisation
    @Volatile var analyzed: Boolean = false
    @Volatile var rms: Float = 0f
    @Volatile var energy: Int = 0          // 1..10
    @Volatile var pattern: String = ""      // "4/4 kick", "Broken beat", "Beatless"
    @Volatile var vibe: String = ""

    /** 3 bytes per bin: low, mid, high (0..255). */
    @Volatile var wave: ByteArray? = null
    /** Overview: OV_COLS columns x 3 bands, 0..255. */
    @Volatile var overview: ByteArray? = null
    /** Display gain so every waveform fills its lane (computed lazily from [wave] / [overview]). */
    @Volatile var waveNorm: Float = 0f
    @Volatile var ovNorm: Float = 0f

    /** Frames per beat; a 120 BPM fallback keeps grid maths finite before analysis. */
    val spb: Double get() = if (bpm > 0) 60.0 / bpm * SR else SR * 0.5

    // ---------------------------------------------------------------- tempo map
    // A track either has one tempo (bpm + firstBeat) or a tempo map: markers at beat positions, each with
    // its own tempo until the next one (rekordbox "dynamic" grids). Beat index 0 is always a downbeat.
    @Volatile private var mPos: DoubleArray? = null     // marker positions (frames)
    @Volatile private var mSpb: DoubleArray? = null     // frames per beat from each marker on
    @Volatile private var mBeat: DoubleArray? = null    // beat index at each marker
    val variable: Boolean get() = mPos != null

    /** Markers as (seconds, bpm) plus the beat-in-bar (1..4) of the first marker. */
    fun setTempoMap(secs: DoubleArray, bpms: DoubleArray, firstBattito: Int) {
        val n = minOf(secs.size, bpms.size)
        val ps = ArrayList<Double>(); val ss = ArrayList<Double>(); val bs = ArrayList<Double>()
        for (i in 0 until n) {
            val sec = secs[i]; val bpm = bpms[i]
            if (!sec.isFinite() || !bpm.isFinite() || bpm <= 0.0) continue
            val p = sec * SR
            if (ps.isEmpty()) {
                ps.add(p); ss.add(60.0 / bpm * SR)
                bs.add(((firstBattito - 1).coerceIn(0, 3)).toDouble())
                continue
            }
            if (p <= ps.last()) continue
            val beats = Math.round((p - ps.last()) / ss.last())
            if (beats < 1) continue
            // fit the previous segment exactly between its two markers, so the map is continuous
            ss[ss.size - 1] = (p - ps.last()) / beats
            ps.add(p); ss.add(60.0 / bpm * SR); bs.add(bs.last() + beats)
        }
        if (ps.isEmpty()) { clearTempoMap(); return }
        mPos = ps.toDoubleArray(); mSpb = ss.toDoubleArray(); mBeat = bs.toDoubleArray()
        // main tempo = the one the track spends most time in (for library, sorting, suggestions)
        val time = HashMap<Double, Double>()
        for (i in 0 until n) {
            val bpm = bpms[i]
            if (!bpm.isFinite() || bpm <= 0.0) continue
            val start = secs[i]
            val end = if (i + 1 < n) secs[i + 1] else maxOf(start + 1.0, durationSec)
            if (end > start) time[bpm] = (time[bpm] ?: 0.0) + (end - start)
        }
        bpm = (time.maxByOrNull { it.value }?.key ?: (60.0 * SR / ss[0])).toFloat()
        firstBeat = beatPos(0.0)
    }
    fun clearTempoMap() { mPos = null; mSpb = null; mBeat = null }

    /** Move the whole grid (map or single tempo) by [frames]. */
    fun shiftGrid(frames: Double) {
        val p = mPos
        if (p != null) { mPos = DoubleArray(p.size) { p[it] + frames }; firstBeat = beatPos(0.0) } else firstBeat += frames
    }

    /** Make the beat nearest [p] beat 1 of a bar (tempo maps keep their markers). */
    fun setDownbeatAt(p: Double) {
        val b = mBeat ?: run { firstBeat = p; return }
        val k = Math.round(beatIndex(p))
        val off = Math.floorMod(k, 4L).toDouble()
        mBeat = DoubleArray(b.size) { b[it] - off }
        firstBeat = beatPos(0.0)
    }

    /** Replace a tempo map by one tempo: the local tempo at [p], keeping the beat there and its bar position. */
    fun flattenAt(p: Double) {
        if (mPos == null) return
        val k = Math.round(beatIndex(p))
        val s = spbAt(p)
        val at = beatPos(k.toDouble())
        bpm = (60.0 * SR / s).toFloat()
        // keep absolute beat numbering: beat 0 stays the track's first beat, so the grid before
        // the playhead is still drawable after a tempo edit.
        firstBeat = at - k * s
        clearTempoMap()
    }

    /** Serialised map: "sec:bpm,sec:bpm,…;battito" (null when the track has one tempo). */
    fun tempoMapString(): String? {
        val p = mPos ?: return null; val s = mSpb!!; val b = mBeat!!
        return p.indices.joinToString(",") { "${p[it] / SR}:${60.0 * SR / s[it]}" } + ";" + (Math.floorMod(b[0].toInt(), 4) + 1)
    }
    fun applyTempoMapString(str: String) {
        val (list, bat) = str.split(";").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
        val pairs = list.split(",").mapNotNull { e -> e.split(":").takeIf { it.size == 2 }?.let { it[0].toDoubleOrNull() to it[1].toDoubleOrNull() } }
            .filter { it.first != null && it.second != null && it.second!! > 0 }
        if (pairs.isEmpty()) return
        setTempoMap(DoubleArray(pairs.size) { pairs[it].first!! }, DoubleArray(pairs.size) { pairs[it].second!! }, bat)
    }

    private fun segByPos(p: Double, pos: DoubleArray): Int {
        var lo = 0; var hi = pos.size - 1
        if (p <= pos[0]) return 0
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (pos[m] <= p) lo = m else hi = m - 1 }
        return lo
    }

    /** Fractional beat index at a position (frames). */
    fun beatIndex(p: Double): Double {
        val pos = mPos
        if (pos == null) return if (bpm > 0) (p - firstBeat) / spb else 0.0
        val i = segByPos(p, pos)
        return mBeat!![i] + (p - pos[i]) / mSpb!![i]
    }

    /** Position (frames) of a (fractional) beat index. */
    fun beatPos(b: Double): Double {
        val pos = mPos
        if (pos == null) return firstBeat + b * spb
        val beat = mBeat!!
        var lo = 0; var hi = beat.size - 1
        if (b > beat[0]) while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (beat[m] <= b) lo = m else hi = m - 1 }
        return pos[lo] + (b - beat[lo]) * mSpb!![lo]
    }

    /** Frames per beat at a position. */
    fun spbAt(p: Double): Double { val pos = mPos ?: return spb; return mSpb!![segByPos(p, pos)] }
    fun bpmAt(p: Double): Float = if (bpm <= 0) 0f else (60.0 * SR / spbAt(p)).toFloat()
    /**
     * Tempo for reading: the average over the 8 beats around [p]. A dynamic grid's per-beat markers
     * wobble by a few hundredths of a BPM, which made the readout flicker; a real section change
     * still shows within a few beats.
     */
    fun shownBpm(p: Double): Double {
        if (bpm <= 0) return 0.0
        if (mPos == null) return bpm.toDouble()
        val b = beatIndex(p)
        val span = beatPos(b + 4.0) - beatPos(b - 4.0)
        return if (span > 0.0) 8.0 * 60.0 * SR / span else bpmAt(p).toDouble()
    }
    val keyName: String get() = Keys.name(key)
    val camelot: String get() = Keys.camelot(key)
}

const val OV_COLS = 900

object Keys {
    private val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val camMajor = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val camMinor = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

    fun name(k: Int) = if (k < 0) "–" else names[k % 12] + if (k >= 12) "m" else ""
    fun camelot(k: Int) = if (k < 0) "–" else if (k >= 12) "${camMinor[k - 12]}A" else "${camMajor[k]}B"
    private fun num(k: Int) = if (k >= 12) camMinor[k - 12] else camMajor[k]

    /** 0 = perfect, 1 = adjacent / relative, 2 = energy boost (+2), 9 = clash, -1 unknown. */
    fun distance(a: Int, b: Int): Int {
        if (a < 0 || b < 0) return -1
        val ma = a >= 12; val mb = b >= 12
        val na = num(a); val nb = num(b)
        val d = minOf((na - nb + 12) % 12, (nb - na + 12) % 12)
        return when {
            ma == mb && d == 0 -> 0
            ma == mb && d == 1 -> 1
            ma != mb && d == 0 -> 1
            ma == mb && d == 2 -> 2
            else -> 9
        }
    }
}

fun peakNorm(a: ByteArray): Float {
    var mx = 1
    for (b in a) { val v = b.toInt() and 255; if (v > mx) mx = v }
    return (255f / mx) * 0.97f
}
