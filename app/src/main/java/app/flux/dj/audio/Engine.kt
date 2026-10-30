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
/** Alignment loop: one beat per second. */
const val CALIB_BEAT = SR

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
    fun startPreview(t: Track, frac: Float) { previewTrack = t; previewSeekReq = (frac * t.decodedFrames).toDouble(); previewPlaying = true }
    fun stopPreview() { previewPlaying = false }
    fun previewSeek(frac: Float) { val t = previewTrack ?: return; previewSeekReq = (frac.coerceIn(0f, 1f) * (t.decodedFrames - 2).coerceAtLeast(1)).toDouble() }

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
    /** During auto-align: -1 = both outputs, 0 = master only, 1 = cue only. */
    @Volatile var calibOnly = -1
    /** System time of the last calibration loop start (the "ding" on beat 1), for mic measurements. */
    @Volatile var calibEpochNs = 0L
    fun calibration(on: Boolean) {
        if (on && !calib) { calibPhase = 0; calibPos = 0; calibEpochNs = System.nanoTime() }
        calib = on
        if (!on) calibOnly = -1
    }
    /** Final volume for everything that leaves the device (not the recording). */
    @Volatile var outVolume = 1f
    private var outVolS = 1f
    /** Measured delay between writing audio and hearing it on the master output (ms). */
    @Volatile var outLatencyMs = 0f
    /** Acoustic delay of the master/room output measured with the microphone (ms). */
    @Volatile var acousticMasterMs = -1f
    /** Delay the eye should use: the measured master delay plus whatever extra delay the room got. */
    val visualLatencyMs: Float get() = (if (acousticMasterMs >= 0f) acousticMasterMs else outLatencyMs) +
        (if (outMode == OutMode.SINGLE) 0 else max(0, offsetMs))

    private var written = 0L
    private val ts = AudioTimestamp()
    private var calibPhase = 0
    /** One bar of drums at 120 BPM (kick every beat, hats between, clap on 2 & 4) for alignment. */
    val calibLoop: FloatArray = run {
        // One beat per second: any output delay (< 0.5 s) is unambiguous — the nearest beat is the right one.
        // Beat 1 is a bright "ding", beats 2–4 a short low "tock", so you also hear where the bar starts.
        val beat = CALIB_BEAT; val loop = FloatArray(beat * 4)
        for (b in 0 until 4) {
            val st = b * beat
            val ding = b == 0
            val len = if (ding) SR / 3 else SR / 12
            for (k in 0 until len) {
                val t = k / SRF
                val v = if (ding) (sin(2 * PI * 1568.0 * t) * 0.6 + sin(2 * PI * 3136.0 * t) * 0.25) * exp(-t * 9.0)
                        else (sin(2 * PI * 880.0 * t) * 0.7) * exp(-t * 60.0)
                loop[st + k] = v.toFloat()
            }
        }
        loop
    }
    /** Current playhead inside [calibLoop] (frames). */
    @Volatile var calibPos = 0
    private val outM = FloatArray(BLOCK * 2)
    private val outC = FloatArray(BLOCK * 2)

    private var thread: Thread? = null
    private var cueThread: Thread? = null
    @Volatile private var trackM: AudioTrack? = null
    @Volatile private var trackC: AudioTrack? = null
    private val cueQ = ArrayBlockingQueue<FloatArray>(6)
    private val cuePool = Array(10) { FloatArray(BLOCK * 2) }   // reused: no allocation per block
    private var cuePoolIdx = 0
    /** Control-plane work (buffer resizing) never runs on the real-time thread. */
    private val control = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "flux-control").apply { isDaemon = true }
    }
    @Volatile var routedCue: String = ""
    private val xg = FloatArray(2)
    private var fxLevelS = 0f

    // sampler voices
    private class Voice {
        @Volatile var data: FloatArray? = null
        @Volatile var p = 0
        @Volatile var g = 1f
    }
    private val voices = Array(8) { Voice() }
    @Volatile private var voiceNext = 0
    fun triggerSample(i: Int) {
        val v = voices[voiceNext]; voiceNext = (voiceNext + 1) % voices.size
        v.p = 0; v.g = 0.9f; v.data = SampleBank.data[i % SampleBank.data.size]
    }

    // transition state (audio thread)
    private var tFrom = 0; private var tTo = 1
    private var tTotal = 0.0; private var tElapsed = 0.0; private var tXf0 = 0f; private var tXf1 = 1f
    private var tFromLo = 0.5f; private var tFromHi = 0.5f
    private var tToLo = 0.5f; private var tToHi = 0.5f

    /** Deck currently acting as tempo master (the one that is not following). */
    fun leader(): Int = when {
        decks[0].syncOn && !decks[1].syncOn -> 1
        decks[1].syncOn && !decks[0].syncOn -> 0
        decks[0].playing && !decks[1].playing -> 0
        decks[1].playing && !decks[0].playing -> 1
        else -> if (xfader <= 0.5f) 0 else 1
    }
    fun masterBpm(): Float = decks[leader()].let { (nominalBpm(it) * deckRatio(it)).toFloat() }

    fun toggleSync(i: Int) {
        val d = decks[i]; val o = decks[1 - i]
        if (d.syncOn) { releaseSync(d); return }
        engageSync(d, o)
    }

    /** Match [d] to [master], align the bars, then keep its beats locked (see [syncStep]). */
    private fun engageSync(d: Deck, master: Deck) {
        if (nominalBpm(d) <= 0.0 || nominalBpm(master) <= 0.0) return
        if (master.syncOn) releaseSync(master)   // the deck we follow becomes the master
        d.syncRatio = syncTargetNow(d, master)
        d.syncFold = syncFold(currentBpm(d), currentBpm(master), deckRatio(master), d.syncRatio)
        d.syncOffset = 0.0
        d.syncGrid = 0.0
        ensurePitchRange(d, d.syncRatio)
        d.syncOn = true
        d.needAlign = true
    }

    fun startTransition(beats: Float) { transCancel = false; transReq = beats }
    fun cancelTransition() { transCancel = true }

    // ------------------------------------------------------------------ lifecycle
    /** Name of the device Android is really playing the master on (may differ from the preference). */
    @Volatile var routedMaster: String = ""
    /** Bumped on every (re)open; a mixer/cue thread exits as soon as its generation is stale. */
    @Volatile private var gen = 0

    @Synchronized fun start() {
        if (running && thread?.isAlive == true) return
        running = true
        outputError = null
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
        val mixer = thread
        val cue = cueThread
        val mixerStopped = mixer == null || run { mixer.join(1500); !mixer.isAlive }
        val cueStopped = cue == null || run { cue.join(500); !cue.isAlive }
        thread = null; cueThread = null
        // Only release a track whose writer is gone; releasing under a blocked write is native UB.
        if (mixerStopped) { try { trackM?.stop(); trackM?.release() } catch (_: Exception) {}; trackM = null }
        if (cueStopped) { try { trackC?.stop(); trackC?.release() } catch (_: Exception) {}; trackC = null }
        if (!mixerStopped || !cueStopped) outputError = "An audio thread did not stop in time; it will be replaced"
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
        written = 0L; outLatencyMs = 0f
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
        val tm = trackM ?: run { running = false; return }
        var n = 0
        var lastU = 0
        try {
            while (running && gen == g) {
                render(BLOCK)
                if (outMode == OutMode.DUAL) {
                    val blk = cuePool[cuePoolIdx]; cuePoolIdx = (cuePoolIdx + 1) % cuePool.size
                    System.arraycopy(outC, 0, blk, 0, BLOCK * 2)
                    if (!cueQ.offer(blk)) { cueQ.poll(); cueQ.offer(blk) }
                }
                val wrote = tm.write(outM, 0, BLOCK * 2, AudioTrack.WRITE_BLOCKING)
                if (wrote < 0) {
                    outputError = "Audio output failed (write ${wrote})"
                    running = false
                    break
                }
                written += BLOCK
                if (++n % 64 == 0) lastU = tuneLatency(tm, lastU)
                if (n % 32 == 0) measureLatency(tm)
            }
        } catch (t: Throwable) {
            outputError = "Audio engine stopped: ${t.javaClass.simpleName}: ${t.message}"
            running = false
        }
    }

    /** How far behind the writing position the speaker is, from the device's own playback timestamp. */
    private fun measureLatency(t: AudioTrack) {
        try {
            if (!t.getTimestamp(ts)) return
            val played = ts.framePosition + (System.nanoTime() - ts.nanoTime) * SR / 1_000_000_000L
            val ms = ((written - played) * 1000f / SR).coerceIn(0f, 1000f)
            outLatencyMs = if (outLatencyMs == 0f) ms else outLatencyMs * 0.9f + ms * 0.1f
        } catch (_: Exception) {}
    }

    /**
     * Grow the playing buffer by two blocks whenever the device reported new underruns.
     * The resize is applied on the control thread; the buffer is never halved back (that
     * re-created the underrun it was reacting to), so latency stays at the last good size.
     */
    private fun tuneLatency(t: AudioTrack, lastU: Int): Int {
        val u = t.underrunCount
        if (u > lastU) {
            control.execute {
                try {
                    val cap = t.bufferCapacityInFrames
                    if (t.bufferSizeInFrames < cap) {
                        t.bufferSizeInFrames = min(cap, t.bufferSizeInFrames + BLOCK * 2)
                        outputInfo = "buffer ${t.bufferSizeInFrames * 1000 / SR} ms"
                    }
                } catch (_: Exception) {}
            }
            return u
        }
        return u
    }

    private fun xfGains(x: Float, c: Curve): FloatArray = when (c) {
        Curve.SMOOTH -> { xg[0] = cos(x * PI / 2).toFloat(); xg[1] = sin(x * PI / 2).toFloat(); xg }
        Curve.SHARP -> { xg[0] = min(1f, 2f * (1f - x)); xg[1] = min(1f, 2f * x); xg }
        Curve.CUT -> { xg[0] = if (x > 0.96f) 0f else 1f; xg[1] = if (x < 0.04f) 0f else 1f; xg }
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
                tFromLo = chans[src].lo; tFromHi = chans[src].hi
                tToLo = chans[to].lo; tToHi = chans[to].hi
                val d = decks[to]
                chans[to].lo = 0f
                if (decks[src].syncOn) releaseSync(decks[src])   // one master at a time
                if (!d.playing) {
                    engageSync(d, decks[src])
                    d.play()
                } else if (!d.syncOn) engageSync(d, decks[src])
                transitionActive = true
            }
        }
        if (transitionActive) {
            if (transCancel) {
                restoreTransitionEq()
                xfader = tXf0
                transitionActive = false; transitionProgress = 0f
            } else {
                tElapsed += n
                val p = (tElapsed / tTotal).toFloat().coerceIn(0f, 1f)
                transitionProgress = p
                xfader = tXf0 + (tXf1 - tXf0) * p
                val sw = ((p - 0.45f) / 0.1f).coerceIn(0f, 1f)
                chans[tFrom].lo = 0.5f * (1f - sw)
                chans[tTo].lo = 0.5f * sw
                chans[tFrom].hi = 0.5f - 0.2f * p
                if (p >= 1f) {
                    decks[tFrom].pause()
                    releaseSync(decks[tTo])
                    restoreTransitionEq()
                    transitionActive = false; transitionProgress = 0f
                }
            }
        }

        // ---- sync: tempo + phase
        // Exactly one deck follows. If both ended up synced (e.g. a cancelled transition), the leader
        // keeps its speed and becomes the master instead of the two re-pitching each other forever.
        if (decks[0].syncOn && decks[1].syncOn) releaseSync(decks[leader()])
        for (i in 0..1) {
            val d = decks[i]; val l = decks[1 - i]
            if (d.syncOn && nominalBpm(d) > 0.0 && nominalBpm(l) > 0.0) {
                if (d.needAlign && d.playing && l.playing) {
                    // Align the bars you actually hear/see: compare the audible (output-delayed)
                    // positions, then put the engine one delay ahead so the picture snaps immediately.
                    val delayD = visualLatencyMs / 1000.0 * SR * d.effRatio
                    val delayL = visualLatencyMs / 1000.0 * SR * l.effRatio
                    val lBeat = l.track!!.beatIndex(l.pos - delayL)
                    val dBeat = d.track!!.beatIndex(d.pos - delayD)
                    val targetBeat = lBeat + 4.0 * Math.round((dBeat - lBeat) / 4.0)
                    d.seekNow(d.track!!.beatPos(targetBeat) + delayD, keepSpeed = true)
                    d.needAlign = false
                    d.syncOffset = 0.0
                }
                syncStep(d, l, visualLatencyMs / 1000.0 * SR, n)
            } else d.phaseAdj = 1.0
        }

        // ---- decks -> channels
        val xf = xfGains(xfader, curve)
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
            val tgt = fv * fv * xf[i]
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
        // With no CUE lit the headphones carry the master mix (so you can take them off/on without
        // silence). As soon as one or both CUE buttons are on, they hear only the cued decks; the
        // CUE·M knob blends the room mix back in if you want it.
        val anyCue = chans[0].cue || chans[1].cue
        val hm = if (anyCue) hpMix else 1f
        val hl = hpLevel * 1.4f
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
            val only = calibOnly
            for (k in 0 until n) {
                if (p == 0) calibEpochNs = System.nanoTime() + (k * 1_000_000_000L / SR)
                val v = loop[p] * 0.8f
                val mv = if (only <= 0) v else 0f
                val cv = if (only != 0) v else 0f
                mL[k] = mv; mR[k] = mv; cL[k] = cv; cR[k] = cv
                p = if (p + 1 >= loop.size) 0 else p + 1
            }
            calibPhase = p
            calibPos = p
        }
        // ---- outputs
        when (outMode) {
            OutMode.SINGLE -> {
                // practice mode: with any CUE on, the single output plays the headphone mix
                // (only the cued decks, even with their faders down); the recording above keeps the master
                val monitor = !calib && (chans[0].cue || chans[1].cue)
                val l = if (monitor) cL else mL; val r = if (monitor) cR else mR
                for (k in 0 until n) { outM[k * 2] = l[k]; outM[k * 2 + 1] = r[k] }
            }
            OutMode.DUAL -> {
                masterDelay.frames = delayFrames(max(0, offsetMs))
                cueDelay.frames = delayFrames(max(0, -offsetMs))
                masterDelay.process(mL, mR, n); cueDelay.process(cL, cR, n)
                for (k in 0 until n) { outM[k * 2] = mL[k]; outM[k * 2 + 1] = mR[k]; outC[k * 2] = cL[k]; outC[k * 2 + 1] = cR[k] }
            }
            OutMode.SPLIT -> {
                // left channel = cue (headphones), right channel = master (PA/aux)
                masterDelay.frames = delayFrames(max(0, offsetMs))
                cueDelay.frames = delayFrames(max(0, -offsetMs))
                masterDelay.process(mL, mR, n); cueDelay.process(cL, cR, n)
                for (k in 0 until n) {
                    outM[k * 2] = (cL[k] + cR[k]) * 0.5f
                    outM[k * 2 + 1] = (mL[k] + mR[k]) * 0.5f
                }
            }
        }
        // ---- one volume for all outputs (ramped, so turning it never clicks)
        val v0 = outVolS; val v1 = outVolume
        if (v0 != 1f || v1 != 1f) {
            val st = (v1 - v0) / n
            var v = v0
            for (k in 0 until n) {
                v += st
                outM[k * 2] *= v; outM[k * 2 + 1] *= v
                if (outMode == OutMode.DUAL) { outC[k * 2] *= v; outC[k * 2 + 1] *= v }
            }
        }
        outVolS = v1
    }

    /** Clamp a millisecond delay to the alignment line's capacity (half a second). */
    private fun delayFrames(ms: Int): Int = (ms.toLong() * SR / 1000L).coerceIn(0L, (SR / 2 - 2).toLong()).toInt()

    /** Put the user's EQ values back after a transition finishes or is cancelled. */
    private fun restoreTransitionEq() {
        chans[tFrom].lo = tFromLo; chans[tFrom].hi = tFromHi
        chans[tTo].lo = tToLo; chans[tTo].hi = tToHi
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        return if (a < 0.8f) x else (if (x > 0) 1f else -1f) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }
}

