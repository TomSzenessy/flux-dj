package app.flux.dj.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.flux.dj.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.*
import app.flux.dj.Controller
import app.flux.dj.LibItem
import app.flux.dj.RefMeta
import app.flux.dj.audio.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs

@Composable
fun SidePanel(visible: Boolean, onDismiss: () -> Unit, title: String, width: Dp = 420.dp, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(C.scrimSoft).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
    }
    AnimatedVisibility(visible, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.widthIn(max = width).fillMaxWidth(0.94f).fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = D.r22, bottomStart = D.r22)).background(C.panelDeep)
                    .border(1.dp, C.line, RoundedCornerShape(topStart = D.r22, bottomStart = D.r22))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = T.tab.copy(fontSize = F.f17, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(D.r24)).background(C.s3).pointerInput2 { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("✕", style = T.tab.copy(color = C.mu, fontSize = F.f14))
                    }
                }
                content()
            }
        }
    }
}

// ----------------------------------------------------------------------------- Library

@Composable
private fun FilterRow(label: String, opts: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label.uppercase(), style = T.cap.copy(fontSize = F.f8_5, letterSpacing = 0.6.sp), modifier = Modifier.width(52.dp))
        opts.forEachIndexed { i, (t, on) -> Chip(t, on, C.ok, Modifier.height(48.dp)) { onPick(i) } }
    }
}

@Composable
fun LibraryPanel(c: Controller, visible: Boolean, target: Int, onDismiss: () -> Unit, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onPermission: () -> Unit, onHistory: () -> Unit = {}, onImportXml: () -> Unit = {}, onSources: () -> Unit = {}) {
    SidePanel(visible, onDismiss, "Library", 580.dp) {
        var q by remember { mutableStateOf("") }
        val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 520
        val rev = c.libRev
        val ref: RefMeta? = remember(visible, target, rev, c.ui[0].title, c.ui[1].title) { c.refMeta(target) }
        val dest = if (target in 0..1) target else c.idleDeck()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(D.r10)).background(C.s2).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(q, { q = it }, singleLine = true, textStyle = T.tab.copy(fontSize = F.f14), cursorBrush = SolidColor(C.a), modifier = Modifier.fillMaxWidth())
                if (q.isEmpty()) Text(stringResource(R.string.search_tracks), style = T.tab.copy(color = C.dim, fontSize = F.f14))
            }
            Chip(stringResource(R.string.files), true, C.a, Modifier.height(48.dp)) { onAddFiles() }
            Chip(stringResource(R.string.folder), true, C.a, Modifier.height(48.dp)) { onAddFolder() }
            Chip(stringResource(R.string.history), false, C.a, Modifier.height(48.dp)) { onHistory() }
            Chip(if (c.plexToken.isNotBlank() || c.cloudFolder.isNotEmpty()) "☁ Sources ●" else "☁ Sources", c.plexToken.isNotBlank() || c.cloudFolder.isNotEmpty(), C.ok, Modifier.height(48.dp)) { onSources() }
            Chip(if (c.rb == null) "rekordbox" else "rekordbox ↻", false, C.a, Modifier.height(48.dp)) { onImportXml() }
        }
        Text(if (target in 0..1) "Tap a track to load it on deck ${c.deckName(target)}  ·  or drag it onto a waveform" else "Tap a track to load it on the free deck  ·  or drag it onto a waveform", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = F.f10, color = C.mu))

        // background analysis status
        if (c.indexTotal > 0) {
            val frac = c.indexDone.toFloat() / c.indexTotal
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r10)).background(C.s1).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (c.indexDone < c.indexTotal) "✦ Analysing local library · ${c.indexDone} / ${c.indexTotal}" + (if (c.indexSkipped > 0) " · ${c.indexSkipped} from collection.xml kept" else "") else "✦ Local library analysed · ${c.indexTotal} tracks" + (if (c.indexSkipped > 0) " · ${c.indexSkipped} with collection.xml grids" else ""), style = T.cap.copy(color = C.ok, letterSpacing = 0.2.sp))
                    if (c.indexDone < c.indexTotal) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(D.r2)).background(C.s3)) { Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.ok)) }
                }
                if (c.indexDone < c.indexTotal) Chip(if (c.indexPaused) "Resume" else "Pause", false, C.ok, Modifier.height(48.dp)) { c.toggleIndexPause() }
            }
        }

        // crate: all tracks or a rekordbox playlist
        var pickerOpen by remember { mutableStateOf(false) }
        if (c.rb != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val pl = c.playlist
            Chip(if (pl == null) "▤  All tracks  ▾" else "▤  ${pl.path}  ▾", pickerOpen || pl != null, C.fx, Modifier.height(48.dp)) { pickerOpen = !pickerOpen }
            if (pl != null) {
                Text("${c.playlistOnTablet(pl)} of ${pl.trackIds.size} on this tablet", style = T.cap.copy(letterSpacing = 0.2.sp))
                Chip("✕", false, C.mu, Modifier.height(48.dp)) { c.selectPlaylist(null) }
            }
        }
        if (pickerOpen && c.rb != null) {
            PlaylistPicker(c, Modifier.weight(1f), onImportXml) { pickerOpen = false }
            return@SidePanel
        }

        // sort (always visible) + filters (folded away)
        var filtersOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(stringResource(R.string.sort), style = T.cap.copy(fontSize = F.f8_5, letterSpacing = 0.6.sp), modifier = Modifier.padding(end = 2.dp))
            if (c.playlist != null) Chip(stringResource(R.string.playlist_order), c.sortMode == 7, C.a, Modifier.height(48.dp)) { c.sortMode = 7 }
            listOf(if (ref != null) "✦ Recommended" else "Recommended", "Newest", "A–Z", "Artist", "BPM", "Key", "Energy").forEachIndexed { i, t ->
                Chip(t, c.sortMode == i, C.a, Modifier.height(48.dp)) { c.sortMode = i }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(stringResource(R.string.source), style = T.cap.copy(fontSize = F.f8_5, letterSpacing = 0.6.sp), modifier = Modifier.padding(end = 2.dp))
            Chip(stringResource(R.string.all), c.filterSource == null, C.ok, Modifier.height(48.dp)) { c.filterSource = null }
            if (c.hasLocal) Chip(stringResource(R.string.local), c.filterSource == "local", C.ok, Modifier.height(48.dp)) { c.filterSource = "local" }
            if (c.hasPlex) Chip(stringResource(R.string.plex), c.filterSource == "plex", C.ok, Modifier.height(48.dp)) { c.filterSource = "plex" }
            if (c.hasDrive) Chip(stringResource(R.string.drive), c.filterSource == "drive", C.ok, Modifier.height(48.dp)) { c.filterSource = "drive" }
        }
        if (!compact) Text(stringResource(R.string.plex_drive_green_cached_tap_to_remove_red_cloud_tap_to_cache_rb_rekordbox_collection_grid_hand_edited_grid), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f9, fontWeight = FontWeight.Normal))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (c.activeFilters > 0) "Filters · ${c.activeFilters}" else "Filters", filtersOpen || c.activeFilters > 0, C.ok, Modifier.height(48.dp)) { filtersOpen = !filtersOpen }
            if (c.activeFilters > 0) Chip(stringResource(R.string.clear), false, C.ok, Modifier.height(48.dp)) { c.filterKey = 0; c.filterBpm = 0; c.filterEnergy = 0; c.filterVibe = null; c.filterSource = null }
            Spacer(Modifier.weight(1f))
            if (c.hiddenCount > 0) Chip(if (c.showHidden) "◀ Back to library" else "Removed · ${c.hiddenCount}", c.showHidden, C.mu, Modifier.height(48.dp)) { c.showHidden = !c.showHidden }
        }
        if (filtersOpen) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterRow("Key", listOf("Any" to (c.filterKey == 0), "Compatible" to (c.filterKey == 1))) { c.filterKey = it }
            FilterRow("Tempo", listOf("Any" to (c.filterBpm == 0), "±3%" to (c.filterBpm == 3), "±6%" to (c.filterBpm == 6), "±10%" to (c.filterBpm == 10))) { c.filterBpm = intArrayOf(0, 3, 6, 10)[it] }
            FilterRow("Energy", listOf("Any" to (c.filterEnergy == 0), "Low" to (c.filterEnergy == 1), "Mid" to (c.filterEnergy == 2), "High" to (c.filterEnergy == 3))) { c.filterEnergy = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(stringResource(R.string.vibe), style = T.cap.copy(fontSize = F.f8_5, letterSpacing = 0.6.sp), modifier = Modifier.width(52.dp))
                Chip(stringResource(R.string.all), c.filterVibe == null, C.fx, Modifier.height(48.dp)) { c.filterVibe = null }
                c.vibesPresent.forEach { v -> Chip(v, c.filterVibe == v, C.fx, Modifier.height(48.dp)) { c.filterVibe = if (c.filterVibe == v) null else v } }
            }
            if (ref == null) Text(stringResource(R.string.key_tempo_filters_compare_against_the_track_on_the_other_deck_load_one_first), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
        }

        val list = remember(rev, q, target, visible, c.filterKey, c.filterBpm, c.filterVibe, c.filterEnergy, c.filterSource, c.sortMode, ref, c.showHidden, c.hidden, c.playlist, c.rbMatch) { c.filteredLibrary(target, q) }
        Text(if (c.showHidden) "${list.size} removed track${if (list.size == 1) "" else "s"} — tap Restore to bring one back" else "${list.size} track${if (list.size == 1) "" else "s"}" + if (c.sortMode == 0 && ref != null) " · best matches for “${ref.title}” first" else "", style = T.cap.copy(letterSpacing = 0.3.sp), maxLines = 1)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!c.libPermission) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r12)).background(C.s2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.allow_access_to_see_and_auto_analyse_the_music_on_this_device), style = T.tab.copy(color = C.mu), modifier = Modifier.weight(1f))
                    Chip(stringResource(R.string.allow), true, C.a, Modifier.height(48.dp)) { onPermission() }
                }
            }
            items(list, key = { it.id }) { it -> LibRow(c, it, ref, target, onDismiss) }
        }
    }
}

