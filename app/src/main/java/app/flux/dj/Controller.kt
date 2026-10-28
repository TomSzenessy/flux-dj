package app.flux.dj

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flux.dj.audio.*
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt


/** [key] (type + address) is stable across reconnects, unlike Android's device [id]. */
class DeviceOpt(val id: Int, val name: String, val kind: String, val key: String)

class Controller(app: Application) : AndroidViewModel(app) {
    internal val ctx: Context = app
    val engine = Engine(app)
    internal val sp = app.getSharedPreferences("flux", Context.MODE_PRIVATE)
    internal val cache = app.getSharedPreferences("flux_analysis", Context.MODE_PRIVATE)

    val tick = mutableLongStateOf(0L)
    var zoomSec by mutableFloatStateOf(8f)
    var showRemain by mutableStateOf(true)
    /** Dragging the waveform scratches (audible) instead of a silent search. */
    var waveScratch by mutableStateOf(sp.getBoolean("wave_scratch", false))
    fun setWaveScratchPref(on: Boolean) { waveScratch = on; sp.edit().putBoolean("wave_scratch", on).apply() }
    var phaseDiff by mutableStateOf<Float?>(null)
    var pendingLoad by mutableStateOf<Pair<Int, LibItem>?>(null)
    var sampleNames by mutableStateOf(SampleBank.names.toList())
    var sampleEdit by mutableStateOf<Int?>(null)
    internal val lastCueRev = intArrayOf(-1, -1)
    internal val lastCue = doubleArrayOf(-1.0, -1.0)
    internal val restoring = booleanArrayOf(false, false)
    internal val loadGen = java.util.concurrent.atomic.AtomicIntegerArray(2)
    internal val activeLoads = java.util.concurrent.atomic.AtomicInteger(0)  // indexer yields while > 0
    // preview
    var previewId by mutableStateOf<String?>(null)
    var previewLoading by mutableStateOf(false)
    var previewFrac by mutableFloatStateOf(0f)
    var previewTime by mutableStateOf("")
    @Volatile internal var previewCache: Track? = null
    val ui = arrayOf(DeckUi(), DeckUi())
    val ch = arrayOf(ChanUi(), ChanUi())
    val view = ViewPrefs(sp)

    var xfader by mutableFloatStateOf(0.5f)
    var curve by mutableStateOf(Curve.SMOOTH)
    var master by mutableFloatStateOf(0.8f)
    var hpMix by mutableFloatStateOf(0f)
    var hpLevel by mutableFloatStateOf(0.8f)
    var masterBpm by mutableStateOf("–")
    var leader by mutableIntStateOf(0)
    var recording by mutableStateOf(false)
    var recSeconds by mutableIntStateOf(0)
    var recSaving by mutableStateOf(false)
    var fxOn by mutableStateOf(false)
    var fxType by mutableStateOf(BeatFx.ECHO)
    var fxDiv by mutableFloatStateOf(0.5f)
    var fxDepth by mutableFloatStateOf(0.5f)
    var fxLevel by mutableFloatStateOf(0.5f)
    var fxA by mutableStateOf(true)
    var fxB by mutableStateOf(true)
    var fxMaster by mutableStateOf(false)
    var colorFx by mutableStateOf(ColorFx.FILTER)
    var transitionBeats by mutableIntStateOf(16)
    var transitionActive by mutableStateOf(false)
    var transitionProgress by mutableFloatStateOf(0f)
    var quantize by mutableStateOf(true)
    var samplerVol by mutableFloatStateOf(0.8f)
    var clip by mutableStateOf(false)

    // outputs
    var outMode by mutableStateOf(OutMode.SINGLE)
    var masterDev by mutableStateOf("")   // device key, "" = system default
    var cueDev by mutableStateOf("")
    var routedTo by mutableStateOf("")     // where Android actually sends the master
    var routedCue by mutableStateOf("")
    var offsetMs by mutableIntStateOf(0)
    var devices by mutableStateOf<List<DeviceOpt>>(emptyList())
    var outputLabel by mutableStateOf("Speaker")

