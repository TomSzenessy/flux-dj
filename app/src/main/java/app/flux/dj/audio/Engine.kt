package app.flux.dj.audio

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.*

const val BLOCK = 256

enum class OutMode { SINGLE, DUAL, SPLIT }
enum class Curve { SMOOTH, SHARP, CUT }

/** Mixer channel parameters + DSP state. */
class Chan(val idx: Int) {
    @Volatile var trim = 0.5f
    @Volatile var hi = 0.5f
    @Volatile var mid = 0.5f
    @Volatile var lo = 0.5f
    @Volatile var color = 0.5f
    @Volatile var fader = 0.85f
    @Volatile var cue = false
    @Volatile var colorFx = ColorFx.FILTER
    @Volatile var meter = 0f

    val eqLo = Biquad(); val eqMid = Biquad(); val eqHi = Biquad()
    val fx = ColorFxUnit()
    var lastLo = Float.NaN; var lastMid = Float.NaN; var lastHi = Float.NaN
    var gain = 0f
}

class Engine(private val ctx: Context) {
    val decks = arrayOf(Deck(0), Deck(1))
    val chans = arrayOf(Chan(0), Chan(1))

    @Volatile var xfader = 0.5f
    @Volatile var curve = Curve.SMOOTH
    @Volatile var master = 0.8f
    @Volatile var hpMix = 0f      // 0 = headphones hear only CUE'd decks
    @Volatile var hpLevel = 0.8f
    @Volatile var masterMeterL = 0f
    @Volatile var masterMeterR = 0f
    @Volatile var clip = false

    // Beat FX
    @Volatile var fxOn = false
    @Volatile var fxType = BeatFx.ECHO
    @Volatile var fxDiv = 0.5f           // in beats
    @Volatile var fxDepth = 0.5f
    @Volatile var fxLevel = 0.5f
    @Volatile var fxA = true
    @Volatile var fxB = true
    @Volatile var fxMaster = false

    @Volatile var samplerVol = 0.8f

    // Library preview (headphones only, or master when there is no separate cue output)
    @Volatile var previewTrack: Track? = null
    @Volatile var previewPlaying = false
    @Volatile var previewPos = 0.0
    @Volatile var previewToMaster = false
    @Volatile private var previewSeekReq = -1.0
    private var previewFade = 0
    fun startPreview(t: Track, frac: Float) { previewTrack = t; previewSeekReq = (frac * t.frames).toDouble(); previewPlaying = true }
    fun stopPreview() { previewPlaying = false }
    fun previewSeek(frac: Float) { val t = previewTrack ?: return; previewSeekReq = (frac.coerceIn(0f, 1f) * (t.frames - 2)).toDouble() }

    // Output routing
    @Volatile var outMode = OutMode.SINGLE
    @Volatile var masterDeviceId = -1
    @Volatile var cueDeviceId = -1
    @Volatile var offsetMs = 0           // negative: delay cue, positive: delay master
    @Volatile var running = false
    @Volatile var outputError: String? = null
    @Volatile var outputInfo: String = ""

    // Transition assist
    @Volatile var transitionActive = false
    @Volatile var transitionProgress = 0f
    @Volatile private var transReq = 0f
    @Volatile private var transCancel = false

    val recorder = Recorder(ctx)

    private val dL = Array(2) { FloatArray(BLOCK) }
    private val dR = Array(2) { FloatArray(BLOCK) }
    private val mL = FloatArray(BLOCK); private val mR = FloatArray(BLOCK)
    private val cL = FloatArray(BLOCK); private val cR = FloatArray(BLOCK)
    private val fiL = FloatArray(BLOCK); private val fiR = FloatArray(BLOCK)
    private val foL = FloatArray(BLOCK); private val foR = FloatArray(BLOCK)
    private val bus = BeatFxBus()
    private val masterDelay = AlignDelay(SR / 2)
    private val cueDelay = AlignDelay(SR / 2)

