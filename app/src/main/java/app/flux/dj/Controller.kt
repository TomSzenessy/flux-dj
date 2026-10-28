package app.flux.dj

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flux.dj.audio.*
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt

class LibItem(
    val id: String, val title: String, val artist: String, var durMs: Long, val uri: Uri?, val demo: Int,
    var bpm: Float = 0f, var key: Int = -1,
    var energy: Int = 0, var pattern: String = "", var vibe: String = "",
    /** When it entered the library (epoch ms) — for "Newest" sorting. */
    var added: Long = 0,
    /** File name without extension (for matching rekordbox / other libraries). */
    var fileStem: String = "",
    /** Grid/key/cues come from a rekordbox import. */
    var fromRb: Boolean = false,
    /** "device", "import", "drive", "plex" or "demo" — where the audio lives. */
    var sourceKind: String = "",
    /** "flux" = analysed on this tablet, "rekordbox" = collection.xml/tempo markers, "manual" = hand edit. */
    var analysisSource: String = "",
    /** HTTP headers needed to stream this item (Plex token). */
    var streamHeaders: Map<String, String> = emptyMap(),
)

/** One played track in the set history. */
class HistEntry(val time: Long, val deck: Int, val title: String, val artist: String, val bpm: Float, val key: Int)

class RefMeta(val bpm: Float, val key: Int, val energy: Int, val vibe: String, val pattern: String, val title: String)

class DeckUi {
    var loaded by mutableStateOf(false)
    var loading by mutableFloatStateOf(-1f)
    var analysing by mutableStateOf(false)
    var title by mutableStateOf("Load a track")
    var artist by mutableStateOf("")
    var bpm by mutableStateOf("–")
    var pct by mutableStateOf("")
    var keyText by mutableStateOf("")
    var time by mutableStateOf("00:00.0")
    var remain by mutableStateOf("-00:00")
    var playing by mutableStateOf(false)
    var synced by mutableStateOf(false)
    var keyLock by mutableStateOf(false)
    var slip by mutableStateOf(false)
    var loopOn by mutableStateOf(false)
    var loopBeats by mutableFloatStateOf(4f)
    var tempo by mutableFloatStateOf(0f)
    var range by mutableFloatStateOf(0.08f)
    var cues by mutableStateOf(List(8) { false })
    var padMode by mutableIntStateOf(0)
    var zoom by mutableIntStateOf(2)
    var hasBpm by mutableStateOf(false)
    var keyShift by mutableIntStateOf(0)
    var match by mutableStateOf("")
    var beat by mutableIntStateOf(0)
    var barText by mutableStateOf("")
    var phraseSoon by mutableStateOf(false)
    var gridEdit by mutableStateOf(false)
    var gridBpm by mutableStateOf("")
    var gridMode by mutableIntStateOf(0)       // 0 move, 1 stretch
    var origBpm by mutableStateOf("")
    var matchGood by mutableStateOf(true)
    var analysisSource by mutableStateOf("")
}

class ChanUi {
    var trim by mutableFloatStateOf(0.5f)
    var hi by mutableFloatStateOf(0.5f)
    var mid by mutableFloatStateOf(0.5f)
    var lo by mutableFloatStateOf(0.5f)
    var color by mutableFloatStateOf(0.5f)
    var fader by mutableFloatStateOf(0.85f)
    var cue by mutableStateOf(false)
}

class ViewPrefs(private val sp: android.content.SharedPreferences) {
    var waves by mutableStateOf(sp.getBoolean("v_waves", true))
    var overview by mutableStateOf(sp.getBoolean("v_overview", true))
    var jog by mutableStateOf(sp.getBoolean("v_jog", true))
    var pads by mutableStateOf(sp.getBoolean("v_pads", true))
    var loops by mutableStateOf(sp.getBoolean("v_loops", true))
    var eq by mutableStateOf(sp.getBoolean("v_eq", true))
    var mixer by mutableStateOf(sp.getBoolean("v_mixer", true))
    var meters by mutableStateOf(sp.getBoolean("v_meters", true))

    fun save() = sp.edit().putBoolean("v_waves", waves).putBoolean("v_overview", overview).putBoolean("v_jog", jog)
        .putBoolean("v_pads", pads).putBoolean("v_loops", loops).putBoolean("v_eq", eq).putBoolean("v_mixer", mixer)
        .putBoolean("v_meters", meters).apply()

    fun preset(p: Int) {
        when (p) {
            0 -> { waves = true; overview = true; jog = true; pads = true; loops = true; eq = true; mixer = true; meters = true } // Full
            1 -> { waves = true; overview = true; jog = false; pads = false; loops = true; eq = true; mixer = true; meters = true } // Mix
            2 -> { waves = false; overview = false; jog = true; pads = false; loops = false; eq = false; mixer = true; meters = false } // Scratch
            3 -> { waves = true; overview = false; jog = false; pads = false; loops = false; eq = false; mixer = true; meters = false } // Minimal
        }
        save()
    }
}

/** Outputs that can actually play music (no call/SCO profile, earpiece or internal routes). */
private val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".aiff", ".aif", ".wma")
private val MEDIA_TYPES: Set<Int> = buildSet {
    addAll(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY))
    if (Build.VERSION.SDK_INT >= 26) add(AudioDeviceInfo.TYPE_USB_HEADSET)
    if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
}

/** [key] (type + address) is stable across reconnects, unlike Android's device [id]. */
class DeviceOpt(val id: Int, val name: String, val kind: String, val key: String)

