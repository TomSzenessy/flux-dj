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
    val id: String, val title: String, val artist: String, val durMs: Long, val uri: Uri?, val demo: Int,
    var bpm: Float = 0f, var key: Int = -1,
    var energy: Int = 0, var pattern: String = "", var vibe: String = "",
)

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
    var keyColorIdx by mutableIntStateOf(-1)
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
    var hpMix by mutableFloatStateOf(0.35f)
    var hpLevel by mutableFloatStateOf(0.8f)
    var masterBpm by mutableStateOf("–")
    var leader by mutableIntStateOf(0)
    var recording by mutableStateOf(false)
    var recSeconds by mutableIntStateOf(0)
    var recSaved by mutableStateOf<String?>(null)
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
    var offsetMs by mutableIntStateOf(0)
    var devices by mutableStateOf<List<DeviceOpt>>(emptyList())
    var outputLabel by mutableStateOf("Speaker")
    var outputWarn by mutableStateOf<String?>(null)

    // library
    val demos = DemoTracks.specs.mapIndexed { i, s -> LibItem(s.id, s.title, s.artist, 0, null, i, s.bpm, s.rootPc + if (s.minor) 12 else 0) }
    var deviceTracks by mutableStateOf<List<LibItem>>(emptyList())
    var imported by mutableStateOf<List<LibItem>>(emptyList())
    var libPermission by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    // library intelligence
    var libRev by mutableIntStateOf(0)
    var indexTotal by mutableIntStateOf(0)
    var indexDone by mutableIntStateOf(0)
    var indexPaused by mutableStateOf(false)
    var nextSuggestion by mutableStateOf<LibItem?>(null)
    var nextReason by mutableStateOf("")
    var filterKey by mutableIntStateOf(0)       // 0 any, 1 compatible
    var filterBpm by mutableIntStateOf(0)       // 0 any, else ± percent
    var filterVibe by mutableStateOf<String?>(null)
    var filterEnergy by mutableIntStateOf(0)    // 0 any, 1 low, 2 mid, 3 high
    var sortMode by mutableIntStateOf(0)        // 0 match, 1 bpm, 2 key, 3 energy, 4 title
    private var indexJob: Job? = null
    private val indexDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "flux-index").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()

    private val demoCache = HashMap<Int, Track>()
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastTenths = longArrayOf(-1, -1)
    private var lastRemain = intArrayOf(-1, -1)


    // ------------------------------------------------------------------ per-frame
    fun poll() {
        tick.longValue = tick.longValue + 1
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
                u.bpm = if (t.bpm > 0) String.format("%.1f", t.bpm * ratio) else if (u.analysing) "···" else "–"
                u.origBpm = if (t.bpm > 0) String.format("%.1f", t.bpm) else ""
                u.pct = if (t.bpm > 0 || true) String.format("%+.1f%%", (ratio - 1.0) * 100) else ""
                val sec = d.pos / SR
                val tenths = (sec * 10).toLong()
                if (tenths != lastTenths[i]) {
                    lastTenths[i] = tenths
                    u.time = fmtTime(sec, true)
                }
                val rem = (t.durationSec - sec).toInt()
                if (rem != lastRemain[i]) { lastRemain[i] = rem; u.remain = "-" + fmtTime(rem.toDouble(), false) }
                val cs = List(8) { d.hotCues[it] >= 0 }
                if (cs != u.cues) u.cues = cs
                if (t.bpm > 0) {
                    val bi = Math.floor((d.pos - t.firstBeat) / t.spb).toLong()
                    if (bi >= 0) {
                        val bar = bi / 4 + 1
                        u.beat = (bi % 4).toInt() + 1
                        val toPhrase = (16 - ((bar - 1) % 16)).toInt()
                        u.barText = "BAR $bar · ${toPhrase} to phrase"
                        u.phraseSoon = toPhrase <= 2
                    } else { u.beat = 0; u.barText = "" }
                }
                // persist cues whenever they change
                if (!restoring[i] && u.loaded && (d.hotCueRev != lastCueRev[i] || d.cue != lastCue[i])) {
                    lastCueRev[i] = d.hotCueRev; lastCue[i] = d.cue
                    saveCues(t.id, d)
                }
            }
            cu.trim = c.trim; cu.hi = c.hi; cu.mid = c.mid; cu.lo = c.lo; cu.color = c.color; cu.fader = c.fader; cu.cue = c.cue
        }
        xfader = engine.xfader
        routedTo = engine.routedMaster
        if (previewId != null && !previewLoading) {
            val pt = engine.previewTrack
            if (pt != null) {
                previewFrac = (engine.previewPos / pt.frames).toFloat()
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
        engine.chans[0].let { }
        transitionActive = engine.transitionActive
        transitionProgress = engine.transitionProgress
        leader = engine.leader()
        val mb = engine.masterBpm()
        masterBpm = if (mb > 0) String.format("%.1f", mb) else "–"
        recording = engine.recorder.active
        recSeconds = engine.recorder.seconds
        if (engine.clip) { clip = true; engine.clip = false } else if (clip && tick.longValue % 90L == 0L) clip = false
        updateMatch()
        if (tick.longValue % 75L == 0L) refreshNext()
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
            u.keyColorIdx = kd
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
    fun toggleKeyLock(i: Int) { deck(i).keyLock = !deck(i).keyLock }
    fun toggleSlip(i: Int) { deck(i).slipOn = !deck(i).slipOn }
    fun pitch(i: Int, v: Float) {
        val d = deck(i)
        d.setTempoFader(v)
    }
    fun toggleQuantize() { quantize = !quantize; engine.decks.forEach { it.quantize = quantize } }
    fun setKeyShift(i: Int, s: Int) { deck(i).keyShift = s }

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
        if (engine.recorder.active) { engine.recorder.stop(); recSaved = engine.recorder.lastFile; toast = "Saved ${engine.recorder.lastFile ?: "recording"}" }
        else { engine.recorder.start(); recSaved = null }
    }

    // ------------------------------------------------------------------ outputs
    private fun deviceKey(d: AudioDeviceInfo) = "${d.type}|${if (Build.VERSION.SDK_INT >= 28) d.address else ""}"

    fun refreshDevices() {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devices = outs.filter { it.type in MEDIA_TYPES }
            .map { DeviceOpt(it.id, deviceName(it), kind(it), deviceKey(it)) }
            .distinctBy { it.key }
            .sortedBy { if (it.kind == "Tablet speaker") 0 else 1 }
        if (outMode == OutMode.DUAL && devices.size < 2) outputWarn = "Only one output connected."
        // ids change when devices reconnect: re-resolve the saved choices
        engine.masterDeviceId = devices.firstOrNull { it.key == masterDev }?.id ?: -1
        engine.cueDeviceId = if (outMode == OutMode.DUAL) devices.firstOrNull { it.key == cueDev }?.id ?: -1 else -1
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

    fun doOutMode(m: OutMode) { outMode = m; applyOutputConfig(true) }
    fun doMasterDev(key: String) { masterDev = key; applyOutputConfig(true) }
    fun doCueDev(key: String) { cueDev = key; applyOutputConfig(true) }
    fun setOffset(ms: Int) { offsetMs = ms; engine.offsetMs = ms; sp.edit().putInt("out_offset", ms).apply() }

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
        if (restart) viewModelScope.launch(Dispatchers.Default) { engine.reopenOutputs() }
    }

    // ------------------------------------------------------------------ library
    fun refreshLibrary() {
        val ok = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        libPermission = ok
        if (!ok) return
        viewModelScope.launch(Dispatchers.IO) {
            val out = ArrayList<LibItem>()
            try {
                val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
                ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000", null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                    while (c.moveToNext() && out.size < 5000) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val it = LibItem("ms:$id", c.getString(1) ?: "Unknown", c.getString(2)?.takeIf { a -> a != "<unknown>" } ?: "", c.getLong(3), uri, -1)
                        applyMeta(it)
                        out.add(it)
                    }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { deviceTracks = out; libRev++; startIndexing() }
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
            if (o.syncOn) { o.tempo = ((o.effRatio - 1.0) / o.range).toFloat().coerceIn(-1f, 1f); o.syncOn = false }
            restoring[i] = true
            d.load(t); d.quantize = quantize
            hadCues = restoreCues(i, t)
            restoring[i] = false
            u.loaded = true; u.loading = if (t.complete) -1f else u.loading; u.title = t.title; u.artist = t.artist
            u.analysing = !t.analyzed
            u.keyText = if (t.key >= 0) "${t.camelot} · ${t.keyName}" else ""
            android.util.Log.i("FLUX", "playable '${item.title}' after ${System.currentTimeMillis() - t0} ms")
        }
        val ready: Track? = when {
            item.demo >= 0 -> demoTrack(item.demo)
            previewCache?.id == item.id && previewCache?.complete == true -> previewCache
            item.uri != null -> loadPcm(item)
            else -> null
        }
        val track: Track? = ready ?: Decoder.decode(
            ctx, item.uri!!, item.id, item.title, item.artist,
            onProgress = { p -> if (!stale()) u.loading = p },
            onStream = { st -> prepare(st); viewModelScope.launch(Dispatchers.Main) { apply(st) } },
        )
        if (stale()) return
        if (track == null) { withContext(Dispatchers.Main) { if (!applied) { u.loading = -1f; u.title = "Couldn't read that file"; toast = "Couldn't decode ${item.title}" } }; return }
        if (ready == null) { if (!applied) prepare(track); savePcm(track) } else if (ready.wave == null) prepare(ready)
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
        if (!track.analyzed) {
            Analyzer.rhythmAndKey(track); Analyzer.profile(track)
            saveMeta(track.id, track, true)
        }
        withContext(Dispatchers.Main) {
            item.bpm = track.bpm; item.key = track.key; item.energy = track.energy; item.pattern = track.pattern; item.vibe = track.vibe; libRev++
            if (stale() || d.track !== track) return@withContext
            if (!hadCues && track.firstBeat > 0 && !d.playing && d.cue != track.firstBeat) { d.cue = track.firstBeat; d.seekReq = track.firstBeat }
            u.analysing = false
            u.keyText = if (track.key >= 0) "${track.camelot} · ${track.keyName}" else ""
            // Smart sync: land in time with whatever is already playing
            val o = engine.decks[1 - i]
            if (!auto && o.playing && track.bpm > 0 && o.bpm > 0 && !d.playing) engine.toggleSync(i)
        }
    }

    private fun rememberLoaded(i: Int, item: LibItem) {
        sp.edit().putString("last_$i", listOf(item.id, item.uri?.toString() ?: "", item.title, item.artist, item.demo.toString()).joinToString("\t")).apply()
    }

    private fun lastLoaded(i: Int): LibItem? {
        val p = sp.getString("last_$i", null)?.split("\t") ?: return null
        if (p.size < 5) return null
        val demo = p[4].toIntOrNull() ?: -1
        if (demo >= 0) return demos.getOrNull(demo)
        if (p[1].isEmpty()) return null
        return LibItem(p[0], p[2], p[3], 0, Uri.parse(p[1]), -1).also { applyMeta(it) }
    }

    /** Fill a freshly decoded track with everything we already know about it (waveform, grid, key, gain). */
    private fun prepare(t: Track) {
        loadWave(t)
        if (!t.analyzed) cache.getString(t.id, null)?.split("|")?.let { p ->
            if (metaCurrent(p) && (p[2].toDoubleOrNull() ?: -1.0) >= 0) {
                t.bpm = p[0].toFloat(); t.key = p[1].toInt(); t.firstBeat = p[2].toDouble(); t.analyzed = true
                t.energy = p[3].toIntOrNull() ?: 0; t.pattern = p[4]; t.vibe = p[5]
            }
        }
    }

    // ------------------------------------------------------------------ decoded-audio disk cache (LRU)
    private fun md5(id: String) = java.security.MessageDigest.getInstance("MD5").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private val pcmDir by lazy { java.io.File(ctx.cacheDir, "pcm").apply { mkdirs() } }
    private val pcmCap = 1_500L * 1024 * 1024
    private fun pcmFile(id: String) = java.io.File(pcmDir, md5(id) + ".pcm")

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
                tmp.renameTo(f)
                // evict least-recently-used beyond the cap
                val files = pcmDir.listFiles { x -> x.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return@launch
                var total = 0L
                for (x in files) { total += x.length(); if (total > pcmCap) x.delete() }
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
            val t = Decoder.decode(ctx, item.uri!!, item.id, item.title, item.artist, onStream = { st ->
                streamed = true
                loadWave(st)
                viewModelScope.launch(Dispatchers.Main) { start(st, 0f) }
            })
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

    fun stopPreview() { engine.stopPreview(); previewId = null; previewLoading = false }
    fun seekPreview(f: Float) = engine.previewSeek(f)
    fun previewTrack(): Track? = engine.previewTrack

    // ------------------------------------------------------------------ library intelligence
    /** Analysis format version; "u" marks a grid the user edited by hand (never re-analysed). */
    private val metaVersion = "2"

    private fun saveMeta(id: String, t: Track, fbValid: Boolean, user: Boolean = false) {
        cache.edit().putString(id, "${t.bpm}|${t.key}|${if (fbValid) t.firstBeat else -1.0}|${t.energy}|${t.pattern}|${t.vibe}|${if (user) "u" else metaVersion}").apply()
    }

    private fun metaParts(id: String): List<String>? = cache.getString(id, null)?.split("|")
    private fun metaCurrent(p: List<String>?): Boolean = p != null && p.size >= 7 && (p[6] == metaVersion || p[6] == "u")

    private fun applyMeta(it: LibItem): Boolean {
        val p = cache.getString(it.id, null)?.split("|") ?: return false
        if (p.size < 6) return false
        it.bpm = p[0].toFloatOrNull() ?: 0f; it.key = p[1].toIntOrNull() ?: -1
        it.energy = p[3].toIntOrNull() ?: 0; it.pattern = p[4]; it.vibe = p[5]
        return true
    }

    fun startIndexing() {
        indexJob?.cancel()
        val pool = imported + deviceTracks
        val todo = pool.filter { it.uri != null && it.energy >= 0 && (it.energy == 0 || !hasWave(it.id)) }
        val stale = pool.filter { it.uri != null && it.energy > 0 && hasWave(it.id) && !metaCurrent(metaParts(it.id)) }
        indexTotal = pool.size; indexDone = pool.size - todo.size
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
        val t = Decoder.decode(ctx, it.uri!!, it.id, it.title, it.artist)
        if (t == null) { it.energy = -1; cache.edit().putString(it.id, "0|-1|-1.0|-1|-|-").apply(); return }
        Analyzer.waveform(t); saveWave(t, t.gain)
        Analyzer.rhythmAndKey(t); Analyzer.profile(t)
        it.bpm = t.bpm; it.key = t.key; it.energy = t.energy; it.pattern = t.pattern; it.vibe = t.vibe
        saveMeta(it.id, t, true)
    }

    private fun rebeat(it: LibItem) {
        val p = metaParts(it.id) ?: return
        val t = Track(it.id, it.title, it.artist, ShortArray(4))
        if (!loadWave(t)) return
        Analyzer.rebeat(t)
        t.key = p[1].toIntOrNull() ?: -1; t.energy = p[3].toIntOrNull() ?: 0; t.pattern = p[4]; t.vibe = p[5]
        saveMeta(it.id, t, true)
        it.bpm = t.bpm
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

    private fun allItems(): List<LibItem> = demos + imported + deviceTracks

    private fun loadedIds(): Set<String> = engine.decks.mapNotNull { it.track?.id }.toSet()

    fun suggestions(target: Int, n: Int = 4): List<LibItem> {
        val r = refMeta(target) ?: return emptyList()
        val skip = loadedIds()
        return allItems().asSequence().filter { it.id !in skip }.mapNotNull { i -> relevance(i, r)?.let { i to it } }
            .filter { tempoDiffPct(it.first.bpm, r.bpm) <= 10f }
            .sortedBy { it.second }.take(n).map { it.first }.toList()
    }

    fun idleDeck(): Int = when {
        engine.decks[0].playing && !engine.decks[1].playing -> 1
        engine.decks[1].playing && !engine.decks[0].playing -> 0
        else -> 1 - engine.leader()
    }

    private fun refreshNext() {
        val tgt = idleDeck()
        val r = refMeta(tgt)
        val s = if (r == null) null else suggestions(tgt, 1).firstOrNull()
        nextSuggestion = s; nextReason = if (s != null && r != null) reasons(s, r) else ""
    }

    fun loadNext() { nextSuggestion?.let { load(idleDeck(), it) } }

    /** Filtered + sorted list for the library panel. */
    fun filteredLibrary(target: Int, query: String): List<LibItem> {
        val ref = refMeta(target)
        var list = allItems().filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
        if (filterVibe != null) list = list.filter { it.vibe == filterVibe }
        if (filterEnergy != 0) list = list.filter { when (filterEnergy) { 1 -> it.energy in 1..3; 2 -> it.energy in 4..7; else -> it.energy >= 8 } }
        if (ref != null) {
            if (filterBpm != 0) list = list.filter { it.bpm > 0 && abs(it.bpm / ref.bpm - 1f) * 100f <= filterBpm }
            if (filterKey == 1) list = list.filter { Keys.distance(it.key, ref.key) in 0..2 }
        }
        return when (sortMode) {
            1 -> list.sortedBy { if (it.bpm <= 0) 999f else it.bpm }
            2 -> list.sortedBy { if (it.key < 0) 99 else (Keys.camelot(it.key).dropLast(1).toInt() * 2 + (if (it.key >= 12) 0 else 1)) }
            3 -> list.sortedByDescending { it.energy }
            4 -> list.sortedBy { it.title.lowercase() }
            else -> if (ref != null) list.sortedBy { relevance(it, ref) ?: 500f } else list
        }
    }

    val vibesPresent: List<String> get() = allItems().map { it.vibe }.filter { it.isNotEmpty() && it != "-" }.distinct().sorted()


    // ------------------------------------------------------------------ importing & loading
    private fun loadImports() {
        val lines = (sp.getString("imports", "") ?: "").split("\n").filter { it.contains("\t") }
        imported = lines.map { l ->
            val (u, t) = l.split("\t", limit = 2)
            LibItem("imp:$u", t, "", 0, Uri.parse(u), -1).also { applyMeta(it) }
        }
    }

    private fun saveImports() {
        sp.edit().putString("imports", imported.joinToString("\n") { "${it.uri}\t${it.title}" }).apply()
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track"
        try { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.') } } catch (_: Exception) {}
        return name
    }

    fun addFiles(uris: List<Uri>) {
        val have = imported.map { it.uri.toString() }.toSet()
        val fresh = uris.filter { it.toString() !in have }.map { LibItem("imp:$it", displayName(it), "", 0, it, -1).also { i -> applyMeta(i) } }
        if (fresh.isEmpty()) { toast = "Already in your library"; return }
        imported = imported + fresh; saveImports(); libRev++; startIndexing()
        toast = "Added ${fresh.size} track${if (fresh.size > 1) "s" else ""} · analysing in the background"
    }

    fun addFolder(tree: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val found = ArrayList<Pair<Uri, String>>()
            fun walk(docId: String) {
                if (found.size >= 3000) return
                val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                try {
                    ctx.contentResolver.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0); val name = c.getString(1) ?: "Track"; val mime = c.getString(2) ?: ""
                            if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id)
                            else if (mime.startsWith("audio/")) found.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id) to name.substringBeforeLast('.'))
                        }
                    }
                } catch (_: Exception) {}
            }
            walk(android.provider.DocumentsContract.getTreeDocumentId(tree))
            withContext(Dispatchers.Main) {
                val have = imported.map { it.uri.toString() }.toSet()
                val fresh = found.filter { it.first.toString() !in have }.map { (u, n) -> LibItem("imp:$u", n, "", 0, u, -1).also { i -> applyMeta(i) } }
                if (found.isEmpty()) toast = "No audio files found in that folder"
                else { imported = imported + fresh; saveImports(); libRev++; startIndexing(); toast = "Added ${fresh.size} tracks from folder · analysing…" }
            }
        }
    }

    fun deckName(i: Int) = if (i == 0) "A" else "B"

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
            else Triple(d.pos, d.pos + (if (t.bpm > 0) t.spb * 4 else SR * 2.0), if (t.bpm > 0) "BAR" else "2S")
        storeSample(k, SampleBank.fromStereo(t.pcm, s.toInt(), e.toInt(), trim = false), "${deckName(deck)}·$what")
    }

    fun resetSample(k: Int) {
        SampleBank.reset(k); sampleNames = SampleBank.names.toList()
        sampleFile(k).delete(); sp.edit().remove("sample_name_$k").apply()
        toast = "Pad ${k + 1} restored"
    }

    // ------------------------------------------------------------------ beat-grid editing
    private val gridOrig = arrayOfNulls<Pair<Float, Double>>(2)
    private val gridAnchor = doubleArrayOf(-1.0, -1.0)
    private val taps = arrayOf(ArrayList<Long>(), ArrayList<Long>())

    private fun gridTrack(i: Int): Track? = engine.decks[i].track

    fun gridOpen(i: Int) {
        val t = gridTrack(i) ?: return
        gridOrig[i] = t.bpm to t.firstBeat
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
        if (o == null || o.first != t.bpm || o.second != t.firstBeat) {
            t.analyzed = true
            saveMeta(t.id, t, true, user = true)
            allItems().firstOrNull { it.id == t.id }?.let { it.bpm = t.bpm }
            libRev++
            toast = "Grid saved for “${t.title}”"
        }
    }

    private fun refreshGridText(i: Int) { ui[i].gridBpm = gridTrack(i)?.let { if (it.bpm > 0) String.format("%.2f", it.bpm) else "—" } ?: "" }

    /** Keep firstBeat within one bar so bar numbering stays meaningful. */
    private fun normalize(t: Track) {
        if (t.bpm <= 0) return
        val bar = t.spb * 4
        var fb = t.firstBeat % bar
        if (fb < 0) fb += bar
        t.firstBeat = fb
    }

    fun gridShiftFrames(i: Int, frames: Double) {
        val t = gridTrack(i) ?: return
        if (t.bpm <= 0) return
        t.firstBeat += frames; normalize(t)
    }

    fun gridShiftMs(i: Int, ms: Float) = gridShiftFrames(i, ms / 1000.0 * SR)

    /** Make the beat nearest the playhead beat 1 of a bar. */
    fun gridSetDownbeat(i: Int) {
        val t = gridTrack(i) ?: return
        if (t.bpm <= 0) return
        val p = engine.decks[i].pos
        t.firstBeat = p; normalize(t)
        gridAnchor[i] = p   // pin for STRETCH
    }

    /** The pinned beat STRETCH pivots around: the last SET 1 HERE, otherwise the track's first beat. */
    fun gridAnchorFrames(i: Int): Double = gridTrack(i)?.let { if (gridAnchor[i] >= 0) gridAnchor[i] else it.firstBeat } ?: 0.0

    /** Stretch: the pinned beat stays put, the beat under the playhead follows the finger (changes BPM). */
    fun gridStretchFrames(i: Int, dx: Double) {
        val t = gridTrack(i) ?: return
        if (t.bpm <= 0) return
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

    fun gridBpmDelta(i: Int, d: Float) { val t = gridTrack(i) ?: return; setBpmAnchored(i, ((t.bpm + d) * 100f).roundToInt() / 100f) }
    fun gridScale(i: Int, f: Float) { val t = gridTrack(i) ?: return; if (t.bpm > 0) setBpmAnchored(i, t.bpm * f) }
    fun gridRound(i: Int) { val t = gridTrack(i) ?: return; if (t.bpm > 0) setBpmAnchored(i, t.bpm.roundToInt().toFloat()) }

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
        t.bpm = o.first; t.firstBeat = o.second
        refreshGridText(i)
    }

    /** Library ranking relative to a reference track (what's on the other deck). */
    fun reference(forDeck: Int): Track? = engine.decks[1 - forDeck].track

    // Startup runs last so every property above is initialised before background work touches it.
    init {
        outMode = OutMode.entries[sp.getInt("out_mode", 0).coerceIn(0, 2)]
        offsetMs = sp.getInt("out_offset", 0)
        masterDev = sp.getString("dev_master_key", "") ?: ""; cueDev = sp.getString("dev_cue_key", "") ?: ""
        refreshDevices()
        // saved device ids may be stale after a reboot: drop them when no longer present
        if (outMode == OutMode.DUAL && devices.none { it.key == cueDev }) outMode = OutMode.SINGLE
        applyOutputConfig(restart = false)
        engine.start()
        // reopen whatever was on each deck last time; demos only as a fallback
        for (i in 0..1) viewModelScope.launch(Dispatchers.Default) {
            val last = lastLoaded(i)
            if (last != null) loadItem(i, last, auto = true)
            if (engine.decks[i].track == null) loadItem(i, demos[i], auto = true)
        }
        loadImports()
        refreshLibrary()
        viewModelScope.launch(Dispatchers.IO) { loadCustomSamples() }
    }

    override fun onCleared() { engine.stop(); super.onCleared() }
}
