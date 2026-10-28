package app.flux.dj.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.Controller
import kotlinx.coroutines.delay

enum class Sheet { NONE, LIBRARY, FX, OUT, VIEW, HISTORY, SOURCES }

@Composable
fun FluxApp(c: Controller, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onPickSample: (Int) -> Unit, onPermission: () -> Unit, onImportXml: () -> Unit = {}, onConnectDrive: () -> Unit = {}) {
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var libTarget by remember { mutableIntStateOf(-1) }

    BackHandler(enabled = c.sampleEdit != null || c.pendingRemove != null || c.rbReport != null ||
        c.pendingLoad != null || c.aligning || sheet != Sheet.NONE) {
        when {
            c.sampleEdit != null -> c.sampleEdit = null
            c.pendingRemove != null -> c.cancelRemove()
            c.rbReport != null -> c.rbReport = null
            c.pendingLoad != null -> c.cancelLoad()
            c.aligning -> c.cancelAlign()
            else -> sheet = Sheet.NONE
        }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (t - last > 13_000_000L) { last = t; c.poll() }
            }
        }
    }
    LaunchedEffect(c.toast) { if (c.toast != null) { delay(2600); c.toast = null } }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TopBar(c, onLibrary = { libTarget = -1; sheet = Sheet.LIBRARY }, onFx = { sheet = Sheet.FX }, onOut = { sheet = Sheet.OUT }, onView = { sheet = Sheet.VIEW },
                modifier = Modifier.fillMaxWidth().height(40.dp))
            if (c.view.waves) {
                Column(Modifier.weight(if (c.view.overview) 1.05f else 0.85f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    WaveStrip(c, 0, c.view.overview, { libTarget = 0; sheet = Sheet.LIBRARY }, Modifier.weight(1f).fillMaxWidth())
                    WaveStrip(c, 1, c.view.overview, { libTarget = 1; sheet = Sheet.LIBRARY }, Modifier.weight(1f).fillMaxWidth())
                }
            }
            Row(Modifier.weight(1.7f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckPanel(c, 0, { libTarget = 0; sheet = Sheet.LIBRARY }, Modifier.weight(1f).fillMaxHeight())
                if (c.view.mixer) MixerPanel(c, Modifier.width(mixerWidth(c)).fillMaxHeight())
                DeckPanel(c, 1, { libTarget = 1; sheet = Sheet.LIBRARY }, Modifier.weight(1f).fillMaxHeight())
            }
        }

        LibraryPanel(c, sheet == Sheet.LIBRARY, libTarget, { sheet = Sheet.NONE }, onAddFiles, onAddFolder, onPermission, onHistory = { sheet = Sheet.HISTORY }, onImportXml = onImportXml, onSources = { sheet = Sheet.SOURCES })
        SourcesPanel(c, sheet == Sheet.SOURCES, { sheet = Sheet.NONE }, onConnectDrive, onImportXml)
        HistoryPanel(c, sheet == Sheet.HISTORY) { sheet = Sheet.NONE }
        FxPanel(c, sheet == Sheet.FX) { sheet = Sheet.NONE }
        OutputPanel(c, sheet == Sheet.OUT) { sheet = Sheet.NONE }
        ViewPanel(c, sheet == Sheet.VIEW) { sheet = Sheet.NONE }

        c.pendingLoad?.let { (dk, item) ->
            val col = C.deck(dk)
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput2 { c.cancelLoad() }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(C.s1).border(1.dp, col.copy(alpha = .6f), RoundedCornerShape(20.dp)).pointerInput2 { }.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Deck ${c.deckName(dk)} is playing", style = T.tab.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = col))
                    Text("Load “${item.title}” anyway? The track that's playing now will stop.", style = T.tab.copy(color = C.mu, fontSize = 13.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Cancel", false, C.tx, Modifier.weight(1f).height(44.dp)) { c.cancelLoad() }
                        PillButton("Load anyway", true, col, Modifier.weight(1f).height(44.dp), solid = true) { c.confirmLoad() }
                    }
                }
            }
        }
        if (c.aligning) AlignScreen(c)
        c.rbReport?.let { r ->
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput2 { c.rbReport = null }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(520.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(C.s1).border(1.dp, C.fx.copy(alpha = .5f), RoundedCornerShape(20.dp)).pointerInput2 { }.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("rekordbox collection imported", style = T.tab.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.fx))
                    Text("${r.matched} of ${r.total} tracks found on this tablet. Their beat grids, keys and hot cues now come from rekordbox.", style = T.tab.copy(fontSize = 13.sp))
                    Text("${r.playlists} playlists · ${r.hotCues} hot cues · ${r.variable} tracks with changing tempo (full tempo maps)", style = T.cap.copy(letterSpacing = 0.2.sp, fontSize = 11.sp))
                    if (r.missing.isNotEmpty()) {
                        Text("Not on this tablet (${r.missing.size}) — add the files and they're matched automatically:", style = T.tab.copy(color = C.mu, fontSize = 12.sp))
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 220.dp)) {
                            items(r.missing.size) { n -> Text("· ${r.missing[n]}", style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = 11.sp, fontWeight = FontWeight.Normal), maxLines = 1) }
                        }
                    }
                    PillButton("Done", true, C.fx, Modifier.fillMaxWidth().height(44.dp), solid = true) { c.rbReport = null }
                }
            }
        }
        c.pendingRemove?.let { item ->
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput2 { c.cancelRemove() }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(C.s1).border(1.dp, C.bad.copy(alpha = .5f), RoundedCornerShape(20.dp)).pointerInput2 { }.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Remove “${item.title}”?", style = T.tab.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                    Text("It disappears from your FLUX library. The file stays on your tablet, and you can restore it any time under “Removed”.", style = T.tab.copy(color = C.mu, fontSize = 13.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Cancel", false, C.tx, Modifier.weight(1f).height(44.dp)) { c.cancelRemove() }
                        PillButton("Remove", true, C.bad, Modifier.weight(1f).height(44.dp), solid = true) { c.confirmRemove() }
                    }
                }
            }
        }
        c.sampleEdit?.let { k ->
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput2 { c.sampleEdit = null }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(440.dp).clip(RoundedCornerShape(20.dp)).background(C.s1).border(1.dp, C.cue[4].copy(alpha = .6f), RoundedCornerShape(20.dp)).pointerInput2 { }.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Sample pad ${k + 1}  ·  ${c.sampleNames[k]}", style = T.tab.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.cue[4]))
                    Text("Replace this pad's sound. Captures take the active loop, or one bar from the playhead.", style = T.tab.copy(color = C.mu, fontSize = 12.sp))
                    PillButton("▶  Play it", false, C.cue[4], Modifier.fillMaxWidth().height(42.dp)) { c.engine.triggerSample(k) }
                    PillButton("Load audio file…", false, C.tx, Modifier.fillMaxWidth().height(42.dp)) { c.sampleEdit = null; onPickSample(k) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (dk in 0..1) {
                            val d = c.engine.decks[dk]
                            PillButton("Capture ${if (d.loopOn) "loop" else "1 bar"} · ${c.deckName(dk)}", false, C.deck(dk), Modifier.weight(1f).height(42.dp)) {
                                if (d.track != null) { c.captureSample(k, dk); c.sampleEdit = null }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Restore built-in", false, C.mu, Modifier.weight(1f).height(42.dp)) { c.resetSample(k); c.sampleEdit = null }
                        PillButton("Done", true, C.cue[4], Modifier.weight(1f).height(42.dp), solid = true) { c.sampleEdit = null }
                    }
                }
            }
        }
        c.toast?.let {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).clip(RoundedCornerShape(14.dp)).background(C.s3).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(it, style = T.tab)
            }
        }
    }
}

