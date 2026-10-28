package app.flux.dj

import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*

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

