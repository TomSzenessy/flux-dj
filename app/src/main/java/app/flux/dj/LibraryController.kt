package app.flux.dj

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import app.flux.dj.audio.*
import kotlinx.coroutines.*
import kotlin.math.abs

// ------------------------------------------------------------------ library
internal fun Controller.refreshLibrary() {
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

internal fun Controller.loadUri(deckIdx: Int, uri: Uri) {
    var name = "Track"
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.') }
    } catch (_: Exception) {}
    val item = LibItem("uri:$uri", name, "", 0, uri, -1)
    load(deckIdx, item)
}

internal fun Controller.load(deckIdx: Int, item: LibItem, force: Boolean = false) {
    if (!force && engine.decks[deckIdx].playing) { pendingLoad = deckIdx to item; return }
    viewModelScope.launch(Dispatchers.Default) { loadItem(deckIdx, item) }
}

internal fun Controller.saveCues(id: String, d: Deck) {
    cache.edit().putString("cues:$id", (listOf(d.cue) + d.hotCues.toList()).joinToString(",")).apply()
}

/** Restore saved cue + hot cues for a track; returns true if any were found. */
internal fun Controller.restoreCues(i: Int, t: Track): Boolean {
    val p = cache.getString("cues:${t.id}", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return false
    if (p.size < 9) return false
    val d = engine.decks[i]
    for (k in 0 until 8) d.hotCues[k] = p[k + 1]
    d.hotCueRev++
    d.cue = p[0]; d.seekReq = p[0]
    lastCueRev[i] = d.hotCueRev; lastCue[i] = d.cue
    return true
}
private fun Controller.demoTrack(i: Int): Track = synchronized(demoLocks[i]) {
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

internal suspend fun Controller.loadItem(i: Int, item: LibItem, auto: Boolean = false) {
    val u = ui[i]; val d = engine.decks[i]
    val gen = loadGen.incrementAndGet(i)   // a newer load for this deck supersedes this one
    withContext(Dispatchers.Main) { u.loading = 0f; u.title = item.title; u.artist = item.artist; u.loaded = false }
    if (previewId == item.id) withContext(Dispatchers.Main) { stopPreview() }
    val t0 = System.currentTimeMillis()
    activeLoads.incrementAndGet()
    try { loadItemInner(i, item, auto, u, d, gen, t0) } finally { activeLoads.decrementAndGet() }
}

private suspend fun Controller.loadItemInner(i: Int, item: LibItem, auto: Boolean, u: DeckUi, d: Deck, gen: Int, t0: Long) {
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
    if (track.truncated) withContext(Dispatchers.Main) {
        toast = "“${item.title}” is longer than 12 minutes — it was cut at the limit"
    }
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

private fun Controller.rememberLoaded(i: Int, item: LibItem) {
    sp.edit().putString("last_$i", listOf(item.id, item.uri?.toString() ?: "", item.title, item.artist, item.demo.toString(), item.durMs.toString()).joinToString("\t")).apply()
}

internal fun Controller.lastLoaded(i: Int): LibItem? {
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
private fun Controller.prepare(t: Track) {
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
private fun Controller.md5(id: String) = java.security.MessageDigest.getInstance("MD5").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun Controller.pcmFile(id: String) = java.io.File(pcmDir, md5(id) + ".pcm")
internal fun Controller.remoteFile(id: String) = java.io.File(remoteDir, md5(id) + ".audio")

internal fun Controller.updatePcmCacheMb(mb: Int) {
    pcmCacheMb = mb.coerceIn(0, 16384)
    sp.edit().putInt("pcm_cache_mb", pcmCacheMb).apply()
    trimPcmCache()
}
internal fun Controller.updateRemoteCacheMb(mb: Int) {
    remoteCacheMb = mb.coerceIn(0, 16384)
    sp.edit().putInt("remote_cache_mb", remoteCacheMb).apply()
    trimRemoteCache()
}
internal fun Controller.cacheUsedMb(): Pair<Int, Int> {
    fun mb(d: java.io.File) = (d.listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)
    return mb(pcmDir).toInt() to mb(remoteDir).toInt()
}
internal fun Controller.clearAudioCache() {
    pcmDir.listFiles()?.forEach { it.delete() }
    remoteDir.listFiles()?.forEach { it.delete() }
    libRev++
    toast = "Audio cache cleared"
}
private fun Controller.trimPcmCache() {
    val files = pcmDir.listFiles { x -> x.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
    var total = 0L
    for (x in files) { total += x.length(); if (total > pcmCap) x.delete() }
}
private fun Controller.trimRemoteCache() {
    val files = remoteDir.listFiles { x -> x.name.endsWith(".audio") }?.sortedByDescending { it.lastModified() } ?: return
    var total = 0L
    for (x in files) { total += x.length(); if (total > remoteCap) x.delete() }
}

/** Memoised per library revision: composition must not stat the same files every frame. */
internal fun Controller.isCached(item: LibItem): Boolean {
    if (cachedMemoRev != libRev) { cachedMemo.clear(); cachedMemoRev = libRev }
    return cachedMemo.getOrPut(item.id) { isCachedNow(item) }
}

/** True when the track can play without the network (Plex original cached, PCM cached, or local). */
private fun Controller.isCachedNow(item: LibItem): Boolean {
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
private fun Controller.mediaSource(item: LibItem): Pair<Uri, Map<String, String>> {
    val r = remoteFile(item.id)
    val ok = remoteOk(r)
    val valid = r.exists() && r.length() > 4096 && ok.exists() &&
        (ok.readText().trim().toLongOrNull() ?: -1L) == r.length()
    if (valid) return Uri.fromFile(r) to emptyMap()
    return item.uri!! to item.streamHeaders
}

internal fun Controller.remoteOk(f: java.io.File) = java.io.File(remoteDir, f.name + ".ok")

/** Keep the original compressed file too: one download, then instant local decodes even after PCM eviction. */
private fun Controller.originalValid(item: LibItem): Boolean {
    val f = remoteFile(item.id)
    val ok = remoteOk(f)
    return f.exists() && f.length() > 4096 && ok.exists() &&
        (ok.readText().trim().toLongOrNull() ?: -1L) == f.length()
}

private fun Controller.prefetchOriginal(item: LibItem) {
    if (remoteCacheMb <= 0 || originalValid(item)) return
    viewModelScope.launch(Dispatchers.IO) { downloadOriginal(item) }
}

/** Download the compressed original; returns true when a valid local copy exists. */
internal suspend fun Controller.downloadOriginal(item: LibItem): Boolean {
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

/** Raw 16-bit PCM straight from disk: a 4-minute track loads in a fraction of a second. */
private fun Controller.loadPcm(item: LibItem): Track? {
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

internal fun Controller.savePcm(t: Track) {
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
private fun Controller.waveFile(id: String): java.io.File =
    java.io.File(java.io.File(ctx.filesDir, "wave").apply { mkdirs() }, "${md5(id)}.bin")
private fun Controller.hasWave(id: String) = waveFile(id).exists()

private fun Controller.saveWave(t: Track, gain: Float) {
    val w = t.wave ?: return; val ov = t.overview ?: return
    try {
        val bb = java.nio.ByteBuffer.allocate(16 + w.size + ov.size)
        bb.putFloat(gain); bb.putFloat(t.rms); bb.putInt(w.size); bb.putInt(ov.size); bb.put(w); bb.put(ov)
        waveFile(t.id).writeBytes(bb.array())
    } catch (_: Exception) {}
}

private fun Controller.loadWave(t: Track): Boolean {
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
internal fun Controller.togglePreview(item: LibItem) {
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

internal fun Controller.stopPreview() {
    engine.stopPreview(); previewId = null; previewLoading = false
    previewCache = null   // release a full decoded track as soon as the preview ends
}

/** A track is "played" once it has been audible on the master for 5 s (fader + crossfader up). */
internal fun Controller.trackOnAir() {
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

internal fun Controller.loadHistory() {
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
internal fun Controller.historySessions(): List<List<HistEntry>> {
    val out = ArrayList<MutableList<HistEntry>>()
    for (e in history) {
        if (out.isEmpty() || e.time - out.last().last().time > 90 * 60_000L) out.add(mutableListOf())
        out.last().add(e)
    }
    return out.reversed()
}

internal fun Controller.tracklistText(session: List<HistEntry>): String {
    val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val df = java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault())
    val head = "FLUX set · ${df.format(java.util.Date(session.first().time))} · ${tf.format(java.util.Date(session.first().time))}–${tf.format(java.util.Date(session.last().time))}"
    val lines = session.mapIndexed { n, e ->
        "${n + 1}. ${tf.format(java.util.Date(e.time))}  [${deckName(e.deck)}]  ${e.title}${if (e.artist.isNotEmpty()) " — ${e.artist}" else ""}  (${String.format("%.0f", e.bpm)} BPM${if (e.key >= 0) " · ${Keys.camelot(e.key)}" else ""})"
    }
    return (listOf(head, "") + lines).joinToString("\n")
}
internal fun Controller.clearHistory() { history = emptyList(); confirmClearHistory = false; viewModelScope.launch(Dispatchers.IO) { historyFile.delete() } }

// ------------------------------------------------------------------ library intelligence
/** Analysis format version; "u" marks a grid the user edited by hand (never re-analysed). */
private val metaVersion = "2"

/**
 * Field 7 is the source: "2" = FLUX analysis, "u:<time>" = edited by hand in FLUX, "rb:<time>" = rekordbox import.
 * Hand edits and rekordbox data are never overwritten by automatic analysis.
 */
internal fun Controller.saveMeta(id: String, t: Track, fbValid: Boolean, user: Boolean = false, source: String? = null) {
    val src = source ?: if (user) "u:${System.currentTimeMillis()}" else metaVersion
    val ed = cache.edit().putString(id, "${t.bpm}|${t.key}|${if (fbValid) t.firstBeat else -1.0}|${t.energy}|${t.pattern}|${t.vibe}|$src")
    val map = t.tempoMapString()
    if (map != null) ed.putString("tmap:$id", map) else if (user || source != null) ed.remove("tmap:$id")
    ed.apply()
}

/** true when the grid/key came from a hand edit or rekordbox (never replace it with automatic analysis). */
internal fun Controller.protectedMeta(p: List<String>?): Boolean = p?.getOrNull(6)?.let { it.startsWith("u") || it.startsWith("rb") } == true

/** Keep the protected grid/key/map, update only the automatic fields (energy, pattern, vibe). */
private fun Controller.saveProfileOnly(id: String, t: Track) {
    val p = metaParts(id) ?: return
    cache.edit().putString(id, listOf(p[0], p[1], p[2], "${t.energy}", t.pattern, t.vibe, p[6]).joinToString("|")).apply()
}

internal fun Controller.metaParts(id: String): List<String>? = cache.getString(id, null)?.split("|")
private fun Controller.metaCurrent(p: List<String>?): Boolean = p != null && p.size >= 7 && (p[6] == metaVersion || protectedMeta(p))

internal fun Controller.applyMeta(it: LibItem): Boolean {
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

internal fun Controller.startIndexing() {
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

private fun Controller.indexOne(it: LibItem) {
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

private fun Controller.rebeat(it: LibItem) {
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

internal fun Controller.refMeta(forDeck: Int): RefMeta? {
    val t = when {
        forDeck in 0..1 -> engine.decks[1 - forDeck].track
        else -> engine.decks[engine.leader()].track
    } ?: return null
    if (t.bpm <= 0) return null
    return RefMeta(t.bpm, t.key, t.energy, t.vibe, t.pattern, t.title)
}

internal fun Controller.allItems(): List<LibItem> =
    (demos + imported + plexItems + deviceTracks).distinctBy { it.id }
/** Everything the user hasn't removed from the library. */
internal fun Controller.visibleItems(): List<LibItem> = allItems().filter { it.id !in hidden }
/** Hides the track from FLUX — the file itself is never touched. */
internal fun Controller.confirmRemove() {
    val it = pendingRemove ?: return
    pendingRemove = null
    hidden = hidden + it.id
    sp.edit().putStringSet("hidden", hidden).apply()
    libRev++
    toast = "Removed “${it.title}” from your library"
}
internal fun Controller.restore(item: LibItem) {
    hidden = hidden - item.id
    sp.edit().putStringSet("hidden", hidden).apply()
    if (hiddenCount == 0) showHidden = false
    libRev++
    toast = "Restored “${item.title}”"
}

private fun Controller.loadedIds(): Set<String> = engine.decks.mapNotNull { it.track?.id }.toSet()

internal fun Controller.suggestions(target: Int, n: Int = 4): List<LibItem> {
    val r = refMeta(target) ?: return emptyList()
    val skip = loadedIds()
    return visibleItems().asSequence().filter { it.id !in skip }.mapNotNull { i -> relevance(i, r)?.let { i to it } }
        .filter { tempoDiffPct(it.first.bpm, r.bpm) <= 10f }
        .sortedBy { it.second }.take(n).map { it.first }.toList()
}

internal fun Controller.idleDeck(): Int = when {
    engine.decks[0].playing && !engine.decks[1].playing -> 1
    engine.decks[1].playing && !engine.decks[0].playing -> 0
    else -> 1 - engine.leader()
}

/**
 * Ranking the whole library is O(n log n); it must never run in the 75 Hz poll on the main
 * thread. Coalesce requests and publish only a result that still matches the library revision.
 */
internal fun Controller.scheduleNext() {
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

/** Filtered + sorted list for the library panel. */
internal fun Controller.filteredLibrary(target: Int, query: String): List<LibItem> {
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

// ------------------------------------------------------------------ importing & loading
internal fun Controller.loadImports() {
    val lines = (sp.getString("imports", "") ?: "").split("\n").filter { it.contains("\t") }
    imported = lines.map { l ->
        val p = l.split("\t", limit = 3)
        val uri = Uri.parse(p[0])
        val kind = if (p[0].startsWith("gdrive:")) "drive" else "import"
        val u = if (p[0].startsWith("gdrive:")) Uri.parse(p[0].removePrefix("gdrive:")) else uri
        LibItem(p[0], p[1], "", 0, u, -1, added = p.getOrNull(2)?.toLongOrNull() ?: 0, fileStem = p[1], sourceKind = kind).also { applyMeta(it) }
    }
}

internal fun Controller.saveImports() {
    sp.edit().putString("imports", imported.joinToString("\n") { "${if (it.sourceKind == "drive") "gdrive:" else ""}${it.uri}\t${it.title}\t${it.added}" }).apply()
}

internal fun Controller.displayName(uri: Uri): String {
    var name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track"
    try { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.') } } catch (_: Exception) {}
    return name
}

internal fun Controller.addFiles(uris: List<Uri>) {
    val have = imported.map { it.uri.toString() }.toSet()
    val fresh = uris.filter { it.toString() !in have }.map { displayName(it).let { n -> LibItem("imp:$it", n, "", 0, it, -1, added = System.currentTimeMillis(), fileStem = n, sourceKind = "import") }.also { i -> applyMeta(i) } }
    if (fresh.isEmpty()) { toast = "Already in your library"; return }
    imported = imported + fresh; saveImports(); libRev++; rbRematch(); startIndexing()
    toast = "Added ${fresh.size} track${if (fresh.size > 1) "s" else ""} · analysing in the background"
}

/** Tap on a library row: load into the requested deck, or the idle one. */
internal fun Controller.loadTap(item: LibItem, target: Int) {
    val dest = if (target in 0..1) target else idleDeck()
    load(dest, item)
    if (pendingLoad == null) toast = "“${item.title}” → Deck ${deckName(dest)}"
}

internal fun Controller.loadById(deck: Int, id: String) {
    val item = allItems().firstOrNull { it.id == id } ?: return
    load(deck, item)
    if (pendingLoad == null) toast = "“${item.title}” → Deck ${deckName(deck)}"
}
private fun Controller.sampleFile(k: Int) = java.io.File(sampleDir, "pad$k.pcm")

internal fun Controller.loadCustomSamples() {
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

private fun Controller.storeSample(k: Int, data: FloatArray, name: String) {
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
internal fun Controller.setSampleFromUri(k: Int, uri: Uri) {
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
internal fun Controller.captureSample(k: Int, deck: Int) {
    val d = engine.decks[deck]
    val t = d.track ?: return
    val (s, e, what) = if (d.loopOn) Triple(d.loopIn, d.loopOut, "LOOP")
        else Triple(d.pos, if (t.bpm > 0) d.beatsFrom(d.pos, 4.0) else d.pos + SR * 2.0, if (t.bpm > 0) "BAR" else "2S")
    storeSample(k, SampleBank.fromStereo(t.pcm, s.toInt(), e.toInt(), trim = false), "${deckName(deck)}·$what")
}

internal fun Controller.resetSample(k: Int) {
    SampleBank.reset(k); sampleNames = SampleBank.names.toList()
    sampleFile(k).delete(); sp.edit().remove("sample_name_$k").apply()
    toast = "Pad ${k + 1} restored"
}
