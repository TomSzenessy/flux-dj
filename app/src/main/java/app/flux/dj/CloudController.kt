package app.flux.dj

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import app.flux.dj.audio.*
import kotlinx.coroutines.*

private val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".aiff", ".aif", ".wma")

/** Download the current Plex selection (collection-only or all music) for offline playback. */
internal fun Controller.downloadPlexCache() {
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

/** Cache one Plex/Drive track (compressed original when possible, otherwise decoded PCM). */
internal fun Controller.cacheOne(item: LibItem) {
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
            if (ok) invalidateCacheStates()
            libRev++
            toast = if (ok) "“${item.title}” cached for offline" else "Couldn't cache “${item.title}”"
        }
    }
}

internal fun Controller.uncacheOne(item: LibItem) {
    pcmFile(item.id).delete()
    val r = remoteFile(item.id)
    r.delete(); remoteOk(r).delete()
    invalidateCacheStates()
    libRev++
    toast = "Removed the offline copy of “${item.title}”"
}

/** Google Drive (or any document provider) as a live source: audio streams, and collection.xml is picked up automatically. */
internal fun Controller.connectCloudFolder(tree: Uri) {
    cloudFolder = tree.toString()
    cloudFolderName = folderName(tree)
    sp.edit().putString("cloud_tree", cloudFolder).putString("cloud_tree_name", cloudFolderName).apply()
    scanFolder(tree, cloud = true, announce = true)
}

internal fun Controller.disconnectCloudFolder() {
    scanGen.incrementAndGet()
    cloudFolder = ""; cloudFolderName = ""
    sp.edit().remove("cloud_tree").remove("cloud_tree_name").apply()
    imported = imported.filter { it.sourceKind != "drive" }
    saveImports(); libRev++
    toast = "Cloud folder disconnected — its tracks stay out of the library"
}

internal fun Controller.resyncCloudFolder() {
    val u = cloudFolder.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) } ?: return
    scanFolder(u, cloud = true, announce = true)
}

private fun Controller.folderName(tree: Uri): String {
    var name = "Cloud folder"
    try {
        val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
        ctx.contentResolver.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
    } catch (_: Exception) {}
    return name
}

internal fun Controller.scanFolder(tree: Uri, cloud: Boolean, announce: Boolean) {
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

// ------------------------------------------------------------------ Plex / Plexamp
internal fun Controller.plexSignIn() {
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

private fun Controller.applyPin(pin: PlexPin) {
    plexPinCode = pin.code
    plexPinExpiresAt = System.currentTimeMillis() + pin.expiresInSec * 1000L
    plexStatus = "Enter the code at app.plex.tv/link"
}

/**
 * Poll the PIN until the user links it. Plex PINs expire, so the code is refreshed in place a
 * few seconds before it dies — the screen always shows a live code, no manual restart.
 */
private fun Controller.pollPlexPin(initial: PlexPin) {
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
internal fun Controller.plexRefresh() {
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

internal fun Controller.setPlexFilter(collectionOnly: Boolean) {
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
private fun Controller.filterPlex(all: List<LibItem>): List<LibItem> {
    if (!plexCollectionOnly) return all
    val lib = rb ?: return all
    val cands = all.map { Rekordbox.Candidate(it.id, it.title, it.artist, (it.durMs / 1000).toInt(), it.fileStem) }
    val matched = Rekordbox.match(lib, cands).values.toSet()
    return all.filter { it.id in matched }
}

internal fun Controller.refilterPlex() {
    if (plexAllCache.isNotEmpty()) {
        plexItems = filterPlex(plexAllCache)
        savePlex(); libRev++
    }
}

/** Close the sign-in sheet: stop polling so no orphan PIN refresh continues in the background. */
internal fun Controller.stopPlexSignIn() {
    if (plexToken.isBlank()) {
        plexPoll?.cancel(); plexPoll = null
        plexBusy = false; plexPinCode = null; plexPinExpiresAt = 0L; plexStatus = ""
    }
}

internal fun Controller.plexSignOut() {
    bulkJob?.cancel(); bulkDownloading = false
    plexPoll?.cancel(); plexPoll = null
    plexToken = ""; plexServer = ""; plexServerName = ""; plexItems = emptyList(); plexPinCode = null
    plexPinExpiresAt = 0L; plexAllCache = emptyList()
    plexStatus = ""; plexError = null; plexBusy = false
    sp.edit().remove("plex_token").remove("plex_server").remove("plex_server_name").apply()
    plexFile.delete(); libRev++
    toast = "Signed out of Plex"
}

private fun Controller.savePlex() {
    try {
        plexFile.writeText(plexItems.joinToString("\n") { i ->
            listOf(i.id, i.title, i.artist, i.durMs.toString(), i.uri.toString(), i.fileStem).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        })
    } catch (_: Exception) {}
}

internal fun Controller.loadPlex() {
    plexItems = try {
        if (!plexFile.exists()) emptyList() else plexFile.readLines().mapNotNull { l ->
            val p = l.split("\t")
            if (p.size < 6) null else LibItem(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0, Uri.parse(p[4]), -1,
                fileStem = p[5], sourceKind = "plex", streamHeaders = mapOf("X-Plex-Token" to plexToken)).also { applyMeta(it) }
        }
    } catch (_: Exception) { emptyList() }
}
