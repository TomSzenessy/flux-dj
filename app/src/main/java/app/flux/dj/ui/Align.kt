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
            Text("Line up the beats", style = T.tab.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.fx))
            Text(
                "Both outputs now play the same drum loop. If the kicks sound doubled or smeared, one device is late. " +
                    "Drag the lower lane until the beats you hear land together — one tight kick instead of two.",
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
    val loop = c.engine.calibLoop
    // peak envelope of the drum loop, one value per 100 frames
    val env = remember(loop) {
        val bin = 100
        FloatArray(loop.size / bin) { b -> var m = 0f; for (k in b * bin until (b + 1) * bin) { val v = abs(loop[k]); if (v > m) m = v }; m }
            .let { e -> val mx = e.maxOrNull()?.coerceAtLeast(1e-4f) ?: 1f; FloatArray(e.size) { e[it] / mx } }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0B10)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    start[0] = c.offsetMs
                    var tot = 0f
                    val pxPerMs = size.width / 2000f          // the lanes show 2 seconds
                    drag(down.id) { ch ->
                        tot += ch.positionChange().x
                        // drag the headphone lane right = the headphones are later = delay the master
                        c.setOffset((start[0] + tot / pxPerMs).roundToInt().coerceIn(-RANGE_MS, RANGE_MS))
                        ch.consume()
                    }
                }
            }
        ) {
            c.tick.longValue
            val w = size.width; val h = size.height
            val windowMs = 2000f
            val pxPerMs = w / windowMs
            val framesPerPx = SR * windowMs / 1000f / w
            val pos = c.engine.calibPos
            val L = loop.size
            val laneH = h * 0.36f
            val y1 = h * 0.08f; val y2 = h * 0.56f
            val mid = w / 2
            drawRoundRect(C.a.copy(alpha = 0.07f), Offset(8f, y1), Size(w - 16f, laneH), CornerRadius(10f))
            drawRoundRect(C.b.copy(alpha = 0.07f), Offset(8f, y2), Size(w - 16f, laneH), CornerRadius(10f))
            val shiftFrames = c.offsetMs * SR / 1000f
            fun lane(y: Float, shift: Float, col: Color) {
                var x = 10f
                while (x < w - 10f) {
                    var f = ((pos + (x - mid) * framesPerPx - shift).toInt()) % L
                    if (f < 0) f += L
                    val a = env[(f / 100).coerceIn(0, env.size - 1)]
                    val hh = (a * laneH * 0.46f).coerceAtLeast(1f)
                    drawLine(col, Offset(x, y + laneH / 2 - hh), Offset(x, y + laneH / 2 + hh), 2f)
                    x += 3f
                }
                // beat markers (every 500 ms)
                val beatFrames = SR / 2f
                var k = Math.floor(((pos - mid * framesPerPx - shift) / beatFrames).toDouble()).toInt()
                while (true) {
                    val bx = mid + ((k * beatFrames + shift - pos) / framesPerPx)
                    if (bx > w) break
                    if (bx >= 0) drawLine(col.copy(alpha = 0.55f), Offset(bx, y - 4f), Offset(bx, y + 6f), 3f)
                    k++
                }
            }
            lane(y1, 0f, C.a)
            lane(y2, shiftFrames, C.b)
            // "now" line: what you hear at this moment
            drawLine(Color.White.copy(alpha = 0.85f), Offset(mid, 0f), Offset(mid, h), 2f)
            val aligned = abs(c.offsetMs) < 1
            drawLine(if (aligned) C.ok else C.fx.copy(alpha = 0.6f), Offset(mid, y1 + laneH), Offset(mid + c.offsetMs * pxPerMs, y2), 2f)
        }
        Text(masterName, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 6.dp), style = T.cap.copy(color = C.a, letterSpacing = 0.4.sp))
        Text(cueName, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 4.dp), style = T.cap.copy(color = C.b, letterSpacing = 0.4.sp))
        Text("◀ drag the lower lane ▶", Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 4.dp), style = T.cap.copy(color = C.dim))
    }
}
