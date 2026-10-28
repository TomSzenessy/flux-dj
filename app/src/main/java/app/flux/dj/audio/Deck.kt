package app.flux.dj.audio

import kotlin.math.*
import java.util.concurrent.atomic.AtomicLong

/** Sentinel for "no seek pending" in the atomic seek slot. */
private val NO_SEEK = java.lang.Double.doubleToRawLongBits(Double.NaN)

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
    /** Sought position as raw double bits; a single atomic get-and-set consumes it in [render]. */
    private val seekBits = AtomicLong(NO_SEEK)
    var seekReq: Double
        get() = java.lang.Double.longBitsToDouble(seekBits.get())
        set(value) { seekBits.set(java.lang.Double.doubleToRawLongBits(value)) }
    @Volatile var cue = 0.0
    @Volatile var loopOn = false
    @Volatile var loopIn = 0.0
    @Volatile var loopOut = 0.0
    @Volatile var loopBeats = 4f
    @Volatile var slipOn = false             // slip mode enabled (jog scratch / roll return to ghost position)
    @Volatile var syncOn = false
    @Volatile var syncRatio = 1.0
    /** Master tempo the sync ratio was matched against; 0 = recompute on the next block. */
    @Volatile var syncRefBpm = 0f
    /** Master playback ratio when the one-time sync match was made; -1 = not matched yet. */
    @Volatile var syncRefMaster = -1f
    @Volatile var phaseAdj = 1.0
    @Volatile var needAlign = false
    @Volatile var effRatio = 1.0             // for UI
    @Volatile var previewing = false
    @Volatile var level = 0f
    val hotCues = DoubleArray(8) { -1.0 }
    @Volatile var hotCueRev = 0              // bumps on change (UI refresh)
    @Volatile var quantize = true
    /** Set when a seek/hot-cue/loop jump is applied; sync holds its ratio briefly so the fader doesn't twitch. */
    @Volatile var lastSeekNs = 0L
    /** When the current jump/seek/play started; the eye ramps its output delay in over this period. */
    @Volatile var rampNs = 0L
    /** While >= 0, the picture holds here until the engine has moved one output delay ahead. */
    @Volatile var visualHoldPos = -1.0
    /** Set with the next seek when it should snap visually immediately (e.g. bar sync). */
    @Volatile var noRampSeek = false
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
    fun snapBeat(p: Double, step: Double = 1.0): Double {
        val t = track ?: return p
        if (t.bpm <= 0 || step <= 0.0) return p
        val b = t.beatIndex(p)
        return max(0.0, t.beatPos(Math.round(b / step) * step))
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
        playing = false; previewing = false; scratching = false; searching = false
        loopOn = false; syncOn = false; slipActive = false; slipPos = 0.0
        bend = 0f; phaseAdj = 1.0; needAlign = false; keyShift = 0
        for (i in 0 until 8) hotCues[i] = -1.0
        hotCueRev++
        cue = maxOf(0.0, t.firstBeat)
        pos = 0.0; rate = 0.0
        seekReq = cue
        resetGrains = true
        visualHoldPos = -1.0
        track = t
    }

    /** Jump to the very start of the track (keeps playing if it was playing). */
    fun toStart() { val t = track ?: return; seekReq = 0.0; loopOn = false; if (!playing) cue = t.firstBeat }

    /** Last legal frame (never negative, even for an empty/streaming track). */
    private fun endFrame(): Double = ((track?.frames ?: 1) - 1).coerceAtLeast(0).toDouble()

    /** Keep a seek inside an active loop instead of leaving it stuck beyond the out point. */
    private fun wrapToLoop(p: Double): Double {
        if (!loopOn) return p
        val end = min(loopOut, endFrame())
        val len = end - loopIn
        if (len <= 1.0) return p
        var x = p
        if (x >= end) x = loopIn + (x - loopIn) % len
        if (x < loopIn) x = loopIn + (((x - loopIn) % len) + len) % len
        return x
    }

    fun seek(p: Double) { seekReq = wrapToLoop(p).coerceIn(0.0, endFrame()); visualHoldPos = -1.0 }

    /** Apply a seek immediately (UI-originated jumps) so the eye does not wait for the audio thread. */
    fun seekNow(p: Double) {
        val at = wrapToLoop(p).coerceIn(0.0, endFrame())
        pos = at
        // Publish the same target atomically so a seek arriving during the next block is not lost.
        seekReq = at
        resetGrains = true
        rate = 0.0
        rampNs = 0L
        visualHoldPos = -1.0
        noRampSeek = false
    }
    /** Jump the playhead (tap on a waveform). An active loop keeps the jump inside the loop. */
    fun jumpTo(p: Double) {
        seek(p)
    }
    fun seekFrac(f: Float) { val t = track ?: return; seek(f.coerceIn(0f, 1f) * t.frames.toDouble()) }

    fun play() {
        if (track == null) return
        previewing = false
        playing = true
        visualHoldPos = pos
        rampNs = System.nanoTime()
        if (syncOn) needAlign = true
    }
    fun pause() { playing = false; previewing = false; visualHoldPos = -1.0 }

    /** Pause at the position the eye/hear is at, cancelling any pending written-ahead seek. */
    fun pauseAt(p: Double) {
        playing = false; previewing = false
        pos = p
        seekReq = Double.NaN
        resetGrains = true
        rampNs = 0L
        visualHoldPos = -1.0
    }
    fun togglePlay() { if (playing) pause() else play() }

    fun cueDown(at: Double = pos) {
        if (track == null) return
        if (playing && !previewing && loopOn) {
            seekReq = loopIn                 // inside a loop: CUE restarts the loop and keeps playing
        } else if (playing && !previewing) {
            playing = false; seekReq = cue
        } else if (!playing) {
            if (abs(at - cue) > 400) { cue = if (quantize) snapBeat(at) else at; seekReq = cue }
            previewing = true; previewReturn = cue; playing = true
        }
    }
    fun cueUp() {
        if (previewing) { previewing = false; playing = false; seekReq = previewReturn }
    }

    /**
     * A hot cue latches: tapping a stored cue jumps there and starts the deck, and it keeps playing
     * after the finger lifts (unlike the momentary CDJ CUE button). Tapping it again while the deck
     * runs restarts from the cue.
     */
    fun hotCueDown(i: Int, at: Double = pos) {
        if (track == null || i !in hotCues.indices) return
        val c = hotCues[i]
        if (c < 0) { hotCues[i] = if (quantize) snapBeat(at) else at; hotCueRev++; return }
        previewing = false
        seekReq = wrapToLoop(c)
        if (!playing) play()
    }
    /** Hot cues latch, so releasing the pad does not stop the deck. */
    fun hotCueUp(i: Int) {}
    fun hotCueClear(i: Int) { hotCues[i] = -1.0; hotCueRev++ }

    fun beatJump(beats: Float, from: Double = pos) { seek(beatsFrom(from, beats.toDouble())) }

    /**
     * Start a loop at the beat nearest [at] — pass the position the user actually hears/sees, not the
     * engine's look-ahead playhead. Pressing a loop pad starts the loop immediately, and a tap just
     * after a beat snaps back onto that beat instead of landing on the next one.
     */
    fun loopSet(beats: Float, at: Double = pos) {
        if (track == null) return
        loopBeats = beats
        val end = endFrame()
        val snapped = if (quantize && bpm > 0) snapBeat(at) else at
        val s = (if (snapped > end) at else snapped).coerceIn(0.0, maxOf(0.0, end - 1.0))
        loopIn = s
        loopOut = beatsFrom(loopIn, beats.toDouble()).coerceIn(loopIn + 1.0, maxOf(loopIn + 1.0, end))
        loopOn = loopOut > loopIn
        // land exactly on the grid point so the loop is tight (a few ms of correction at most)
        if (loopOn && playing && abs(s - pos) < spbHere() * 0.75) seekReq = s
    }
    fun loopToggle(at: Double = pos) { if (loopOn) loopOn = false else loopSet(loopBeats, at) }
    fun loopOff() { loopOn = false }
    fun loopScale(f: Float) {
        val nb = (loopBeats * f).coerceIn(0.125f, 64f)
        loopBeats = nb
        if (loopOn) {
            val end = endFrame()
            loopOut = beatsFrom(loopIn, nb.toDouble()).coerceIn(loopIn + 1.0, maxOf(loopIn + 1.0, end))
        }
    }
    fun rollStart(beats: Float, at: Double = pos) {
        slipActive = true; slipPos = pos
        loopSet(beats, at)
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
        rampNs = System.nanoTime()
        visualHoldPos = pos
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
        // Clamp to the array we actually hold: the decoder may publish a bigger frame count
        // before/after swapping `pcm`, and the audio thread must never index past its snapshot.
        val frames = minOf(if (complete) t.frames else t.available, pcm.size / 2)
        if (frames < 6) {
            java.util.Arrays.fill(oL, 0, n, 0f); java.util.Arrays.fill(oR, 0, n, 0f)
            rate = 0.0; level = 0f
            return
        }
        val lastIdx = frames - 3
        val req = seekBits.getAndSet(NO_SEEK)
        if (req != NO_SEEK) {
            pos = java.lang.Double.longBitsToDouble(req).coerceIn(0.0, lastIdx.toDouble())
            resetGrains = true
            rate = if (playing) rate else 0.0
            lastSeekNs = System.nanoTime()
            rampNs = if (noRampSeek) 0L else System.nanoTime()
            noRampSeek = false
        }
        if (searching) {
            java.util.Arrays.fill(oL, 0, n, 0f); java.util.Arrays.fill(oR, 0, n, 0f)
            rate = 0.0; level = 0f; resetGrains = true
            return
        }
        var posD = pos.coerceIn(0.0, lastIdx.toDouble())
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
        val lo = loopOn; val li = loopIn; val lo2 = min(loopOut, lastIdx.toDouble())
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
                if (lo && r > 0 && posD >= lo2 && !scr && lo2 > li) {
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