/** Records the master mix to Music/FLUX as 16-bit WAV. */
class Recorder(private val ctx: Context) {
    @Volatile var active = false
    /** True while the writer is draining/committing after [stop]; the UI must not start a second file. */
    @Volatile var saving = false
    @Volatile var seconds = 0
    @Volatile var lastFile: String? = null
    @Volatile var error: String? = null
    /** Blocks dropped because the writer could not keep up; surfaced in the UI, never silent. */
    @Volatile var dropped = 0
    private val q = ArrayBlockingQueue<ShortArray>(400)
    private val pool = ArrayBlockingQueue<ShortArray>(16).apply { repeat(16) { put(ShortArray(BLOCK * 2)) } }
    private var thread: Thread? = null

    fun start() {
        if (active || saving) return
        active = true; saving = true; seconds = 0; lastFile = null; error = null; dropped = 0
        val name = "FLUX_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".wav"
        thread = Thread({
            var pfd: android.os.ParcelFileDescriptor? = null
            var uri: android.net.Uri? = null
            var out: FileOutputStream? = null
            var ok = false
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
                        ?: throw java.io.IOException("MediaStore refused the recording file")
                    pfd = res.openFileDescriptor(uri, "rw")
                        ?: throw java.io.IOException("Could not open the recording file")
                    out = FileOutputStream(pfd.fileDescriptor)
                } else {
                    val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "FLUX"); dir.mkdirs()
                    out = FileOutputStream(java.io.File(dir, name))
                }
                val ch = out.channel
                ch.position(0); out.write(ByteArray(44))
                var bytes = 0L
                var frames = 0L
                while (active || q.isNotEmpty()) {
                    val blk = q.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val bb = ByteBuffer.allocate(blk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(blk)
                    out.write(bb.array()); bytes += blk.size * 2; frames += blk.size / 2
                    seconds = (frames / SR).toInt()
                    pool.offer(blk)
                }
                ch.position(0)
                out.write(wavHeader(bytes))
                out.flush()
                lastFile = "Music/FLUX/$name"
                ok = bytes > 0
                if (!ok) error = "The recording was empty"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                try { out?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
                if (uri != null && Build.VERSION.SDK_INT >= 29) {
                    if (ok) {
                        val cv = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                        try { ctx.contentResolver.update(uri, cv, null, null) } catch (_: Exception) {}
                    } else {
                        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
                active = false; saving = false
            }
        }, "flux-rec").also { it.start() }
    }

    fun push(l: FloatArray, r: FloatArray, n: Int) {
        val s = pool.poll() ?: run { dropped++; return }
        try {
            for (i in 0 until n) {
                s[i * 2] = (l[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                s[i * 2 + 1] = (r[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (!q.offer(s)) { dropped++; pool.offer(s) }
        } catch (t: Throwable) {
            dropped++; pool.offer(s)
        }
    }

    /** Signal stop; the writer drains and commits in the background, then clears [saving]. */
    fun stop() { active = false }

    private fun wavHeader(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2); b.putInt(SR); b.putInt(SR * 4); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}

// ---------------------------------------------------------------------------- sync maths (pure, unit-tested)

/**
 * The tempo a deck shows and syncs against: the track's dominant BPM, never the tempo of the grid
 * segment under the playhead. Rekordbox dynamic grids change tempo every few beats; matching those
 * would make the two decks disagree and re-pitch on every SYNC press.
 */
internal fun nominalBpm(d: Deck): Double = d.track?.bpm?.toDouble() ?: 0.0

/** Ratio that makes [followerBpm] play at [masterBpm] × [masterRatio], folded into 0.67–1.5. */
internal fun syncMatchRatio(followerBpm: Double, masterBpm: Double, masterRatio: Double): Double {
    if (followerBpm <= 0.0 || masterBpm <= 0.0) return 1.0
    var r = (masterBpm * masterRatio) / followerBpm
    while (r > 1.5) r /= 2
    while (r < 0.67) r *= 2
    return r
}

/**
 * The ratio a deck will play at. Unlike [Deck.effRatio] (written by the audio thread once per block),
 * this is the value the UI just set, so sync never reads a stale speed — e.g. right after the master
 * was released and its tempo was frozen into the fader.
 */
internal fun deckRatio(d: Deck): Double = if (d.syncOn) d.syncRatio else 1.0 + d.tempo * d.range

/**
 * The tempo a deck is playing at right now: the grid segment under its playhead (loop-in while a
 * loop is active), before the pitch ratio. A rekordbox dynamic grid can hold 160 for three minutes
 * and 180 for two; the track's average is not what either deck is playing. Used for the one-time
 * match on SYNC; while synced, [syncStep] follows the grids beat by beat.
 */
internal fun currentBpm(d: Deck): Double {
    val t = d.track ?: return d.bpm.toDouble()
    if (t.bpm <= 0) return 0.0
    return t.bpmAt(if (d.loopOn) d.loopIn else d.pos).toDouble()
}

/**
 * One match, made where the master is *now*: the section under its playhead, not the track average.
 * After this the deck holds the ratio; only the master's fader is followed (see the mixer loop).
 */
internal fun syncTargetNow(f: Deck, l: Deck): Double {
    val follower = currentBpm(f).takeIf { it > 0.0 } ?: nominalBpm(f)
    val master = currentBpm(l).takeIf { it > 0.0 } ?: nominalBpm(l)
    return syncMatchRatio(follower, master, deckRatio(l))
}

/** Master beats per follower beat for a match: 1, or a power of two when tempos were folded. */
internal fun syncFold(followerBpm: Double, masterBpm: Double, masterRatio: Double, ratio: Double): Double {
    if (followerBpm <= 0.0 || masterBpm <= 0.0 || ratio <= 0.0) return 1.0
    val x = (masterBpm * masterRatio) / (followerBpm * ratio)
    return 2.0.pow(Math.round(ln(x) / ln(2.0)).toDouble())
}

/** Beat-phase error in master beats, wrapped to ±½; positive = the follower is behind. */
internal fun syncPhaseError(masterBeat: Double, followerBeat: Double, fold: Double, offset: Double): Double {
    val e = masterBeat - followerBeat * fold - offset
    return e - Math.floor(e + 0.5)
}

/** Longest a phase error takes to close (seconds), and the most sync bends the speed to do it. */
private const val SYNC_LOCK_SEC = 2.0
private const val SYNC_MAX_BEND = 0.004
/**
 * Phase error (beats) sync leaves alone: rekordbox grids place single beats a few ms off (one track's
 * markers swing 159–163 BPM beat to beat), and chasing that noise warbled the pitch. ~11 ms at 160 BPM
 * is about where the ear starts to hear a flam.
 */
private const val SYNC_DEADBAND = 0.03
/** Beats either side whose average sets the synced speed: long enough to ignore per-beat noise. */
private const val SYNC_TEMPO_HALF = 16.0
/** The synced speed glides to a new value over about this long (seconds), so it never steps. */
private const val SYNC_GLIDE_SEC = 1.0
/** A phase error this large (beats) is a jump, not drift: re-align the bars instead of bending. */
private const val SYNC_REALIGN_BEATS = 0.25

/**
 * Speed factor that closes [err] master beats (beyond [SYNC_DEADBAND]) over about [SYNC_LOCK_SEC],
 * capped at ±0.4 % (7 cents) so the correction is never audible as a pitch bend.
 */
internal fun phaseCorrection(err: Double, masterBeatsPerSec: Double): Double {
    if (masterBeatsPerSec <= 0.0) return 1.0
    val e = sign(err) * max(0.0, abs(err) - SYNC_DEADBAND)
    return 1.0 + (e / (masterBeatsPerSec * SYNC_LOCK_SEC)).coerceIn(-SYNC_MAX_BEND, SYNC_MAX_BEND)
}

/**
 * One audio block of sync for follower [d] against master [l]. The speed is the master's speed times
 * the ratio of the two grids' beat lengths (averaged over 32 beats and glided, so a dynamic grid's per-beat
 * noise does not warble the pitch) where each deck is audible: sections and the master's pitch
 * fader are followed instead of matched once. Nothing changes while either deck is stopped. A small proportional
 * bend ([Deck.phaseAdj]) then pulls the beats back onto each other, so rounding in either grid or a
 * slightly wrong BPM cannot add up to drift over a long mix. Nudging either deck moves the held
 * offset instead of being fought. While either deck loops or scratches the phase is left alone; a
 * large error after that (or after a jump on the master) re-aligns the bars once.
 * [delayFrames] is the output delay at 1× speed (the audible positions are compared, as in align).
 */
internal fun syncStep(d: Deck, l: Deck, delayFrames: Double, n: Int) {
    val dt = d.track ?: return; val lt = l.track ?: return
    if (!d.playing || !l.playing) { d.phaseAdj = 1.0; return }
    val lRatio = deckRatio(l)
    val dPos = d.pos - delayFrames * d.effRatio
    val lPos = l.pos - delayFrames * l.effRatio
    val lSpb = lt.spbAround(lPos, SYNC_TEMPO_HALF)
    // the grids' ratio glides (their noise must not step the speed); the master's fader applies at once
    val grid = dt.spbAround(dPos, SYNC_TEMPO_HALF) / lSpb
    d.syncGrid = if (d.syncGrid <= 0.0) grid else d.syncGrid + (grid - d.syncGrid) * (1.0 - exp(-n / (SR * SYNC_GLIDE_SEC)))
    d.syncRatio = (lRatio * d.syncGrid / d.syncFold).coerceIn(0.5, 2.0)
    // A loop, roll or scratch on either deck jumps its beat position on purpose: each wrap of a half-beat
    // loop looked like a big phase error, so sync seeked the follower and it looped along with the master.
    if (d.scratching || l.scratching || d.loopOn || l.loopOn || d.needAlign) { d.phaseAdj = 1.0; return }
    val err = syncPhaseError(lt.beatIndex(lPos), dt.beatIndex(dPos), d.syncFold, d.syncOffset)
    if (d.bend != 0f || l.bend != 0f) {
        // the DJ is nudging: hold whatever offset they leave, rather than pulling it back
        d.syncOffset = syncPhaseError(d.syncOffset + err, 0.0, 1.0, 0.0)
        d.phaseAdj = 1.0
        return
    }
    if (abs(err) > SYNC_REALIGN_BEATS) { d.needAlign = true; d.phaseAdj = 1.0; return }
    d.phaseAdj = phaseCorrection(err, lRatio * SR / lSpb)
}

/** Pitch ranges offered by the UI; reused by sync so it never allocates a fresh list. */
private val PITCH_RANGES = floatArrayOf(0.06f, 0.10f, 0.16f, 0.50f)

/** Widen the pitch range if the synced ratio does not fit, so SYNC off cannot snap the tempo. */
internal fun ensurePitchRange(d: Deck, ratio: Double) {
    val need = abs(ratio - 1.0)
    if (need <= d.range) return
    PITCH_RANGES.firstOrNull { it >= need }?.let { d.range = it }
}

/**
 * Turn sync off without changing the speed: freeze the matched ratio into the pitch fader so the
 * track keeps playing at exactly the tempo it had while synced.
 */
internal fun releaseSync(d: Deck) {
    if (!d.syncOn) return
    val ratio = d.syncRatio
    ensurePitchRange(d, ratio)
    d.tempo = ((ratio - 1.0) / d.range).toFloat().coerceIn(-1f, 1f)
    d.syncOn = false
    d.syncOffset = 0.0
    d.phaseAdj = 1.0
}
