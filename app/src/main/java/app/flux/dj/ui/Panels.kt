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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.Controller
import app.flux.dj.LibItem
import app.flux.dj.RefMeta
import app.flux.dj.audio.*
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
fun SidePanel(visible: Boolean, onDismiss: () -> Unit, title: String, width: Dp = 420.dp, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
    }
    AnimatedVisibility(visible, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)).background(Color(0xFF0C0E14))
                    .border(1.dp, C.line, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = T.tab.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(C.s3).pointerInput2 { onDismiss() }, contentAlignment = Alignment.Center) {
                        Text("✕", style = T.tab.copy(color = C.mu, fontSize = 14.sp))
                    }
                }
                content()
            }
        }
    }
}

// ----------------------------------------------------------------------------- Library

@Composable
private fun FilterRow(label: String, opts: List<Pair<String, Boolean>>, color: Color = C.ok, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label.uppercase(), style = T.cap.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp), modifier = Modifier.width(52.dp))
        opts.forEachIndexed { i, (t, on) -> Chip(t, on, color, Modifier.height(28.dp)) { onPick(i) } }
    }
}

@Composable
fun LibraryPanel(c: Controller, visible: Boolean, target: Int, onDismiss: () -> Unit, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onPermission: () -> Unit, onHistory: () -> Unit = {}) {
    SidePanel(visible, onDismiss, "Library", 580.dp) {
        var q by remember { mutableStateOf("") }
        val rev = c.libRev
        val ref: RefMeta? = remember(visible, target, rev, c.ui[0].title, c.ui[1].title) { c.refMeta(target) }
        val dest = if (target in 0..1) target else c.idleDeck()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(C.s2).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(q, { q = it }, singleLine = true, textStyle = T.tab.copy(fontSize = 14.sp), cursorBrush = SolidColor(C.a), modifier = Modifier.fillMaxWidth())
                if (q.isEmpty()) Text("Search tracks", style = T.tab.copy(color = C.dim, fontSize = 14.sp))
            }
            Chip("＋ Files", true, C.a, Modifier.height(38.dp)) { onAddFiles() }
            Chip("＋ Folder", true, C.a, Modifier.height(38.dp)) { onAddFolder() }
            Chip("History", false, C.a, Modifier.height(38.dp)) { onHistory() }
        }
        Text(if (target in 0..1) "Tap a track to load it on deck ${c.deckName(target)}  ·  or drag it onto a waveform" else "Tap a track to load it on the free deck  ·  or drag it onto a waveform", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = 10.sp, color = C.mu))

        // background analysis status
        if (c.indexTotal > 0) {
            val frac = c.indexDone.toFloat() / c.indexTotal
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.s1).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (c.indexDone < c.indexTotal) "✦ Analysing your library · ${c.indexDone} / ${c.indexTotal}" else "✦ Library analysed · ${c.indexTotal} tracks", style = T.cap.copy(color = C.ok, letterSpacing = 0.2.sp))
                    if (c.indexDone < c.indexTotal) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.s3)) { Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.ok)) }
                }
                if (c.indexDone < c.indexTotal) Chip(if (c.indexPaused) "Resume" else "Pause", false, C.ok, Modifier.height(26.dp)) { c.toggleIndexPause() }
            }
        }

        // sort (always visible) + filters (folded away)
        var filtersOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text("SORT", style = T.cap.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp), modifier = Modifier.padding(end = 2.dp))
            listOf(if (ref != null) "✦ Recommended" else "Recommended", "Newest", "A–Z", "Artist", "BPM", "Key", "Energy").forEachIndexed { i, t ->
                Chip(t, c.sortMode == i, C.a, Modifier.height(30.dp)) { c.sortMode = i }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (c.activeFilters > 0) "Filters · ${c.activeFilters}" else "Filters", filtersOpen || c.activeFilters > 0, C.ok, Modifier.height(30.dp)) { filtersOpen = !filtersOpen }
            if (c.activeFilters > 0) Chip("Clear", false, C.ok, Modifier.height(30.dp)) { c.filterKey = 0; c.filterBpm = 0; c.filterEnergy = 0; c.filterVibe = null }
            Spacer(Modifier.weight(1f))
            if (c.hiddenCount > 0) Chip(if (c.showHidden) "◀ Back to library" else "Removed · ${c.hiddenCount}", c.showHidden, C.mu, Modifier.height(30.dp)) { c.showHidden = !c.showHidden }
        }
        if (filtersOpen) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterRow("Key", listOf("Any" to (c.filterKey == 0), "Compatible" to (c.filterKey == 1))) { c.filterKey = it }
            FilterRow("Tempo", listOf("Any" to (c.filterBpm == 0), "±3%" to (c.filterBpm == 3), "±6%" to (c.filterBpm == 6), "±10%" to (c.filterBpm == 10))) { c.filterBpm = intArrayOf(0, 3, 6, 10)[it] }
            FilterRow("Energy", listOf("Any" to (c.filterEnergy == 0), "Low" to (c.filterEnergy == 1), "Mid" to (c.filterEnergy == 2), "High" to (c.filterEnergy == 3))) { c.filterEnergy = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text("VIBE", style = T.cap.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp), modifier = Modifier.width(52.dp))
                Chip("All", c.filterVibe == null, C.fx, Modifier.height(28.dp)) { c.filterVibe = null }
                c.vibesPresent.forEach { v -> Chip(v, c.filterVibe == v, C.fx, Modifier.height(28.dp)) { c.filterVibe = if (c.filterVibe == v) null else v } }
            }
            if (ref == null) Text("Key / tempo filters compare against the track on the other deck — load one first.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, fontWeight = FontWeight.Normal))
        }

        val list = remember(rev, q, target, visible, c.filterKey, c.filterBpm, c.filterVibe, c.filterEnergy, c.sortMode, ref, c.showHidden, c.hidden) { c.filteredLibrary(target, q) }
        Text(if (c.showHidden) "${list.size} removed tracks — tap Restore to bring one back" else "${list.size} tracks" + if (c.sortMode == 0 && ref != null) " · best matches for “${ref.title}” first" else "", style = T.cap.copy(letterSpacing = 0.3.sp), maxLines = 1)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!c.libPermission) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.s2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow access to see and auto-analyse the music on this device.", style = T.tab.copy(color = C.mu), modifier = Modifier.weight(1f))
                    Chip("Allow", true, C.a, Modifier.height(30.dp)) { onPermission() }
                }
            }
            items(list, key = { it.id }) { it -> LibRow(c, it, ref, target, onDismiss) }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LibRow(c: Controller, it: LibItem, ref: RefMeta?, target: Int, onDismiss: () -> Unit) {
    val kd = if (ref != null) Keys.distance(it.key, ref.key) else -1
    val kcol = when (kd) { 0 -> C.ok; 1 -> C.ok.copy(alpha = .75f); 2 -> C.fx; 9 -> C.bad.copy(alpha = .7f); else -> C.dim }
    val previewing = c.previewId == it.id
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (previewing) C.s2 else C.s1).border(1.dp, if (previewing) C.fx.copy(alpha = .6f) else Color.Transparent, RoundedCornerShape(12.dp))) {
    Row(
        Modifier.fillMaxWidth()
            .pointerInput2 { c.loadTap(it, target); onDismiss() }
            .dragAndDropSource {
                detectTapGestures(onLongPress = { _ ->
                    startTransfer(DragAndDropTransferData(android.content.ClipData.newPlainText("flux-track", it.id)))
                })
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(it.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), maxLines = 1)
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
        if (it.key >= 0) Box(Modifier.clip(RoundedCornerShape(7.dp)).background(kcol.copy(alpha = .18f)).border(1.dp, kcol.copy(alpha = .7f), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text(Keys.camelot(it.key), style = T.num.copy(color = kcol, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        if (c.showHidden) {
            Chip("Restore", true, C.ok, Modifier.height(34.dp)) { c.restore(it) }
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
        Modifier.size(44.dp, 34.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = .22f))
            .border(1.5.dp, color, RoundedCornerShape(9.dp)).pointerInput2(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("▸ $label", style = T.tab.copy(color = color, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun RemoveBtn(onClick: () -> Unit) {
    Box(Modifier.size(30.dp, 34.dp).clip(RoundedCornerShape(9.dp)).pointerInput2(onClick), contentAlignment = Alignment.Center) {
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
        Modifier.size(38.dp, 34.dp).clip(RoundedCornerShape(9.dp)).background(if (on) C.fx else C.s3).pointerInput2(onClick),
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
        Text(if (c.previewLoading) "Decoding…" else "🎧  ${c.previewTime}", style = T.num.copy(fontSize = 11.sp, color = C.fx), modifier = Modifier.width(118.dp), maxLines = 1)
        androidx.compose.foundation.Canvas(
            Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0A0B10))
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
            val doneFrac = if (t.complete) 1f else (t.available.toFloat() / t.frames).coerceIn(0f, 1f)
            if (ov != null) {
                val cols = minOf(OV_COLS, w.toInt() / 2)
                val cw = w / cols
                for (x in 0 until cols) {
                    val oc = x * OV_COLS / cols
                    val a = ((ov[oc * 3].toInt() and 255) * 0.55f + (ov[oc * 3 + 1].toInt() and 255) * 0.3f + (ov[oc * 3 + 2].toInt() and 255) * 0.15f) / 255f
                    val hh = (a * h * 0.45f).coerceAtLeast(1f)
                    val played = x * cw < c.previewFrac * w
                    drawRect(if (played) C.fx else Color(0xFF4A5068), androidx.compose.ui.geometry.Offset(x * cw, h / 2 - hh), androidx.compose.ui.geometry.Size(cw * 0.7f, hh * 2))
                }
            } else {
                // not analysed yet: show how much has been decoded
                drawRect(Color(0xFF1D2131), size = androidx.compose.ui.geometry.Size(w * doneFrac, h))
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
        for (i in 1..10) Box(Modifier.width(3.dp).height((4 + i * 1.2f).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= e) (if (e >= 8) C.b else if (e >= 5) C.fx else C.a) else C.s3))
    }
}

// ----------------------------------------------------------------------------- FX

@Composable
fun FxPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    SidePanel(visible, onDismiss, "Effects", 400.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Beat FX")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BeatFx.entries.take(3).forEach { Chip(it.label, c.fxType == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doFxType(it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BeatFx.entries.drop(3).forEach { Chip(it.label, c.fxType == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doFxType(it) } }
            }
            SectionLabel("Beat division")
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
            SectionLabel("Apply to")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Deck A", c.fxA, C.a, Modifier.weight(1f).height(34.dp)) { c.setFxAssign(!c.fxA, c.fxB, c.fxMaster) }
                Chip("Deck B", c.fxB, C.b, Modifier.weight(1f).height(34.dp)) { c.setFxAssign(c.fxA, !c.fxB, c.fxMaster) }
                Chip("Master", c.fxMaster, C.tx, Modifier.weight(1f).height(34.dp)) { c.setFxAssign(c.fxA, c.fxB, !c.fxMaster) }
            }
            Spacer(Modifier.height(4.dp))
            SectionLabel("Colour FX  (the FX knob on each channel)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorFx.entries.take(3).forEach { Chip(it.label, c.colorFx == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doColorFx(it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorFx.entries.drop(3).forEach { Chip(it.label, c.colorFx == it, C.fx, Modifier.weight(1f).height(34.dp)) { c.doColorFx(it) } }
            }
            SectionLabel("Sampler volume")
            Slider(c.samplerVol, { c.doSamplerVol(it) }, colors = SliderDefaults.colors(thumbColor = C.fx, activeTrackColor = C.fx, inactiveTrackColor = C.s3))
        }
    }
}

// ----------------------------------------------------------------------------- Outputs

@Composable
fun OutputPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    SidePanel(visible, onDismiss, "Audio output", 460.dp) {
        LaunchedEffect(visible) { if (visible) c.refreshDevices() }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard("Single output", "Everything to one device. Headphone cue is shown on the meters only.", c.outMode == OutMode.SINGLE) { c.doOutMode(OutMode.SINGLE) }
            if (c.dualPossible || c.outMode == OutMode.DUAL)
                ModeCard("Two devices", "Room on one device, headphone cue on another: Bluetooth + tablet speaker or USB-C.", c.outMode == OutMode.DUAL) { c.doOutMode(OutMode.DUAL) }
            if (c.splitPossible || c.outMode == OutMode.SPLIT)
                ModeCard("Split cable", "USB-C headphone adapter + splitter: left = cue, right = master.", c.outMode == OutMode.SPLIT) { c.doOutMode(OutMode.SPLIT) }
            if (!c.dualPossible) Text("Connect Bluetooth headphones to cue on them while the speaker or USB-C plays the room.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, fontWeight = FontWeight.Normal))

            if (c.outMode != OutMode.SPLIT) {
                SectionLabel(if (c.outMode == OutMode.DUAL) "Master output" else "Output")
                DeviceList(c, c.masterDev, allowDefault = c.outMode != OutMode.DUAL, onPick = { c.doMasterDev(it) })
            }
            if (c.outMode == OutMode.DUAL) {
                SectionLabel("Headphone cue output")
                DeviceList(c, c.cueDev, allowDefault = false, options = c.cueOptions(), onPick = { c.doCueDev(it) })
                Text("Headphones play only decks with CUE switched on. Turn the CUE·M knob (bottom of the mixer) to blend the room mix in.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, fontWeight = FontWeight.Normal))
                if (c.cueDev.isNotEmpty() && c.cueDev == c.masterDev) Text("Master and cue are the same device — pick two different outputs.", style = T.tab.copy(color = C.bad, fontSize = 12.sp))
                if (c.routedCue.isNotEmpty()) Text("Cue playing through: ${c.routedCue}", style = T.tab.copy(color = if (c.routedCue == c.routedTo) C.fx else C.ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                if (c.routedCue.isNotEmpty() && c.routedCue == c.routedTo) Text("Android put master and cue on the same device. Try the other way round (Bluetooth as master, speaker as cue), or use USB / wired headphones for cueing.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, color = C.fx))
                AlignCard(c, onDismiss)
            }
            if (c.outMode == OutMode.SPLIT) {
                AlignCard(c, onDismiss)
            }
            Text("Android plays to one Bluetooth device at a time, and the speaker and USB-C share one output — so two devices always means one Bluetooth + one wired/speaker.", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = 10.sp))
            SectionLabel("Test outputs")
            Text("Every device beeps a different number of times at the same moment — 1st device once, 2nd twice… Listen to which speaker plays what.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, fontWeight = FontWeight.Normal))
            PillButton("▶  Test all outputs at once", false, C.ok, Modifier.fillMaxWidth().height(40.dp)) { c.testOutputs(c.devices.map { it.key }) }
            c.devices.forEachIndexed { idx, d ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.s1).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${idx + 1}", style = T.num.copy(color = C.ok, fontWeight = FontWeight.Bold))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = T.tab.copy(fontSize = 13.sp), maxLines = 1)
                        c.testResults[d.key]?.let { Text(it, style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, color = C.ok, fontWeight = FontWeight.Normal)) }
                    }
                    Chip("TEST", false, C.ok, Modifier.height(30.dp)) { c.testOutputs(listOf(d.key)) }
                }
            }
            if (c.routedTo.isNotEmpty()) {
                val wanted = c.devices.firstOrNull { it.key == c.masterDev }?.name
                val mismatch = c.outMode != OutMode.SPLIT && wanted != null && !c.routedTo.startsWith(wanted) && !(wanted == "Bluetooth" && c.routedTo.startsWith("Bluetooth"))
                Text("${if (c.outMode == OutMode.DUAL) "Master" else "Now"} playing through: ${c.routedTo}", style = T.tab.copy(color = if (mismatch) C.fx else C.ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                if (c.routedTo.startsWith("Built-in speaker") && c.devices.any { it.kind.startsWith("Bluetooth") })
                    Text("Hearing nothing from the speaker? Android keeps a separate volume per device, and the speaker's is often 0 while headphones are paired. Turn Bluetooth off once, raise the volume, then turn it back on.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, color = C.fx))
                if (mismatch) Text("Android is overriding your choice of “$wanted”. Some tablets always send media to connected Bluetooth headphones — disconnect them, or use “Two devices” to keep the headphones for cueing.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, color = C.fx))
            }
            c.engine.outputError?.let { Text("Output error: $it", style = T.tab.copy(color = C.bad, fontSize = 11.sp)) }
        }
    }
}

@Composable
private fun ModeCard(title: String, body: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) C.a.copy(alpha = .1f) else C.s1)
            .border(1.dp, if (on) C.a else C.line, RoundedCornerShape(14.dp)).pointerInput2(onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (on) C.a else C.tx))
        Text(body, style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 11.sp, fontWeight = FontWeight.Normal))
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) C.s3 else C.s1).pointerInput2(onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (on) C.ok else C.s4))
        Spacer(Modifier.width(10.dp))
        Text(name, style = T.tab.copy(fontSize = 13.sp), modifier = Modifier.weight(1f), maxLines = 1)
        Text(kind, style = T.cap.copy(letterSpacing = 0.2.sp))
    }
}