// ----------------------------------------------------------------------------- Sources

@Composable
fun SourcesPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit, onConnectDrive: () -> Unit, onImportXml: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.plexPinExpiresAt) {
        while (c.plexPinExpiresAt > 0L) {
            clock = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    SidePanel(visible, onDismiss, "Music sources", 540.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SectionLabel(stringResource(R.string.plex_plexamp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r14)).background(C.s1).border(1.dp, C.ok.copy(alpha = .35f), RoundedCornerShape(D.r14)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.plexToken.isNotBlank()) {
                    Text(if (c.plexServerName.isNotEmpty()) "Connected to ${c.plexServerName}" else "Plex connected", style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = F.f14, color = C.ok))
                    Text("${c.plexItems.size} tracks stream from Plex · ${c.plexStatus.ifBlank { "ready" }}", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(stringResource(R.string.my_collection), c.plexCollectionOnly, C.ok, Modifier.height(48.dp)) { c.setPlexFilter(true) }
                        Chip(stringResource(R.string.all_music), !c.plexCollectionOnly, C.ok, Modifier.height(48.dp)) { c.setPlexFilter(false) }
                    }
                    Text(
                        if (c.plexCollectionOnly && c.rb == null) "Import a collection.xml to use this filter — until then all Plex music is shown."
                        else if (c.plexCollectionOnly) "Only Plex tracks that match a collection.xml entry are shown. Audiobook libraries are always skipped."
                        else "Every music track from Plex is shown. Audiobook/spoken-word libraries are skipped.",
                        style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (c.plexBusy) "Syncing…" else "Sync library", !c.plexBusy, C.ok, Modifier.weight(1.4f).height(38.dp), solid = true) { if (!c.plexBusy) c.plexRefresh() }
                        PillButton(stringResource(R.string.sign_out), false, C.mu, Modifier.weight(1f).height(38.dp)) { c.plexSignOut() }
                    }
                    Text(stringResource(R.string.tracks_play_straight_from_the_server_and_are_cached_as_you_use_them_so_the_same_file_is_instant_next_time_even_offline_plex_drive_tracks_are_analysed_lazily_when_you_first_play_them_not_downloaded_in_the_background), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                    Text(stringResource(R.string.plex_only_setup_choose_my_collection_import_the_matching_collection_xml_and_let_plex_supply_the_audio_while_the_xml_supplies_grids_bpm_and_playlists), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.ok, fontWeight = FontWeight.Normal))
                    if (c.bulkDownloading) {
                        val frac = if (c.bulkTotal > 0) c.bulkDone.toFloat() / c.bulkTotal else 0f
                        Text("Downloading for offline: ${c.bulkDone} / ${c.bulkTotal}" + (if (c.bulkFailed > 0) " · ${c.bulkFailed} failed" else ""), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.a, fontWeight = FontWeight.Normal))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(D.r2)).background(C.s4)) {
                            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(C.ok))
                        }
                        PillButton(stringResource(R.string.cancel_download), false, C.bad, Modifier.fillMaxWidth().height(34.dp)) { c.cancelPlexDownload() }
                    } else {
                        val cached = c.plexItems.count { c.isCached(it) }
                        PillButton("Download ${c.plexItems.size - cached} Plex tracks for offline", true, C.ok, Modifier.fillMaxWidth().height(38.dp)) { c.downloadPlexCache() }
                        Text("${cached} of ${c.plexItems.size} already cached. Downloads the current selection (collection-only or all music) as compressed originals; raise the Plex cache size below first if needed.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                    }
                } else {
                    Text(stringResource(R.string.use_your_plex_library_the_same_one_plexamp_sees_without_downloading_it_first), style = T.tab.copy(fontSize = F.f13))
                    c.plexPinCode?.let { code ->
                        Text(stringResource(R.string.sign_in_code), style = T.cap.copy(letterSpacing = 0.4.sp, fontSize = F.f9))
                        Text(code.chunked(4).joinToString(" "), style = T.num.copy(fontSize = F.f16, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = C.ok), maxLines = 3)
                        val left = ((c.plexPinExpiresAt - clock) / 1000L).coerceAtLeast(0L)
                        Text(if (left > 0) "Plex one-time code · refreshes automatically in ${left / 60}:${String.format("%02d", left % 60)}" else "Refreshing the code…", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = F.f10, color = C.mu))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(stringResource(R.string.copy_code), true, C.ok, Modifier.weight(1f).height(36.dp)) {
                                try {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Plex code", code))
                                    c.toast = "Code copied — paste it at plex.tv/link"
                                } catch (_: Exception) { c.toast = "Copy failed — the code is $code" }
                            }
                            PillButton(stringResource(R.string.open_plex_sign_in), true, C.ok, Modifier.weight(1.3f).height(36.dp), solid = true) {
                                try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.plexAuthUrl() ?: "https://app.plex.tv/auth"))) }
                                catch (_: Exception) { c.toast = "Open app.plex.tv on any browser" }
                            }
                        }
                        Text(stringResource(R.string.you_don_t_need_to_type_it_tap_open_plex_sign_in_if_you_prefer_copy_code_and_paste_it_at_app_plex_tv_link), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                    }
                    PillButton(if (c.plexBusy) "Waiting for plex.tv…" else "Sign in with Plex", !c.plexBusy, C.ok, Modifier.fillMaxWidth().height(40.dp), solid = true) { c.plexSignIn() }
                }
                c.plexError?.let { Text(it, style = T.tab.copy(color = C.bad, fontSize = F.f11)) }
            }

            SectionLabel(stringResource(R.string.google_drive_cloud_folder))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r14)).background(C.s1).border(1.dp, C.a.copy(alpha = .35f), RoundedCornerShape(D.r14)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.cloudFolder.isNotEmpty()) {
                    Text(c.cloudFolderName.ifBlank { "Cloud folder" }, style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = F.f14, color = C.a))
                    Text("${c.imported.count { it.sourceKind == "drive" }} tracks · files stream from Drive, never copied to the tablet", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                    if (c.rbSource.contains("collection", true) && c.rbSource.contains(c.cloudFolderName, true)) Text("collection.xml found in this folder · ${c.rbMatch.size} tracks matched", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.fx, fontWeight = FontWeight.Normal))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (c.cloudScanning) "Scanning…" else "Resync folder", !c.cloudScanning, C.a, Modifier.weight(1.4f).height(38.dp), solid = true) { c.resyncCloudFolder() }
                        PillButton(stringResource(R.string.disconnect), false, C.mu, Modifier.weight(1f).height(38.dp)) { c.disconnectCloudFolder() }
                    }
                } else {
                    Text(stringResource(R.string.pick_a_drive_folder_or_any_cloud_folder_flux_lists_its_audio_streams_it_on_demand_and_picks_up_a_collection_xml_in_the_same_folder), style = T.tab.copy(fontSize = F.f13))
                    PillButton(stringResource(R.string.connect_drive_folder), true, C.a, Modifier.fillMaxWidth().height(40.dp), solid = true) { onDismiss(); onConnectDrive() }
                }
            }

            SectionLabel(stringResource(R.string.collection_xml))
            Text(
                if (c.rb == null) "No collection imported yet. Import one here, or put collection.xml inside the Drive folder and resync."
                else "${c.rbSource.ifBlank { "collection.xml" }} · ${c.rbMatch.size} of ${c.rb!!.tracks.size} matched · ${c.rb!!.playlists.size} playlists. Cloud/rekordbox grids override local analysis, but a hand-edited BPM or grid is never touched.",
                style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.import_xml), false, C.a, Modifier.weight(1f).height(38.dp)) { onDismiss(); onImportXml() }
                if (c.rb != null) PillButton(stringResource(R.string.remove), false, C.bad, Modifier.weight(1f).height(38.dp)) { c.removeRekordbox() }
            }

            SectionLabel(stringResource(R.string.local_storage))
            var usage by remember { mutableStateOf(c.cacheUsedMb()) }
            LaunchedEffect(visible) { if (visible) usage = c.cacheUsedMb() }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r14)).background(C.s1).border(1.dp, C.line, RoundedCornerShape(D.r14)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Decoded audio: ${usage.first} MB of ${c.pcmCacheMb} MB · Plex originals: ${usage.second} MB of ${c.remoteCacheMb} MB", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                Text(stringResource(R.string.decoded_pcm), style = T.cap.copy(letterSpacing = 0.4.sp, fontSize = F.f9))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (gb in listOf(0, 1, 2, 3, 4, 6, 8, 12, 16)) Chip(if (gb == 0) "Off" else "${gb}G", c.pcmCacheMb == gb * 1024, C.a, Modifier.height(48.dp)) { c.updatePcmCacheMb(gb * 1024); usage = c.cacheUsedMb() }
                }
                Text(stringResource(R.string.plex_cloud_originals), style = T.cap.copy(letterSpacing = 0.4.sp, fontSize = F.f9))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (gb in listOf(0, 1, 2, 4, 8, 16)) Chip(if (gb == 0) "Off" else "${gb}G", c.remoteCacheMb == gb * 1024, C.a, Modifier.height(48.dp)) { c.updateRemoteCacheMb(gb * 1024); usage = c.cacheUsedMb() }
                }
                Text(stringResource(R.string.original_files_are_kept_compressed_and_decode_locally_next_time_so_a_plex_only_library_stays_fast_without_keeping_every_track_as_huge_pcm), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.clear_audio_cache), false, C.bad, Modifier.weight(1f).height(36.dp)) { c.clearAudioCache(); usage = 0 to 0 }
                    PillButton(stringResource(R.string.refresh), false, C.mu, Modifier.weight(0.7f).height(36.dp)) { usage = c.cacheUsedMb() }
                }
            }

            SectionLabel(stringResource(R.string.analysis))
            Text("${c.indexSkipped} tracks use a rekordbox/collection grid: BPM/key analysis is skipped for them (waveforms are still built).", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
            Text(stringResource(R.string.local_analysed_on_this_tablet_rb_from_collection_xml_you_changed_the_grid_by_hand), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LibRow(c: Controller, it: LibItem, ref: RefMeta?, target: Int, onDismiss: () -> Unit) {
    val kd = if (ref != null) Keys.distance(it.key, ref.key) else -1
    val kcol = when (kd) { 0 -> C.ok; 1 -> C.ok.copy(alpha = .75f); 2 -> C.fx; 9 -> C.bad.copy(alpha = .7f); else -> C.dim }
    val previewing = c.previewId == it.id
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r12)).background(if (previewing) C.s2 else C.s1).border(1.dp, if (previewing) C.fx.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(D.r12))) {
    Row(
        Modifier.fillMaxWidth()
            .pointerInput2 { c.loadTap(it, target); onDismiss() }
            .dragAndDropSource(block = {
                detectTapGestures(onLongPress = { _ ->
                    startTransfer(DragAndDropTransferData(android.content.ClipData.newPlainText("flux-track", it.id)))
                })
            })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(it.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = F.f14), maxLines = 1)
            val sub = buildString {
                append(it.artist.ifEmpty { if (it.demo >= 0) "FLUX demo" else "" })
                if (it.vibe.isNotEmpty() && it.vibe != "-") { if (isNotEmpty()) append(" · "); append(it.vibe); if (it.pattern.isNotEmpty() && it.pattern != "-") append(" · ${it.pattern}") }
            }
            Text(sub, style = T.cap.copy(letterSpacing = 0.2.sp), maxLines = 1)
        }
        if (it.energy > 0) EnergyBars(it.energy)
        if (it.bpm > 0) {
            val delta = if (ref != null) (it.bpm / ref.bpm - 1f) * 100f else null
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(46.dp)) {
                Text(String.format("%.0f", it.bpm), style = T.num.copy(fontWeight = FontWeight.SemiBold))
                if (delta != null) Text(String.format("%+.1f%%", delta), style = T.cap.copy(letterSpacing = 0.sp, color = if (abs(delta) < 3f) C.ok else if (abs(delta) < 8f) C.fx else C.mu))
            }
        } else if (it.energy == 0 && it.uri != null) Text("···", style = T.cap)
        if (it.analysisSource == "rekordbox") Text(stringResource(R.string.rb), style = T.cap.copy(fontSize = F.f8, letterSpacing = 0.5.sp, color = C.fx, fontWeight = FontWeight.Bold))
        else if (it.analysisSource == "manual") Text("✎", style = T.cap.copy(fontSize = F.f10, letterSpacing = 0.sp, color = C.fx, fontWeight = FontWeight.Bold))
        else if (it.analysisSource == "flux") Text(stringResource(R.string.local_2), style = T.cap.copy(fontSize = F.f7, letterSpacing = 0.4.sp, color = C.dim, fontWeight = FontWeight.Bold))
        if (it.sourceKind == "plex" || it.sourceKind == "drive") {
            val cached = c.isCached(it)
            val label = (if (it.sourceKind == "plex") "PLEX" else "DRIVE") + if (cached) " ✓" else " ⇩"
            Text(
                label,
                modifier = Modifier.clip(RoundedCornerShape(D.r5)).pointerInput2 { c.cacheOne(it) }.padding(horizontal = 2.dp, vertical = 1.dp),
                style = T.cap.copy(fontSize = F.f7, letterSpacing = 0.4.sp, fontWeight = FontWeight.Bold),
                color = if (cached) C.ok else C.bad,
            )
        }
        if (it.key >= 0) Box(Modifier.clip(RoundedCornerShape(D.r7)).background(kcol.copy(alpha = .18f)).border(1.dp, kcol.copy(alpha = .7f), RoundedCornerShape(D.r7)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text(Keys.camelot(it.key), style = T.num.copy(color = kcol, fontSize = F.f12, fontWeight = FontWeight.Bold))
        }
        if (c.showHidden) {
            Chip(stringResource(R.string.restore), true, C.ok, Modifier.height(48.dp)) { c.restore(it) }
        } else {
        HeadphoneBtn(previewing) { c.togglePreview(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (target != 1) DeckLoadBtn("A", C.a) { c.loadTap(it, 0); onDismiss() }
            if (target != 0) DeckLoadBtn("B", C.b) { c.loadTap(it, 1); onDismiss() }
        }
        RemoveBtn { c.askRemove(it) }
        }
    }
    if (previewing) PreviewBar(c)
    }
}

