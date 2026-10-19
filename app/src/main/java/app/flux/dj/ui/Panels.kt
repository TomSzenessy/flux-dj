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
fun LibraryPanel(c: Controller, visible: Boolean, target: Int, onDismiss: () -> Unit, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onPermission: () -> Unit) {
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

        // proactive suggestions
        val sugg = remember(visible, target, rev, c.ui[0].title, c.ui[1].title) { if (ref != null) c.suggestions(target, 4) else emptyList() }
        if (ref != null && sugg.isNotEmpty()) {
            Text("✦ SUGGESTED AFTER “${ref.title.uppercase()}”", style = T.cap.copy(color = C.ok, letterSpacing = 0.6.sp, fontSize = 9.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                sugg.forEach { s -> SuggestCard(c, s, ref, dest, onDismiss) }
            }
        }

        // filters & sort
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterRow("Key", listOf("Any" to (c.filterKey == 0), "Compatible" to (c.filterKey == 1))) { c.filterKey = it }
            FilterRow("Tempo", listOf("Any" to (c.filterBpm == 0), "±3%" to (c.filterBpm == 3), "±6%" to (c.filterBpm == 6), "±10%" to (c.filterBpm == 10))) { c.filterBpm = intArrayOf(0, 3, 6, 10)[it] }
            FilterRow("Energy", listOf("Any" to (c.filterEnergy == 0), "Low" to (c.filterEnergy == 1), "Mid" to (c.filterEnergy == 2), "High" to (c.filterEnergy == 3))) { c.filterEnergy = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text("VIBE", style = T.cap.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp), modifier = Modifier.width(52.dp))
                Chip("All", c.filterVibe == null, C.fx, Modifier.height(28.dp)) { c.filterVibe = null }
                c.vibesPresent.forEach { v -> Chip(v, c.filterVibe == v, C.fx, Modifier.height(28.dp)) { c.filterVibe = if (c.filterVibe == v) null else v } }
            }
            FilterRow("Sort", listOf("Best match", "BPM", "Key", "Energy", "A–Z").mapIndexed { i, t -> t to (c.sortMode == i) }, C.a) { c.sortMode = it }
        }

        val list = remember(rev, q, target, visible, c.filterKey, c.filterBpm, c.filterVibe, c.filterEnergy, c.sortMode, ref) { c.filteredLibrary(target, q) }
        Text("${list.size} tracks", style = T.cap.copy(letterSpacing = 0.3.sp))
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
private fun SuggestCard(c: Controller, it: LibItem, ref: RefMeta, dest: Int, onDismiss: () -> Unit) {
    val kd = Keys.distance(it.key, ref.key)
    val kcol = when (kd) { 0 -> C.ok; 1 -> C.ok; 2 -> C.fx; else -> C.dim }
    Column(
        Modifier.width(210.dp).clip(RoundedCornerShape(14.dp)).background(C.s1).border(1.dp, C.ok.copy(alpha = .35f), RoundedCornerShape(14.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(it.title, style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("%.0f", it.bpm), style = T.num.copy(fontWeight = FontWeight.SemiBold))
            Text(Keys.camelot(it.key), style = T.num.copy(color = kcol, fontWeight = FontWeight.Bold, fontSize = 12.sp))
            if (it.energy > 0) Text("E${it.energy}", style = T.cap.copy(letterSpacing = 0.sp))
            if (it.vibe.isNotEmpty()) Text(it.vibe, style = T.cap.copy(letterSpacing = 0.sp, color = C.fx))
        }
        Text(c.reasons(it, ref), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 9.sp, color = C.ok), maxLines = 2)
        Chip("Load to ${if (dest == 0) "A" else "B"}", true, C.deck(dest), Modifier.fillMaxWidth().height(28.dp)) { c.loadTap(it, dest); onDismiss() }
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
        HeadphoneBtn(previewing) { c.togglePreview(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (target != 1) DeckLoadBtn("A", C.a) { c.loadTap(it, 0); onDismiss() }
            if (target != 0) DeckLoadBtn("B", C.b) { c.loadTap(it, 1); onDismiss() }
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
            ModeCard("Two devices", "Master to one output, headphone cue to another — e.g. aux/USB for the room and Bluetooth for your ears.", c.outMode == OutMode.DUAL) { c.doOutMode(OutMode.DUAL) }
            ModeCard("Split cable", "One stereo jack with a splitter: left = cue, right = master. Works with any wired headset + adapter.", c.outMode == OutMode.SPLIT) { c.doOutMode(OutMode.SPLIT) }

            if (c.outMode != OutMode.SPLIT) {
                SectionLabel(if (c.outMode == OutMode.DUAL) "Master output" else "Output")
                DeviceList(c, c.masterDev, onPick = { c.doMasterDev(it) })
            }
            if (c.outMode == OutMode.DUAL) {
                SectionLabel("Headphone cue output")
                DeviceList(c, c.cueDev, onPick = { c.doCueDev(it) })
                SectionLabel("Align cue ↔ master  (${if (c.offsetMs == 0) "off" else if (c.offsetMs > 0) "delay master ${c.offsetMs} ms" else "delay cue ${-c.offsetMs} ms"})")
                Slider((c.offsetMs + 300) / 600f, { c.setOffset(((it * 600f) - 300f).toInt() / 10 * 10) }, colors = SliderDefaults.colors(thumbColor = C.fx, activeTrackColor = C.fx, inactiveTrackColor = C.s3))
                Text("Bluetooth adds 100–250 ms of latency. If the beat in your headphones trails the room, delay the master by the same amount.", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = 10.sp))
            }
            if (c.outMode == OutMode.SPLIT) {
                SectionLabel("Align cue ↔ master  (${c.offsetMs} ms)")
                Slider((c.offsetMs + 300) / 600f, { c.setOffset(((it * 600f) - 300f).toInt() / 10 * 10) }, colors = SliderDefaults.colors(thumbColor = C.fx, activeTrackColor = C.fx, inactiveTrackColor = C.s3))
            }
            Text("Android lets most phones route to one Bluetooth speaker at a time. Wired + Bluetooth, or USB + Bluetooth, work best.", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = 10.sp))
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
private fun DeviceList(c: Controller, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DeviceRow("System default", "", selected < 0) { onPick(-1) }
        c.devices.forEach { DeviceRow(it.name, it.kind, selected == it.id) { onPick(it.id) } }
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