// ----------------------------------------------------------------------------- View / layout

@Composable
fun ViewPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    val v = c.view
    SidePanel(visible, onDismiss, "View", 360.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Layouts")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Full", "Mix", "Scratch", "Minimal").forEachIndexed { k, n -> Chip(n, false, C.a, Modifier.weight(1f).height(36.dp)) { v.preset(k) } }
            }
            Spacer(Modifier.height(4.dp))
            SectionLabel("Panels")
            ToggleRow("Waveforms", "Scrolling waveforms on top", v.waves) { v.waves = it; v.save() }
            ToggleRow("Track overview", "Whole-track strip, tap to seek", v.overview) { v.overview = it; v.save() }
            ToggleRow("Jog wheels", "Scratch & pitch-bend platters", v.jog) { v.jog = it; v.save() }
            ToggleRow("Performance pads", "Hot cue, loop, roll, jump, sample, pitch", v.pads) { v.pads = it; v.save() }
            ToggleRow("Loop controls", "Loop on/off, ÷2 / ×2", v.loops) { v.loops = it; v.save() }
            ToggleRow("EQ & knobs", "Trim, EQ, colour FX, master, headphone", v.eq) { v.eq = it; v.save() }
            ToggleRow("Level meters", "Channel LED meters", v.meters) { v.meters = it; v.save() }
            Spacer(Modifier.height(4.dp))
            SectionLabel("Behaviour")
            ToggleRow("Scratch when dragging waveform", "Off: dragging the waveform searches silently (jog wheels always scratch)", c.waveScratch) { c.setWaveScratchPref(it) }
            ToggleRow("Mixer", "Channel faders & crossfader", v.mixer) { v.mixer = it; v.save() }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.s1).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
            Text(sub, style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, fontWeight = FontWeight.Normal))
        }
        Toggle(on, onChange)
    }
}