/** Clear, deck-coloured "load to deck" button. */
@Composable
private fun DeckLoadBtn(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp, 48.dp).clip(RoundedCornerShape(D.r9)).background(color.copy(alpha = .22f))
            .border(1.5.dp, color, RoundedCornerShape(D.r9)).pointerInput2(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("▸ $label", style = T.tab.copy(color = color, fontWeight = FontWeight.ExtraBold, fontSize = F.f13), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun RemoveBtn(onClick: () -> Unit) {
    Box(Modifier.size(48.dp, 48.dp).clip(RoundedCornerShape(D.r9))
        .semantics { role = androidx.compose.ui.semantics.Role.Button; contentDescription = "Remove from library" }
        .pointerInput2(onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
            val col = C.dim; val sw = size.width * 0.12f
            drawLine(col, androidx.compose.ui.geometry.Offset(0f, size.height * 0.2f), androidx.compose.ui.geometry.Offset(size.width, size.height * 0.2f), sw)
            drawLine(col, androidx.compose.ui.geometry.Offset(size.width * 0.35f, 0f), androidx.compose.ui.geometry.Offset(size.width * 0.65f, 0f), sw)
            drawRoundRect(col, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.3f), androidx.compose.ui.geometry.Size(size.width * 0.7f, size.height * 0.7f), androidx.compose.ui.geometry.CornerRadius(2f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        }
    }
}

@Composable
private fun HeadphoneBtn(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp, 48.dp).clip(RoundedCornerShape(D.r9)).background(if (on) C.fx else C.s3)
            .semantics { role = androidx.compose.ui.semantics.Role.Button; contentDescription = if (on) "Stop preview" else "Preview in headphones" }
            .pointerInput2(onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val col = if (on) Color.Black else C.tx
            val sw = size.width * 0.12f
            drawArc(col, 180f, 180f, false, androidx.compose.ui.geometry.Offset(sw, sw), androidx.compose.ui.geometry.Size(size.width - sw * 2, size.height - sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            drawRoundRect(col, androidx.compose.ui.geometry.Offset(0f, size.height * 0.5f), androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.45f), androidx.compose.ui.geometry.CornerRadius(3f))
            drawRoundRect(col, androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.5f), androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.45f), androidx.compose.ui.geometry.CornerRadius(3f))
        }
    }
}

