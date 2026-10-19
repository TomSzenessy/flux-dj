package app.flux.dj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.Controller
import app.flux.dj.audio.OutMode
import app.flux.dj.audio.SR
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private const val RANGE_MS = 450

/**
 * Align-by-ear: both outputs click on every beat; drag the cue lane until the
 * two clicks fuse into one. The offset is applied live while dragging.
 */
@Composable
fun AlignScreen(c: Controller) {
    val masterName = if (c.outMode == OutMode.SPLIT) "Master (right)" else c.routedTo.ifEmpty { "Master" }
    val cueName = if (c.outMode == OutMode.SPLIT) "Cue (left)" else c.routedCue.ifEmpty { "Headphones" }
    Box(Modifier.fillMaxSize().background(Color(0xEE05060A)).pointerInput2 { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(0.85f).clip(RoundedCornerShape(24.dp)).background(C.s1).border(1.dp, C.fx.copy(alpha = .5f), RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Line up the clicks", style = T.tab.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.fx))
            Text(
                "Both outputs now click on every beat. If you hear a double click (“flam”), one device is late. " +
                    "Drag left or right until the two lanes line up and you hear one sharp click.",
                style = T.tab.copy(color = C.mu, fontSize = 13.sp)
            )
            AlignLanes(c, masterName, cueName, Modifier.fillMaxWidth().height(170.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        c.offsetMs == 0 -> "In sync (no delay)"
                        c.offsetMs > 0 -> "Master waits ${c.offsetMs} ms for the cue"
                        else -> "Cue waits ${-c.offsetMs} ms for the master"
                    },
                    style = T.num.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f)
                )
                for (d in listOf(-10, -1, 1, 10)) Chip(if (d > 0) "+$d" else "$d", false, C.fx, Modifier.height(36.dp).width(52.dp)) {
                    c.setOffset((c.offsetMs + d).coerceIn(-RANGE_MS, RANGE_MS))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Reset", false, C.mu, Modifier.weight(1f).height(46.dp)) { c.setOffset(0) }
                PillButton("Done", true, C.fx, Modifier.weight(2f).height(46.dp), solid = true) { c.stopAlign() }
            }
        }
    }
}

@Composable
private fun AlignLanes(c: Controller, masterName: String, cueName: String, modifier: Modifier) {
    val start = remember { intArrayOf(0) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0B10)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    start[0] = c.offsetMs
                    var tot = 0f
                    val pxPerMs = size.width / (2f * RANGE_MS)
                    drag(down.id) { ch ->
                        tot += ch.positionChange().x
                        // dragging the cue lane right = cue arrives later = delay the master
                        c.setOffset((start[0] + tot / pxPerMs).roundToInt().coerceIn(-RANGE_MS, RANGE_MS))
                        ch.consume()
                    }
                }
            }
        ) {
            c.tick.longValue
            val w = size.width; val h = size.height
            val pxPerMs = w / (2f * RANGE_MS)
            val mid = w / 2
            val laneH = h * 0.32f
            val y1 = h * 0.12f; val y2 = h * 0.56f
            // pulse (flashes with every click)
            val since = c.engine.calibSinceClick / (SR / 1000f)
            val flash = exp(-since / 90f)
            drawRoundRect(C.a.copy(alpha = 0.08f + 0.25f * flash), Offset(8f, y1), Size(w - 16f, laneH), CornerRadius(10f))
            drawRoundRect(C.b.copy(alpha = 0.08f + 0.25f * flash), Offset(8f, y2), Size(w - 16f, laneH), CornerRadius(10f))
            // ms grid
            for (ms in -RANGE_MS..RANGE_MS step 50) {
                val x = mid + ms * pxPerMs
                drawLine(Color.White.copy(alpha = if (ms == 0) 0.4f else 0.08f), Offset(x, 0f), Offset(x, h), if (ms == 0) 2f else 1f)
            }
            // master click fixed at centre; cue click shifted by the offset
            val off = c.offsetMs
            val aligned = abs(off) < 1
            fun click(x: Float, y: Float, col: Color) {
                drawRoundRect(col, Offset(x - 5f, y + 6f), Size(10f, laneH - 12f), CornerRadius(5f))
                drawCircle(col.copy(alpha = 0.35f * flash), 26f, Offset(x, y + laneH / 2))
            }
            click(mid, y1, C.a)
            click(mid + off * pxPerMs, y2, C.b)
            drawLine(if (aligned) C.ok else C.fx, Offset(mid, y1 + laneH), Offset(mid + off * pxPerMs, y2), 2f)
        }
        Text(masterName, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 24.dp), style = T.cap.copy(color = C.a, letterSpacing = 0.4.sp))
        Text(cueName, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 30.dp), style = T.cap.copy(color = C.b, letterSpacing = 0.4.sp))
        Text("◀ drag ▶", Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 10.dp), style = T.cap.copy(color = C.dim))
    }
}