@Composable
private fun AlignCard(c: Controller, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s1).border(1.dp, C.fx.copy(alpha = .5f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Cue ↔ master timing", style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.fx))
        Text(
            if (c.offsetMs == 0) "Not aligned yet. Bluetooth usually lags 100–250 ms behind the speaker."
            else if (c.offsetMs > 0) "Master delayed by ${c.offsetMs} ms" else "Cue delayed by ${-c.offsetMs} ms",
            style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 11.sp, fontWeight = FontWeight.Normal)
        )
        PillButton("Align by ear…", true, C.fx, Modifier.fillMaxWidth().height(40.dp), solid = true) { onDismiss(); c.startAlign() }
    }
}

// ----------------------------------------------------------------------------- Set history

@Composable
fun HistoryPanel(c: Controller, visible: Boolean, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SidePanel(visible, onDismiss, "Set history", 560.dp) {
        val sessions = remember(c.history, visible) { c.historySessions() }
        Text("Tracks are logged once they've been audible on the master for 5 seconds. The letter shows which deck played it.", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 10.sp, fontWeight = FontWeight.Normal))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Nothing played yet — mix a few tracks and they'll show up here.", style = T.tab.copy(color = C.mu))
            }
        } else {
            val tf = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
            val df = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sessions.forEach { session ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${df.format(java.util.Date(session.first().time))} · ${tf.format(java.util.Date(session.first().time))}–${tf.format(java.util.Date(session.last().time))}", style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp))
                                // the deck sequence at a glance: A → B → A → B
                                Text(session.joinToString(" → ") { c.deckName(it.deck) } + "  ·  ${session.size} tracks", style = T.cap.copy(letterSpacing = 0.3.sp, color = C.mu), maxLines = 1)
                            }
                            Chip("Share", true, C.a, Modifier.height(30.dp)) {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, c.tracklistText(session)) }
                                ctx.startActivity(android.content.Intent.createChooser(send, "Share tracklist"))
                            }
                        }
                    }
                    items(session.size) { n ->
                        val e = session[n]
                        val col = C.deck(e.deck)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.s1).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${n + 1}", style = T.num.copy(color = C.dim, fontSize = 11.sp), modifier = Modifier.width(20.dp))
                            Text(tf.format(java.util.Date(e.time)), style = T.num.copy(color = C.mu, fontSize = 11.sp))
                            Box(Modifier.size(22.dp, 20.dp).clip(RoundedCornerShape(6.dp)).background(col), contentAlignment = Alignment.Center) {
                                Text(c.deckName(e.deck), style = T.tab.copy(color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), maxLines = 1)
                                if (e.artist.isNotEmpty()) Text(e.artist, style = T.cap.copy(letterSpacing = 0.1.sp), maxLines = 1)
                            }
                            Text(String.format("%.0f", e.bpm), style = T.num.copy(fontSize = 12.sp))
                            if (e.key >= 0) Text(Keys.camelot(e.key), style = T.num.copy(fontSize = 12.sp, color = C.ok))
                        }
                    }
                }
            }
            if (c.confirmClearHistory) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Delete the whole history?", style = T.tab.copy(color = C.bad), modifier = Modifier.weight(1f))
                PillButton("Cancel", false, C.tx, Modifier.width(100.dp).height(38.dp)) { c.confirmClearHistory = false }
                PillButton("Delete", true, C.bad, Modifier.width(100.dp).height(38.dp), solid = true) { c.clearHistory() }
            } else Chip("Clear history", false, C.bad, Modifier.height(32.dp)) { c.confirmClearHistory = true }
        }
    }
}