/** Inline headphone preview: waveform overview you can tap / drag to scrub. */
@Composable
private fun PreviewBar(c: Controller) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (c.previewLoading) "Decoding…" else "🎧  ${c.previewTime}", style = T.num.copy(fontSize = F.f11, color = C.fx), modifier = Modifier.width(118.dp), maxLines = 1)
        androidx.compose.foundation.Canvas(
            Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(D.r6)).background(C.deep)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        c.seekPreview(down.position.x / size.width)
                        drag(down.id) { ch -> c.seekPreview(ch.position.x / size.width); ch.consume() }
                    }
                }
        ) {
            c.tick.longValue
            val t = c.previewTrack() ?: return@Canvas
            val w = size.width; val h = size.height
            val ov = t.overview
            val doneFrac = if (t.complete) 1f else (t.available.toFloat() / t.decodedFrames.coerceAtLeast(1)).coerceIn(0f, 1f)
            if (ov != null) {
                val cols = minOf(OV_COLS, w.toInt() / 2)
                val cw = w / cols
                for (x in 0 until cols) {
                    val oc = x * OV_COLS / cols
                    val a = ((ov[oc * 3].toInt() and 255) * 0.55f + (ov[oc * 3 + 1].toInt() and 255) * 0.3f + (ov[oc * 3 + 2].toInt() and 255) * 0.15f) / 255f
                    val hh = (a * h * 0.45f).coerceAtLeast(1f)
                    val played = x * cw < c.previewFrac * w
                    drawRect(if (played) C.fx else C.previewOff, androidx.compose.ui.geometry.Offset(x * cw, h / 2 - hh), androidx.compose.ui.geometry.Size(cw * 0.7f, hh * 2))
                }
            } else {
                // not analysed yet: show how much has been decoded
                drawRect(C.previewPending, size = androidx.compose.ui.geometry.Size(w * doneFrac, h))
                drawRect(C.fx.copy(alpha = .5f), androidx.compose.ui.geometry.Offset(0f, h / 2 - 1f), androidx.compose.ui.geometry.Size(w * c.previewFrac, 2f))
            }
            val x = c.previewFrac * w
            drawRect(Color.White, androidx.compose.ui.geometry.Offset(x - 1f, 0f), androidx.compose.ui.geometry.Size(2.5f, h))
        }
    }
}

