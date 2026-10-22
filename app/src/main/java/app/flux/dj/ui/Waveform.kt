package app.flux.dj.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.Controller
import app.flux.dj.audio.*
import kotlin.math.*


/** Scrolling 3-band waveform with beat grid, cues and loop; drag to scrub. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun WaveStrip(c: Controller, i: Int, showOverview: Boolean, onOpenLibrary: () -> Unit, modifier: Modifier = Modifier) {
    val u = c.ui[i]
    val col = C.deck(i)
    val d = c.engine.decks[i]
    val ctx = LocalContext.current
    var hover by remember { mutableStateOf(false) }
    val dropTarget = remember(i) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hover = true }
            override fun onExited(event: DragAndDropEvent) { hover = false }
            override fun onEnded(event: DragAndDropEvent) { hover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover = false
                val ev = event.toAndroidDragEvent()
                val cd = ev.clipData ?: return false
                if (cd.itemCount == 0) return false
                val item = cd.getItemAt(0)
                if (cd.description.label == "flux-track") { c.loadById(i, item.text.toString()); return true }
                val uri = item.uri ?: return false
                try { (ctx as? android.app.Activity)?.requestDragAndDropPermissions(ev) } catch (_: Exception) {}
                c.dropUri(i, uri)
                return true
            }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.s1)
            .border(if (hover) 2.dp else 1.dp, if (hover) col else C.line, RoundedCornerShape(12.dp))
            .dragAndDropTarget(shouldStartDragAndDrop = { e -> e.mimeTypes().any { it.startsWith("audio/") || it == "text/plain" || it == "application/octet-stream" || it == "text/uri-list" } }, target = dropTarget)
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MainWave(c, i, Modifier.fillMaxSize())
                // header overlay
                Row(
                    Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier.size(22.dp, 20.dp).clip(RoundedCornerShape(6.dp)).background(col),
                        contentAlignment = Alignment.Center
                    ) { Text(if (i == 0) "A" else "B", style = T.tab.copy(color = Color.Black, fontWeight = FontWeight.ExtraBold)) }
                    // LOAD / eject button
                    Box(
                        Modifier.size(34.dp, 26.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).border(1.dp, col.copy(alpha = .6f), RoundedCornerShape(8.dp)).pointerInput2 { onOpenLibrary() },
                        contentAlignment = Alignment.Center
                    ) { EjectIcon(col) }
                    if (u.loaded) Box(
                        Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(if (u.gridEdit) C.fx else Color(0x99000000))
                            .border(1.dp, if (u.gridEdit) C.fx else C.line, RoundedCornerShape(8.dp))
                            .pointerInput2 { if (u.gridEdit) c.gridDone(i) else c.gridOpen(i) }.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("GRID", style = T.cap.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, color = if (u.gridEdit) Color.Black else C.mu, fontWeight = FontWeight.Bold)) }
                    Column(Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); val up = waitForUpOrCancellation(); if (up != null) onOpenLibrary() } }
                        .background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(u.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp), maxLines = 1)
                        val sub = buildString {
                            if (u.keyText.isNotEmpty()) append(u.keyText)
                            if (u.origBpm.isNotEmpty()) { if (isNotEmpty()) append(" · "); append("${u.origBpm} BPM") }
                            if (u.analysing) append("  analysing…")
                        }
                        if (sub.isNotEmpty() || u.artist.isNotEmpty()) Text(if (sub.isNotEmpty()) sub else u.artist, style = T.cap.copy(fontSize = 9.sp, letterSpacing = 0.3.sp, color = C.mu), maxLines = 1)
                    }
                    if (u.match.isNotEmpty() && u.loaded) {
                        Text("● " + u.match, style = T.cap.copy(fontSize = 9.sp, color = if (u.matchGood) C.ok else C.fx))
                    }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (u.hasBpm && u.loaded) Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (b in 1..4) Box(Modifier.size(9.dp, 9.dp).clip(RoundedCornerShape(2.dp)).background(if (b == u.beat) (if (b == 1) col else C.tx) else C.s3))
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(u.barText, style = T.num.copy(fontSize = 10.sp, color = if (u.phraseSoon) C.fx else C.mu))
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.pointerInput2 { c.showRemain = !c.showRemain }) {
                        Text(if (c.showRemain) u.remain else u.time, style = T.num.copy(fontSize = 19.sp, color = col, fontWeight = FontWeight.SemiBold))
                        Text(if (c.showRemain) u.time else u.remain, style = T.cap.copy(fontSize = 10.sp, letterSpacing = 0.sp))
                    }
                }
                if (u.loading >= 0f && !u.loaded) Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Loading ${(u.loading * 100).toInt()}%", style = T.tab)
                }
                // already playable: just a hairline while the rest of the file decodes
                if (u.loading >= 0f && u.loaded) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(u.loading.coerceIn(0f, 1f)).height(2.dp).background(col.copy(alpha = .8f)))
                if (!u.loaded && u.loading < 0f) Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(col.copy(alpha = .14f)).border(1.dp, col, RoundedCornerShape(18.dp)).pointerInput2 { onOpenLibrary() }.padding(horizontal = 18.dp, vertical = 9.dp)
                ) { Text("＋  Load a track  ·  or drop a file here", style = T.tab.copy(color = col, fontWeight = FontWeight.SemiBold)) }
                if (u.gridEdit) GridBar(c, i, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 5.dp, end = 150.dp))
                // zoom
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (c.zoomSec < 10f) String.format("%.1fs", c.zoomSec) else "${c.zoomSec.roundToInt()}s", style = T.cap.copy(fontSize = 9.sp, letterSpacing = 0.sp))
                    ZoomBtn("−") { c.zoomSec = (c.zoomSec * 2f).coerceAtMost(48f) }
                    ZoomBtn("+") { c.zoomSec = (c.zoomSec / 2f).coerceAtLeast(1f) }
                }
                if (hover) Box(Modifier.fillMaxSize().background(col.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Text("Drop to load on deck ${if (i == 0) "A" else "B"}", style = T.tab.copy(color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp)) }
            }
            if (showOverview) Overview(c, i, Modifier.fillMaxWidth().height(22.dp))
        }
    }
}

@Composable
private fun MainWave(c: Controller, i: Int, modifier: Modifier) {
    val d = c.engine.decks[i]
    val u = c.ui[i]
    val col = C.deck(i)
    val tick = c.tick
    val pts = remember { Array(3) { FloatArray(4 * 1400) } }
    Canvas(
        modifier.pointerInput(i) {
            // one finger: grab & scrub the record; two fingers: pinch to zoom (both decks)
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var mode = 0 // 0 undecided, 1 scrub, 2 pinch
                var tot = 0f; var p0 = 0.0
                var dist0 = 1f; var z0 = c.zoomSec
                val slop = 6.dp.toPx()
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        val dd = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(1f)
                        if (mode == 1) d.scratchEnd()
                        if (mode == 4) d.searching = false
                        if (mode != 2) { mode = 2; dist0 = dd; z0 = c.zoomSec }
                        else c.zoomSec = (z0 * dist0 / dd).coerceIn(1f, 48f)
                        ev.changes.forEach { it.consume() }
                    } else if (mode != 2 && d.track != null) {
                        val ch = pressed[0]
                        tot += ch.positionChange().x
                        val fpp = c.zoomSec * SR * d.effRatio / size.width
                        if (u.gridEdit) {
                            // grid edit: drag slides the beat grid under the music
                            if (mode == 0 && abs(tot) > slop) { mode = 3; tot = 0f }
                            if (mode == 3) c.gridDrag(i, ch.positionChange().x * fpp.toDouble())
                        } else {
                            if (mode == 0 && abs(tot) > slop) {
                                p0 = d.pos; tot = 0f
                                // scratch sound only if enabled in VIEW; otherwise a silent needle search
                                if (c.waveScratch) { mode = 1; d.scratchBegin() } else { mode = 4; d.searching = true }
                            }
                            if (mode == 1) d.scratchTo(p0 - tot * fpp)
                            if (mode == 4) d.seek(p0 - tot * fpp)
                        }
                        ch.consume()
                    }
                }
                if (mode == 1) d.scratchEnd()
                if (mode == 4) d.searching = false
            }
        }
    ) {
        tick.longValue
        val w = size.width; val h = size.height
        drawRect(C.s1)
        val t = d.track
        val wave = t?.wave
        val mid = h / 2f
        if (t == null || wave == null) {
            drawLine(C.s3, Offset(0f, mid), Offset(w, mid), 2f)
            return@Canvas
        }
        val zoomSec = c.zoomSec
        val fpp = zoomSec * SR * d.effRatio.toFloat() / w
        val pos = c.vpos(i)
        val fStart = pos - fpp * w / 2
        val bars = min((w / 2.5f).toInt(), 1390)
        val colW = w / bars
        val bins = wave.size / 3
        val norm = t.waveNorm.let { if (it > 0f) it else peakNorm(wave).also { v -> t.waveNorm = v } } / 255f
        for (x in 0 until bars) {
            val f0 = fStart + x * colW * fpp
            val b0 = (f0 / BIN).toInt()
            val b1 = ((f0 + colW * fpp) / BIN).toInt()
            var lo = 0; var mi = 0; var hi = 0
            val step = max(1, (b1 - b0) / 3)
            var b = b0
            while (b <= b1) {
                if (b in 0 until bins) {
                    val l = wave[b * 3].toInt() and 255; val m = wave[b * 3 + 1].toInt() and 255; val hh = wave[b * 3 + 2].toInt() and 255
                    if (l > lo) lo = l; if (m > mi) mi = m; if (hh > hi) hi = hh
                }
                b += step
            }
            val px = x * colW + colW / 2
            val a0 = min(1f, lo * norm) * mid * 0.94f
            val a1 = min(1f, mi * norm) * mid * 0.78f
            val a2 = min(1f, hi * norm) * mid * 0.55f
            var o = x * 4
            pts[0][o] = px; pts[0][o + 1] = mid - a0; pts[0][o + 2] = px; pts[0][o + 3] = mid + a0
            pts[1][o] = px; pts[1][o + 1] = mid - a1; pts[1][o + 2] = px; pts[1][o + 3] = mid + a1
            pts[2][o] = px; pts[2][o + 1] = mid - a2; pts[2][o + 2] = px; pts[2][o + 3] = mid + a2
        }
        val sw = colW * 0.82f
        // loop region (behind)
        if (d.loopOn) {
            val x0 = ((d.loopIn - fStart) / fpp).toFloat(); val x1 = ((d.loopOut - fStart) / fpp).toFloat()
            drawRect(C.fx.copy(alpha = 0.16f), Offset(x0, 0f), Size(x1 - x0, h))
            drawLine(C.fx, Offset(x0, 0f), Offset(x0, h), 2f); drawLine(C.fx, Offset(x1, 0f), Offset(x1, h), 2f)
        }
        // beat grid
        if (t.bpm > 0) {
            val spb = t.spb
            val k0 = ceil((fStart - t.firstBeat) / spb).toInt()
            val k1 = floor((fStart + w * fpp - t.firstBeat) / spb).toInt()
            for (k in k0..k1) {
                val x = ((t.firstBeat + k * spb - fStart) / fpp).toFloat()
                val bar = Math.floorMod(k, 4) == 0
                drawLine(Color.White.copy(alpha = if (bar) 0.35f else 0.12f), Offset(x, if (bar) 0f else h * 0.78f), Offset(x, h), if (bar) 2f else 1f)
            }
        }
        val editGrid = u.gridEdit && t.bpm > 0
        lines(pts[0], bars * 4, C.lo, sw)
        lines(pts[1], bars * 4, C.mid.copy(alpha = 0.92f), sw)
        lines(pts[2], bars * 4, C.hi.copy(alpha = 0.95f), sw)
        // past dimming
        drawRect(Color(0x99090A0F), Offset.Zero, Size(w / 2, h))
        // grid edit: draw the grid on top of the waveform so it can be lined up with the kicks
        if (editGrid) {
            val spb = t.spb
            val k0 = ceil((fStart - t.firstBeat) / spb).toInt()
            val k1 = floor((fStart + w * fpp - t.firstBeat) / spb).toInt()
            for (k in k0..k1) {
                val x = ((t.firstBeat + k * spb - fStart) / fpp).toFloat()
                val bar = Math.floorMod(k, 4) == 0
                drawLine(if (bar) C.fx else Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, h), if (bar) 3f else 2f)
                if (bar) drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x - 6f, 0f); lineTo(x + 6f, 0f); lineTo(x, 9f); close() }, C.fx)
            }
            drawRect(C.fx.copy(alpha = 0.05f))
            if (u.gridMode == 1) {
                // the pin STRETCH pivots around
                val ax = ((c.gridAnchorFrames(i) - fStart) / fpp).toFloat()
                if (ax in -10f..w + 10f) {
                    drawLine(C.ok, Offset(ax, 0f), Offset(ax, h), 3f)
                    drawCircle(C.ok, 7f, Offset(ax, 10f))
                }
            }
        }
        // hot cues
        for (k in 0 until 8) {
            val cp = d.hotCues[k]
            if (cp < 0) continue
            val x = ((cp - fStart) / fpp).toFloat()
            if (x < -20 || x > w + 20) continue
            drawLine(C.cue[k], Offset(x, 0f), Offset(x, h), 2f)
            drawRoundRect(C.cue[k], Offset(x, 0f), Size(15.dp.toPx(), 13.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = .75f), 2.dp.toPx(), Offset(x + 7.5.dp.toPx(), 6.5.dp.toPx()))
        }
        // main cue
        run {
            val x = ((d.cue - fStart) / fpp).toFloat()
            if (x in 0f..w) { drawLine(Color(0xFFFF8A3D), Offset(x, 0f), Offset(x, h), 2f) }
        }
        // playhead
        drawLine(col.copy(alpha = 0.25f), Offset(w / 2, 0f), Offset(w / 2, h), 8f)
        drawLine(col, Offset(w / 2, 0f), Offset(w / 2, h), 2.5f)
        // end warning
        val remain = t.durationSec - pos / SR
        if (remain < 30 && d.playing) {
            val pulse = (0.5f + 0.5f * sin(System.nanoTime() / 1.2e8).toFloat())
            drawRoundRect(C.bad.copy(alpha = 0.25f + 0.55f * pulse), Offset(1f, 1f), Size(w - 2f, h - 2f), CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        if (d.scratching) drawRect(Color(0x11FFFFFF))
    }
}

@Composable
private fun Overview(c: Controller, i: Int, modifier: Modifier) {
    val d = c.engine.decks[i]
    val col = C.deck(i)
    Canvas(
        modifier.background(Color(0xFF0A0B10)).pointerInput(i) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                d.seekFrac(down.position.x / size.width)
                drag(down.id) { ch -> d.seekFrac(ch.position.x / size.width); ch.consume() }
            }
        }
    ) {
        c.tick.longValue
        val t = d.track ?: return@Canvas
        val ov = t.overview ?: return@Canvas
        val w = size.width; val h = size.height
        val cols = min(OV_COLS, w.toInt())
        val colW = w / cols
        val on = t.ovNorm.let { if (it > 0f) it else peakNorm(ov).also { v -> t.ovNorm = v } }
        val frac = (c.vpos(i) / t.frames).toFloat()
        val px = frac * w
        val pts = FloatArray(cols * 4)
        for (x in 0 until cols) {
            val oc = (x.toLong() * OV_COLS / cols).toInt()
            val a = ((ov[oc * 3].toInt() and 255) * 0.55f + (ov[oc * 3 + 1].toInt() and 255) * 0.3f + (ov[oc * 3 + 2].toInt() and 255) * 0.15f) / 255f
            val hh = (min(1f, a * on) * h * 0.5f).coerceAtLeast(0.5f)
            val xx = x * colW + colW / 2
            pts[x * 4] = xx; pts[x * 4 + 1] = h / 2 - hh; pts[x * 4 + 2] = xx; pts[x * 4 + 3] = h / 2 + hh
        }
        lines(pts, cols * 4, Color(0xFF3B4158), colW)
        drawRect(col.copy(alpha = 0.0f))
        // played portion tint
        clipPlayed(this, pts, cols, colW, px, col)
        for (k in 0 until 8) {
            val cp = d.hotCues[k]; if (cp < 0) continue
            val x = (cp / t.frames).toFloat() * w
            drawRect(C.cue[k], Offset(x - 1f, 0f), Size(2.5f, h))
        }
        if (d.loopOn) drawRect(C.fx.copy(alpha = 0.5f), Offset((d.loopIn / t.frames).toFloat() * w, h - 3.dp.toPx()), Size(((d.loopOut - d.loopIn) / t.frames).toFloat() * w, 3.dp.toPx()))
        drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2f)
    }
}

private fun clipPlayed(s: androidx.compose.ui.graphics.drawscope.DrawScope, pts: FloatArray, cols: Int, colW: Float, px: Float, col: Color) {
    val n = min(cols, max(0, (px / colW).toInt()))
    if (n <= 0) return
    s.lines(pts, n * 4, col.copy(alpha = 0.85f), colW)
}

private val linePaint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; isAntiAlias = false }
private fun DrawScope.lines(pts: FloatArray, count: Int, color: Color, sw: Float) {
    drawIntoCanvas { cv ->
        linePaint.color = color.toArgb(); linePaint.strokeWidth = sw
        cv.nativeCanvas.drawLines(pts, 0, count, linePaint)
    }
}

@Composable
private fun ZoomBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp, 24.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).border(1.dp, C.line, RoundedCornerShape(8.dp)).pointerInput2(onClick), contentAlignment = Alignment.Center) {
        Text(label, style = T.tab.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun EjectIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height * 0.62f); lineTo(0f, size.height * 0.62f); close() }
        drawPath(p, color)
        drawRect(color, Offset(0f, size.height * 0.76f), Size(size.width, size.height * 0.2f))
    }
}

@Composable
private fun GridBar(c: Controller, i: Int, modifier: Modifier) {
    val u = c.ui[i]
    Row(
        modifier.horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(10.dp)).background(Color(0xE6000000)).border(1.dp, C.fx.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        GridBtn("MOVE", accent = u.gridMode == 0) { u.gridMode = 0 }
        GridBtn("STRETCH", accent = u.gridMode == 1) { u.gridMode = 1 }
        Text(if (u.gridMode == 0) "drag = slide grid" else "pin 1 early · go later · drag", style = T.cap.copy(fontSize = 8.sp, letterSpacing = 0.3.sp, color = C.fx), modifier = Modifier.padding(horizontal = 4.dp))
        GridBtn("◀") { c.gridShiftMs(i, -5f) }
        GridBtn("▶") { c.gridShiftMs(i, 5f) }
        GridBtn("SET 1 HERE", accent = true) { c.gridSetDownbeat(i) }
        Spacer(Modifier.width(4.dp))
        GridBtn("−0.1") { c.gridBpmDelta(i, -0.1f) }
        GridBtn("−.01") { c.gridBpmDelta(i, -0.01f) }
        Text(u.gridBpm, style = T.num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 4.dp))
        GridBtn("+.01") { c.gridBpmDelta(i, 0.01f) }
        GridBtn("+0.1") { c.gridBpmDelta(i, 0.1f) }
        GridBtn("½") { c.gridScale(i, 0.5f) }
        GridBtn("×2") { c.gridScale(i, 2f) }
        GridBtn("ROUND") { c.gridRound(i) }
        GridBtn("TAP") { c.gridTap(i) }
        Spacer(Modifier.width(4.dp))
        GridBtn("RESET") { c.gridReset(i) }
        GridBtn("DONE", accent = true) { c.gridDone(i) }
    }
}

@Composable
private fun GridBtn(label: String, accent: Boolean = false, onClick: () -> Unit) {
    PressBox(Modifier.height(28.dp).clip(RoundedCornerShape(7.dp)), onDown = onClick) { p ->
        Box(
            Modifier.fillMaxHeight().background(if (p) C.fx.copy(alpha = .5f) else if (accent) C.fx.copy(alpha = .2f) else C.s3)
                .border(1.dp, if (accent) C.fx else C.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center
        ) { Text(label, style = T.tab.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (accent) C.fx else C.tx), maxLines = 1, softWrap = false) }
    }
}
