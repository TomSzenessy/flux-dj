package app.flux.dj.audio

import kotlin.math.*

/**
 * One virtual turntable/CDJ. All fields touched by the UI thread are @Volatile; structural changes
 * (seeks) go through [seekReq] and are applied by the audio thread at the start of each block.
 */
class Deck(val index: Int) {
    @Volatile var track: Track? = null
    @Volatile var pos = 0.0
    @Volatile var playing = false
    @Volatile var tempo = 0f                 // fader -1..1 (times range)
    @Volatile var range = 0.08f
    @Volatile var keyLock = false
    @Volatile var keyShift = 0
    @Volatile var scratching = false
    @Volatile var scratchTarget = 0.0
    @Volatile var bend = 0f
    @Volatile var seekReq = Double.NaN
    @Volatile var cue = 0.0
    @Volatile var loopOn = false
    @Volatile var loopIn = 0.0
    @Volatile var loopOut = 0.0
    @Volatile var loopBeats = 4f
    @Volatile var slipOn = false             // slip mode enabled (jog scratch / roll return to ghost position)
    @Volatile var syncOn = false
    @Volatile var syncRatio = 1.0
    @Volatile var phaseAdj = 1.0
    @Volatile var needAlign = false
    @Volatile var effRatio = 1.0             // for UI
    @Volatile var previewing = false
    @Volatile var level = 0f
    val hotCues = DoubleArray(8) { -1.0 }
    @Volatile var hotCueRev = 0              // bumps on change (UI refresh)
    @Volatile var quantize = true
    /** Silent needle search: output is muted and the playhead only moves by seeks. */
    @Volatile var searching = false

    private var slipPos = 0.0
    private var slipActive = false
    private var previewReturn = 0.0
    private var rate = 0.0
    private var wasScratch = false