@Composable
private fun EnergyBars(e: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(16.dp)) {
        for (i in 1..10) Box(Modifier.width(3.dp).height((4 + i * 1.2f).dp).clip(RoundedCornerShape(D.r1)).background(if (i <= e) (if (e >= 8) C.b else if (e >= 5) C.fx else C.a) else C.s3))
    }
}

// ----------------------------------------------------------------------------- FX

@Composable
fun FxPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    SidePanel(visible, onDismiss, "Effects", 400.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.beat_fx))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BeatFx.entries.take(3).forEach { Chip(it.label, c.fxType == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doFxType(it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BeatFx.entries.drop(3).forEach { Chip(it.label, c.fxType == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doFxType(it) } }
            }
            SectionLabel(stringResource(R.string.beat_division))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.25f to "¼", 0.5f to "½", 0.75f to "¾", 1f to "1", 2f to "2", 4f to "4").forEach { (b, l) ->
                    Chip(l, abs(c.fxDiv - b) < 0.01f, C.fx, Modifier.weight(1f).height(32.dp)) { c.doFxDiv(b) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Knob(c.fxLevel, { c.doFxLevel(it) }, C.fx, "LEVEL", Modifier.size(74.dp), default = 0.5f, bipolar = false)
                Knob(c.fxDepth, { c.doFxDepth(it) }, C.fx, "DEPTH", Modifier.size(74.dp), default = 0.5f, bipolar = false)
                PillButton(if (c.fxOn) "FX ON" else "FX OFF", c.fxOn, C.fx, Modifier.weight(1f).height(60.dp), solid = true) { c.doFxOn(!c.fxOn) }
            }
            SectionLabel(stringResource(R.string.apply_to))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.deck_a), c.fxA, C.a, Modifier.weight(1f).height(34.dp)) { c.setFxAssign(!c.fxA, c.fxB, c.fxMaster) }
                Chip(stringResource(R.string.deck_b), c.fxB, C.b, Modifier.weight(1f).height(34.dp)) { c.setFxAssign(c.fxA, !c.fxB, c.fxMaster) }
                Chip(stringResource(R.string.master), c.fxMaster, C.tx, Modifier.weight(1f).height(34.dp)) { c.setFxAssign(c.fxA, c.fxB, !c.fxMaster) }
            }
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.colour_fx_the_fx_knob_on_each_channel))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorFx.entries.take(3).forEach { Chip(it.label, c.colorFx == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doColorFx(it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorFx.entries.drop(3).forEach { Chip(it.label, c.colorFx == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doColorFx(it) } }
            }
            SectionLabel(stringResource(R.string.sampler_volume))
            Slider(c.samplerVol, { c.doSamplerVol(it) }, colors = SliderDefaults.colors(thumbColor = C.fx, activeTrackColor = C.fx, inactiveTrackColor = C.s3))
        }
    }
}