class Controller(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    val engine = Engine(app)
    private val sp = app.getSharedPreferences("flux", Context.MODE_PRIVATE)
    private val cache = app.getSharedPreferences("flux_analysis", Context.MODE_PRIVATE)

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
    private val lastCueRev = intArrayOf(-1, -1)
    private val lastCue = doubleArrayOf(-1.0, -1.0)
    private val restoring = booleanArrayOf(false, false)
    private val loadGen = java.util.concurrent.atomic.AtomicIntegerArray(2)
    private val activeLoads = java.util.concurrent.atomic.AtomicInteger(0)  // indexer yields while > 0
    // preview
    var previewId by mutableStateOf<String?>(null)
    var previewLoading by mutableStateOf(false)
    var previewFrac by mutableFloatStateOf(0f)
    var previewTime by mutableStateOf("")
    @Volatile private var previewCache: Track? = null
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
    private val plexClient by lazy {
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
    private var plexAllCache: List<LibItem> = emptyList()
    private var plexPoll: Job? = null
    var bulkDownloading by mutableStateOf(false)
    var bulkDone by mutableIntStateOf(0)
    var bulkTotal by mutableIntStateOf(0)
    var bulkFailed by mutableIntStateOf(0)
    private var bulkJob: Job? = null
    private val plexFile by lazy { java.io.File(ctx.filesDir, "plex.tsv") }

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
    private val onAirMs = longArrayOf(0, 0)
    private val loggedLoad = intArrayOf(-1, -1)   // load generation already logged per deck
    private var lastPollNs = 0L
    private var indexJob: Job? = null
    private val libraryGen = java.util.concurrent.atomic.AtomicInteger(0)
    private val scanGen = java.util.concurrent.atomic.AtomicInteger(0)
    private val plexGen = java.util.concurrent.atomic.AtomicInteger(0)
    private val indexDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "flux-index").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()

    private val demoCache = HashMap<Int, Track>()
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastTenths = longArrayOf(-1, -1)
    private var lastRemain = intArrayOf(-1, -1)
    private val lastRatio = DoubleArray(2) { Double.NaN }
    private val lastShownBpm = FloatArray(2) { Float.NaN }
    private val lastBarText = arrayOfNulls<String>(2)
    private var lastMasterText = ""
    private var nextJob: Job? = null


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

    // ------------------------------------------------------------------ outputs
    private fun deviceKey(d: AudioDeviceInfo) = "${d.type}|${if (Build.VERSION.SDK_INT >= 28) d.address else ""}"

    fun refreshDevices() {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devices = outs.filter { it.type in MEDIA_TYPES }
            .map { DeviceOpt(it.id, deviceName(it), kind(it), deviceKey(it)) }
            .distinctBy { it.key }
            .sortedBy { if (it.kind == "Tablet speaker") 0 else 1 }
        var changed = false
        val masterPresent = masterDev.isEmpty() || devices.any { it.key == masterDev }
        val cuePresent = cueDev.isNotEmpty() && devices.any { it.key == cueDev }

        // A disconnected headphone/cue device must switch that output OFF. Letting it fall back to
        // "default" would send the cue to the speaker and play the same deck twice.
        if (outMode == OutMode.DUAL && (!cuePresent || !dualPossible)) {
            outMode = OutMode.SINGLE
            cueDev = ""
            changed = true
            toast = "Headphone cue output disconnected — switched to single output"
        }
        if (outMode == OutMode.SPLIT && !splitPossible) {
            outMode = OutMode.SINGLE
            changed = true
            toast = "Split-cable output disconnected — switched to single output"
        }
        if (outMode == OutMode.DUAL && (masterDev.isBlank() || !masterPresent)) {
            val fallback = devices.firstOrNull { !isBt(it) && it.key != cueDev }
                ?: devices.firstOrNull { it.key != cueDev }
            if (fallback != null) {
                masterDev = fallback.key
                changed = true
                toast = "Master output disconnected — now using ${fallback.name}"
            } else {
                outMode = OutMode.SINGLE; cueDev = ""; changed = true
                toast = "Master output disconnected — switched to single output"
            }
        }
        if (outMode == OutMode.SINGLE && masterDev.isNotEmpty() && !masterPresent) {
            masterDev = ""; changed = true
            toast = "Output disconnected — back to the system default"
        }

        if (changed) applyOutputConfig(restart = true)
        else {
            // ids change when devices reconnect: re-resolve the saved choices
            engine.masterDeviceId = devices.firstOrNull { it.key == masterDev }?.id ?: -1
            engine.cueDeviceId = if (outMode == OutMode.DUAL) devices.firstOrNull { it.key == cueDev }?.id ?: -1 else -1
        }
    }

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refreshDevices() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // The list can lag the callback by a moment; check again once Android has settled.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshDevices() }, 300)
        }
    }

    private fun kind(d: AudioDeviceInfo) = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Tablet speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line out / aux"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        else -> if (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) "Bluetooth LE" else "USB"
    }

    private fun deviceName(d: AudioDeviceInfo): String {
        val n = d.productName?.toString().orEmpty()
        val k = kind(d)
        return when {
            k == "Tablet speaker" -> "Built-in speaker"
            n.isBlank() || n == Build.MODEL || n.equals(Build.DEVICE, true) -> k
            else -> n
        }
    }

    fun doOutMode(m: OutMode) { outMode = m; if (m == OutMode.DUAL) ensureDualDevices(); applyOutputConfig(true) }

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

    /** "Two devices": room → speaker/USB, ears → Bluetooth (or the reverse), never two on the same side. */
    private fun ensureDualDevices() {
        val m0 = devices.firstOrNull { it.key == masterDev }
        val master = m0 ?: devices.firstOrNull { it.kind == "Tablet speaker" } ?: devices.firstOrNull { !isBt(it) } ?: devices.firstOrNull()
        masterDev = master?.key ?: ""
        val cue = devices.firstOrNull { it.key == cueDev }
        if (master == null || cue == null || !canPair(master, cue)) {
            cueDev = devices.firstOrNull { master != null && canPair(master, it) }?.key ?: ""
        }
    }
    fun doMasterDev(key: String) { masterDev = key; if (outMode == OutMode.DUAL) ensureDualDevices(); applyOutputConfig(true) }
    fun doCueDev(key: String) { cueDev = key; applyOutputConfig(true) }
    /** Extra fine-tune on top of the measured output delay (ms). */
    var visualTrimMs by mutableIntStateOf(sp.getInt("visual_trim", 0))
    private var trimSave: Job? = null
    fun setVisualTrim(ms: Int) {
        visualTrimMs = ms.coerceIn(-300, 500)
        trimSave?.cancel()
        trimSave = viewModelScope.launch { delay(450); sp.edit().putInt("visual_trim", visualTrimMs).apply() }
    }
    var latencyMs by mutableIntStateOf(0)
    var outVolume by mutableFloatStateOf(sp.getFloat("out_volume", 1f))
    private var volumeSave: Job? = null
    fun doOutVolume(v: Float) {
        outVolume = v; engine.outVolume = v
        volumeSave?.cancel()
        volumeSave = viewModelScope.launch { delay(450); sp.edit().putFloat("out_volume", outVolume).apply() }
    }

    /**
     * The playhead you *hear*: the engine is ahead of the speaker by the output delay, so everything
     * on screen is drawn that much later while playing.
     */
    fun vpos(i: Int): Double {
        val d = engine.decks[i]
        if (!d.playing || d.scratching || d.searching) return d.pos
        val full = outputDelayFrames(i)
        val hold = d.visualHoldPos
        val delay = if (hold >= 0.0) minOf(full, (d.pos - hold).coerceAtLeast(0.0)) else full
        return (d.pos - delay).coerceAtLeast(0.0)
    }

    /** The engine must be this many frames ahead of the picture for the sound to arrive together. */
    fun outputDelayFrames(i: Int): Double {
        val d = engine.decks[i]
        return (engine.visualLatencyMs + visualTrimMs).coerceAtLeast(0f) / 1000.0 * SR * d.effRatio
    }

    /** Finish a held needle-search at a grid-snapped target (SNAP on) and resume from the picture. */
    private fun resyncIfNeeded(i: Int) {
        val d = deck(i)
        if (d.syncOn && !d.loopOn) d.needAlign = true
    }

    fun releaseSearchAt(i: Int, target: Double) {
        val d = engine.decks[i]
        val p = if (d.quantize) d.snapBeat(target, 0.5) else target
        android.util.Log.i("FLUX-SEARCH", "raw=${target.toLong()} snapped=${p.toLong()} q=${d.quantize} bpm=${d.track?.bpm} beat=${d.track?.beatIndex(target)} halfBeat=${(d.track?.spb ?: 0.0) * 0.5}")
        d.searching = false
        d.seekNow(p + (if (d.playing) outputDelayFrames(i) else 0.0))
        resyncIfNeeded(i)
    }

    /** Finish a held needle-search at the visual position; skip the in-flight output delay. */

    /** Tap-to-jump. The visual position is authoritative; skip the in-flight output delay. */
    fun jumpAudible(i: Int, target: Double) {
        val d = engine.decks[i]
        d.seekNow(target + (if (d.playing) outputDelayFrames(i) else 0.0))
        resyncIfNeeded(i)
    }

    /** Play/pause. Pause freezes the picture; play holds it at the cue while the first audio is in flight. */
    fun togglePlayPad(i: Int) {
        val d = engine.decks[i]
        if (d.playing) d.pauseAt(vpos(i))
        else d.play()
    }
    /** Manual/automatic timing for every output: room, headphones and the picture. */
    var aligning by mutableStateOf(false)
    private var alignGen = 0
    private var bakOffset = 0
    private var bakTrim = 0
    private var bakAcoustic = -1f
    private var bakMeasured = false
    private var bakKey: String? = null
    private var bakValid = false
    fun startAlign() {
        if (!aligning) {
            bakOffset = offsetMs; bakTrim = visualTrimMs
            bakAcoustic = engine.acousticMasterMs; bakMeasured = timingMeasured
            bakKey = sp.getString("acoustic_key", null)
            bakValid = true
        }
        alignGen++
        aligning = true
        engine.calibration(true)
        autoStep = 0; autoMasterMs = -1f; autoCueMs = -1f; autoProgress = 0f
        autoMsg = "Put the tablet's mic next to the speaker — or fine-tune by hand below."
    }
    fun stopAlign() {
        alignGen++; aligning = false; engine.calibration(false); measuringMic = false
        bakValid = false
    }

    /** Dismiss the timing sheet and put back the settings it had when it opened. */
    fun cancelAlign() {
        alignGen++; measuringMic = false; engine.calibration(false)
        if (bakValid) {
            setOffset(bakOffset); setVisualTrim(bakTrim)
            engine.acousticMasterMs = bakAcoustic; timingMeasured = bakMeasured
            if (bakKey != null && bakMeasured) sp.edit().putString("acoustic_key", bakKey).putFloat("acoustic_master_ms", bakAcoustic).apply()
            else sp.edit().remove("acoustic_master_ms").remove("acoustic_key").apply()
        }
        bakValid = false
        aligning = false
    }

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

    fun ensureBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBtPermission?.invoke()
        }
    }

    fun beginAutoAlign() {
        // measure from a clean slate: no old delay, no fine-tune
        setOffset(0); setVisualTrim(0)
        engine.acousticMasterMs = -1f
        engine.calibration(true)
        autoMasterMs = -1f; autoCueMs = -1f; autoProgress = 0f
        autoStep = 1
        autoMsg = "Place the tablet's mic right next to the speaker (or main output). Keep the room quiet, then press Measure speaker."
    }

    fun measureMaster() {
        if (!micGranted) { requestMicPermission?.invoke(); toast = "Microphone access is needed for automatic alignment"; return }
        if (measuringMic) return
        val gen = alignGen
        measuringMic = true; autoStep = 2; autoProgress = 0f
        autoMsg = "Listening to the speaker…"
        engine.calibOnly = 0
        viewModelScope.launch(Dispatchers.Default) {
            val r = MicAlign.measure(ctx, engine, 6500) { p ->
                viewModelScope.launch(Dispatchers.Main) { if (gen == alignGen) autoProgress = p }
            }
            withContext(Dispatchers.Main) {
                if (gen != alignGen) return@withContext
                measuringMic = false
                if (r == null || r.delayMs <= 0f || r.delayMs > 700f || r.confidence < 3f) {
                    autoStep = 1; autoMsg = "Couldn't hear the speaker clearly. Move the mic closer, turn the volume up a little, and try again."
                } else {
                    autoMasterMs = r.delayMs
                    android.util.Log.i("FLUX-TIME", "master measured=${r.delayMs} ms conf=${r.confidence} · offset=${offsetMs} · trim=${visualTrimMs} · mode=$outMode")
                    if (outMode == OutMode.SINGLE) {
                        doneAutoAlign()
                        autoMsg = "Output delay ${r.delayMs.toInt()} ms — picture, grid and waveforms now match what you hear."
                    } else {
                        engine.calibOnly = 1
                        autoStep = 3
                        autoMsg = "Speaker: ${r.delayMs.toInt()} ms. Now move the mic right next to the headphones and press Measure headphones."
                    }
                }
            }
        }
    }

    fun measureCue() {
        if (!micGranted) { requestMicPermission?.invoke(); toast = "Microphone access is needed for automatic alignment"; return }
        if (measuringMic) return
        val gen = alignGen
        measuringMic = true; autoStep = 4; autoProgress = 0f
        autoMsg = "Listening to the headphones…"
        engine.calibOnly = 1
        viewModelScope.launch(Dispatchers.Default) {
            val r = MicAlign.measure(ctx, engine, 6500) { p ->
                viewModelScope.launch(Dispatchers.Main) { if (gen == alignGen) autoProgress = p }
            }
            withContext(Dispatchers.Main) {
                if (gen != alignGen) return@withContext
                measuringMic = false
                if (r == null || r.delayMs <= 0f || r.delayMs > 700f || r.confidence < 3f) {
                    autoStep = 3; autoMsg = "Couldn't hear the headphones clearly. Hold the mic against the earcup and try again."
                } else {
                    autoCueMs = r.delayMs
                    android.util.Log.i("FLUX-TIME", "cue measured=${r.delayMs} ms conf=${r.confidence} · offset=${offsetMs} · trim=${visualTrimMs} · mode=$outMode")
                    doneAutoAlign()
                }
            }
        }
    }

    private fun doneAutoAlign() {
        val m = autoMasterMs
        val c = autoCueMs
        if (m > 0f && c > 0f) {
            // Align the faster output to the slower one (the classic cue/master alignment).
            val wanted = (c - m).roundToInt()
            val capped = wanted.coerceIn(-450, 450)
            setOffset(capped)
            engine.acousticMasterMs = m
            saveAcoustic(m)
            autoMsg = "Speaker ${m.toInt()} ms · headphones ${c.toInt()} ms · aligned · picture held back ${(m + maxOf(0, capped)).toInt()} ms" +
                if (wanted != capped) " (alignment capped)" else ""
        } else if (m > 0f) {
            setOffset(0)
            engine.acousticMasterMs = m
            saveAcoustic(m)
            autoMsg = "Output delay ${m.toInt()} ms — picture held back to match the room."
        }
        autoStep = 5; autoProgress = 1f; engine.calibOnly = -1
    }

    private fun saveAcoustic(ms: Float) {
        acousticBufferMs = engine.outLatencyMs
        sp.edit().putFloat("acoustic_master_ms", ms).putFloat("acoustic_buffer_ms", acousticBufferMs)
            .putString("acoustic_key", timingKey()).apply()
        timingMeasured = true
    }

    fun finishMasterOnly() { autoCueMs = -1f; doneAutoAlign() }
    fun resetTiming() {
        setOffset(0); setVisualTrim(0); engine.acousticMasterMs = -1f; timingMeasured = false
        sp.edit().remove("acoustic_master_ms").remove("acoustic_key").apply()
        toast = "Timing reset — room, headphones and picture use the device delay again"
    }

    private var offsetSave: Job? = null
    fun setOffset(ms: Int) {
        offsetMs = ms; engine.offsetMs = ms
        offsetSave?.cancel()
        offsetSave = viewModelScope.launch { delay(450); sp.edit().putInt("out_offset", offsetMs).apply() }
    }

    /** key -> "N beeps → routed to X" from the last output test. */
    var testResults by mutableStateOf<Map<String, String>>(emptyMap())

    /** Device n (1-based in list order) beeps n times, each at its own pitch, all at the same time. */
    fun testOutputs(keys: List<String>) {
        refreshDevices()
        testResults = emptyMap()
        keys.forEachIndexed { idx, key ->
            val d = devices.firstOrNull { it.key == key } ?: return@forEachIndexed
            val beeps = idx + 1
            engine.playTest(d.id, beeps, 520.0 + idx * 260.0) { routed ->
                viewModelScope.launch(Dispatchers.Main) {
                    testResults = testResults + (key to "$beeps beep${if (beeps > 1) "s" else ""} → Android routed to: $routed")
                }
            }
        }
    }

    private fun devName(key: String, fallback: String) = devices.firstOrNull { it.key == key }?.name ?: fallback

    /** Which rig the acoustic measurement was made on. */
    private fun timingKey() = "$outMode|$masterDev|$cueDev"
    private fun syncAcousticFromPrefs() {
        val key = sp.getString("acoustic_key", "")
        val valid = key == timingKey() && sp.getFloat("acoustic_master_ms", -1f) >= 0f
        timingMeasured = valid
        acousticBufferMs = sp.getFloat("acoustic_buffer_ms", -1f)
        engine.acousticMasterMs = if (valid) sp.getFloat("acoustic_master_ms", -1f) else -1f
    }
    var timingMeasured by mutableStateOf(false)
    private var acousticBufferMs = -1f

    fun applyOutputConfig(restart: Boolean) {
        engine.outMode = outMode
        engine.masterDeviceId = devices.firstOrNull { it.key == masterDev }?.id ?: -1
        engine.cueDeviceId = if (outMode == OutMode.DUAL) devices.firstOrNull { it.key == cueDev }?.id ?: -1 else -1
        engine.offsetMs = offsetMs
        sp.edit().putInt("out_mode", outMode.ordinal).putString("dev_master_key", masterDev).putString("dev_cue_key", cueDev).apply()
        outputLabel = when (outMode) {
            OutMode.SINGLE -> devName(masterDev, "System default")
            OutMode.DUAL -> devName(masterDev, "Default") + " + " + devName(cueDev, "cue")
            OutMode.SPLIT -> "Split cable (L cue · R master)"
        }
        syncAcousticFromPrefs()
        if (restart) viewModelScope.launch(Dispatchers.Default) { engine.reopenOutputs() }
    }

    // ------------------------------------------------------------------ library
    fun refreshLibrary() {
        val ok = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        libPermission = ok
        if (!ok) return
        val gen = libraryGen.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val out = ArrayList<LibItem>()
            try {
                val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME)
                ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000", null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                    while (c.moveToNext() && out.size < 5000) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val it = LibItem("ms:$id", c.getString(1) ?: "Unknown", c.getString(2)?.takeIf { a -> a != "<unknown>" } ?: "", c.getLong(3), uri, -1, added = c.getLong(4) * 1000, fileStem = (c.getString(5) ?: "").substringBeforeLast('.'))
                        applyMeta(it)
                        out.add(it)
                    }
                }
            } catch (_: Exception) {}
            if (!isActive || libraryGen.get() != gen) return@launch
            withContext(Dispatchers.Main) { deviceTracks = out; libRev++; rbRematch(); startIndexing() }
        }
    }

    fun loadUri(deckIdx: Int, uri: Uri) {
        var name = "Track"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.') }
        } catch (_: Exception) {}
        val item = LibItem("uri:$uri", name, "", 0, uri, -1)
        load(deckIdx, item)
    }

    fun load(deckIdx: Int, item: LibItem, force: Boolean = false) {
        if (!force && engine.decks[deckIdx].playing) { pendingLoad = deckIdx to item; return }
        viewModelScope.launch(Dispatchers.Default) { loadItem(deckIdx, item) }
    }
    fun confirmLoad() { pendingLoad?.let { (d, it) -> pendingLoad = null; load(d, it, force = true); toast = "“${it.title}” → Deck ${deckName(d)}" } }
    fun cancelLoad() { pendingLoad = null }

    private fun saveCues(id: String, d: Deck) {
        cache.edit().putString("cues:$id", (listOf(d.cue) + d.hotCues.toList()).joinToString(",")).apply()
    }

    /** Restore saved cue + hot cues for a track; returns true if any were found. */
    private fun restoreCues(i: Int, t: Track): Boolean {
        val p = cache.getString("cues:${t.id}", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return false
        if (p.size < 9) return false
        val d = engine.decks[i]
        for (k in 0 until 8) d.hotCues[k] = p[k + 1]
        d.hotCueRev++
        d.cue = p[0]; d.seekReq = p[0]
        lastCueRev[i] = d.hotCueRev; lastCue[i] = d.cue
        return true
    }

    private val demoLocks = Array(DemoTracks.specs.size) { Any() }
    private fun demoTrack(i: Int): Track = synchronized(demoLocks[i]) {
        demoCache[i] ?: run {
            val spec = DemoTracks.specs[i]
            val f = java.io.File(ctx.cacheDir, "demo_${spec.id.substringAfter(':')}_v1.pcm")
            var t: Track? = null
            if (f.exists()) try {
                val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val pcm = ShortArray(b.remaining() / 2); b.asShortBuffer().get(pcm)
                t = Track(spec.id, spec.title, spec.artist, pcm)
                Analyzer.waveform(t); Analyzer.rhythmAndKey(t, spec.bpm, spec.rootPc + if (spec.minor) 12 else 0)
            } catch (_: Exception) { t = null }
            if (t == null) {
                t = DemoTracks.build(spec)
                try {
                    val bb = java.nio.ByteBuffer.allocate(t.pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(t.pcm); f.writeBytes(bb.array())
                } catch (_: Exception) {}
            }
            Analyzer.profile(t)
            demos[i].let { d -> d.energy = t.energy; d.pattern = t.pattern; d.vibe = t.vibe }
            synchronized(demoCache) { demoCache[i] = t }
            t
        }
    }

    private suspend fun loadItem(i: Int, item: LibItem, auto: Boolean = false) {
        val u = ui[i]; val d = engine.decks[i]
        val gen = loadGen.incrementAndGet(i)   // a newer load for this deck supersedes this one
        withContext(Dispatchers.Main) { u.loading = 0f; u.title = item.title; u.artist = item.artist; u.loaded = false }
        if (previewId == item.id) withContext(Dispatchers.Main) { stopPreview() }
        val t0 = System.currentTimeMillis()
        activeLoads.incrementAndGet()
        try { loadItemInner(i, item, auto, u, d, gen, t0) } finally { activeLoads.decrementAndGet() }
    }

    private suspend fun loadItemInner(i: Int, item: LibItem, auto: Boolean, u: DeckUi, d: Deck, gen: Int, t0: Long) {
        fun stale() = loadGen.get(i) != gen
        var hadCues = false
        var applied = false
        fun apply(t: Track) {
            if (stale() || applied) return
            applied = true
            rememberLoaded(i, item)
            // If the other deck was following this one, it keeps its current tempo and becomes master
            // (otherwise it would jump to the new track's BPM).
            val o = engine.decks[1 - i]
            if (o.syncOn) releaseSync(o)
            restoring[i] = true
            d.load(t); d.quantize = quantize
            hadCues = restoreCues(i, t)
            restoring[i] = false
            u.loaded = true; u.loading = if (t.complete) -1f else u.loading; u.title = t.title; u.artist = t.artist
            u.analysisSource = item.analysisSource.ifEmpty { if (item.fromRb) "rekordbox" else "" }
            u.analysing = !t.analyzed
            u.keyText = if (t.key >= 0) "${t.camelot} · ${t.keyName}" else ""
            android.util.Log.i("FLUX", "playable '${item.title}' after ${System.currentTimeMillis() - t0} ms")
        }
        var ready: Track? = when {
            item.demo >= 0 -> demoTrack(item.demo)
            previewCache?.id == item.id && previewCache?.complete == true -> previewCache
            item.uri != null -> loadPcm(item)
            else -> null
        }
        // A stream can end early (server/range hiccup) and the short PCM used to be cached forever.
        // Drop a decoded cache that is materially shorter than the known track length.
        if (ready != null && item.durMs > 10_000 && kotlin.math.abs(ready.durationSec * 1000 - item.durMs) > 10_000) {
            android.util.Log.w("FLUX-TRUNC", "discarding short PCM ${item.title}: ${ready.durationSec}s vs ${item.durMs / 1000}s")
            pcmFile(item.id).delete()
            if (previewCache?.id == item.id) previewCache = null
            ready = null
        }
        val src: Pair<Uri, Map<String, String>>? = if (ready != null) null else {
            val remoteUri = item.uri
            // Downloading the compressed original first is slower by a second but avoids the
            // server closing a long HTTP stream early (which used to cut songs off at 10 s / 60 s).
            if (remoteUri != null && (remoteUri.scheme == "http" || remoteUri.scheme == "https") &&
                remoteCacheMb > 0 && !originalValid(item)) {
                android.util.Log.i("FLUX-CACHE", "download-first ${item.title}")
                if (!downloadOriginal(item)) android.util.Log.w("FLUX-CACHE", "download-first failed ${item.title}; falling back to stream")
            }
            mediaSource(item)
        }
        if (src != null && src.first.scheme == "file") android.util.Log.i("FLUX-CACHE", "loading original cache: ${item.title}")
        val track: Track? = ready ?: Decoder.decode(
            ctx, src!!.first, item.id, item.title, item.artist,
            onProgress = { p -> if (!stale()) u.loading = p },
            onStream = { st -> prepare(st); viewModelScope.launch(Dispatchers.Main) { apply(st) } },
            headers = src.second,
        )
        if (stale()) return
        if (track == null) { withContext(Dispatchers.Main) { if (!applied) { u.loading = -1f; u.title = "Couldn't read that file"; toast = "Couldn't decode ${item.title}" } }; return }
        val truncated = item.durMs > 10_000 && track.complete &&
            kotlin.math.abs(track.durationSec * 1000 - item.durMs) > 10_000
        if (truncated) {
            android.util.Log.w("FLUX-TRUNC", "decoded short ${item.title}: ${track.durationSec}s vs ${item.durMs / 1000}s (source=${src?.first})")
            // If a cached original is short, throw it away so the next load goes back to the server.
            if (src?.first?.scheme == "file") {
                val r = remoteFile(item.id); r.delete(); remoteOk(r).delete()
            }
            withContext(Dispatchers.Main) { toast = "“${item.title}” looks truncated — not caching it; try loading it again" }
        }
        if (ready == null) { if (!applied) prepare(track); if (!truncated) { savePcm(track); prefetchOriginal(item) } } else if (ready.wave == null) prepare(ready)
        withContext(Dispatchers.Main) { apply(track); if (!stale()) u.loading = -1f }
        android.util.Log.i("FLUX", "load '${item.title}': ${System.currentTimeMillis() - t0} ms (${if (ready != null) "cache" else "decode"})")
        if (!applied) return

        // first time we see this track: waveform + analysis, then remember them on disk
        if (track.wave == null) {
            val g0 = track.gain
            Analyzer.waveform(track)
            val g = track.gain
            if (d.playing && d.track === track) track.gain = g0   // never jump the level of a playing track
            saveWave(track, g)
        }
        if (!track.analyzed && !protectedMeta(metaParts(track.id))) {
            Analyzer.rhythmAndKey(track); Analyzer.profile(track)
            saveMeta(track.id, track, true)
            item.analysisSource = "flux"
        } else if (track.energy == 0 && protectedMeta(metaParts(track.id))) {
            Analyzer.profile(track); saveProfileOnly(track.id, track)
        }
        withContext(Dispatchers.Main) {
            item.bpm = track.bpm; item.key = track.key; item.energy = track.energy; item.pattern = track.pattern; item.vibe = track.vibe; libRev++
            if (stale() || d.track !== track) return@withContext
            if (!hadCues && track.firstBeat > 0 && !d.playing && d.cue != track.firstBeat) { d.cue = track.firstBeat; d.seekReq = track.firstBeat }
            u.analysing = false
            u.analysisSource = item.analysisSource.ifEmpty { if (item.fromRb) "rekordbox" else "" }
            u.keyText = if (track.key >= 0) "${track.camelot} · ${track.keyName}" else ""
            // Smart sync: land in time with whatever is already playing
            val o = engine.decks[1 - i]
            if (!auto && o.playing && track.bpm > 0 && o.bpm > 0 && !d.playing) engine.toggleSync(i)
        }
    }

    private fun rememberLoaded(i: Int, item: LibItem) {
        sp.edit().putString("last_$i", listOf(item.id, item.uri?.toString() ?: "", item.title, item.artist, item.demo.toString(), item.durMs.toString()).joinToString("\t")).apply()
    }

    private fun lastLoaded(i: Int): LibItem? {
        val p = sp.getString("last_$i", null)?.split("\t") ?: return null
        if (p.size < 5) return null
        val demo = p[4].toIntOrNull() ?: -1
        if (demo >= 0) return demos.getOrNull(demo)
        if (p[1].isEmpty()) return null
        val id = p[0]
        val dur = p.getOrNull(5)?.toLongOrNull() ?: 0L
        val kind = when {
            id.startsWith("plex:") -> "plex"
            id.startsWith("gdrive:") -> "drive"
            id.startsWith("imp:") -> "import"
            else -> "device"
        }
        val headers = if (kind == "plex" && plexToken.isNotBlank()) mapOf("X-Plex-Token" to plexToken) else emptyMap()
        return LibItem(id, p[2], p[3], dur, Uri.parse(p[1]), -1, fileStem = p[2], sourceKind = kind, streamHeaders = headers).also { applyMeta(it) }
    }

    /** Fill a freshly decoded track with everything we already know about it (waveform, grid, key, gain). */
    private fun prepare(t: Track) {
        loadWave(t)
        if (!t.analyzed) cache.getString(t.id, null)?.split("|")?.let { p ->
            // tempo maps may start their first bar before 0:00, so a negative beat-1 is valid for protected grids
            if (metaCurrent(p) && ((p[2].toDoubleOrNull() ?: -1.0) >= 0 || protectedMeta(p))) {
                t.bpm = p[0].toFloat(); t.key = p[1].toInt(); t.firstBeat = p[2].toDouble(); t.analyzed = true
                t.energy = p[3].toIntOrNull() ?: 0; t.pattern = p[4]; t.vibe = p[5]
                cache.getString("tmap:${t.id}", null)?.let { m -> t.applyTempoMapString(m) }
            }
        }
    }

    // ------------------------------------------------------------------ decoded-audio disk cache (LRU)
    private fun md5(id: String) = java.security.MessageDigest.getInstance("MD5").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private val MAX_XML_BYTES = 32L * 1024 * 1024

    private val pcmDir by lazy { java.io.File(ctx.cacheDir, "pcm").apply { mkdirs() } }
    private val remoteDir by lazy { java.io.File(ctx.cacheDir, "remote").apply { mkdirs() } }
    var pcmCacheMb by mutableIntStateOf(sp.getInt("pcm_cache_mb", 3072))
    var remoteCacheMb by mutableIntStateOf(sp.getInt("remote_cache_mb", 2048))
    private val pcmCap get() = pcmCacheMb.toLong() * 1024 * 1024
    private val remoteCap get() = remoteCacheMb.toLong() * 1024 * 1024
    private fun pcmFile(id: String) = java.io.File(pcmDir, md5(id) + ".pcm")
    private fun remoteFile(id: String) = java.io.File(remoteDir, md5(id) + ".audio")

    fun updatePcmCacheMb(mb: Int) {
        pcmCacheMb = mb.coerceIn(0, 16384)
        sp.edit().putInt("pcm_cache_mb", pcmCacheMb).apply()
        trimPcmCache()
    }
    fun updateRemoteCacheMb(mb: Int) {
        remoteCacheMb = mb.coerceIn(0, 16384)
        sp.edit().putInt("remote_cache_mb", remoteCacheMb).apply()
        trimRemoteCache()
    }
    fun cacheUsedMb(): Pair<Int, Int> {
        fun mb(d: java.io.File) = (d.listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)
        return mb(pcmDir).toInt() to mb(remoteDir).toInt()
    }
    fun clearAudioCache() {
        pcmDir.listFiles()?.forEach { it.delete() }
        remoteDir.listFiles()?.forEach { it.delete() }
        libRev++
        toast = "Audio cache cleared"
    }
    private fun trimPcmCache() {
        val files = pcmDir.listFiles { x -> x.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (x in files) { total += x.length(); if (total > pcmCap) x.delete() }
    }
    private fun trimRemoteCache() {
        val files = remoteDir.listFiles { x -> x.name.endsWith(".audio") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (x in files) { total += x.length(); if (total > remoteCap) x.delete() }
    }

    private val cachedMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var cachedMemoRev = -1

    /** Memoised per library revision: composition must not stat the same files every frame. */
    fun isCached(item: LibItem): Boolean {
        if (cachedMemoRev != libRev) { cachedMemo.clear(); cachedMemoRev = libRev }
        return cachedMemo.getOrPut(item.id) { isCachedNow(item) }
    }

    /** True when the track can play without the network (Plex original cached, PCM cached, or local). */
    private fun isCachedNow(item: LibItem): Boolean {
        if (item.demo >= 0) return true
        val pcm = pcmFile(item.id)
        if (pcm.exists()) {
            // A short/truncated PCM is not really cached; let the next load repair it.
            if (item.durMs > 10_000) {
                val expectedBytes = item.durMs / 1000.0 * SR * 4.0
                if (pcm.length() >= expectedBytes - SR * 4 * 10) return true
            } else return true
        }
        if (originalValid(item)) return true
        return item.sourceKind != "plex" && item.sourceKind != "drive"
    }

    /** Stream from the cached original when we have it; otherwise from the server. */
    private fun mediaSource(item: LibItem): Pair<Uri, Map<String, String>> {
        val r = remoteFile(item.id)
        val ok = remoteOk(r)
        val valid = r.exists() && r.length() > 4096 && ok.exists() &&
            (ok.readText().trim().toLongOrNull() ?: -1L) == r.length()
        if (valid) return Uri.fromFile(r) to emptyMap()
        return item.uri!! to item.streamHeaders
    }

    private fun remoteOk(f: java.io.File) = java.io.File(remoteDir, f.name + ".ok")

    /** Keep the original compressed file too: one download, then instant local decodes even after PCM eviction. */
    private fun originalValid(item: LibItem): Boolean {
        val f = remoteFile(item.id)
        val ok = remoteOk(f)
        return f.exists() && f.length() > 4096 && ok.exists() &&
            (ok.readText().trim().toLongOrNull() ?: -1L) == f.length()
    }

    private fun prefetchOriginal(item: LibItem) {
        if (remoteCacheMb <= 0 || originalValid(item)) return
        viewModelScope.launch(Dispatchers.IO) { downloadOriginal(item) }
    }

    /** Download the compressed original; returns true when a valid local copy exists. */
    private suspend fun downloadOriginal(item: LibItem): Boolean {
        if (remoteCacheMb <= 0) return false
        if (originalValid(item)) return true
        val u = item.uri ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return isCached(item)
        val f = remoteFile(item.id)
        val ok = remoteOk(f)
        val tmp = java.io.File(remoteDir, f.name + ".tmp")
        return try {
            Http.requireSafe(u.toString())
            val c = java.net.URL(u.toString()).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity")
            item.streamHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) { c.disconnect(); return false }
            val expected = c.contentLengthLong
            if (expected > remoteCap) { c.disconnect(); return false }
            var written = 0L
            c.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > remoteCap) throw java.io.IOException("download exceeds the cache size")
                    out.write(buf, 0, n)
                }
            } }
            c.disconnect()
            if (expected > 0 && tmp.length() != expected) {
                android.util.Log.w("FLUX-CACHE", "partial download ${item.title}: ${tmp.length()} / $expected")
                tmp.delete(); return false
            }
            if (tmp.length() <= 4096) { tmp.delete(); return false }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) { tmp.delete(); return false }
            ok.writeText(f.length().toString())
            android.util.Log.i("FLUX-CACHE", "saved ${item.title} (${f.length() / 1024} KB)")
            withContext(Dispatchers.Main) { libRev++ }
            true
        } catch (e: Exception) {
            android.util.Log.w("FLUX-CACHE", "prefetch failed ${item.title}: ${e.message}")
            tmp.delete(); false
        }
    }

    /** Download the current Plex selection (collection-only or all music) for offline playback. */
    fun downloadPlexCache() {
        if (plexToken.isBlank()) { toast = "Connect Plex first"; return }
        if (remoteCacheMb <= 0) { toast = "Set Plex originals cache above 0 first"; return }
        if (bulkDownloading) return
        val items = plexItems.filter { it.sourceKind == "plex" }
        val todo = items.filter { !isCached(it) }
        if (todo.isEmpty()) { toast = "All ${items.size} tracks in this selection are already cached"; return }
        bulkJob?.cancel()
        bulkDownloading = true; bulkDone = 0; bulkTotal = todo.size; bulkFailed = 0
        toast = "${items.size - todo.size} already cached · downloading ${todo.size} Plex tracks…"
        bulkJob = viewModelScope.launch(Dispatchers.IO) {
            var used = (remoteDir.listFiles()?.sumOf { it.length() } ?: 0L)
            for (it in todo) {
                if (!isActive) break
                if (used >= remoteCap) { bulkFailed++; break }
                val ok = downloadOriginal(it)
                if (ok) used = (remoteDir.listFiles()?.sumOf { x -> x.length() } ?: used)
                else bulkFailed++
                bulkDone++
                if (bulkDone % 3 == 0) withContext(Dispatchers.Main) { libRev++ }
            }
            withContext(Dispatchers.Main) {
                bulkDownloading = false; libRev++
                toast = if (used >= remoteCap) "Plex download stopped: cache is full — raise “Plex / cloud originals” and retry"
                else "Plex offline cache: ${bulkDone - bulkFailed} downloaded, ${bulkFailed} failed"
            }
        }
    }

    fun cancelPlexDownload() { bulkJob?.cancel(); bulkJob = null; bulkDownloading = false; toast = "Plex download cancelled" }

    /** Cache one Plex/Drive track (compressed original when possible, otherwise decoded PCM). */
    fun cacheOne(item: LibItem) {
        if (isCached(item)) { uncacheOne(item); return }
        if (item.sourceKind != "plex" && item.sourceKind != "drive") return
        if (remoteCacheMb <= 0 && item.uri?.scheme in listOf("http", "https")) {
            toast = "Set “Plex / cloud originals” above 0 first"; return
        }
        toast = "Caching “${item.title}”…"
        viewModelScope.launch(Dispatchers.IO) {
            var ok = false
            try {
                val u = item.uri
                if (u != null && (u.scheme == "http" || u.scheme == "https")) {
                    ok = downloadOriginal(item)
                } else if (u != null) {
                    val t = Decoder.decode(ctx, u, item.id, item.title, item.artist, headers = item.streamHeaders)
                    if (t != null) { savePcm(t); ok = true }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                libRev++
                toast = if (ok) "“${item.title}” cached for offline" else "Couldn't cache “${item.title}”"
            }
        }
    }

    fun uncacheOne(item: LibItem) {
        pcmFile(item.id).delete()
        val r = remoteFile(item.id)
        r.delete(); remoteOk(r).delete()
        libRev++
        toast = "Removed the offline copy of “${item.title}”"
    }

    /** Raw 16-bit PCM straight from disk: a 4-minute track loads in a fraction of a second. */
    private fun loadPcm(item: LibItem): Track? {
        val f = pcmFile(item.id)
        if (!f.exists() || f.length() < SR * 4) return null
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val ch = raf.channel
                val arr = ShortArray((ch.size() / 2).toInt())
                ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(arr)
                f.setLastModified(System.currentTimeMillis())
                Track(item.id, item.title, item.artist, arr)
            }
        } catch (_: Exception) { f.delete(); null }
    }

    private fun savePcm(t: Track) {
        if (pcmCacheMb <= 0) return
        if (!t.complete) return
        viewModelScope.launch(Dispatchers.IO) {
            val f = pcmFile(t.id)
            if (f.exists()) return@launch
            val tmp = java.io.File(pcmDir, f.name + ".tmp")
            try {
                java.io.FileOutputStream(tmp).channel.use { ch ->
                    val bb = java.nio.ByteBuffer.allocateDirect(1 shl 20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val pcm = t.pcm; val n = t.frames * 2
                    var i = 0
                    while (i < n) {
                        bb.clear()
                        val m = minOf(n - i, bb.capacity() / 2)
                        bb.asShortBuffer().put(pcm, i, m)
                        bb.limit(m * 2)
                        while (bb.hasRemaining()) ch.write(bb)
                        i += m
                    }
                }
                if (!tmp.renameTo(f)) { tmp.delete(); return@launch }
                // evict least-recently-used beyond the cap
                val files = pcmDir.listFiles { x -> x.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return@launch
                var total = 0L
                for (x in files) { total += x.length(); if (total > pcmCap) x.delete() }
                withContext(Dispatchers.Main) { libRev++ }
            } catch (_: Exception) { tmp.delete() }
        }
    }

    // ------------------------------------------------------------------ waveform disk cache
    private fun waveFile(id: String): java.io.File =
        java.io.File(java.io.File(ctx.filesDir, "wave").apply { mkdirs() }, "${md5(id)}.bin")
    private fun hasWave(id: String) = waveFile(id).exists()

    private fun saveWave(t: Track, gain: Float) {
        val w = t.wave ?: return; val ov = t.overview ?: return
        try {
            val bb = java.nio.ByteBuffer.allocate(16 + w.size + ov.size)
            bb.putFloat(gain); bb.putFloat(t.rms); bb.putInt(w.size); bb.putInt(ov.size); bb.put(w); bb.put(ov)
            waveFile(t.id).writeBytes(bb.array())
        } catch (_: Exception) {}
    }

    private fun loadWave(t: Track): Boolean {
        val f = waveFile(t.id)
        if (!f.exists()) return false
        return try {
            val bb = java.nio.ByteBuffer.wrap(f.readBytes())
            val g = bb.float; val rms = bb.float; val wn = bb.int; val on = bb.int
            val w = ByteArray(wn); bb.get(w); val ov = ByteArray(on); bb.get(ov)
            // A wave cached from a short/streaming decode is worse than no wave: the tail is flat.
            val expectedBins = (t.frames / BIN).coerceAtLeast(1)
            if (w.size / 3 < expectedBins - 16 || ov.size < OV_COLS * 3 - 16) {
                android.util.Log.w("FLUX-WAVE", "discarding short waveform ${t.title}: ${w.size / 3} vs $expectedBins bins")
                f.delete(); return false
            }
            t.gain = g; t.rms = rms; t.wave = w; t.overview = ov
            true
        } catch (_: Exception) { false }
    }

    // ------------------------------------------------------------------ preview (headphones)
    fun togglePreview(item: LibItem) {
        if (previewId == item.id) { stopPreview(); return }
        val single = outMode == OutMode.SINGLE
        if (single && engine.decks.any { it.playing }) {
            toast = "Preview needs headphones on their own output — set OUT to “Two devices” or “Split cable”"
            return
        }
        engine.stopPreview()
        engine.previewToMaster = single
        previewId = item.id; previewLoading = true; previewFrac = 0f
        fun start(t: Track, from: Float) {
            if (previewId != item.id) return
            previewLoading = false
            if (item.demo < 0) previewCache = t
            engine.startPreview(t, from)
        }
        val known = when {
            item.demo >= 0 -> null
            previewCache?.id == item.id -> previewCache
            else -> engine.decks.firstOrNull { it.track?.id == item.id }?.track
        }
        if (known != null) { start(known, 0.3f); return }
        viewModelScope.launch(Dispatchers.Default) {
            if (item.demo >= 0) {
                val t = demoTrack(item.demo)
                withContext(Dispatchers.Main) { start(t, 0.3f) }
                return@launch
            }
            loadPcm(item)?.let { cached ->
                loadWave(cached)
                withContext(Dispatchers.Main) { start(cached, 0.3f) }
                return@launch
            }
            // stream: start hearing it ~1 s after tapping, while the rest decodes
            var streamed = false
            val (src, hdr) = mediaSource(item)
            val t = Decoder.decode(ctx, src, item.id, item.title, item.artist, onStream = { st ->
                streamed = true
                loadWave(st)
                viewModelScope.launch(Dispatchers.Main) { start(st, 0f) }
            }, headers = hdr)
            withContext(Dispatchers.Main) {
                if (t == null) {
                    if (previewId == item.id) { previewId = null; previewLoading = false }
                    toast = "Couldn't decode ${item.title}"
                } else {
                    savePcm(t)
                    if (!streamed) { loadWave(t); start(t, 0f) }
                }
            }
        }
    }

    fun stopPreview() {
        engine.stopPreview(); previewId = null; previewLoading = false
        previewCache = null   // release a full decoded track as soon as the preview ends
    }
    fun seekPreview(f: Float) = engine.previewSeek(f)
    fun previewTrack(): Track? = engine.previewTrack

    // ------------------------------------------------------------------ set history
    private val historyFile by lazy { java.io.File(ctx.filesDir, "history.tsv") }

    /** A track is "played" once it has been audible on the master for 5 s (fader + crossfader up). */
    private fun trackOnAir() {
        val now = System.nanoTime()
        val dt = if (lastPollNs == 0L) 0L else ((now - lastPollNs) / 1_000_000L).coerceAtMost(200)
        lastPollNs = now
        for (i in 0..1) {
            val d = engine.decks[i]
            val t = d.track ?: continue
            val gen = loadGen.get(i)
            val onAir = d.playing && !d.previewing && engine.chans[i].gain > 0.12f && engine.master > 0.02f
            if (!onAir || loggedLoad[i] == gen) { if (!onAir) onAirMs[i] = 0; continue }
            onAirMs[i] += dt
            if (onAirMs[i] >= 5000) {
                loggedLoad[i] = gen; onAirMs[i] = 0
                val e = HistEntry(System.currentTimeMillis(), i, t.title, t.artist, (t.bpm * d.effRatio).toFloat(), t.key)
                history = history + e
                viewModelScope.launch(Dispatchers.IO) {
                    try { historyFile.appendText("${e.time}\t${e.deck}\t${e.title.replace('\t', ' ')}\t${e.artist.replace('\t', ' ')}\t${e.bpm}\t${e.key}\n") } catch (_: Exception) {}
                }
            }
        }
    }

    private fun loadHistory() {
        history = try {
            if (!historyFile.exists()) emptyList() else historyFile.readLines().mapNotNull { l ->
                val p = l.split("\t")
                if (p.size < 6) null else HistEntry(p[0].toLong(), p[1].toInt(), p[2], p[3], p[4].toFloatOrNull() ?: 0f, p[5].toIntOrNull() ?: -1)
            }
        } catch (_: Exception) {
            try { historyFile.renameTo(java.io.File(historyFile.parentFile, historyFile.name + ".bad")) } catch (_: Exception) {}
            emptyList()
        }
    }

    /** Sessions = runs of entries without a 90-minute gap, newest session first. */
    fun historySessions(): List<List<HistEntry>> {
        val out = ArrayList<MutableList<HistEntry>>()
        for (e in history) {
            if (out.isEmpty() || e.time - out.last().last().time > 90 * 60_000L) out.add(mutableListOf())
            out.last().add(e)
        }
        return out.reversed()
    }

    fun tracklistText(session: List<HistEntry>): String {
        val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val df = java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault())
        val head = "FLUX set · ${df.format(java.util.Date(session.first().time))} · ${tf.format(java.util.Date(session.first().time))}–${tf.format(java.util.Date(session.last().time))}"
        val lines = session.mapIndexed { n, e ->
            "${n + 1}. ${tf.format(java.util.Date(e.time))}  [${deckName(e.deck)}]  ${e.title}${if (e.artist.isNotEmpty()) " — ${e.artist}" else ""}  (${String.format("%.0f", e.bpm)} BPM${if (e.key >= 0) " · ${Keys.camelot(e.key)}" else ""})"
        }
        return (listOf(head, "") + lines).joinToString("\n")
    }

    var confirmClearHistory by mutableStateOf(false)
    fun clearHistory() { history = emptyList(); confirmClearHistory = false; viewModelScope.launch(Dispatchers.IO) { historyFile.delete() } }

    // ------------------------------------------------------------------ library intelligence
    /** Analysis format version; "u" marks a grid the user edited by hand (never re-analysed). */
    private val metaVersion = "2"

    /**
     * Field 7 is the source: "2" = FLUX analysis, "u:<time>" = edited by hand in FLUX, "rb:<time>" = rekordbox import.
     * Hand edits and rekordbox data are never overwritten by automatic analysis.
     */
    private fun saveMeta(id: String, t: Track, fbValid: Boolean, user: Boolean = false, source: String? = null) {
        val src = source ?: if (user) "u:${System.currentTimeMillis()}" else metaVersion
        val ed = cache.edit().putString(id, "${t.bpm}|${t.key}|${if (fbValid) t.firstBeat else -1.0}|${t.energy}|${t.pattern}|${t.vibe}|$src")
        val map = t.tempoMapString()
        if (map != null) ed.putString("tmap:$id", map) else if (user || source != null) ed.remove("tmap:$id")
        ed.apply()
    }

    /** true when the grid/key came from a hand edit or rekordbox (never replace it with automatic analysis). */
    private fun protectedMeta(p: List<String>?): Boolean = p?.getOrNull(6)?.let { it.startsWith("u") || it.startsWith("rb") } == true

    /** Keep the protected grid/key/map, update only the automatic fields (energy, pattern, vibe). */
    private fun saveProfileOnly(id: String, t: Track) {
        val p = metaParts(id) ?: return
        cache.edit().putString(id, listOf(p[0], p[1], p[2], "${t.energy}", t.pattern, t.vibe, p[6]).joinToString("|")).apply()
    }

    private fun metaParts(id: String): List<String>? = cache.getString(id, null)?.split("|")
    private fun metaCurrent(p: List<String>?): Boolean = p != null && p.size >= 7 && (p[6] == metaVersion || protectedMeta(p))

    private fun applyMeta(it: LibItem): Boolean {
        val p = cache.getString(it.id, null)?.split("|") ?: return false
        if (p.size < 6) return false
        it.bpm = p[0].toFloatOrNull() ?: 0f; it.key = p[1].toIntOrNull() ?: -1
        it.energy = p[3].toIntOrNull() ?: 0; it.pattern = p[4]; it.vibe = p[5]
        it.analysisSource = when {
            p.getOrNull(6)?.startsWith("u") == true -> "manual"
            p.getOrNull(6)?.startsWith("rb") == true -> "rekordbox"
            p.getOrNull(6) == metaVersion -> "flux"
            else -> ""
        }
        return true
    }

    fun startIndexing() {
        indexJob?.cancel()
        // Cloud tracks are analysed lazily when first loaded: indexing a whole Plex/Drive library
        // would download every original file. Local files still get the full background pass.
        val pool = imported.filter { it.sourceKind != "drive" } + deviceTracks
        // Tracks covered by collection.xml are not analysed at all: their grid/key comes from the
        // cloud file and their waveform is built on first load, so a big rekordbox import costs
        // nothing in the background.
        val todo = pool.filter { it.uri != null && it.energy >= 0 && (it.energy == 0 || !hasWave(it.id)) && !protectedMeta(metaParts(it.id)) }
        val stale = pool.filter { it.uri != null && it.energy > 0 && hasWave(it.id) && !metaCurrent(metaParts(it.id)) }
        indexTotal = pool.size; indexDone = pool.size - todo.size
        indexSkipped = pool.count { protectedMeta(metaParts(it.id)) }
        if (todo.isEmpty() && stale.isEmpty()) return
        indexJob = viewModelScope.launch(indexDispatcher) {
            // older analyses: re-run the (better) beat detection from the cached waveform — no decoding needed
            for (it in stale) {
                while (indexPaused || activeLoads.get() > 0) delay(300)
                try { rebeat(it) } catch (_: Exception) {}
            }
            if (stale.isNotEmpty()) libRev++
            for (it in todo) {
                while (indexPaused || activeLoads.get() > 0) delay(300)
                try { indexOne(it) } catch (_: Exception) { it.energy = -1 }
                indexDone++
                if (indexDone % 2 == 0) libRev++
            }
            libRev++
        }
    }

    private fun indexOne(it: LibItem) {
        // whole track, once: afterwards it loads with waveform, grid, key and gain ready instantly
        val t = Decoder.decode(ctx, it.uri!!, it.id, it.title, it.artist, headers = it.streamHeaders)
        if (t == null) {
            // Retry a failed file a bounded number of times instead of re-decoding it forever.
            val fails = cache.getInt("fail:${it.id}", 0) + 1
            val done = fails >= 3
            it.energy = if (done) -1 else 0
            cache.edit().putInt("fail:${it.id}", fails)
                .putString(it.id, "0|-1|-1.0|${if (done) -1 else 0}|-|-|$metaVersion").apply()
            return
        }
        cache.edit().remove("fail:${it.id}").apply()
        Analyzer.waveform(t); saveWave(t, t.gain)
        val prot = metaParts(it.id)?.takeIf { p -> protectedMeta(p) }
        if (prot != null) {
            // rekordbox / hand-edited grid and key stay; only the profile is filled in
            t.bpm = prot[0].toFloat(); t.key = prot[1].toInt()
            Analyzer.profile(t)
            it.energy = t.energy; it.pattern = t.pattern; it.vibe = t.vibe
            saveProfileOnly(it.id, t)
            applyMeta(it)
            return
        }
        Analyzer.rhythmAndKey(t); Analyzer.profile(t)
        it.bpm = t.bpm; it.key = t.key; it.energy = t.energy; it.pattern = t.pattern; it.vibe = t.vibe
        it.analysisSource = "flux"
        saveMeta(it.id, t, true)
    }

    private fun rebeat(it: LibItem) {
        val p = metaParts(it.id) ?: return
        if (protectedMeta(p)) return
        val t = Track(it.id, it.title, it.artist, ShortArray(4))
        if (!loadWave(t)) return
        Analyzer.rebeat(t)
        t.key = p[1].toIntOrNull() ?: -1; t.energy = p[3].toIntOrNull() ?: 0; t.pattern = p[4]; t.vibe = p[5]
        saveMeta(it.id, t, true)
        it.bpm = t.bpm
        it.analysisSource = "flux"
    }

    fun toggleIndexPause() { indexPaused = !indexPaused }

    fun refMeta(forDeck: Int): RefMeta? {
        val t = when {
            forDeck in 0..1 -> engine.decks[1 - forDeck].track
            else -> engine.decks[engine.leader()].track
        } ?: return null
        if (t.bpm <= 0) return null
        return RefMeta(t.bpm, t.key, t.energy, t.vibe, t.pattern, t.title)
    }

    /** Lower is better. null = not analysed yet. */
    fun relevance(it: LibItem, r: RefMeta): Float? {
        if (it.bpm <= 0f) return null
        val kd = Keys.distance(it.key, r.key)
        val kp = when (kd) { 0 -> 0f; 1 -> 2f; 2 -> 6f; 9 -> 30f; else -> 20f }
        val bd = tempoDiffPct(it.bpm, r.bpm)
        val bp = bd * 2.5f + (if (bd > 8f) 40f else 0f)
        val ed = if (it.energy > 0 && r.energy > 0) it.energy - r.energy else 0
        val ep = if (ed in 0..2) 0f else abs(ed).toFloat()
        val bonus = (if (it.pattern.isNotEmpty() && it.pattern == r.pattern) 2f else 0f) + (if (it.vibe.isNotEmpty() && it.vibe == r.vibe) 1f else 0f)
        return kp + bp + ep - bonus
    }

    /** Tempo distance in %, treating half/double time as a match (e.g. 87 ↔ 174). */
    fun tempoDiffPct(a: Float, b: Float): Float =
        minOf(abs(a / b - 1f), abs(a * 2f / b - 1f) * 1.1f, abs(a / 2f / b - 1f) * 1.1f) * 100f

    fun reasons(it: LibItem, r: RefMeta): String {
        val parts = ArrayList<String>()
        when (Keys.distance(it.key, r.key)) { 0 -> parts.add("same key"); 1 -> parts.add("harmonic"); 2 -> parts.add("energy lift") }
        val d = (it.bpm / r.bpm - 1f) * 100f
        parts.add(String.format("%+.1f%% tempo", d))
        if (it.energy > 0 && r.energy > 0 && it.energy - r.energy in 1..2) parts.add("builds energy")
        if (it.vibe.isNotEmpty() && it.vibe == r.vibe) parts.add("same vibe")
        return parts.joinToString(" · ")
    }

    private fun allItems(): List<LibItem> =
        (demos + imported + plexItems + deviceTracks).distinctBy { it.id }
    /** Everything the user hasn't removed from the library. */
    private fun visibleItems(): List<LibItem> = allItems().filter { it.id !in hidden }
    val hiddenCount: Int get() = allItems().count { it.id in hidden }

    fun askRemove(item: LibItem) { pendingRemove = item }
    fun cancelRemove() { pendingRemove = null }
    /** Hides the track from FLUX — the file itself is never touched. */
    fun confirmRemove() {
        val it = pendingRemove ?: return
        pendingRemove = null
        hidden = hidden + it.id
        sp.edit().putStringSet("hidden", hidden).apply()
        libRev++
        toast = "Removed “${it.title}” from your library"
    }
    fun restore(item: LibItem) {
        hidden = hidden - item.id
        sp.edit().putStringSet("hidden", hidden).apply()
        if (hiddenCount == 0) showHidden = false
        libRev++
        toast = "Restored “${item.title}”"
    }

    private fun loadedIds(): Set<String> = engine.decks.mapNotNull { it.track?.id }.toSet()

    fun suggestions(target: Int, n: Int = 4): List<LibItem> {
        val r = refMeta(target) ?: return emptyList()
        val skip = loadedIds()
        return visibleItems().asSequence().filter { it.id !in skip }.mapNotNull { i -> relevance(i, r)?.let { i to it } }
            .filter { tempoDiffPct(it.first.bpm, r.bpm) <= 10f }
            .sortedBy { it.second }.take(n).map { it.first }.toList()
    }

    fun idleDeck(): Int = when {
        engine.decks[0].playing && !engine.decks[1].playing -> 1
        engine.decks[1].playing && !engine.decks[0].playing -> 0
        else -> 1 - engine.leader()
    }

    /**
     * Ranking the whole library is O(n log n); it must never run in the 75 Hz poll on the main
     * thread. Coalesce requests and publish only a result that still matches the library revision.
     */
    private fun scheduleNext() {
        if (nextJob?.isActive == true) return
        val tgt = idleDeck()
        val r = refMeta(tgt) ?: run { nextSuggestion = null; nextReason = ""; return }
        val src = filterSource
        val rev = libRev
        nextJob = viewModelScope.launch(Dispatchers.Default) {
            val s = suggestions(tgt, 6).firstOrNull { src == null || sourceOf(it) == src }
            val reason = if (s != null) reasons(s, r) else ""
            withContext(Dispatchers.Main) {
                if (rev == libRev) { nextSuggestion = s; nextReason = reason }
            }
        }
    }



    fun loadNext() { nextSuggestion?.let { load(idleDeck(), it) } }

    /** Filtered + sorted list for the library panel. */
    fun filteredLibrary(target: Int, query: String): List<LibItem> {
        val ref = refMeta(target)
        val pl = playlist
        val base = when {
            showHidden -> allItems().filter { it.id in hidden }
            pl != null -> { val byId = visibleItems().associateBy { it.id }; pl.trackIds.mapNotNull { rbMatch[it] }.mapNotNull { byId[it] } }
            else -> visibleItems()
        }
        var list = base.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
        if (filterSource != null) list = list.filter { sourceOf(it) == filterSource }
        if (filterVibe != null) list = list.filter { it.vibe == filterVibe }
        if (filterEnergy != 0) list = list.filter { when (filterEnergy) { 1 -> it.energy in 1..3; 2 -> it.energy in 4..7; else -> it.energy >= 8 } }
        if (ref != null) {
            if (filterBpm != 0) list = list.filter { it.bpm > 0 && tempoDiffPct(it.bpm, ref.bpm) <= filterBpm }
            if (filterKey == 1) list = list.filter { Keys.distance(it.key, ref.key) in 0..2 }
        }
        return when (sortMode) {
            7 -> list                                   // playlist order
            1 -> list.sortedByDescending { it.added }
            2 -> list.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            3 -> list.sortedWith(compareBy({ it.artist.ifEmpty { "\uFFFF" }.lowercase(java.util.Locale.ROOT) }, { it.title.lowercase(java.util.Locale.ROOT) }))
            4 -> list.sortedBy { if (it.bpm <= 0) 999f else it.bpm }
            5 -> list.sortedBy { if (it.key < 0) 99 else (Keys.camelot(it.key).dropLast(1).toInt() * 2 + (if (it.key >= 12) 0 else 1)) }
            6 -> list.sortedByDescending { it.energy }
            // already on a deck → bottom; the rest by how well it follows the other deck
            else -> if (ref != null) {
                // decorate once: relevance() is O(1) but the comparator runs O(n log n) times
                val loaded = loadedIds()
                list.map { it to (relevance(it, ref) ?: 500f) }
                    .sortedWith(compareBy({ it.first.id in loaded }, { it.second }))
                    .map { it.first }
            } else list.sortedByDescending { it.added }
        }
    }

    /** Number of active filters (for the collapsed "Filters" button). */
    val activeFilters: Int get() = listOf(filterKey != 0, filterBpm != 0, filterEnergy != 0, filterVibe != null, filterSource != null).count { it }

    /** Where a track comes from, for the source filter. */
    fun sourceOf(it: LibItem): String = when (it.sourceKind) {
        "plex" -> "plex"
        "drive" -> "drive"
        else -> "local"
    }

    val hasLocal: Boolean get() = demos.isNotEmpty() || imported.any { sourceOf(it) == "local" } || deviceTracks.isNotEmpty()
    val hasDrive: Boolean get() = imported.any { it.sourceKind == "drive" }
    val hasPlex: Boolean get() = plexItems.isNotEmpty()

    val vibesPresent: List<String> get() = visibleItems().map { it.vibe }.filter { it.isNotEmpty() && it != "-" }.distinct().sorted()


    // ------------------------------------------------------------------ importing & loading
    private fun loadImports() {
        val lines = (sp.getString("imports", "") ?: "").split("\n").filter { it.contains("\t") }
        imported = lines.map { l ->
            val p = l.split("\t", limit = 3)
            val uri = Uri.parse(p[0])
            val kind = if (p[0].startsWith("gdrive:")) "drive" else "import"
            val u = if (p[0].startsWith("gdrive:")) Uri.parse(p[0].removePrefix("gdrive:")) else uri
            LibItem(p[0], p[1], "", 0, u, -1, added = p.getOrNull(2)?.toLongOrNull() ?: 0, fileStem = p[1], sourceKind = kind).also { applyMeta(it) }
        }
    }

    private fun saveImports() {
        sp.edit().putString("imports", imported.joinToString("\n") { "${if (it.sourceKind == "drive") "gdrive:" else ""}${it.uri}\t${it.title}\t${it.added}" }).apply()
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track"
        try { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.') } } catch (_: Exception) {}
        return name
    }

    fun addFiles(uris: List<Uri>) {
        val have = imported.map { it.uri.toString() }.toSet()
        val fresh = uris.filter { it.toString() !in have }.map { displayName(it).let { n -> LibItem("imp:$it", n, "", 0, it, -1, added = System.currentTimeMillis(), fileStem = n, sourceKind = "import") }.also { i -> applyMeta(i) } }
        if (fresh.isEmpty()) { toast = "Already in your library"; return }
        imported = imported + fresh; saveImports(); libRev++; rbRematch(); startIndexing()
        toast = "Added ${fresh.size} track${if (fresh.size > 1) "s" else ""} · analysing in the background"
    }

    fun addFolder(tree: Uri) { scanFolder(tree, cloud = false, announce = true) }

    /** Google Drive (or any document provider) as a live source: audio streams, and collection.xml is picked up automatically. */
    fun connectCloudFolder(tree: Uri) {
        cloudFolder = tree.toString()
        cloudFolderName = folderName(tree)
        sp.edit().putString("cloud_tree", cloudFolder).putString("cloud_tree_name", cloudFolderName).apply()
        scanFolder(tree, cloud = true, announce = true)
    }

    fun disconnectCloudFolder() {
        scanGen.incrementAndGet()
        cloudFolder = ""; cloudFolderName = ""
        sp.edit().remove("cloud_tree").remove("cloud_tree_name").apply()
        imported = imported.filter { it.sourceKind != "drive" }
        saveImports(); libRev++
        toast = "Cloud folder disconnected — its tracks stay out of the library"
    }

    fun resyncCloudFolder() {
        val u = cloudFolder.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) } ?: return
        scanFolder(u, cloud = true, announce = true)
    }

    private fun folderName(tree: Uri): String {
        var name = "Cloud folder"
        try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
            ctx.contentResolver.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        } catch (_: Exception) {}
        return name
    }

    private fun scanFolder(tree: Uri, cloud: Boolean, announce: Boolean) {
        if (cloud && cloudScanning) return
        val scan = scanGen.incrementAndGet()
        if (cloud) cloudScanning = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = ArrayList<Pair<Uri, String>>()
            var xmlUri: Uri? = null
            fun walk(docId: String) {
                if (found.size >= 3000) return
                val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                try {
                    ctx.contentResolver.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0); val name = c.getString(1) ?: "Track"; val mime = c.getString(2) ?: ""
                            if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id)
                            else if (mime.startsWith("audio/") || AUDIO_EXT.any { name.lowercase(java.util.Locale.ROOT).endsWith(it) }) found.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id) to name.substringBeforeLast('.'))
                            else if (name.equals("collection.xml", true) || name.lowercase(java.util.Locale.ROOT).endsWith(".xml") && name.lowercase(java.util.Locale.ROOT).contains("collection")) {
                                xmlUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            walk(android.provider.DocumentsContract.getTreeDocumentId(tree))

            // collection.xml in the same folder is authoritative; read it before touching UI state.
            var lib: RbLibrary? = null
            xmlUri?.let { lib = readRekordbox(it) }

            if (scanGen.get() != scan) return@launch
            withContext(Dispatchers.Main) {
                if (scanGen.get() != scan) return@withContext
                if (cloud) cloudScanning = false
                if (lib != null && lib!!.tracks.isNotEmpty()) {
                    rbSource = if (cloud) "$cloudFolderName / collection.xml" else displayName(xmlUri!!) + ".xml"
                    sp.edit().putString("rb_source", rbSource).apply()
                    applyRbLibrary(lib!!)
                }
                val have = imported.map { it.uri.toString() }.toSet()
                val fresh = found.filter { it.first.toString() !in have }.map { (u, n) ->
                    val prefix = if (cloud) "gdrive:" else "imp:"
                    LibItem("$prefix$u", n, "", 0, u, -1, added = System.currentTimeMillis(), fileStem = n, sourceKind = if (cloud) "drive" else "import").also { applyMeta(it) }
                }
                if (found.isEmpty()) {
                    if (announce) toast = "No audio files found in that folder"
                } else {
                    imported = imported + fresh; saveImports(); libRev++; rbRematch(); startIndexing()
                    if (announce) toast = buildString {
                        append("Added ${fresh.size} cloud track${if (fresh.size != 1) "s" else ""}")
                        if (lib != null) append(" · collection.xml matched ${rbMatch.size}")
                        append(" · analysing…")
                    }
                }
            }
        }
    }

    fun deckName(i: Int) = if (i == 0) "A" else "B"

    // ------------------------------------------------------------------ Plex / Plexamp
    fun plexSignIn() {
        if (plexBusy) return
        if (plexToken == "null") plexToken = ""
        plexError = null; plexBusy = true; plexPinCode = null; plexStatus = "Contacting plex.tv…"
        viewModelScope.launch(Dispatchers.IO) {
            val pin = try { plexClient.requestPin() } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (pin == null) {
                    plexBusy = false; plexError = "Couldn't reach plex.tv. Check the internet connection."
                } else {
                    applyPin(pin)
                    pollPlexPin(pin)
                }
            }
        }
    }

    private fun applyPin(pin: PlexPin) {
        plexPinCode = pin.code
        plexPinExpiresAt = System.currentTimeMillis() + pin.expiresInSec * 1000L
        plexStatus = "Enter the code at app.plex.tv/link"
    }

    /**
     * Poll the PIN until the user links it. Plex PINs expire, so the code is refreshed in place a
     * few seconds before it dies — the screen always shows a live code, no manual restart.
     */
    private fun pollPlexPin(initial: PlexPin) {
        plexPoll?.cancel()
        plexPoll = viewModelScope.launch(Dispatchers.IO) {
            var pin = initial
            var expiresAt = System.currentTimeMillis() + pin.expiresInSec * 1000L
            var failures = 0
            repeat(1080) {
                delay(2500)
                val now = System.currentTimeMillis()
                if (now >= expiresAt - 30_000) {
                    val next = try { plexClient.requestPin() } catch (_: Exception) { null }
                    if (next != null) {
                        pin = next
                        expiresAt = System.currentTimeMillis() + next.expiresInSec * 1000L
                        failures = 0
                        withContext(Dispatchers.Main) { applyPin(next) }
                    } else {
                        withContext(Dispatchers.Main) { plexStatus = "Couldn't refresh the code — retrying…" }
                    }
                    return@repeat
                }
                val token = try { plexClient.pollPin(pin.id) } catch (_: Exception) { failures++; null }
                if (!token.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        plexToken = token; sp.edit().putString("plex_token", token).apply()
                        plexPinCode = null; plexPinExpiresAt = 0L
                        plexBusy = false
                        plexStatus = "Signed in — finding your server…"
                        plexRefresh()
                    }
                    return@launch
                }
                if (failures >= 3) {
                    failures = 0
                    val next = try { plexClient.requestPin() } catch (_: Exception) { null }
                    if (next != null) {
                        pin = next
                        expiresAt = System.currentTimeMillis() + next.expiresInSec * 1000L
                        withContext(Dispatchers.Main) { applyPin(next) }
                    }
                }
            }
            withContext(Dispatchers.Main) { plexBusy = false; plexError = "Sign-in timed out — try again." }
        }
    }

    /** Pull every music library from the Plex server and make the tracks streamable in FLUX. */
    fun plexRefresh() {
        if (plexToken.isBlank()) { plexSignIn(); return }
        if (plexBusy) return
        val gen = plexGen.incrementAndGet()
        plexBusy = true; plexError = null; plexStatus = "Syncing Plex…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var server = plexServer
                var name = plexServerName
                if (server.isBlank() || !plexClient.reachable(server, plexToken)) {
                    val s = plexClient.workingServer(plexToken)
                        ?: throw IllegalStateException("No reachable Plex server found for this account")
                    server = s.uri; name = s.name
                }
                plexServer = server; plexServerName = name
                sp.edit().putString("plex_server", server).putString("plex_server_name", name).apply()
                val sections = plexClient.sections(server, plexToken)
                val knownAdded = plexItems.associate { it.id to it.added }
                val all = ArrayList<LibItem>()
                for (s in sections) {
                    val tracks = plexClient.tracks(server, plexToken, s.key)
                    for (t in tracks) all.add(LibItem(
                        t.id, t.title, t.artist, t.durMs, Uri.parse(t.url), -1,
                        added = knownAdded[t.id] ?: System.currentTimeMillis(), fileStem = t.fileStem, sourceKind = "plex",
                        streamHeaders = mapOf("X-Plex-Token" to plexToken),
                    ).also { applyMeta(it) })
                }
                plexAllCache = all
                val filtered = filterPlex(all)
                if (plexGen.get() != gen) return@launch
                withContext(Dispatchers.Main) {
                    if (plexGen.get() != gen) return@withContext
                    plexItems = filtered
                    plexBusy = false
                    plexStatus = "$name · ${filtered.size} of ${all.size} tracks"
                    savePlex(); rbRematch(); libRev++; startIndexing()
                    toast = if (plexCollectionOnly && rb != null)
                        "Plex: ${filtered.size} tracks that match your collection.xml (${all.size} in the music libraries)"
                    else "Plex: ${filtered.size} tracks from “$name” are in the library"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    plexBusy = false
                    plexStatus = "Sync failed"
                    plexError = e.message ?: "Plex sync failed"
                }
            }
        }
    }

    /** app.plex.tv sign-in URL that links the current PIN in one tap. */
    fun plexAuthUrl(): String? = plexPinCode?.let { plexClient.authUrl(it) }

    fun setPlexFilter(collectionOnly: Boolean) {
        plexCollectionOnly = collectionOnly
        sp.edit().putBoolean("plex_collection_only", collectionOnly).apply()
        if (plexAllCache.isNotEmpty()) {
            plexItems = filterPlex(plexAllCache)
            plexStatus = "${plexItems.size} of ${plexAllCache.size} tracks"
            savePlex(); rbRematch(); libRev++
        } else if (plexToken.isNotBlank()) plexRefresh()
        toast = if (collectionOnly) "Plex: showing only tracks that match your collection.xml" else "Plex: showing all music (audiobook libraries are excluded)"
    }

    /** Collection-only mode is the DJ-friendly default; audiobook/spoken-word sections are always skipped. */
    private fun filterPlex(all: List<LibItem>): List<LibItem> {
        if (!plexCollectionOnly) return all
        val lib = rb ?: return all
        val cands = all.map { Rekordbox.Candidate(it.id, it.title, it.artist, (it.durMs / 1000).toInt(), it.fileStem) }
        val matched = Rekordbox.match(lib, cands).values.toSet()
        return all.filter { it.id in matched }
    }

    private fun refilterPlex() {
        if (plexAllCache.isNotEmpty()) {
            plexItems = filterPlex(plexAllCache)
            savePlex(); libRev++
        }
    }

    /** Close the sign-in sheet: stop polling so no orphan PIN refresh continues in the background. */
    fun stopPlexSignIn() {
        if (plexToken.isBlank()) {
            plexPoll?.cancel(); plexPoll = null
            plexBusy = false; plexPinCode = null; plexPinExpiresAt = 0L; plexStatus = ""
        }
    }

    fun plexSignOut() {
        bulkJob?.cancel(); bulkDownloading = false
        plexPoll?.cancel(); plexPoll = null
        plexToken = ""; plexServer = ""; plexServerName = ""; plexItems = emptyList(); plexPinCode = null
        plexPinExpiresAt = 0L; plexAllCache = emptyList()
        plexStatus = ""; plexError = null; plexBusy = false
        sp.edit().remove("plex_token").remove("plex_server").remove("plex_server_name").apply()
        plexFile.delete(); libRev++
        toast = "Signed out of Plex"
    }

    private fun savePlex() {
        try {
            plexFile.writeText(plexItems.joinToString("\n") { i ->
                listOf(i.id, i.title, i.artist, i.durMs.toString(), i.uri.toString(), i.fileStem).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            })
        } catch (_: Exception) {}
    }

    private fun loadPlex() {
        plexItems = try {
            if (!plexFile.exists()) emptyList() else plexFile.readLines().mapNotNull { l ->
                val p = l.split("\t")
                if (p.size < 6) null else LibItem(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0, Uri.parse(p[4]), -1,
                    fileStem = p[5], sourceKind = "plex", streamHeaders = mapOf("X-Plex-Token" to plexToken)).also { applyMeta(it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ------------------------------------------------------------------ rekordbox XML
    var rb by mutableStateOf<RbLibrary?>(null)
    /** rekordbox TrackID → FLUX library id */
    var rbMatch by mutableStateOf<Map<String, String>>(emptyMap())
    var playlist by mutableStateOf<RbPlaylist?>(null)
    var rbReport by mutableStateOf<RbReport?>(null)
    private val rbFile by lazy { java.io.File(ctx.filesDir, "rekordbox.xml") }
    private var rbImportTime: Long = sp.getLong("rb_import_time", 0L)

    class RbReport(val total: Int, val matched: Int, val playlists: Int, val variable: Int, val hotCues: Int, val missing: List<String>,
               val duplicateTracks: Int = 0, val duplicateCues: Int = 0)

    fun selectPlaylist(p: RbPlaylist?) { playlist = p; sortMode = if (p != null) 7 else 0; libRev++ }
    fun playlistOnTablet(p: RbPlaylist): Int = p.trackIds.count { rbMatch[it]?.let { id -> id !in hidden } == true }

    private fun loadRekordbox() {
        if (!rbFile.exists()) return
        viewModelScope.launch(Dispatchers.IO) {
            val lib = try { rbFile.inputStream().use { Rekordbox.parse(it) } } catch (_: Exception) { null }
            withContext(Dispatchers.Main) { rb = lib; rbRematch() }
        }
    }

    /** Called when the user picks a collection.xml: keep a copy, match, and apply everything. */
    fun importRekordbox(uri: Uri) {
        toast = "Reading rekordbox collection…"
        viewModelScope.launch(Dispatchers.IO) {
            val lib = readRekordbox(uri)
            withContext(Dispatchers.Main) {
                if (lib == null || lib.tracks.isEmpty()) { toast = "That doesn't look like a rekordbox collection.xml"; return@withContext }
                rbSource = displayName(uri) + ".xml"
                sp.edit().putString("rb_source", rbSource).apply()
                applyRbLibrary(lib)
            }
        }
    }

    /** Copy + parse a collection.xml from any document provider, without touching UI state. */
    private fun readRekordbox(uri: Uri): RbLibrary? = try {
        val tmp = java.io.File(rbFile.parentFile, rbFile.name + ".tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_XML_BYTES) throw java.io.IOException("collection.xml is too large")
                    out.write(buf, 0, n)
                }
            }
        }
        val lib = tmp.inputStream().use { Rekordbox.parse(it) }
        if (lib.tracks.isEmpty()) { tmp.delete(); null }
        else { if (rbFile.exists()) rbFile.delete(); tmp.renameTo(rbFile); lib }
    } catch (_: Exception) { null }

    private fun applyRbLibrary(lib: RbLibrary) {
        rbImportTime = System.currentTimeMillis(); sp.edit().putLong("rb_import_time", rbImportTime).apply()
        rb = lib
        rbRematch()
        refilterPlex()
        val missing = lib.tracks.values.filter { it.id !in rbMatch }.map { t -> if (t.artist.isNotEmpty()) "${t.artist} – ${t.name}" else t.name }
        rbReport = RbReport(lib.tracks.size, rbMatch.size, lib.playlists.size,
            lib.tracks.values.count { t -> t.id in rbMatch && t.tempoBpms.toSet().size > 1 },
            lib.tracks.values.filter { it.id in rbMatch }.sumOf { it.hotCues.size }, missing,
            lib.duplicateTracks, lib.duplicateCues)
    }

    fun removeRekordbox() {
        rbFile.delete(); rb = null; rbMatch = emptyMap(); playlist = null; rbSource = ""
        sp.edit().remove("rb_source").apply()
        allItems().forEach { it.fromRb = false; it.analysisSource = ""; applyMeta(it) }
        libRev++; toast = "rekordbox playlists removed (grids and cues already imported stay)"
    }

    /** Match the collection against the library and apply rekordbox data to newly matched tracks. */
    fun rbRematch() {
        val lib = rb ?: return
        val items = (imported + plexItems + deviceTracks)
        val cands = items.map { Rekordbox.Candidate(it.id, it.title, it.artist, (it.durMs / 1000).toInt(), it.fileStem) }
        val m = Rekordbox.match(lib, cands)
        rbMatch = m
        val byId = items.associateBy { it.id }
        val ed = cache.edit()
        for ((rbId, libId) in m) {
            val item = byId[libId] ?: continue
            item.fromRb = true
            // already applied — unless its grid was lost since (self-heal), then apply again
            if (cache.getLong("rbApplied:$libId", 0L) == rbImportTime && protectedMeta(metaParts(libId))) { applyMeta(item); continue }
            applyRb(lib.tracks[rbId]!!, item)
            ed.putLong("rbApplied:$libId", rbImportTime)
        }
        ed.apply()
        libRev++
    }

    /** Write rekordbox's grid, key and cues into FLUX's stores (and onto a deck if the track is loaded). */
    private fun applyRb(r: RbTrack, item: LibItem) {
        val old = metaParts(item.id)
        // A track whose Plex/MediaStore duration is unknown still knows its real length from rekordbox.
        if (item.durMs <= 0 && r.totalSec > 0) item.durMs = r.totalSec * 1000L
        // A hand-edited BPM/grid is sacred: cloud/collection data never replaces it.
        val handEdited = old?.getOrNull(6)?.startsWith("u") == true
        val t = Track(item.id, item.title, item.artist, ShortArray(4))
        if (!handEdited && r.tempoSecs.isNotEmpty()) {
            if (r.tempoSecs.size == 1) {
                t.bpm = r.tempoBpms[0].toFloat()
                t.firstBeat = r.tempoSecs[0] * SR - (r.firstBattito - 1).coerceIn(0, 3) * t.spb
                val bar = t.spb * 4; t.firstBeat = ((t.firstBeat % bar) + bar) % bar
            } else t.setTempoMap(r.tempoSecs, r.tempoBpms, r.firstBattito)
            t.key = if (r.key >= 0) r.key else old?.getOrNull(1)?.toIntOrNull() ?: -1
            t.energy = old?.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            t.pattern = old?.getOrNull(4)?.takeIf { it != "-" } ?: ""; t.vibe = old?.getOrNull(5)?.takeIf { it != "-" } ?: ""
            saveMeta(item.id, t, true, source = "rb:$rbImportTime")
            item.bpm = t.bpm; item.key = t.key
        } else if (!handEdited && r.bpm > 0f) {
            // Some exports carry AverageBpm but no TEMPO markers: still let the collection win on BPM.
            t.bpm = r.bpm
            t.firstBeat = old?.getOrNull(2)?.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
            t.key = if (r.key >= 0) r.key else old?.getOrNull(1)?.toIntOrNull() ?: -1
            t.energy = old?.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            t.pattern = old?.getOrNull(4)?.takeIf { it != "-" } ?: ""; t.vibe = old?.getOrNull(5)?.takeIf { it != "-" } ?: ""
            saveMeta(item.id, t, true, source = "rb:$rbImportTime")
            item.bpm = t.bpm; item.key = t.key
        } else if (r.key >= 0 && !handEdited && old != null) {
            cache.edit().putString(item.id, old.toMutableList().also { it[1] = "${r.key}" }.joinToString("|")).apply()
            item.key = r.key
        }
        // Cues: rekordbox fills an untouched track, but a hand-edited grid is treated as the
        // user's own edit and keeps their cues too. Main cue = first memory cue, else beat 1.
        if (!handEdited && (r.hotCues.isNotEmpty() || r.memoryCues.isNotEmpty())) {
            val main = r.memoryCues.firstOrNull()?.times(SR) ?: (if (t.bpm > 0) t.beatPos(0.0).coerceAtLeast(0.0) else 0.0)
            val slots = (0 until 8).map { k -> r.hotCues[k]?.times(SR) ?: -1.0 }
            cache.edit().putString("cues:${item.id}", (listOf(main) + slots).joinToString(",")).apply()
        }
        applyMeta(item)
        // a loaded deck picks the new grid / cues up right away
        for (i in 0..1) {
            val d = engine.decks[i]; val lt = d.track ?: continue
            if (lt.id != item.id) continue
            cache.getString(item.id, null)?.split("|")?.let { p ->
                lt.bpm = p[0].toFloat(); lt.key = p[1].toInt()
                p.getOrNull(2)?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { lt.firstBeat = it }
                val map = cache.getString("tmap:${item.id}", null)
                if (map != null) lt.applyTempoMapString(map) else lt.clearTempoMap()
            }
            restoreCues(i, lt)
            ui[i].keyText = if (lt.key >= 0) "${lt.camelot} · ${lt.keyName}" else ""
            ui[i].analysisSource = item.analysisSource
        }
    }

    /** Tap on a library row: load into the requested deck, or the idle one. */
    fun loadTap(item: LibItem, target: Int) {
        val dest = if (target in 0..1) target else idleDeck()
        load(dest, item)
        if (pendingLoad == null) toast = "“${item.title}” → Deck ${deckName(dest)}"
    }

    fun loadById(deck: Int, id: String) {
        val item = allItems().firstOrNull { it.id == id } ?: return
        load(deck, item)
        if (pendingLoad == null) toast = "“${item.title}” → Deck ${deckName(deck)}"
    }

    fun dropUri(deck: Int, uri: Uri) { loadUri(deck, uri); if (pendingLoad == null) toast = "Loading → Deck ${deckName(deck)}" }

    // ------------------------------------------------------------------ editable sampler pads
    private val sampleDir by lazy { java.io.File(ctx.filesDir, "samples").apply { mkdirs() } }
    private fun sampleFile(k: Int) = java.io.File(sampleDir, "pad$k.pcm")

    private fun loadCustomSamples() {
        for (k in 0 until 8) {
            val f = sampleFile(k)
            if (!f.exists()) continue
            try {
                val bb = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val arr = FloatArray(bb.remaining()) { bb.get(it) / 32768f }
                SampleBank.set(k, arr, sp.getString("sample_name_$k", null) ?: "PAD ${k + 1}")
            } catch (_: Exception) {}
        }
        viewModelScope.launch(Dispatchers.Main) { sampleNames = SampleBank.names.toList() }
    }

    private fun storeSample(k: Int, data: FloatArray, name: String) {
        if (data.size < SR / 50) { toast = "That sample is empty"; return }
        SampleBank.set(k, data, name)
        sampleNames = SampleBank.names.toList()
        sp.edit().putString("sample_name_$k", name).apply()
        viewModelScope.launch(Dispatchers.IO) {
            val bb = java.nio.ByteBuffer.allocate(data.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in data) bb.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            sampleFile(k).writeBytes(bb.array())
        }
        toast = "Pad ${k + 1} → $name"
    }

    /** Use any audio file as a one-shot (first 10 s, leading silence trimmed). */
    fun setSampleFromUri(k: Int, uri: Uri) {
        val name = displayName(uri).take(10).uppercase()
        toast = "Loading sample…"
        viewModelScope.launch(Dispatchers.Default) {
            val t = Decoder.decode(ctx, uri, "sample:$uri", name, "")
            withContext(Dispatchers.Main) {
                if (t == null) toast = "Couldn't read that file"
                else storeSample(k, SampleBank.fromStereo(t.pcm, 0, t.frames), name)
            }
        }
    }

    /** Grab the active loop (or one bar from the playhead) of a deck straight into a pad. */
    fun captureSample(k: Int, deck: Int) {
        val d = engine.decks[deck]
        val t = d.track ?: return
        val (s, e, what) = if (d.loopOn) Triple(d.loopIn, d.loopOut, "LOOP")
            else Triple(d.pos, if (t.bpm > 0) d.beatsFrom(d.pos, 4.0) else d.pos + SR * 2.0, if (t.bpm > 0) "BAR" else "2S")
        storeSample(k, SampleBank.fromStereo(t.pcm, s.toInt(), e.toInt(), trim = false), "${deckName(deck)}·$what")
    }

    fun resetSample(k: Int) {
        SampleBank.reset(k); sampleNames = SampleBank.names.toList()
        sampleFile(k).delete(); sp.edit().remove("sample_name_$k").apply()
        toast = "Pad ${k + 1} restored"
    }

    // ------------------------------------------------------------------ beat-grid editing
    private class GridSnap(val bpm: Float, val firstBeat: Double, val map: String?)
    private val gridOrig = arrayOfNulls<GridSnap>(2)
    private val gridAnchor = doubleArrayOf(-1.0, -1.0)
    private val taps = arrayOf(ArrayList<Long>(), ArrayList<Long>())

    private fun gridTrack(i: Int): Track? = engine.decks[i].track

    fun gridOpen(i: Int) {
        val t = gridTrack(i) ?: return
        gridOrig[i] = GridSnap(t.bpm, t.firstBeat, t.tempoMapString())
        gridAnchor[i] = -1.0
        ui[i].gridMode = 0
        taps[i].clear()
        ui[i].gridEdit = true
        ui[1 - i].gridEdit = false
        refreshGridText(i)
    }

    fun gridDone(i: Int) {
        ui[i].gridEdit = false
        val t = gridTrack(i) ?: return
        val o = gridOrig[i]
        if (o == null || o.bpm != t.bpm || o.firstBeat != t.firstBeat || o.map != t.tempoMapString()) {
            t.analyzed = true
            saveMeta(t.id, t, true, user = true)
            allItems().firstOrNull { it.id == t.id }?.let { it.bpm = t.bpm; it.analysisSource = "manual" }
            ui[i].analysisSource = "manual"
            libRev++
            toast = "Grid saved for “${t.title}”"
        }
    }

    private fun refreshGridText(i: Int) {
        ui[i].gridBpm = gridTrack(i)?.let { if (it.bpm <= 0) "—" else if (it.variable) "~" + String.format("%.2f", it.bpmAt(engine.decks[i].pos)) else String.format("%.2f", it.bpm) } ?: ""
    }

    /** Keep firstBeat within one bar so bar numbering stays meaningful. */
    private fun normalize(t: Track) {
        if (t.bpm <= 0) return
        if (t.variable) return
        val bar = t.spb * 4
        var fb = t.firstBeat % bar
        if (fb < 0) fb += bar
        t.firstBeat = fb
    }

    fun gridShiftFrames(i: Int, frames: Double) {
        val t = gridTrack(i) ?: return
        if (t.bpm <= 0) return
        t.shiftGrid(frames); normalize(t)
    }

    fun gridShiftMs(i: Int, ms: Float) = gridShiftFrames(i, ms / 1000.0 * SR)

    /** Make the beat nearest the playhead beat 1 of a bar. */
    fun gridSetDownbeat(i: Int) {
        val t = gridTrack(i) ?: return
        if (t.bpm <= 0) return
        val p = engine.decks[i].pos
        t.setDownbeatAt(p); normalize(t)
        gridAnchor[i] = p   // pin for STRETCH
    }

    /** The pinned beat STRETCH pivots around: the last SET 1 HERE, otherwise the track's first beat. */
    fun gridAnchorFrames(i: Int): Double = gridTrack(i)?.let { if (gridAnchor[i] >= 0) gridAnchor[i] else it.firstBeat } ?: 0.0

    /** Tempo edits need one tempo: a tempo map is flattened to the tempo at the playhead first. */
    private fun flattenForEdit(i: Int, t: Track) {
        if (!t.variable) return
        t.flattenAt(engine.decks[i].pos)
        toast = "This track had changing tempo — the grid is now one tempo (${String.format("%.2f", t.bpm)} BPM). RESET brings the original back."
    }

    /** Stretch: the pinned beat stays put, the beat under the playhead follows the finger (changes BPM). */
    fun gridStretchFrames(i: Int, dx: Double) {
        val t = gridTrack(i) ?: return
        if (t.bpm <= 0) return
        flattenForEdit(i, t)
        val a = gridAnchorFrames(i)
        val k = Math.round((engine.decks[i].pos - a) / t.spb)
        if (abs(k) < 2) { gridShiftFrames(i, dx); return }   // too close to the pin to stretch meaningfully
        val b = a + k * t.spb
        val nspb = (b + dx - a) / k
        if (nspb <= 0) return
        t.bpm = (60.0 * SR / nspb).toFloat().coerceIn(40f, 250f)
        t.firstBeat = a; normalize(t)
        refreshGridText(i)
    }

    fun gridDrag(i: Int, dx: Double) { if (ui[i].gridMode == 1) gridStretchFrames(i, dx) else gridShiftFrames(i, dx) }

    /** Change tempo while the beat under the playhead stays where it is. */
    private fun setBpmAnchored(i: Int, bpm: Float) {
        val t = gridTrack(i) ?: return
        flattenForEdit(i, t)
        val nb = bpm.coerceIn(40f, 250f)
        if (t.bpm <= 0) { t.bpm = nb; t.firstBeat = engine.decks[i].pos; normalize(t); refreshGridText(i); return }
        val pos = engine.decks[i].pos
        val k = Math.round((pos - t.firstBeat) / t.spb)
        val anchor = t.firstBeat + k * t.spb
        t.bpm = nb
        t.firstBeat = anchor - k * t.spb
        normalize(t)
        refreshGridText(i)
    }

    fun gridBpmDelta(i: Int, d: Float) { val t = gridTrack(i) ?: return; flattenForEdit(i, t); setBpmAnchored(i, ((t.bpm + d) * 100f).roundToInt() / 100f) }
    fun gridScale(i: Int, f: Float) { val t = gridTrack(i) ?: return; flattenForEdit(i, t); if (t.bpm > 0) setBpmAnchored(i, t.bpm * f) }
    fun gridRound(i: Int) { val t = gridTrack(i) ?: return; flattenForEdit(i, t); if (t.bpm > 0) setBpmAnchored(i, t.bpm.roundToInt().toFloat()) }

    fun gridTap(i: Int) {
        val now = System.currentTimeMillis()
        val l = taps[i]
        if (l.isNotEmpty() && now - l.last() > 2000) l.clear()
        l.add(now); while (l.size > 9) l.removeAt(0)
        if (l.size >= 4) {
            val avg = (l.last() - l.first()).toDouble() / (l.size - 1)
            setBpmAnchored(i, ((60000.0 / avg) * 10).roundToInt() / 10f)
        }
    }

    fun gridReset(i: Int) {
        val t = gridTrack(i) ?: return
        val o = gridOrig[i] ?: return
        if (o.map != null) t.applyTempoMapString(o.map) else t.clearTempoMap()
        t.bpm = o.bpm; t.firstBeat = o.firstBeat
        refreshGridText(i)
    }

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