@Composable
private fun mixerWidth(c: Controller): Dp = if (c.view.eq) 310.dp else 220.dp

@Composable
private fun TopBar(c: Controller, onLibrary: () -> Unit, onFx: () -> Unit, onOut: () -> Unit, onView: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopChip("☰  Library", false, C.tx, onClick = onLibrary)
        Text("FL", style = T.tab.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp, fontSize = 14.sp), modifier = Modifier.padding(start = 4.dp))
        Text("U", style = T.tab.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp, fontSize = 14.sp, color = C.a))
        Text("X", style = T.tab.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp, fontSize = 14.sp, color = C.b))
        Spacer(Modifier.weight(1f))
        // tempo master
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(C.s2).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("MASTER", style = T.cap.copy(fontSize = 9.sp))
            Text(if (c.leader == 0) "A" else "B", style = T.tab.copy(fontWeight = FontWeight.ExtraBold, color = C.deck(c.leader)))
            Text(c.masterBpm, style = T.num.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text("BPM", style = T.cap.copy(fontSize = 9.sp))
        }
        // auto transition
        Box(Modifier.height(32.dp).width(104.dp)) {
            val prog = c.transitionProgress
            PressBox(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), onDown = { c.toggleTransition() }, onLong = { c.cycleTransitionBeats() }) { p ->
                Box(Modifier.fillMaxSize().background(if (c.transitionActive) C.fx.copy(alpha = .12f) else C.s2).border(1.dp, if (c.transitionActive) C.fx else C.line, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (c.transitionActive) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(prog).background(C.fx.copy(alpha = .3f)))
                    Text(
                        if (c.transitionActive) "MIXING…" else (if (c.leader == 0) "A ▸ B" else "B ▸ A") + "  ${c.transitionBeats}",
                        style = T.tab.copy(fontWeight = FontWeight.Bold, color = if (c.transitionActive) C.fx else C.tx, fontSize = 12.sp, letterSpacing = 0.5.sp)
                    )
                }
            }
        }
        c.nextSuggestion?.let { s ->
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(C.ok.copy(alpha = .12f)).border(1.dp, C.ok.copy(alpha = .6f), RoundedCornerShape(16.dp))
                    .pointerInput2 { c.loadNext() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("✦", style = T.tab.copy(color = C.ok, fontSize = 12.sp))
                Column {
                    Text(s.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = C.tx), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                    Text(c.nextReason, style = T.cap.copy(fontSize = 7.5.sp, letterSpacing = 0.sp, color = C.ok), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TopChip(if (c.recording) "● REC ${fmt(c.recSeconds)}" else if (c.recSaving) "● SAVING" else "● REC", c.recording || c.recSaving, C.bad, onClick = { c.toggleRecord() })
        TopChip("FX  ${if (c.fxOn) c.fxType.label else "off"}", c.fxOn, C.fx, onClick = onFx)
        TopChip("OUT", false, C.tx, sub = c.outputLabel, onClick = onOut)
        TopChip("VIEW", false, C.tx, onClick = onView)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(24.dp)) {
            VuMeter({ c.engine.masterMeterL }, c.tick, Modifier.width(5.dp).fillMaxHeight(), 8)
            VuMeter({ c.engine.masterMeterR }, c.tick, Modifier.width(5.dp).fillMaxHeight(), 8)
        }
        if (c.clip) Text("CLIP", style = T.cap.copy(color = C.bad, fontSize = 9.sp))
    }
}

private fun fmt(s: Int) = String.format("%d:%02d", s / 60, s % 60)

@Composable
private fun TopChip(text: String, on: Boolean, color: Color, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) color.copy(alpha = .16f) else C.s2)
            .border(1.dp, if (on) color else C.line, RoundedCornerShape(16.dp)).pointerInput2(onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, style = T.tab.copy(fontWeight = FontWeight.SemiBold, color = if (on) color else C.tx, fontSize = 12.sp), maxLines = 1)
        if (sub != null) Text(sub, style = T.cap.copy(fontSize = 9.sp, letterSpacing = 0.sp), maxLines = 1, modifier = Modifier.widthIn(max = 110.dp))
    }
}