// ----------------------------------------------------------------------------- Outputs

@Composable
fun OutputPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    SidePanel(visible, onDismiss, "Audio output", 460.dp) {
        LaunchedEffect(visible) { if (visible) { c.refreshDevices(); c.ensureBtPermission() } }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard("Single output", "Everything to one device. Switch a CUE on to practise privately — the output then plays only the cued decks.", c.outMode == OutMode.SINGLE) { c.doOutMode(OutMode.SINGLE) }
            if (c.dualPossible || c.outMode == OutMode.DUAL)
                ModeCard("Two devices", "Room on one device, headphone cue on another: Bluetooth + tablet speaker or USB-C.", c.outMode == OutMode.DUAL) { c.doOutMode(OutMode.DUAL) }
            if (c.splitPossible || c.outMode == OutMode.SPLIT)
                ModeCard("Split cable", "USB-C headphone adapter + splitter: left = cue, right = master.", c.outMode == OutMode.SPLIT) { c.doOutMode(OutMode.SPLIT) }
            if (!c.dualPossible) Text(stringResource(R.string.connect_bluetooth_headphones_to_cue_on_them_while_the_speaker_or_usb_c_plays_the_room), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))

            if (c.outMode != OutMode.SPLIT) {
                SectionLabel(if (c.outMode == OutMode.DUAL) "Master output" else "Output")
                DeviceList(c, c.masterDev, allowDefault = c.outMode != OutMode.DUAL, onPick = { c.doMasterDev(it) })
            }
            if (c.outMode == OutMode.SINGLE) Text(stringResource(R.string.practice_mode_while_any_cue_is_on_this_output_plays_only_the_cued_decks_even_with_faders_down_recording_always_captures_the_real_master), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
            if (c.outMode != OutMode.DUAL && c.outMode != OutMode.SPLIT) TimingCard(c, onDismiss)
            if (c.outMode == OutMode.DUAL) {
                SectionLabel(stringResource(R.string.headphone_cue_output))
                DeviceList(c, c.cueDev, allowDefault = false, options = c.cueOptions(), onPick = { c.doCueDev(it) })
                Text(stringResource(R.string.with_no_cue_lit_the_headphones_play_the_master_mix_switch_a_cue_on_and_they_hear_only_that_deck_turn_cue_m_up_to_blend_the_room_back_in_with_two_cues_on_you_hear_both), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
                if (c.cueDev.isNotEmpty() && c.cueDev == c.masterDev) Text(stringResource(R.string.master_and_cue_are_the_same_device_pick_two_different_outputs), style = T.tab.copy(color = C.bad, fontSize = F.f12))
                if (c.routedCue.isNotEmpty()) Text("Cue playing through: ${c.routedCue}", style = T.tab.copy(color = if (c.routedCue == c.routedTo) C.fx else C.ok, fontSize = F.f12, fontWeight = FontWeight.SemiBold))
                if (c.routedCue.isNotEmpty() && c.routedCue == c.routedTo) Text(stringResource(R.string.android_put_master_and_cue_on_the_same_device_try_the_other_way_round_bluetooth_as_master_speaker_as_cue_or_use_usb_wired_headphones_for_cueing), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.fx))
                TimingCard(c, onDismiss)
            }
            if (c.outMode == OutMode.SPLIT) {
                TimingCard(c, onDismiss)
            }
            Text(stringResource(R.string.android_plays_to_one_bluetooth_device_at_a_time_and_the_speaker_and_usb_c_share_one_output_so_two_devices_always_means_one_bluetooth_one_wired_speaker), style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = F.f10))
            SectionLabel(stringResource(R.string.test_outputs))
            Text(stringResource(R.string.every_device_beeps_a_different_number_of_times_at_the_same_moment_1st_device_once_2nd_twice_listen_to_which_speaker_plays_what), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
            PillButton(stringResource(R.string.test_all_outputs_at_once), false, C.ok, Modifier.fillMaxWidth().height(40.dp)) { c.testOutputs(c.devices.map { it.key }) }
            c.devices.forEachIndexed { idx, d ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r10)).background(C.s1).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${idx + 1}", style = T.num.copy(color = C.ok, fontWeight = FontWeight.Bold))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = T.tab.copy(fontSize = F.f13), maxLines = 1)
                        c.testResults[d.key]?.let { Text(it, style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.ok, fontWeight = FontWeight.Normal)) }
                    }
                    Chip(stringResource(R.string.test), false, C.ok, Modifier.height(48.dp)) { c.testOutputs(listOf(d.key)) }
                }
            }
            if (c.routedTo.isNotEmpty()) {
                val wanted = c.devices.firstOrNull { it.key == c.masterDev }?.name
                val mismatch = c.outMode != OutMode.SPLIT && wanted != null && !c.routedTo.startsWith(wanted) && !(wanted == "Bluetooth" && c.routedTo.startsWith("Bluetooth"))
                Text("${if (c.outMode == OutMode.DUAL) "Master" else "Now"} playing through: ${c.routedTo}", style = T.tab.copy(color = if (mismatch) C.fx else C.ok, fontSize = F.f12, fontWeight = FontWeight.SemiBold))
                if (c.routedTo.startsWith("Built-in speaker") && c.devices.any { it.kind.startsWith("Bluetooth") })
                    Text(stringResource(R.string.hearing_nothing_from_the_speaker_android_keeps_a_separate_volume_per_device_and_the_speaker_s_is_often_0_while_headphones_are_paired_turn_bluetooth_off_once_raise_the_volume_then_turn_it_back_on), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.fx))
                if (mismatch) Text("Android is overriding your choice of “$wanted”. Some tablets always send media to connected Bluetooth headphones — disconnect them, or use “Two devices” to keep the headphones for cueing.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, color = C.fx))
            }
            c.engine.outputError?.let { Text("Output error: $it", style = T.tab.copy(color = C.bad, fontSize = F.f11)) }
        }
    }
}