    /** Alignment mode: music muted, both outputs click on every beat (120 BPM). */
    @Volatile var calib = false
    private var calibPhase = 0
    /** One bar of drums at 120 BPM (kick every beat, hats between, clap on 2 & 4) for alignment. */
    val calibLoop: FloatArray by lazy {
        val beat = SR / 2; val loop = FloatArray(beat * 4)
        val kick = Synth.kick(); val hat = Synth.hat(false); val clap = Synth.clap()
        fun put(s: FloatArray, at: Int, g: Float) { for (i in s.indices) { val j = at + i; if (j < loop.size) loop[j] += s[i] * g } }
        for (b in 0 until 4) { put(kick, b * beat, 0.9f); put(hat, b * beat + beat / 2, 0.45f); if (b % 2 == 1) put(clap, b * beat, 0.45f) }
        loop
    }
    /** Current playhead inside [calibLoop] (frames). */
    @Volatile var calibPos = 0
    /** Frames since the last click (for the UI pulse). */
    @Volatile var calibSinceClick = 0
    private val outM = FloatArray(BLOCK * 2)
    private val outC = FloatArray(BLOCK * 2)

    private var thread: Thread? = null
    private var cueThread: Thread? = null
    private var trackM: AudioTrack? = null
    private var trackC: AudioTrack? = null
    private val cueQ = ArrayBlockingQueue<FloatArray>(6)
    private val cuePool = Array(10) { FloatArray(BLOCK * 2) }   // reused: no allocation per block
    private var cuePoolIdx = 0
    @Volatile var routedCue: String = ""
    private var fxCross = 0f
    private var xfGainA = 1f; private var xfGainB = 0f
    private var fxLevelS = 0f

    // sampler voices
    private class Voice { var data: FloatArray? = null; var p = 0; var g = 1f }
    private val voices = Array(8) { Voice() }
    @Volatile private var voiceNext = 0
    fun triggerSample(i: Int) {
        val v = voices[voiceNext]; voiceNext = (voiceNext + 1) % voices.size
        v.p = 0; v.g = 0.9f; v.data = SampleBank.data[i % SampleBank.data.size]
    }

    // transition state (audio thread)
    private var tFrom = 0; private var tTo = 1
    private var tTotal = 0.0; private var tElapsed = 0.0; private var tXf0 = 0f; private var tXf1 = 1f

    val master_: Int get() = leader()

    /** Deck currently acting as tempo master (the one that is not following). */
    fun leader(): Int = when {
        decks[0].syncOn && !decks[1].syncOn -> 1
        decks[1].syncOn && !decks[0].syncOn -> 0
        decks[0].playing && !decks[1].playing -> 0
        decks[1].playing && !decks[0].playing -> 1
        else -> if (xfader <= 0.5f) 0 else 1
    }
    fun masterBpm(): Float = decks[leader()].currentBpm

    fun toggleSync(i: Int) {
        val d = decks[i]; val o = decks[1 - i]
        if (d.syncOn) { d.tempo = ((d.effRatio - 1.0) / d.range).toFloat().coerceIn(-1f, 1f); d.syncOn = false; return }
        if (d.bpm <= 0f || o.bpm <= 0f) return
        if (o.syncOn) { o.tempo = ((o.effRatio - 1.0) / o.range).toFloat().coerceIn(-1f, 1f); o.syncOn = false }
        d.syncRatio = syncTarget(d, o)
        d.syncOn = true
        if (d.playing && o.playing) d.needAlign = true
    }

    private fun syncTarget(f: Deck, l: Deck): Double {
        var r = l.currentBpm.toDouble() / f.bpm
        while (r > 1.5) r /= 2; while (r < 0.67) r *= 2
        return r
    }

    fun setSyncOffTempoKeep(d: Deck) { d.tempo = ((d.effRatio - 1.0) / d.range).toFloat().coerceIn(-1f, 1f) }

    fun startTransition(beats: Float) { transCancel = false; transReq = beats }
    fun cancelTransition() { transCancel = true }

    // ------------------------------------------------------------------ lifecycle
    /** Name of the device Android is really playing the master on (may differ from the preference). */
    @Volatile var routedMaster: String = ""
    /** Bumped on every (re)open; a mixer/cue thread exits as soon as its generation is stale. */
    @Volatile private var gen = 0

    @Synchronized fun start() {
        if (running) return
        running = true
        launchThreads()
    }

    @Synchronized fun stop() {
        running = false
        shutdownThreads()
        recorder.stop()
    }

