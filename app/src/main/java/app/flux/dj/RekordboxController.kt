package app.flux.dj

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.flux.dj.audio.*
import kotlinx.coroutines.*

private val MAX_XML_BYTES = 32L * 1024 * 1024

class RbReport(val total: Int, val matched: Int, val playlists: Int, val variable: Int, val hotCues: Int, val missing: List<String>,
           val duplicateTracks: Int = 0, val duplicateCues: Int = 0)

internal fun Controller.loadRekordbox() {
    if (!rbFile.exists()) return
    viewModelScope.launch(Dispatchers.IO) {
        val lib = try { rbFile.inputStream().use { Rekordbox.parse(it) } } catch (_: Exception) { null }
        withContext(Dispatchers.Main) { rb = lib; rbRematch() }
    }
}

/** Called when the user picks a collection.xml: keep a copy, match, and apply everything. */
internal fun Controller.importRekordbox(uri: Uri) {
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
internal fun Controller.readRekordbox(uri: Uri): RbLibrary? = try {
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

internal fun Controller.applyRbLibrary(lib: RbLibrary) {
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

internal fun Controller.removeRekordbox() {
    rbFile.delete(); rb = null; rbMatch = emptyMap(); playlist = null; rbSource = ""
    sp.edit().remove("rb_source").apply()
    allItems().forEach { it.fromRb = false; it.analysisSource = ""; applyMeta(it) }
    libRev++; toast = "rekordbox playlists removed (grids and cues already imported stay)"
}

/** Match the collection against the library and apply rekordbox data to newly matched tracks. */
internal fun Controller.rbRematch() {
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
private fun Controller.applyRb(r: RbTrack, item: LibItem) {
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