@Composable
private fun ModeCard(title: String, body: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r14)).background(if (on) C.a.copy(alpha = .1f) else C.s1)
            .border(1.dp, if (on) C.a else C.line, RoundedCornerShape(D.r14)).pointerInput2(onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = F.f14, color = if (on) C.a else C.tx))
        Text(body, style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f11, fontWeight = FontWeight.Normal))
    }
}

@Composable
private fun DeviceList(c: Controller, selected: String, allowDefault: Boolean = true, options: List<app.flux.dj.DeviceOpt>? = null, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowDefault) DeviceRow("System default", "follows Android", selected.isEmpty()) { onPick("") }
        (options ?: c.devices).forEach { DeviceRow(it.name, it.kind, selected == it.key) { onPick(it.key) } }
    }
}

@Composable
private fun DeviceRow(name: String, kind: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(D.r10)).background(if (on) C.s3 else C.s1).pointerInput2(onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(D.r5)).background(if (on) C.ok else C.s4))
        Spacer(Modifier.width(10.dp))
        Text(name, style = T.tab.copy(fontSize = F.f13), modifier = Modifier.weight(1f), maxLines = 1)
        Text(kind, style = T.cap.copy(letterSpacing = 0.2.sp))
    }
}

// ----------------------------------------------------------------------------- View / layout

@Composable
fun ViewPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    val v = c.view
    SidePanel(visible, onDismiss, "View", 360.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.layouts))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Full", "Mix", "Scratch", "Minimal").forEachIndexed { k, n -> Chip(n, false, C.a, Modifier.weight(1f).height(36.dp)) { v.preset(k) } }
            }
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.panels))
            ToggleRow("Waveforms", "Scrolling waveforms on top", v.waves) { v.waves = it; v.save() }
            ToggleRow("Track overview", "Whole-track strip, tap to seek", v.overview) { v.overview = it; v.save() }
            ToggleRow("Jog wheels", "Scratch & pitch-bend platters", v.jog) { v.jog = it; v.save() }
            ToggleRow("Performance pads", "Hot cue, loop, roll, jump, sample, pitch", v.pads) { v.pads = it; v.save() }
            ToggleRow("Loop controls", "Loop on/off, ÷2 / ×2", v.loops) { v.loops = it; v.save() }
            ToggleRow("EQ & knobs", "Trim, EQ, colour FX, master, headphone", v.eq) { v.eq = it; v.save() }
            ToggleRow("Level meters", "Channel LED meters", v.meters) { v.meters = it; v.save() }
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.behaviour))
            ToggleRow("Scratch when dragging waveform", "Off: dragging the waveform searches silently (jog wheels always scratch)", c.waveScratch) { c.setWaveScratchPref(it) }
            ToggleRow("Mixer", "Channel faders & crossfader", v.mixer) { v.mixer = it; v.save() }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r12)).background(C.s1).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = F.f14))
            Text(sub, style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
        }
        Toggle(on, onChange, label = title)
    }
}