    /** Switch outputs without ever having two mixer threads alive. */
    @Synchronized fun reopenOutputs() {
        if (!running) return
        shutdownThreads()
        launchThreads()
    }

    private fun launchThreads() {
        val g = ++gen
        openOutputs(g)
        thread = Thread({ loop(g) }, "flux-mixer").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    private fun shutdownThreads() {
        gen++                                   // old loops exit after their current block
        try { trackM?.pause(); trackM?.flush() } catch (_: Exception) {}   // unblocks a blocking write()
        try { trackC?.pause(); trackC?.flush() } catch (_: Exception) {}
        cueThread?.interrupt()
        thread?.join(1500); cueThread?.join(500)
        thread = null; cueThread = null
        closeOutputs()
    }

    private fun closeOutputs() {
        try { trackM?.stop(); trackM?.release() } catch (_: Exception) {}
        try { trackC?.stop(); trackC?.release() } catch (_: Exception) {}
        trackM = null; trackC = null
    }

    private fun devices(): Array<AudioDeviceInfo> =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    /**
     * Low-latency ("fast") tracks all share the device's single primary output, so two of them can never
     * reach two different devices. Use them only for a single non-Bluetooth output.
     */
    private fun isBluetooth(d: AudioDeviceInfo?) = d != null && (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)))

    /**
     * Android only routes two streams to two devices if they sit on *different* outputs. Low-latency
     * ("fast") tracks always share the primary output (speaker / wired / USB side); normal tracks to
     * Bluetooth get the Bluetooth output. So: Bluetooth → normal, everything else → fast, and in
     * "two devices" mode at most one fast track (the master) so master and cue never share an output.
     */
    private fun wantsFast(devId: Int, isCue: Boolean): Boolean {
        val all = devices()
        val d = all.firstOrNull { it.id == devId }
        if (isBluetooth(d)) return false
        if (devId < 0) return outMode != OutMode.DUAL      // system default: whatever Android picks
        if (outMode == OutMode.DUAL && isCue) {
            val m = all.firstOrNull { it.id == masterDeviceId }
            return isBluetooth(m)                           // cue may be fast only if the master is on Bluetooth
        }
        return true
    }

    private fun makeTrack(devId: Int, isCue: Boolean = false, onRoute: ((String) -> Unit)? = null): AudioTrack? {
        return try {
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val fast = wantsFast(devId, isCue)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(if (fast) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
                .setBufferSizeInBytes(max(min, 8192 * 2 * 4))   // capacity; the playing size adapts (see tuneLatency)
                .build()
            if (devId >= 0) devices().firstOrNull { it.id == devId }?.let { d -> t.setPreferredDevice(d) }
            if (Build.VERSION.SDK_INT >= 26) {
                val minFrames = min / 8
                t.bufferSizeInFrames = max(minFrames, if (fast) BLOCK * 4 else BLOCK * 8)
            }
            if (onRoute != null) {
                t.addOnRoutingChangedListener({ r -> onRoute(describe((r as AudioTrack).routedDevice)) }, null)
            }
            t.play()
            onRoute?.invoke(describe(t.routedDevice))
            t
        } catch (e: Exception) { outputError = e.message; null }
    }

    /**
     * Output test: play [beeps] short beeps on one device through its own track (independent of the mixer),
     * and report which device Android really routed it to.
     */
    fun playTest(devId: Int, beeps: Int, freq: Double, onRouted: (String) -> Unit) {
        Thread({
            var t: AudioTrack? = null
            try {
                // Same stream setup as the real outputs (fast for speaker/wired/USB, normal for Bluetooth),
                // otherwise Android may park the test on a different output than the music would use.
                val d = devices().firstOrNull { it.id == devId }
                val fast = !isBluetooth(d)
                val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(if (fast) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
                    .setBufferSizeInBytes(max(min, SR * 4))
                    .build()
                if (d != null) t.setPreferredDevice(d)
                val beep = (SR * 0.16).toInt(); val gap = (SR * 0.22).toInt(); val lead = (SR * 0.4).toInt()
                val total = lead + beeps * (beep + gap)
                val pcm = FloatArray(total * 2)
                for (b in 0 until beeps) {
                    val st = lead + b * (beep + gap)
                    for (k in 0 until beep) {
                        val env = minOf(1.0, k / 300.0, (beep - k) / 300.0)
                        val v = (kotlin.math.sin(2 * PI * freq * k / SR) * env * 0.5).toFloat()
                        pcm[(st + k) * 2] = v; pcm[(st + k) * 2 + 1] = v
                    }
                }
                t.play()
                t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                onRouted(describe(t.routedDevice).ifEmpty { "unknown" })   // read after audio is flowing
                Thread.sleep(400)
            } catch (e: Exception) {
                onRouted("error: ${e.message}")
            } finally {
                try { t?.stop(); t?.release() } catch (_: Exception) {}
            }
        }, "flux-test").start()
    }

    private fun describe(d: AudioDeviceInfo?): String = when {
        d == null -> ""
        d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth · ${d.productName}"
        d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        else -> d.productName?.toString() ?: "Output"
    }

    private fun openOutputs(g: Int) {
        outputError = null
        routedMaster = ""
        routedCue = ""
        trackM = makeTrack(masterDeviceId) { routedMaster = it }
        if (outMode == OutMode.DUAL) {
            trackC = makeTrack(cueDeviceId, isCue = true) { routedCue = it }
            cueQ.clear()
            val tc = trackC
            if (tc != null) {
                cueThread = Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        var n = 0
                        var lastU = 0
                        while (running && gen == g) {
                            val blk = cueQ.take()
                            tc.write(blk, 0, blk.size, AudioTrack.WRITE_BLOCKING)
                            if (++n % 64 == 0) lastU = tuneLatency(tc, lastU)
                        }
                    } catch (_: InterruptedException) {}
                }, "flux-cue").also { it.start() }
            }
        }
        val lat = trackM?.let { if (Build.VERSION.SDK_INT >= 26) it.bufferSizeInFrames * 1000 / SR else 0 } ?: 0
        outputInfo = "buffer ${lat}ms"
    }

    // ------------------------------------------------------------------ render loop
    private fun loop(g: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var n = 0
        var lastU = 0
        while (running && gen == g) {
            render(BLOCK)
            val tm = trackM ?: break
            if (outMode == OutMode.DUAL) {
                val blk = cuePool[cuePoolIdx]; cuePoolIdx = (cuePoolIdx + 1) % cuePool.size
                System.arraycopy(outC, 0, blk, 0, BLOCK * 2)
                if (!cueQ.offer(blk)) { cueQ.poll(); cueQ.offer(blk) }
            }
            tm.write(outM, 0, BLOCK * 2, AudioTrack.WRITE_BLOCKING)
            if (++n % 64 == 0) lastU = tuneLatency(tm, lastU)
        }
    }

    /** Grow the playing buffer by two blocks whenever the device reported new underruns (glitches). */
    private fun tuneLatency(t: AudioTrack, lastU: Int): Int {
        if (Build.VERSION.SDK_INT < 26) return lastU
        val u = t.underrunCount
        if (u > lastU) {
            val cap = t.bufferCapacityInFrames
            if (t.bufferSizeInFrames < cap) t.bufferSizeInFrames = min(cap, t.bufferSizeInFrames + BLOCK * 2)
            outputInfo = "buffer ${t.bufferSizeInFrames * 1000 / SR} ms"
        }
        return u
    }

    private fun xfGains(x: Float, c: Curve): Pair<Float, Float> = when (c) {
        Curve.SMOOTH -> Pair(cos(x * PI / 2).toFloat(), sin(x * PI / 2).toFloat())
        Curve.SHARP -> Pair(min(1f, 2f * (1f - x)), min(1f, 2f * x))
        Curve.CUT -> Pair(if (x > 0.96f) 0f else 1f, if (x < 0.04f) 0f else 1f)
    }

    private fun eqDb(v: Float): Double = if (v < 0.5f) -60.0 * (1.0 - 2.0 * v).pow(1.8) else (v - 0.5) * 12.0

    private fun render(n: Int) {
        // ---- transition assist
        val req = transReq
        if (req > 0f) {
            transReq = 0f
            val from = if (xfader <= 0.5f) 0 else 1
            val src = if (decks[0].playing && !decks[1].playing) 0 else if (decks[1].playing && !decks[0].playing) 1 else from
            val to = 1 - src
            if (decks[to].track != null && decks[src].track != null) {
                tFrom = src; tTo = to
                val bpm = max(60f, decks[src].currentBpm)
                tTotal = req * 60.0 / bpm * SR; tElapsed = 0.0
                tXf0 = if (src == 0) 0f else 1f; tXf1 = 1f - tXf0
                xfader = tXf0
                val d = decks[to]
                chans[to].lo = 0f
                if (!d.playing) {
                    if (d.bpm > 0 && decks[src].bpm > 0) { d.syncRatio = syncTarget(d, decks[src]); d.syncOn = true; d.needAlign = true }
                    d.play()
                } else if (!d.syncOn && d.bpm > 0 && decks[src].bpm > 0) { d.syncRatio = syncTarget(d, decks[src]); d.syncOn = true; d.needAlign = true }
                transitionActive = true
            }
        }
        if (transitionActive) {
            if (transCancel) { transitionActive = false }
            else {
                tElapsed += n
                val p = (tElapsed / tTotal).toFloat().coerceIn(0f, 1f)
                transitionProgress = p
                xfader = tXf0 + (tXf1 - tXf0) * p
                val sw = ((p - 0.45f) / 0.1f).coerceIn(0f, 1f)
                chans[tFrom].lo = 0.5f * (1f - sw)
                chans[tTo].lo = 0.5f * sw
                chans[tFrom].hi = 0.5f - 0.2f * p
                if (p >= 1f) {
                    decks[tFrom].pause(); decks[tTo].syncOn = false
                    decks[tTo].tempo = ((decks[tTo].effRatio - 1.0) / decks[tTo].range).toFloat().coerceIn(-1f, 1f)
                    chans[tFrom].lo = 0.5f; chans[tFrom].hi = 0.5f
                    transitionActive = false; transitionProgress = 0f
                }
            }
        }

        // ---- sync: tempo + phase
        for (i in 0..1) {
            val d = decks[i]; val l = decks[1 - i]
            if (d.syncOn && d.bpm > 0 && l.bpm > 0) {
                d.syncRatio = syncTarget(d, l)
                if (d.needAlign && d.playing && l.playing) {
                    var diff = d.phase() - l.phase(); diff -= Math.round(diff).toDouble()
                    d.seekReq = d.pos - diff * d.track!!.spb
                    d.needAlign = false
                } else if (d.playing && l.playing && !d.scratching) {
                    var diff = d.phase() - l.phase(); diff -= Math.round(diff).toDouble()
                    d.phaseAdj = 1.0 - diff.coerceIn(-0.1, 0.1) * 0.25
                } else d.phaseAdj = 1.0
            } else d.phaseAdj = 1.0
        }

        // ---- decks -> channels
        val (gA0, gB0) = xfGains(xfader, curve)
        val xg = floatArrayOf(gA0, gB0)
        java.util.Arrays.fill(mL, 0, n, 0f); java.util.Arrays.fill(mR, 0, n, 0f)
        java.util.Arrays.fill(cL, 0, n, 0f); java.util.Arrays.fill(cR, 0, n, 0f)
        java.util.Arrays.fill(fiL, 0, n, 0f); java.util.Arrays.fill(fiR, 0, n, 0f)
        val mbpm = max(60f, masterBpm().let { if (it <= 0f) 120f else it })
        val beatFrames = SR * 60f / mbpm
        for (i in 0..1) {
            val d = decks[i]; val c = chans[i]
            val l = dL[i]; val r = dR[i]
            d.render(n, l, r)
            val tg = d.track?.gain ?: 1f
            val g = tg * 10.0.pow((c.trim - 0.5) * 24.0 / 20.0).toFloat()
            for (k in 0 until n) { l[k] *= g; r[k] *= g }
            if (c.lo != c.lastLo) { c.lastLo = c.lo; val db = eqDb(c.lo); if (abs(db) < 0.05) c.eqLo.bypass = true else c.eqLo.lowShelf(250.0, db) }
            if (c.mid != c.lastMid) { c.lastMid = c.mid; val db = eqDb(c.mid); if (abs(db) < 0.05) c.eqMid.bypass = true else c.eqMid.peak(1100.0, 0.7, db) }
            if (c.hi != c.lastHi) { c.lastHi = c.hi; val db = eqDb(c.hi); if (abs(db) < 0.05) c.eqHi.bypass = true else c.eqHi.highShelf(3500.0, db) }
            c.eqLo.process(l, r, n); c.eqMid.process(l, r, n); c.eqHi.process(l, r, n)
            c.fx.process(c.colorFx, c.color, d.currentBpm.takeIf { it > 0 } ?: mbpm, l, r, n)
            if (c.cue) for (k in 0 until n) { cL[k] += l[k]; cR[k] += r[k] }
            val fv = c.fader
            val tgt = fv * fv * (if (fv > 0.98f) 1f else 1f) * xg[i]
            val g0 = c.gain; val step = (tgt - g0) / n
            var gg = g0; var pk = 0f
            val asg = if (i == 0) fxA else fxB
            for (k in 0 until n) {
                gg += step
                val a = l[k] * gg; val b = r[k] * gg
                l[k] = a; r[k] = b
                mL[k] += a; mR[k] += b
                if (asg) { fiL[k] += a; fiR[k] += b }
                val m = max(abs(a), abs(b)); if (m > pk) pk = m
            }
            c.gain = tgt
            c.meter = max(pk, c.meter * 0.92f)
        }

        // ---- sampler into master
        for (v in voices) {
            val dd = v.data ?: continue
            val m = min(n, dd.size - v.p)
            for (k in 0 until m) { val s = dd[v.p + k] * v.g * samplerVol; mL[k] += s; mR[k] += s }
            v.p += m
            if (v.p >= dd.size) v.data = null
        }

        // ---- beat FX
        if (fxOn || fxLevelS > 0.002f) {
            val target = if (fxOn) fxLevel else 0f
            if (fxMaster) for (k in 0 until n) { fiL[k] += mL[k]; fiR[k] += mR[k] }
            val type = fxType
            bus.process(type, fiL, fiR, foL, foR, n, beatFrames, fxDiv, fxDepth)
            val s0 = fxLevelS; val st = (target - s0) / n
            var s = s0
            for (k in 0 until n) {
                s += st
                if (type.insert) { mL[k] += s * (foL[k] - fiL[k]); mR[k] += s * (foR[k] - fiR[k]) }
                else { mL[k] += s * foL[k] * 1.3f; mR[k] += s * foR[k] * 1.3f }
            }
            fxLevelS = s
        }

        // ---- master
        val mg = master * 1.25f
        var pl = 0f; var pr = 0f
        for (k in 0 until n) {
            mL[k] = soft(mL[k] * mg); mR[k] = soft(mR[k] * mg)
            val a = abs(mL[k]); val b = abs(mR[k]); if (a > pl) pl = a; if (b > pr) pr = b
        }
        masterMeterL = max(pl, masterMeterL * 0.92f); masterMeterR = max(pr, masterMeterR * 0.92f)
        if (pl > 0.98f || pr > 0.98f) clip = true
        if (recorder.active) recorder.push(mL, mR, n)

        // ---- headphones
        val hm = hpMix; val hl = hpLevel * 1.4f
        val monitorOnly = outMode == OutMode.SPLIT
        for (k in 0 until n) {
            val a = cL[k] * (1f - hm) + mL[k] * hm
            val b = cR[k] * (1f - hm) + mR[k] * hm
            cL[k] = soft(a * hl); cR[k] = soft(b * hl)
        }
        // ---- library preview
        val pt = previewTrack
        if (pt != null && previewPlaying) {
            if (previewSeekReq >= 0) { previewPos = previewSeekReq; previewSeekReq = -1.0; previewFade = 0 }
            val pcm = pt.pcm; val done = pt.complete
            val fr = (if (done) pt.frames else minOf(pt.available, pcm.size / 2)) - 1
            var p = previewPos.toInt()
            val g = pt.gain * hpLevel * 1.2f / 32768f
            for (k in 0 until n) {
                if (p >= fr) { if (done) previewPlaying = false; break }
                val fade = if (previewFade < 512) previewFade++ / 512f else 1f
                val l = pcm[p * 2] * g * fade; val r = pcm[p * 2 + 1] * g * fade
                if (previewToMaster) { mL[k] = soft(mL[k] + l); mR[k] = soft(mR[k] + r) } else { cL[k] = soft(cL[k] + l); cR[k] = soft(cR[k] + r) }
                p++
            }
            previewPos = p.toDouble()
        }
        // ---- alignment loop (replaces the music on both outputs, before the alignment delays)
        if (calib) {
            val loop = calibLoop
            var p = calibPhase
            for (k in 0 until n) {
                val v = loop[p] * 0.8f
                mL[k] = v; mR[k] = v; cL[k] = v; cR[k] = v
                p = if (p + 1 >= loop.size) 0 else p + 1
            }
            calibPhase = p
            calibPos = p
            calibSinceClick = p % (SR / 2)
        }
        // ---- outputs
        when (outMode) {
            OutMode.SINGLE -> {
                for (k in 0 until n) { outM[k * 2] = mL[k]; outM[k * 2 + 1] = mR[k] }
            }
            OutMode.DUAL -> {
                masterDelay.frames = max(0, offsetMs) * SR / 1000
                cueDelay.frames = max(0, -offsetMs) * SR / 1000
                masterDelay.process(mL, mR, n); cueDelay.process(cL, cR, n)
                for (k in 0 until n) { outM[k * 2] = mL[k]; outM[k * 2 + 1] = mR[k]; outC[k * 2] = cL[k]; outC[k * 2 + 1] = cR[k] }
            }
            OutMode.SPLIT -> {
                // left channel = cue (headphones), right channel = master (PA/aux)
                masterDelay.frames = max(0, offsetMs) * SR / 1000
                cueDelay.frames = max(0, -offsetMs) * SR / 1000
                masterDelay.process(mL, mR, n); cueDelay.process(cL, cR, n)
                for (k in 0 until n) {
                    outM[k * 2] = (cL[k] + cR[k]) * 0.5f
                    outM[k * 2 + 1] = (mL[k] + mR[k]) * 0.5f
                }
            }
        }
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        return if (a < 0.8f) x else (if (x > 0) 1f else -1f) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }
}