    // library
    val demos = DemoTracks.specs.mapIndexed { i, s -> LibItem(s.id, s.title, s.artist, 0, null, i, s.bpm, s.rootPc + if (s.minor) 12 else 0, sourceKind = "demo") }
    var deviceTracks by mutableStateOf<List<LibItem>>(emptyList())
    var imported by mutableStateOf<List<LibItem>>(emptyList())
    var libPermission by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    // cloud sources
    internal val plexClient by lazy {
        val id = sp.getString("plex_client_id", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("plex_client_id", it).apply() }
        PlexClient(id)
    }
    var plexToken by mutableStateOf(sp.getString("plex_token", "")?.takeIf { it != "null" } ?: "")
    var plexServer by mutableStateOf(sp.getString("plex_server", "") ?: "")
    var plexServerName by mutableStateOf(sp.getString("plex_server_name", "") ?: "")
    var plexItems by mutableStateOf<List<LibItem>>(emptyList())
    var plexBusy by mutableStateOf(false)
    var plexPinCode by mutableStateOf<String?>(null)
    var plexPinExpiresAt by mutableLongStateOf(0L)
    var plexStatus by mutableStateOf("")
    var plexError by mutableStateOf<String?>(null)
    var plexCollectionOnly by mutableStateOf(sp.getBoolean("plex_collection_only", false))
    internal var plexAllCache: List<LibItem> = emptyList()
    internal var plexPoll: Job? = null
    var bulkDownloading by mutableStateOf(false)
    var bulkDone by mutableIntStateOf(0)
    var bulkTotal by mutableIntStateOf(0)
    var bulkFailed by mutableIntStateOf(0)
    internal var bulkJob: Job? = null
    internal val plexFile by lazy { java.io.File(ctx.filesDir, "plex.tsv") }

    var cloudFolder by mutableStateOf(sp.getString("cloud_tree", "") ?: "")
    var cloudFolderName by mutableStateOf(sp.getString("cloud_tree_name", "") ?: "")
    var cloudScanning by mutableStateOf(false)
    var rbSource by mutableStateOf(sp.getString("rb_source", "") ?: "")