@Composable
private fun TimingCard(c: Controller, onDismiss: () -> Unit) {
    val measured = c.timingMeasured
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r14)).background(C.s1).border(1.dp, C.fx.copy(alpha = .5f), RoundedCornerShape(D.r14)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.timing_room_headphones_picture), style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = F.f14, color = C.fx))
        Text(
            buildString {
                if (!measured) {
                    if (c.outMode == OutMode.SINGLE) append("Picture uses the device delay (${c.latencyMs} ms) — measure with the mic for an exact match")
                    else append("This output set isn't measured yet. Run Auto-align so room, headphones and picture match")
                } else {
                    if (c.outMode == OutMode.SINGLE) append("Output delay ${c.latencyMs} ms")
                    else append(
                        when {
                            c.offsetMs == 0 -> "Room and headphones in sync"
                            c.offsetMs > 0 -> "Room waits ${c.offsetMs} ms for the headphones"
                            else -> "Headphones wait ${-c.offsetMs} ms for the room"
                        }
                    )
                    append("  ·  picture held back ${c.latencyMs} ms")
                    append("  ·  measured with the mic")
                }
                if (c.visualTrimMs != 0) append("  ·  fine-tune ${if (c.visualTrimMs > 0) "+" else ""}${c.visualTrimMs} ms")
            } + ". Waveforms, beat grid and platters are drawn at the same moment you hear the room.",
            style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f11, fontWeight = FontWeight.Normal)
        )
        PillButton(stringResource(R.string.auto_align_fine_tune), true, C.ok, Modifier.fillMaxWidth().height(40.dp), solid = true) { onDismiss(); c.startAlign() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.fine_tune_by_hand), false, C.fx, Modifier.weight(1.6f).height(36.dp)) { onDismiss(); c.startAlign() }
            PillButton(stringResource(R.string.reset), false, C.mu, Modifier.weight(1f).height(36.dp)) { c.resetTiming() }
        }
    }
}

// ----------------------------------------------------------------------------- Set history

@Composable
fun HistoryPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SidePanel(visible, onDismiss, "Set history", 560.dp) {
        val sessions = remember(c.history, visible) { c.historySessions() }
        Text(stringResource(R.string.tracks_are_logged_once_they_ve_been_audible_on_the_master_for_5_seconds_the_letter_shows_which_deck_played_it), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.nothing_played_yet_mix_a_few_tracks_and_they_ll_show_up_here), style = T.tab.copy(color = C.mu))
            }
        } else {
            val tf = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
            val df = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sessions.forEach { session ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${df.format(java.util.Date(session.first().time))} · ${tf.format(java.util.Date(session.first().time))}–${tf.format(java.util.Date(session.last().time))}", style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = F.f14))
                                // the deck sequence at a glance: A → B → A → B
                                Text(session.joinToString(" → ") { c.deckName(it.deck) } + "  ·  ${session.size} track${if (session.size == 1) "" else "s"}", style = T.cap.copy(letterSpacing = 0.3.sp, color = C.mu), maxLines = 1)
                            }
                            Chip(stringResource(R.string.share), true, C.a, Modifier.height(48.dp)) {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, c.tracklistText(session)) }
                                ctx.startActivity(android.content.Intent.createChooser(send, "Share tracklist"))
                            }
                        }
                    }
                    items(session.size) { n ->
                        val e = session[n]
                        val col = C.deck(e.deck)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r10)).background(C.s1).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${n + 1}", style = T.num.copy(color = C.dim, fontSize = F.f11), modifier = Modifier.width(20.dp))
                            Text(tf.format(java.util.Date(e.time)), style = T.num.copy(color = C.mu, fontSize = F.f11))
                            Box(Modifier.size(22.dp, 20.dp).clip(RoundedCornerShape(D.r6)).background(col), contentAlignment = Alignment.Center) {
                                Text(c.deckName(e.deck), style = T.tab.copy(color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = F.f11))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = F.f13), maxLines = 1)
                                if (e.artist.isNotEmpty()) Text(e.artist, style = T.cap.copy(letterSpacing = 0.1.sp), maxLines = 1)
                            }
                            Text(String.format("%.0f", e.bpm), style = T.num.copy(fontSize = F.f12))
                            if (e.key >= 0) Text(Keys.camelot(e.key), style = T.num.copy(fontSize = F.f12, color = C.ok))
                        }
                    }
                }
            }
            if (c.confirmClearHistory) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.delete_the_whole_history), style = T.tab.copy(color = C.bad), modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.cancel), false, C.tx, Modifier.width(100.dp).height(38.dp)) { c.confirmClearHistory = false }
                PillButton(stringResource(R.string.delete), true, C.bad, Modifier.width(100.dp).height(38.dp), solid = true) { c.clearHistory() }
            } else Chip(stringResource(R.string.clear_history), false, C.bad, Modifier.height(48.dp)) { c.confirmClearHistory = true }
        }
    }
}

/** rekordbox playlists, grouped by folder, with how many of each are on this tablet. */
@Composable
private fun PlaylistPicker(c: Controller, modifier: Modifier, onImportXml: () -> Unit, onClose: () -> Unit) {
    val lib = c.rb ?: return
    val groups = remember(lib) { lib.playlists.groupBy { it.folders.joinToString(" / ") } }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            PickRow("All tracks", "${c.rbMatch.size} from ${lib.product}", c.playlist == null) { c.selectPlaylist(null); onClose() }
        }
        groups.forEach { (folder, lists) ->
            if (folder.isNotEmpty()) item { Text("▾  ${folder.uppercase()}", style = T.cap.copy(letterSpacing = 0.6.sp, color = C.fx), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
            items(lists.size) { n ->
                val pl = lists[n]
                val on = c.playlistOnTablet(pl)
                PickRow(pl.name, "$on of ${pl.trackIds.size} on this tablet", c.playlist == pl, indent = folder.isNotEmpty()) { c.selectPlaylist(pl); onClose() }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.import_a_newer_collection_xml), false, C.a, Modifier.height(48.dp)) { onClose(); onImportXml() }
                Chip(stringResource(R.string.remove_rekordbox_playlists), false, C.bad, Modifier.height(48.dp)) { onClose(); c.removeRekordbox() }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, sub: String, on: Boolean, indent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (indent) 14.dp else 0.dp).clip(RoundedCornerShape(D.r10)).background(if (on) C.fx.copy(alpha = .15f) else C.s1)
            .border(1.dp, if (on) C.fx.copy(alpha = .7f) else Color.Transparent, RoundedCornerShape(D.r10)).pointerInput2(onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = F.f14, color = if (on) C.fx else C.tx), modifier = Modifier.weight(1f), maxLines = 1)
        Text(sub, style = T.cap.copy(letterSpacing = 0.2.sp))
    }
}