/** Records the master mix to Music/FLUX as 16-bit WAV. */
class Recorder(private val ctx: Context) {
    @Volatile var active = false
    @Volatile var seconds = 0
    @Volatile var lastFile: String? = null
    private val q = ArrayBlockingQueue<ShortArray>(400)
    private var thread: Thread? = null

    fun start() {
        if (active) return
        active = true; seconds = 0
        val name = "FLUX_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".wav"
        thread = Thread({
            var pfd: android.os.ParcelFileDescriptor? = null
            var uri: android.net.Uri? = null
            var out: FileOutputStream? = null
            try {
                val res = ctx.contentResolver
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/FLUX")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    uri = res.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
                    pfd = res.openFileDescriptor(uri!!, "rw")
                    out = FileOutputStream(pfd!!.fileDescriptor)
                } else {
                    val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "FLUX"); dir.mkdirs()
                    out = FileOutputStream(java.io.File(dir, name))
                }
                val ch = out!!.channel
                ch.position(0); out.write(ByteArray(44))
                var bytes = 0L
                val bb = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)
                var frames = 0L
                while (active || q.isNotEmpty()) {
                    val blk = q.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val bb2 = ByteBuffer.allocate(blk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bb2.asShortBuffer().put(blk)
                    out.write(bb2.array()); bytes += blk.size * 2; frames += blk.size / 2
                    seconds = (frames / SR).toInt()
                }
                ch.position(0)
                out.write(wavHeader(bytes))
                out.flush()
                lastFile = "Music/FLUX/$name"
            } catch (_: Exception) {
            } finally {
                try { out?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
                if (uri != null && Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    try { ctx.contentResolver.update(uri, cv, null, null) } catch (_: Exception) {}
                }
            }
        }, "flux-rec").also { it.start() }
    }

    fun push(l: FloatArray, r: FloatArray, n: Int) {
        val s = ShortArray(n * 2)
        for (i in 0 until n) {
            s[i * 2] = (l[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            s[i * 2 + 1] = (r[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        q.offer(s)
    }

    fun stop() { active = false; thread?.join(1500); thread = null }

    private fun wavHeader(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2); b.putInt(SR); b.putInt(SR * 4); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