    // library intelligence
    var libRev by mutableIntStateOf(0)
    var indexTotal by mutableIntStateOf(0)
    var indexDone by mutableIntStateOf(0)
    var indexSkipped by mutableIntStateOf(0)
    var indexPaused by mutableStateOf(false)
    var nextSuggestion by mutableStateOf<LibItem?>(null)
    var nextReason by mutableStateOf("")
    var filterKey by mutableIntStateOf(0)       // 0 any, 1 compatible
    var filterBpm by mutableIntStateOf(0)       // 0 any, else ± percent
    var filterVibe by mutableStateOf<String?>(null)
    var filterEnergy by mutableIntStateOf(0)    // 0 any, 1 low, 2 mid, 3 high
    /** null = all sources, "local", "plex" or "drive". */
    var filterSource by mutableStateOf<String?>(null)
    var sortMode by mutableIntStateOf(0)        // 0 recommended, 1 newest, 2 A–Z, 3 artist, 4 BPM, 5 key, 6 energy
    var hidden by mutableStateOf(sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet())
    var showHidden by mutableStateOf(false)
    var pendingRemove by mutableStateOf<LibItem?>(null)
    var history by mutableStateOf<List<HistEntry>>(emptyList())
    internal val onAirMs = longArrayOf(0, 0)
    internal val loggedLoad = intArrayOf(-1, -1)   // load generation already logged per deck
    internal var lastPollNs = 0L
    internal var indexJob: Job? = null
    internal val libraryGen = java.util.concurrent.atomic.AtomicInteger(0)
    internal val scanGen = java.util.concurrent.atomic.AtomicInteger(0)
    internal val plexGen = java.util.concurrent.atomic.AtomicInteger(0)
    internal val indexDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "flux-index").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()

    internal val demoCache = HashMap<Int, Track>()
    internal val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastTenths = longArrayOf(-1, -1)
    private var lastRemain = intArrayOf(-1, -1)
    private val lastRatio = DoubleArray(2) { Double.NaN }
    private val lastShownBpm = FloatArray(2) { Float.NaN }
    private val lastBarText = arrayOfNulls<String>(2)
    private var lastMasterText = ""
    internal var nextJob: Job? = null

    /** True while something visible is moving; an idle screen must not burn a frame every 13 ms. */
    fun needsPolling(): Boolean {
        if (engine.decks.any { it.playing || it.previewing || it.scratching || it.searching }) return true
        if (engine.previewPlaying || engine.transitionActive) return true
        if (engine.recorder.active || engine.recorder.saving) return true
        if (aligning || pendingLoad != null || engine.calib) return true
        return ui.any { it.analysing || it.loading >= 0f }
    }

    // ------------------------------------------------------------------ per-frame
    fun poll() {
        tick.longValue = tick.longValue + 1
        trackOnAir()
        for (i in 0..1) {
            val d = engine.decks[i]; val u = ui[i]; val c = engine.chans[i]; val cu = ch[i]
            u.playing = d.playing && !d.previewing || (d.previewing && d.playing)
            u.synced = d.syncOn
            u.keyLock = d.keyLock
            u.slip = d.slipOn
            u.loopOn = d.loopOn
            u.loopBeats = d.loopBeats
            u.range = d.range
            u.keyShift = d.keyShift
            val t = d.track
            if (t != null) {
                val ratio = d.effRatio
                u.tempo = ((ratio - 1.0) / d.range).toFloat().coerceIn(-1f, 1f)
                u.hasBpm = t.bpm > 0
                // tempo at the playhead (follows tempo maps); "ORIG" = the track's own tempo there
                val here = t.bpmAt(vpos(i))
                // A dynamic/rekordbox grid can change BPM slightly every beat. Show the track's
                // stable (dominant) tempo for the big number so the master deck does not appear to
                // drift while the other deck is synced; the grid still follows the map internally.
                val shownBpm = if (t.variable) t.bpm else here
                if (ratio != lastRatio[i] || shownBpm != lastShownBpm[i] || u.analysing) {
                    lastRatio[i] = ratio; lastShownBpm[i] = shownBpm
                    u.bpm = if (t.bpm > 0) String.format("%.1f", shownBpm * ratio) else if (u.analysing) "···" else "–"
                    u.origBpm = if (t.bpm > 0) String.format("%.1f", shownBpm) else ""
                    u.pct = if (t.bpm > 0) String.format("%+.1f%%", (ratio - 1.0) * 100) else ""
                }
                if (d.syncOn && tick.longValue % 75L == 0L) {
                    android.util.Log.i("FLUX-SYNC", "deck ${deckName(i)} ‘${t.title}’ sync=${d.syncOn} ratio=${d.effRatio} " +
                        "syncRatio=${d.syncRatio} refMaster=${d.syncRefMaster} refBpm=${d.syncRefBpm} tempo=${d.tempo} range=${d.range} " +
                        "trackBpm=${t.bpm} here=${here} phase=${d.phase()} pos=${d.pos.toLong()} variable=${t.variable}")
                }
                val vp = vpos(i)
                val sec = vp / SR
                val tenths = (sec * 10).toLong()
                if (tenths != lastTenths[i]) {
                    lastTenths[i] = tenths
                    u.time = fmtTime(sec, true)
                }
                val rem = (t.durationSec - sec).toInt()
                if (rem != lastRemain[i]) { lastRemain[i] = rem; u.remain = "-" + fmtTime(rem.toDouble(), false) }
                var cuesChanged = false
                if (d.hotCueRev != lastCueRev[i]) {
                    lastCueRev[i] = d.hotCueRev
                    cuesChanged = true
                    val cs = List(8) { d.hotCues[it] >= 0 }
                    if (cs != u.cues) u.cues = cs
                }
                if (t.bpm > 0) {
                    val bi = Math.floor(t.beatIndex(vp)).toLong()
                    if (bi >= 0) {
                        val bar = bi / 4 + 1
                        u.beat = (bi % 4).toInt() + 1
                        val toPhrase = (16 - ((bar - 1) % 16)).toInt()
                        val text = "BAR $bar · $toPhrase to phrase"
                        if (lastBarText[i] != text) { lastBarText[i] = text; u.barText = text }
                        u.phraseSoon = toPhrase <= 2
                    } else if (lastBarText[i] != "") { lastBarText[i] = ""; u.beat = 0; u.barText = "" }
                }
                // persist cues whenever they change (lastCueRev was updated above)
                if (!restoring[i] && u.loaded && (cuesChanged || d.cue != lastCue[i])) {
                    lastCue[i] = d.cue
                    saveCues(t.id, d)
                }
            }
            cu.trim = c.trim; cu.hi = c.hi; cu.mid = c.mid; cu.lo = c.lo; cu.color = c.color; cu.fader = c.fader; cu.cue = c.cue
        }
        xfader = engine.xfader
        routedTo = engine.routedMaster
        routedCue = engine.routedCue
        latencyMs = engine.visualLatencyMs.toInt()
        if (previewId != null && !previewLoading) {
            val pt = engine.previewTrack
            if (pt != null) {
                previewFrac = (engine.previewPos / pt.decodedFrames.coerceAtLeast(1)).toFloat()
                val sec = (engine.previewPos / SR).toInt()
                previewTime = String.format("%d:%02d / %d:%02d", sec / 60, sec % 60, pt.durationSec.toInt() / 60, pt.durationSec.toInt() % 60)
            }
            if (!engine.previewPlaying) previewId = null
            else if (outMode == OutMode.SINGLE && engine.decks.any { it.playing }) { stopPreview(); toast = "Preview stopped — it would have played through the speakers" }
        }
        val a = engine.decks[0]; val b = engine.decks[1]
        phaseDiff = if (a.playing && b.playing && a.bpm > 0 && b.bpm > 0) {
            var df = b.phase() - a.phase(); df -= Math.round(df).toDouble(); df.toFloat()
        } else null
        if (tick.longValue % 150L == 0L) {
            // The buffer can shrink after startup; an acoustic number measured against a much
            // larger buffer is stale and would hold the picture far too far back.
            if (timingMeasured && engine.outLatencyMs > 20f && acousticBufferMs >= 0f && abs(engine.outLatencyMs - acousticBufferMs) > 80f) {
                engine.acousticMasterMs = -1f; timingMeasured = false
                sp.edit().remove("acoustic_master_ms").remove("acoustic_key").remove("acoustic_buffer_ms").apply()
                toast = "Output buffer changed — timing re-measured from the device estimate"
            }
            android.util.Log.i("FLUX-STATE",
                "mode=$outMode offset=${offsetMs}ms trim=${visualTrimMs}ms latency=${engine.visualLatencyMs.toInt()}ms out=${engine.outLatencyMs.toInt()}ms " +
                "A[playing=${a.playing} pos=${a.pos.toLong()} vpos=${vpos(0).toLong()} sync=${a.syncOn} ratio=${a.effRatio} tempo=${a.tempo}] " +
                "B[playing=${b.playing} pos=${b.pos.toLong()} vpos=${vpos(1).toLong()} sync=${b.syncOn} ratio=${b.effRatio} tempo=${b.tempo}] " +
                "phase=${phaseDiff}")
        }
        engine.chans[0].let { }
        transitionActive = engine.transitionActive
        transitionProgress = engine.transitionProgress
        leader = engine.leader()
        val mb = engine.masterBpm()
        val masterText = if (mb > 0) String.format("%.1f", mb) else "–"
        if (masterText != lastMasterText) { lastMasterText = masterText; masterBpm = masterText }
        val wasSaving = recSaving
        recording = engine.recorder.active
        recSaving = engine.recorder.saving
        recSeconds = engine.recorder.seconds
        if (wasSaving && !recSaving) {
            val f = engine.recorder.lastFile
            val err = engine.recorder.error
            val drops = engine.recorder.dropped
            toast = when {
                f != null && drops > 0 -> "Saved $f · $drops block(s) dropped"
                f != null -> "Saved $f"
                else -> "Recording failed${if (err != null) ": $err" else ""}"
            }
        }
        if (engine.clip) { clip = true; engine.clip = false } else if (clip && tick.longValue % 90L == 0L) clip = false
        updateMatch()
        if (tick.longValue % 75L == 0L) scheduleNext()
    }

    private fun updateMatch() {
        for (i in 0..1) {
            val a = engine.decks[i].track; val b = engine.decks[1 - i].track
            val u = ui[i]
            if (a == null || b == null || a.bpm <= 0 || b.bpm <= 0) { u.match = ""; continue }
            val kd = Keys.distance(a.key, b.key)
            val diff = (a.bpm / b.bpm - 1f) * 100f
            val kTxt = when (kd) { 0 -> "perfect key"; 1 -> "harmonic"; 2 -> "energy lift"; 9 -> "key clash"; else -> "" }
            u.matchGood = kd in 0..2
            u.match = if (i == 1 || true) kTxt else ""
        }
    }

    private fun fmtTime(s: Double, tenth: Boolean): String {
        val m = (s / 60).toInt(); val sec = s - m * 60
        return if (tenth) String.format("%02d:%04.1f", m, sec) else String.format("%02d:%02d", m, sec.toInt())
    }

    // ------------------------------------------------------------------ deck actions
    fun deck(i: Int) = engine.decks[i]
    fun setRange(i: Int, r: Float) {
        val d = deck(i); val ratio = d.effRatio
        d.range = r
        if (d.syncOn) return
        d.tempo = ((ratio - 1.0) / r).toFloat().coerceIn(-1f, 1f)
    }
    fun cycleRange(i: Int) {
        val rs = floatArrayOf(0.06f, 0.10f, 0.16f, 0.50f)
        val k = rs.indexOfFirst { abs(it - deck(i).range) < 0.001f }
        setRange(i, rs[(k + 1) % rs.size])
    }
    fun toggleSync(i: Int) = engine.toggleSync(i)
    fun toggleKeyLock(i: Int) {
        val on = !deck(i).keyLock; deck(i).keyLock = on
        toast = if (on) "Pitch lock ON — changing the tempo keeps the song's key (no chipmunk voices)" else "Pitch lock OFF — tempo changes the pitch, like a turntable"
    }
    fun toggleSlip(i: Int) {
        val on = !deck(i).slipOn; deck(i).slipOn = on
        toast = if (on) "Slip ON — scratch, loop or roll freely: the track keeps running underneath and resumes where it would have been" else "Slip OFF"
    }
    fun pitch(i: Int, v: Float) {
        val d = deck(i)
        d.setTempoFader(v)
        val other = deck(1 - i)
        if (other.syncOn) other.needAlign = true
    }
    fun toggleQuantize() {
        quantize = !quantize; engine.decks.forEach { it.quantize = quantize }
        toast = if (quantize) "Snap ON — cues, loops and jumps land exactly on the beat" else "Snap OFF — cues and loops land exactly where you tap"
    }

    // ---------------------------------------------------------- performance inputs
    /**
     * The playhead the user is reacting to right now. The engine is already ahead of the speaker by
     * the output/acoustic delay, so pads and loops must quantise the sound the user hears (and the
     * waveform they see), not the engine's look-ahead position.
     */
    fun touchPos(i: Int): Double = vpos(i)
    fun cuePad(i: Int) = deck(i).cueDown(touchPos(i))
    fun hotCuePad(i: Int, k: Int) {
        val d = deck(i)
        d.hotCueDown(k, touchPos(i))
        val c = d.hotCues[k]
        if (d.playing && c >= 0) { d.seekNow(c + outputDelayFrames(i)); resyncIfNeeded(i) }
    }
    fun loopPad(i: Int, beats: Float) {
        val d = deck(i)
        if (d.loopOn && abs(d.loopBeats - beats) < 0.001f) d.loopOff()
        else {
            d.loopSet(beats, touchPos(i))
            if (d.playing) d.seekNow(d.loopIn + outputDelayFrames(i))
        }
    }
    fun loopTogglePad(i: Int) {
        val d = deck(i)
        d.loopToggle(touchPos(i))
        if (d.playing && d.loopOn) d.seekNow(d.loopIn + outputDelayFrames(i))
    }
    fun rollPad(i: Int, beats: Float) = deck(i).rollStart(beats, touchPos(i))
    fun beatJumpPad(i: Int, beats: Float) {
        val d = deck(i)
        val from = touchPos(i)
        val target = d.beatsFrom(from, beats.toDouble())
        android.util.Log.i("FLUX-JUMP", "beats=$beats from=${from.toLong()} target=${target.toLong()} delta=${(target-from).toLong()} pos=${d.pos.toLong()}")
        jumpAudible(i, target)
    }

    fun setXf(v: Float) { engine.xfader = v; xfader = v }
    fun cancelTransition() { if (engine.transitionActive) engine.cancelTransition() }
    fun toggleTransition() {
        if (engine.transitionActive) engine.cancelTransition() else engine.startTransition(transitionBeats.toFloat())
    }
    fun cycleTransitionBeats() { transitionBeats = when (transitionBeats) { 8 -> 16; 16 -> 32; else -> 8 } }

    fun setFader(i: Int, v: Float) { engine.chans[i].fader = v; ch[i].fader = v }
    fun setTrim(i: Int, v: Float) { engine.chans[i].trim = v; ch[i].trim = v }
    fun setHi(i: Int, v: Float) { engine.chans[i].hi = v; ch[i].hi = v }
    fun setMid(i: Int, v: Float) { engine.chans[i].mid = v; ch[i].mid = v }
    fun setLo(i: Int, v: Float) { engine.chans[i].lo = v; ch[i].lo = v }
    fun setColor(i: Int, v: Float) { engine.chans[i].color = v; ch[i].color = v }
    fun toggleCue(i: Int) { val c = engine.chans[i]; c.cue = !c.cue; ch[i].cue = c.cue }
    fun doMaster(v: Float) { engine.master = v; master = v }
    fun doHpMix(v: Float) { engine.hpMix = v; hpMix = v }
    fun doHpLevel(v: Float) { engine.hpLevel = v; hpLevel = v }
    fun doCurve(c: Curve) { engine.curve = c; curve = c }
    fun doColorFx(f: ColorFx) { colorFx = f; engine.chans.forEach { it.colorFx = f } }

    fun doFxOn(b: Boolean) { fxOn = b; engine.fxOn = b }
    fun doFxType(t: BeatFx) { fxType = t; engine.fxType = t }
    fun doFxDiv(d: Float) { fxDiv = d; engine.fxDiv = d }
    fun doFxDepth(d: Float) { fxDepth = d; engine.fxDepth = d }
    fun doFxLevel(d: Float) { fxLevel = d; engine.fxLevel = d }
    fun setFxAssign(a: Boolean, b: Boolean, m: Boolean) { fxA = a; fxB = b; fxMaster = m; engine.fxA = a; engine.fxB = b; engine.fxMaster = m }
    fun doSamplerVol(v: Float) { samplerVol = v; engine.samplerVol = v }

    fun toggleRecord() {
        val r = engine.recorder
        if (r.active) { r.stop(); toast = "Saving the recording…" }
    }

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refreshDevices() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // The list can lag the callback by a moment; check again once Android has settled.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshDevices() }, 300)
        }
    }

    /** "Two devices" needs two *different*, explicit devices: room → speaker/USB/aux, ears → headphones. */
    /**
     * Android can only play two devices at once if they sit on different outputs: Bluetooth on one side,
     * the tablet's own outputs (speaker, USB-C, wired) on the other. Two Bluetooth devices, or speaker +
     * USB-C, share one output and always end up on the same device.
     */
    fun isBt(d: DeviceOpt) = d.kind.startsWith("Bluetooth")
    fun canPair(a: DeviceOpt, b: DeviceOpt) = a.key != b.key && isBt(a) != isBt(b)
    val dualPossible: Boolean get() = devices.any { isBt(it) } && devices.any { !isBt(it) }
    val splitPossible: Boolean get() = devices.any { it.kind == "USB" || it.kind == "Wired headphones" || it.kind == "Line out / aux" }
    /** Cue devices that can actually play alongside the chosen master. */
    fun cueOptions(): List<DeviceOpt> { val m = devices.firstOrNull { it.key == masterDev } ?: return emptyList(); return devices.filter { canPair(m, it) } }
    fun doMasterDev(key: String) { masterDev = key; if (outMode == OutMode.DUAL) ensureDualDevices(); applyOutputConfig(true) }
    fun doCueDev(key: String) { cueDev = key; applyOutputConfig(true) }
    /** Extra fine-tune on top of the measured output delay (ms). */
    var visualTrimMs by mutableIntStateOf(sp.getInt("visual_trim", 0))
    internal var trimSave: Job? = null
    var latencyMs by mutableIntStateOf(0)
    var outVolume by mutableFloatStateOf(sp.getFloat("out_volume", 1f))
    internal var volumeSave: Job? = null
    /** Manual/automatic timing for every output: room, headphones and the picture. */
    var aligning by mutableStateOf(false)
    internal var alignGen = 0
    internal var bakOffset = 0
    internal var bakTrim = 0
    internal var bakAcoustic = -1f
    internal var bakMeasured = false
    internal var bakKey: String? = null
    internal var bakValid = false

    // --- automatic calibration with the tablet microphone -------------------------------
    /**
     * 0 = idle/prompt, 1 = master prompt, 2 = measuring master, 3 = cue prompt,
     * 4 = measuring cue, 5 = done.
     */
    var autoStep by mutableIntStateOf(0)
    var autoMsg by mutableStateOf("")
    var autoProgress by mutableFloatStateOf(0f)
    var autoMasterMs by mutableFloatStateOf(-1f)
    var autoCueMs by mutableFloatStateOf(-1f)
    var measuringMic by mutableStateOf(false)
    var micGranted by mutableStateOf(MicAlign.hasPermission(app))
    /** Set by MainActivity so the align sheet can ask for RECORD_AUDIO. */
    var requestMicPermission: (() -> Unit)? = null
    /** Set by MainActivity; requested only when the Outputs sheet needs device names. */
    var requestBtPermission: (() -> Unit)? = null

    fun finishMasterOnly() { autoCueMs = -1f; doneAutoAlign() }

    internal var offsetSave: Job? = null

    /** key -> "N beeps → routed to X" from the last output test. */
    var testResults by mutableStateOf<Map<String, String>>(emptyMap())
    var timingMeasured by mutableStateOf(false)
    internal var acousticBufferMs = -1f
    fun confirmLoad() { pendingLoad?.let { (d, it) -> pendingLoad = null; load(d, it, force = true); toast = "“${it.title}” → Deck ${deckName(d)}" } }
    fun cancelLoad() { pendingLoad = null }

    internal val demoLocks = Array(DemoTracks.specs.size) { Any() }

    internal val pcmDir by lazy { java.io.File(ctx.cacheDir, "pcm").apply { mkdirs() } }
    internal val remoteDir by lazy { java.io.File(ctx.cacheDir, "remote").apply { mkdirs() } }
    var pcmCacheMb by mutableIntStateOf(sp.getInt("pcm_cache_mb", 3072))
    var remoteCacheMb by mutableIntStateOf(sp.getInt("remote_cache_mb", 2048))
    internal val pcmCap get() = pcmCacheMb.toLong() * 1024 * 1024
    internal val remoteCap get() = remoteCacheMb.toLong() * 1024 * 1024

    internal val cachedMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    internal var cachedMemoRev = -1

    fun cancelPlexDownload() { bulkJob?.cancel(); bulkJob = null; bulkDownloading = false; toast = "Plex download cancelled" }
    fun seekPreview(f: Float) = engine.previewSeek(f)
    fun previewTrack(): Track? = engine.previewTrack

    // ------------------------------------------------------------------ set history
    internal val historyFile by lazy { java.io.File(ctx.filesDir, "history.tsv") }

    var confirmClearHistory by mutableStateOf(false)

    fun toggleIndexPause() { indexPaused = !indexPaused }

    fun relevance(it: LibItem, r: RefMeta): Float? = relevanceOf(it, r)

    fun reasons(it: LibItem, r: RefMeta): String = reasonsFor(it, r)
    val hiddenCount: Int get() = allItems().count { it.id in hidden }

    fun askRemove(item: LibItem) { pendingRemove = item }
    fun cancelRemove() { pendingRemove = null }

    fun loadNext() { nextSuggestion?.let { load(idleDeck(), it) } }

    /** Number of active filters (for the collapsed "Filters" button). */
    val activeFilters: Int get() = listOf(filterKey != 0, filterBpm != 0, filterEnergy != 0, filterVibe != null, filterSource != null).count { it }

    /** Where a track comes from, for the source filter. */
    fun sourceOf(it: LibItem): String = it.sourceGroup()

    val hasLocal: Boolean get() = demos.isNotEmpty() || imported.any { sourceOf(it) == "local" } || deviceTracks.isNotEmpty()
    val hasDrive: Boolean get() = imported.any { it.sourceKind == "drive" }
    val hasPlex: Boolean get() = plexItems.isNotEmpty()

    val vibesPresent: List<String> get() = visibleItems().map { it.vibe }.filter { it.isNotEmpty() && it != "-" }.distinct().sorted()

    fun addFolder(tree: Uri) { scanFolder(tree, cloud = false, announce = true) }

    fun deckName(i: Int) = if (i == 0) "A" else "B"

    /** app.plex.tv sign-in URL that links the current PIN in one tap. */
    fun plexAuthUrl(): String? = plexPinCode?.let { plexClient.authUrl(it) }

    // ------------------------------------------------------------------ rekordbox XML
    var rb by mutableStateOf<RbLibrary?>(null)
    /** rekordbox TrackID → FLUX library id */
    var rbMatch by mutableStateOf<Map<String, String>>(emptyMap())
    var playlist by mutableStateOf<RbPlaylist?>(null)
    var rbReport by mutableStateOf<RbReport?>(null)
    internal val rbFile by lazy { java.io.File(ctx.filesDir, "rekordbox.xml") }
    internal var rbImportTime: Long = sp.getLong("rb_import_time", 0L)

    fun selectPlaylist(p: RbPlaylist?) { playlist = p; sortMode = if (p != null) 7 else 0; libRev++ }
    fun playlistOnTablet(p: RbPlaylist): Int = p.trackIds.count { rbMatch[it]?.let { id -> id !in hidden } == true }

    fun dropUri(deck: Int, uri: Uri) { loadUri(deck, uri); if (pendingLoad == null) toast = "Loading → Deck ${deckName(deck)}" }

    // ------------------------------------------------------------------ editable sampler pads
    internal val sampleDir by lazy { java.io.File(ctx.filesDir, "samples").apply { mkdirs() } }
    internal val gridOrig = arrayOfNulls<GridSnap>(2)
    internal val gridAnchor = doubleArrayOf(-1.0, -1.0)
    internal val taps = arrayOf(ArrayList<Long>(), ArrayList<Long>())

    fun gridShiftMs(i: Int, ms: Float) = gridShiftFrames(i, ms / 1000.0 * SR)

    /** The pinned beat STRETCH pivots around: the last SET 1 HERE, otherwise the track's first beat. */
    fun gridAnchorFrames(i: Int): Double = gridTrack(i)?.let { if (gridAnchor[i] >= 0) gridAnchor[i] else it.firstBeat } ?: 0.0

    fun gridDrag(i: Int, dx: Double) { if (ui[i].gridMode == 1) gridStretchFrames(i, dx) else gridShiftFrames(i, dx) }

    fun gridBpmDelta(i: Int, d: Float) { val t = gridTrack(i) ?: return; flattenForEdit(i, t); setBpmAnchored(i, ((t.bpm + d) * 100f).roundToInt() / 100f) }
    fun gridScale(i: Int, f: Float) { val t = gridTrack(i) ?: return; flattenForEdit(i, t); if (t.bpm > 0) setBpmAnchored(i, t.bpm * f) }
    fun gridRound(i: Int) { val t = gridTrack(i) ?: return; flattenForEdit(i, t); if (t.bpm > 0) setBpmAnchored(i, t.bpm.roundToInt().toFloat()) }

    // Startup runs last so every property above is initialised before background work touches it.
    init {
        engine.outVolume = outVolume
        // The old 4 fps waveform renderer ran ~230 ms behind; a negative visual trim was the
        // workaround. The cached-window renderer removes that lag, so drop the legacy trim once.
        if (!sp.getBoolean("timing_v2", false)) {
            visualTrimMs = 0
            sp.edit().putInt("visual_trim", 0).putBoolean("timing_v2", true).apply()
        }
        outMode = OutMode.entries[sp.getInt("out_mode", 0).coerceIn(0, 2)]
        offsetMs = sp.getInt("out_offset", 0)
        if (!sp.getBoolean("timing_v3", false)) {
            // Cap legacy alignment offsets (the old renderer made users push them to 250–450 ms)
            // and drop the old acoustic measurement made against the oversized buffer.
            if (abs(offsetMs) > 120) setOffset(offsetMs.coerceIn(-120, 120))
            sp.edit().remove("acoustic_master_ms").remove("acoustic_key").remove("acoustic_buffer_ms")
                .putBoolean("timing_v3", true).apply()
            engine.acousticMasterMs = -1f; timingMeasured = false
        }
        if (!sp.getBoolean("timing_v4", false)) {
            if (offsetMs > 0) setOffset(0)
            else if (offsetMs < -120) setOffset(-120)
            sp.edit().putBoolean("timing_v4", true).apply()
        }
        masterDev = sp.getString("dev_master_key", "") ?: ""; cueDev = sp.getString("dev_cue_key", "") ?: ""
        refreshDevices()
        // saved device ids may be stale after a reboot: drop them when no longer present
        if (outMode == OutMode.DUAL) { ensureDualDevices(); if (cueDev.isEmpty()) outMode = OutMode.SINGLE }
        applyOutputConfig(restart = false)
        try { am.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper())) } catch (_: Exception) {}
        engine.start()
        // reopen whatever was on each deck last time; demos only as a fallback
        for (i in 0..1) viewModelScope.launch(Dispatchers.Default) {
            val last = lastLoaded(i)
            if (last != null) loadItem(i, last, auto = true)
            if (engine.decks[i].track == null) loadItem(i, demos[i], auto = true)
        }
        loadImports()
        loadHistory()
        loadRekordbox()
        viewModelScope.launch(Dispatchers.IO) {
            loadPlex()
            withContext(Dispatchers.Main) { if (plexItems.isNotEmpty()) { libRev++; rbRematch(); startIndexing() } }
            if (plexToken.isNotBlank() && plexItems.isEmpty()) withContext(Dispatchers.Main) { plexRefresh() }
        }
        cloudFolder.takeIf { it.isNotEmpty() }?.let { scanFolder(Uri.parse(it), cloud = true, announce = false) }
        refreshLibrary()
        viewModelScope.launch(Dispatchers.IO) { loadCustomSamples() }
    }

    override fun onCleared() {
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        plexPoll?.cancel(); bulkJob?.cancel(); indexJob?.cancel()
        try { indexDispatcher.close() } catch (_: Exception) {}
        engine.stop(); super.onCleared()
    }
}
