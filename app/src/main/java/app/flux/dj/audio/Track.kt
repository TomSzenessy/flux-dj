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
    val durationSec: Double get() = frames / SR.toDouble()

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

    val spb: Double get() = if (bpm > 0) 60.0 / bpm * SR else 0.0
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