    // granular engine
    private val G = 2048
    private val H = G / 2
    private val win = FloatArray(G) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / G)).toFloat() }
    private val gStart = DoubleArray(2)
    private val gIdx = intArrayOf(-1, -1)
    private var hop = 0
    private var nextSlot = 1
    private var resetGrains = true
    private var grainMode = false

    // ------------------------------------------------------------- helpers (UI thread)
    /** Tempo at the playhead (follows tempo maps). */
    val bpm: Float get() = track?.let { it.bpmAt(pos) } ?: 0f
    val currentBpm: Float get() = bpm * effRatio.toFloat()
    fun spbHere(): Double { val t = track; return if (t != null && t.bpm > 0) t.spbAt(pos) else SR * 0.5 }
    fun snapBeat(p: Double): Double {
        val t = track ?: return p
        if (t.bpm <= 0) return p
        return max(0.0, t.beatPos(Math.round(t.beatIndex(p)).toDouble()))
    }
    fun phase(p: Double = pos): Double {
        val t = track ?: return 0.0
        if (t.bpm <= 0) return 0.0
        val x = t.beatIndex(p)
        return x - floor(x)
    }
    /** Position [beats] beats away from [p], following the tempo map. */
    fun beatsFrom(p: Double, beats: Double): Double {
        val t = track ?: return p
        if (t.bpm <= 0) return p + beats * SR * 0.5
        return t.beatPos(t.beatIndex(p) + beats)
    }

    fun load(t: Track) {
        playing = false; previewing = false; scratching = false; loopOn = false; syncOn = false
        track = t
        for (i in 0 until 8) hotCues[i] = -1.0
        hotCueRev++
        cue = t.firstBeat
        pos = 0.0; rate = 0.0
        seekReq = t.firstBeat
        resetGrains = true
    }

    /** Jump to the very start of the track (keeps playing if it was playing). */
    fun toStart() { val t = track ?: return; seekReq = 0.0; loopOn = false; if (!playing) cue = t.firstBeat }

    fun seek(p: Double) { seekReq = p.coerceIn(0.0, (track?.frames ?: 1).toDouble() - 2) }
    /** Jump the playhead (tap on a waveform). Leaving an active loop switches the loop off. */
    fun jumpTo(p: Double) {
        if (loopOn && (p < loopIn || p >= loopOut)) loopOn = false
        seek(p)
    }
    fun seekFrac(f: Float) { val t = track ?: return; seek(f.coerceIn(0f, 1f) * t.frames.toDouble()) }

    fun play() {
        if (track == null) return
        previewing = false
        playing = true
        if (syncOn) needAlign = true
    }
    fun pause() { playing = false; previewing = false }
    fun togglePlay() { if (playing) pause() else play() }

    fun cueDown() {
        if (track == null) return
        if (playing && !previewing && loopOn) {
            seekReq = loopIn                 // inside a loop: CUE restarts the loop and keeps playing
        } else if (playing && !previewing) {
            playing = false; seekReq = cue
        } else if (!playing) {
            if (abs(pos - cue) > 400) { cue = if (quantize) snapBeat(pos) else pos; seekReq = cue }
            previewing = true; previewReturn = cue; playing = true
        }
    }
    fun cueUp() {
        if (previewing) { previewing = false; playing = false; seekReq = previewReturn }
    }

    fun hotCueDown(i: Int) {
        if (track == null) return
        val c = hotCues[i]
        if (c < 0) { hotCues[i] = if (quantize) snapBeat(pos) else pos; hotCueRev++; return }
        seekReq = c
        if (!playing) { previewing = true; previewReturn = c; playing = true }
    }
    fun hotCueUp(i: Int) { if (previewing) { previewing = false; playing = false; seekReq = previewReturn } }
    fun hotCueClear(i: Int) { hotCues[i] = -1.0; hotCueRev++ }

    fun beatJump(beats: Float) { seek(beatsFrom(pos, beats.toDouble())) }

    fun loopSet(beats: Float) {
        if (track == null) return
        loopBeats = beats
        val s = if (quantize && bpm > 0) snapBeat(pos).let { if (it > pos + spbHere() * 0.5) beatsFrom(it, -1.0) else it } else pos
        loopIn = s; loopOut = beatsFrom(s, beats.toDouble())
        loopOn = true
    }
    fun loopToggle() { if (loopOn) loopOn = false else loopSet(loopBeats) }
    fun loopOff() { loopOn = false }
    fun loopScale(f: Float) {
        val nb = (loopBeats * f).coerceIn(0.125f, 64f)
        loopBeats = nb
        if (loopOn) loopOut = beatsFrom(loopIn, nb.toDouble())
    }
    fun rollStart(beats: Float) {
        slipActive = true; slipPos = pos
        loopSet(beats)
    }
    fun rollEnd() {
        if (!slipActive) return
        slipActive = false; loopOn = false
        seekReq = slipPos.coerceIn(0.0, (track?.frames ?: 1).toDouble() - 2)
    }

    fun scratchBegin() { scratchTarget = pos; scratching = true; if (slipOn && !slipActive) { slipActive = true; slipPos = pos } }
    fun scratchTo(p: Double) { scratchTarget = p.coerceIn(0.0, (track?.frames ?: 1).toDouble() - 2) }
    fun scratchEnd() {
        scratching = false
        if (slipOn && slipActive && playing) { seekReq = slipPos.coerceIn(0.0, (track?.frames ?: 1).toDouble() - 2) }
        if (slipOn) slipActive = false
    }

    fun setTempoFader(v: Float) { tempo = v.coerceIn(-1f, 1f); syncOn = false }
    fun ratioBase(): Double = if (syncOn && bpm > 0) syncRatio else (1.0 + tempo * range)

    // ------------------------------------------------------------- audio thread
    fun render(n: Int, oL: FloatArray, oR: FloatArray) {
        val t = track
        if (t == null) { java.util.Arrays.fill(oL, 0, n, 0f); java.util.Arrays.fill(oR, 0, n, 0f); return }
        val pcm = t.pcm
        val complete = t.complete
        val frames = if (complete) t.frames else minOf(t.available, pcm.size / 2)
        val lastIdx = maxOf(2, frames - 3)
        if (!seekReq.isNaN()) { pos = seekReq; seekReq = Double.NaN; resetGrains = true; rate = if (playing) rate else 0.0 }
        if (searching) {
            java.util.Arrays.fill(oL, 0, n, 0f); java.util.Arrays.fill(oR, 0, n, 0f)
            rate = 0.0; level = 0f; resetGrains = true
            return
        }
        var posD = pos
        val base = ratioBase()
        effRatio = base
        val target = base * (1.0 + bend) * phaseAdj
        val scr = scratching
        val shift = keyShift
        val wantGrain = playing && !scr && ((keyLock && abs(base - 1.0) > 0.002) || shift != 0)
        if (wantGrain != grainMode) { grainMode = wantGrain; resetGrains = true }
        val pitchF = (if (keyLock) 1.0 else base) * 2.0.pow(shift / 12.0)
        var r = rate
        var lvl = 0f
        val lo = loopOn; val li = loopIn; val lo2 = loopOut
        for (i in 0 until n) {
            val desired: Double; val coef: Double
            if (scr) { desired = ((scratchTarget - posD) / 1400.0).coerceIn(-14.0, 14.0); coef = 0.06 }
            else if (playing) { desired = target; coef = 0.004 }
            else { desired = 0.0; coef = 0.0035 }
            r += (desired - r) * coef
            var l = 0f; var rr = 0f
            if (abs(r) > 1e-4 || scr) {
                if (grainMode && !scr) {
                    if (resetGrains) {
                        gStart[0] = posD - H * pitchF; gIdx[0] = H; gIdx[1] = -1; hop = 0; nextSlot = 1; resetGrains = false
                    }
                    if (hop >= H) {
                        hop = 0
                        val s = nextSlot; nextSlot = 1 - nextSlot
                        gStart[s] = posD + r * H - H * pitchF
                        gIdx[s] = 0
                    }
                    hop++
                    for (g in 0..1) {
                        val gi = gIdx[g]
                        if (gi >= 0) {
                            val w = win[gi]
                            val sp = gStart[g] + gi * pitchF
                            val ip = sp.toInt()
                            if (sp >= 0 && ip < lastIdx) {
                                val f = (sp - ip).toFloat()
                                val a = ip * 2
                                l += w * (pcm[a] + (pcm[a + 2] - pcm[a]) * f)
                                rr += w * (pcm[a + 1] + (pcm[a + 3] - pcm[a + 1]) * f)
                            }
                            gIdx[g] = if (gi + 1 >= G) -1 else gi + 1
                        }
                    }
                } else {
                    val ip = floor(posD).toInt()
                    if (ip in 1 until lastIdx) {
                        val f = (posD - ip).toFloat()
                        val a = ip * 2
                        l = hermite(pcm[a - 2].toFloat(), pcm[a].toFloat(), pcm[a + 2].toFloat(), pcm[a + 4].toFloat(), f)
                        rr = hermite(pcm[a - 1].toFloat(), pcm[a + 1].toFloat(), pcm[a + 3].toFloat(), pcm[a + 5].toFloat(), f)
                    }
                    if (!scr) resetGrains = true
                }
                posD += r
                if (slipActive && playing) slipPos += base
                if (lo && r > 0 && posD >= lo2 && !scr) {
                    posD -= (lo2 - li)
                    resetGrains = true
                }
                if (posD < 0) { posD = 0.0; r = 0.0 }
                if (posD >= lastIdx) {
                    posD = lastIdx.toDouble()
                    if (complete) { r = 0.0; playing = false; previewing = false }  // else: wait for the decoder to catch up
                }
            }
            oL[i] = l * (1f / 32768f); oR[i] = rr * (1f / 32768f)
            val a = abs(l); if (a > lvl) lvl = a
        }
        // slip ghost advances with real time while looping / scratching
        wasScratch = scr
        rate = r
        pos = posD
        level = lvl * (1f / 32768f)
    }

    private fun hermite(xm1: Float, x0: Float, x1: Float, x2: Float, t: Float): Float {
        val c = (x1 - xm1) * 0.5f
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) * 0.5f
        val b = w + a
        return ((a * t - b) * t + c) * t + x0
    }
}
