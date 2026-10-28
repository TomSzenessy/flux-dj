package app.flux.dj

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import app.flux.dj.audio.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Outputs that can actually play music (no call/SCO profile, earpiece or internal routes). */
private val MEDIA_TYPES: Set<Int> = buildSet {
    addAll(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY))
    if (Build.VERSION.SDK_INT >= 26) add(AudioDeviceInfo.TYPE_USB_HEADSET)
    if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
}

// ------------------------------------------------------------------ outputs
private fun Controller.deviceKey(d: AudioDeviceInfo) = "${d.type}|${if (Build.VERSION.SDK_INT >= 28) d.address else ""}"

internal fun Controller.refreshDevices() {
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

private fun Controller.kind(d: AudioDeviceInfo) = when (d.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Tablet speaker"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line out / aux"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
    else -> if (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) "Bluetooth LE" else "USB"
}

private fun Controller.deviceName(d: AudioDeviceInfo): String {
    val n = d.productName?.toString().orEmpty()
    val k = kind(d)
    return when {
        k == "Tablet speaker" -> "Built-in speaker"
        n.isBlank() || n == Build.MODEL || n.equals(Build.DEVICE, true) -> k
        else -> n
    }
}

internal fun Controller.doOutMode(m: OutMode) { outMode = m; if (m == OutMode.DUAL) ensureDualDevices(); applyOutputConfig(true) }

/** "Two devices": room → speaker/USB, ears → Bluetooth (or the reverse), never two on the same side. */
internal fun Controller.ensureDualDevices() {
    val m0 = devices.firstOrNull { it.key == masterDev }
    val master = m0 ?: devices.firstOrNull { it.kind == "Tablet speaker" } ?: devices.firstOrNull { !isBt(it) } ?: devices.firstOrNull()
    masterDev = master?.key ?: ""
    val cue = devices.firstOrNull { it.key == cueDev }
    if (master == null || cue == null || !canPair(master, cue)) {
        cueDev = devices.firstOrNull { master != null && canPair(master, it) }?.key ?: ""
    }
}
internal fun Controller.setVisualTrim(ms: Int) {
    visualTrimMs = ms.coerceIn(-300, 500)
    trimSave?.cancel()
    trimSave = viewModelScope.launch { delay(450); sp.edit().putInt("visual_trim", visualTrimMs).apply() }
}
internal fun Controller.doOutVolume(v: Float) {
    outVolume = v; engine.outVolume = v
    volumeSave?.cancel()
    volumeSave = viewModelScope.launch { delay(450); sp.edit().putFloat("out_volume", outVolume).apply() }
}

/**
 * The playhead you *hear*: the engine is ahead of the speaker by the output delay, so everything
 * on screen is drawn that much later while playing.
 */
internal fun Controller.vpos(i: Int): Double {
    val d = engine.decks[i]
    if (!d.playing || d.scratching || d.searching) return d.pos
    val full = outputDelayFrames(i)
    val hold = d.visualHoldPos
    val delay = if (hold >= 0.0) minOf(full, (d.pos - hold).coerceAtLeast(0.0)) else full
    return (d.pos - delay).coerceAtLeast(0.0)
}

/** The engine must be this many frames ahead of the picture for the sound to arrive together. */
internal fun Controller.outputDelayFrames(i: Int): Double {
    val d = engine.decks[i]
    return (engine.visualLatencyMs + visualTrimMs).coerceAtLeast(0f) / 1000.0 * SR * d.effRatio
}

/** Finish a held needle-search at a grid-snapped target (SNAP on) and resume from the picture. */
internal fun Controller.resyncIfNeeded(i: Int) {
    val d = deck(i)
    if (d.syncOn && !d.loopOn) d.needAlign = true
}

internal fun Controller.releaseSearchAt(i: Int, target: Double) {
    val d = engine.decks[i]
    val p = if (d.quantize) d.snapBeat(target, 0.5) else target
    android.util.Log.i("FLUX-SEARCH", "raw=${target.toLong()} snapped=${p.toLong()} q=${d.quantize} bpm=${d.track?.bpm} beat=${d.track?.beatIndex(target)} halfBeat=${(d.track?.spb ?: 0.0) * 0.5}")
    d.searching = false
    d.seekNow(p + (if (d.playing) outputDelayFrames(i) else 0.0))
    resyncIfNeeded(i)
}

/** Finish a held needle-search at the visual position; skip the in-flight output delay. */

/** Tap-to-jump. The visual position is authoritative; skip the in-flight output delay. */
internal fun Controller.jumpAudible(i: Int, target: Double) {
    val d = engine.decks[i]
    d.seekNow(target + (if (d.playing) outputDelayFrames(i) else 0.0))
    resyncIfNeeded(i)
}

/** Play/pause. Pause freezes the picture; play holds it at the cue while the first audio is in flight. */
internal fun Controller.togglePlayPad(i: Int) {
    val d = engine.decks[i]
    if (d.playing) d.pauseAt(vpos(i))
    else d.play()
}
internal fun Controller.startAlign() {
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
internal fun Controller.stopAlign() {
    alignGen++; aligning = false; engine.calibration(false); measuringMic = false
    bakValid = false
}

/** Dismiss the timing sheet and put back the settings it had when it opened. */
internal fun Controller.cancelAlign() {
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

internal fun Controller.ensureBtPermission() {
    if (Build.VERSION.SDK_INT < 31) return
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        requestBtPermission?.invoke()
    }
}

internal fun Controller.beginAutoAlign() {
    // measure from a clean slate: no old delay, no fine-tune
    setOffset(0); setVisualTrim(0)
    engine.acousticMasterMs = -1f
    engine.calibration(true)
    autoMasterMs = -1f; autoCueMs = -1f; autoProgress = 0f
    autoStep = 1
    autoMsg = "Place the tablet's mic right next to the speaker (or main output). Keep the room quiet, then press Measure speaker."
}

internal fun Controller.measureMaster() {
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

internal fun Controller.measureCue() {
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

internal fun Controller.doneAutoAlign() {
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

private fun Controller.saveAcoustic(ms: Float) {
    acousticBufferMs = engine.outLatencyMs
    sp.edit().putFloat("acoustic_master_ms", ms).putFloat("acoustic_buffer_ms", acousticBufferMs)
        .putString("acoustic_key", timingKey()).apply()
    timingMeasured = true
}
internal fun Controller.resetTiming() {
    setOffset(0); setVisualTrim(0); engine.acousticMasterMs = -1f; timingMeasured = false
    sp.edit().remove("acoustic_master_ms").remove("acoustic_key").apply()
    toast = "Timing reset — room, headphones and picture use the device delay again"
}
internal fun Controller.setOffset(ms: Int) {
    offsetMs = ms; engine.offsetMs = ms
    offsetSave?.cancel()
    offsetSave = viewModelScope.launch { delay(450); sp.edit().putInt("out_offset", offsetMs).apply() }
}

/** Device n (1-based in list order) beeps n times, each at its own pitch, all at the same time. */
internal fun Controller.testOutputs(keys: List<String>) {
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

private fun Controller.devName(key: String, fallback: String) = devices.firstOrNull { it.key == key }?.name ?: fallback

/** Which rig the acoustic measurement was made on. */
private fun Controller.timingKey() = "$outMode|$masterDev|$cueDev"
private fun Controller.syncAcousticFromPrefs() {
    val key = sp.getString("acoustic_key", "")
    val valid = key == timingKey() && sp.getFloat("acoustic_master_ms", -1f) >= 0f
    timingMeasured = valid
    acousticBufferMs = sp.getFloat("acoustic_buffer_ms", -1f)
    engine.acousticMasterMs = if (valid) sp.getFloat("acoustic_master_ms", -1f) else -1f
}

internal fun Controller.applyOutputConfig(restart: Boolean) {
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
