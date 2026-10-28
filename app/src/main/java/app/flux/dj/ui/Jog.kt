package app.flux.dj.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.*
import app.flux.dj.Controller
import app.flux.dj.audio.SR
import kotlin.math.*

private const val FRAMES_PER_REV = SR * 1.8 // 33 1/3 rpm

/**
 * Touch platter. Centre = scratch (finger angle drives the playhead like vinyl).
 * Outer rim while playing = pitch bend. Spins in sync with the track.
 */
@Composable
fun Jog(c: Controller, i: Int, modifier: Modifier = Modifier) {
    val d = c.engine.decks[i]
    val u = c.ui[i]
    val col = C.deck(i)
    val hp = rememberHaptic()
    Box(
        modifier.aspectRatio(1f)
            .semantics { contentDescription = "Jog wheel deck ${if (i == 0) "A" else "B"}; scratch in the centre, pitch bend on the rim" }
            .pointerInput(i) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (d.track == null) return@awaitEachGesture
                val cx = size.width / 2f; val cy = size.height / 2f
                val rad = min(cx, cy)
                val r0 = hypot(down.position.x - cx, down.position.y - cy)
                val rim = r0 > rad * 0.76f && d.playing
                var lastAng = atan2(down.position.y - cy, down.position.x - cx)
                hp(HapticFeedbackConstants.KEYBOARD_TAP)
                if (!rim) {
                    d.scratchBegin()
                    var target = d.pos
                    try {
                        drag(down.id) { ch ->
                            val a = atan2(ch.position.y - cy, ch.position.x - cx)
                            var da = a - lastAng
                            if (da > PI) da -= (2 * PI).toFloat() else if (da < -PI) da += (2 * PI).toFloat()
                            lastAng = a
                            target += da / (2 * PI) * FRAMES_PER_REV
                            d.scratchTo(target)
                            ch.consume()
                        }
                    } finally { d.scratchEnd() }
                } else {
                    var lastT = System.nanoTime(); var vel = 0f
                    try {
                        drag(down.id) { ch ->
                            val a = atan2(ch.position.y - cy, ch.position.x - cx)
                            var da = a - lastAng
                            if (da > PI) da -= (2 * PI).toFloat() else if (da < -PI) da += (2 * PI).toFloat()
                            lastAng = a
                            val now = System.nanoTime(); val dt = ((now - lastT) / 1e9f).coerceAtLeast(0.004f); lastT = now
                            val w = da / dt
                            vel += (w - vel) * 0.4f
                            val nominal = (2 * PI / 1.8).toFloat()
                            d.bend = ((vel - nominal) / nominal * 0.12f).coerceIn(-0.2f, 0.2f)
                            ch.consume()
                        }
                    } finally { d.bend = 0f }
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            c.tick.longValue
            val s = size.minDimension
            val ctr = center
            val R = s / 2f - 2.dp.toPx()
            val t = d.track
            val vp = c.vpos(i)
            val frac = if (t != null) (vp / t.frames).toFloat() else 0f
            // body
            drawCircle(Brush.radialGradient(listOf(C.jogBody, C.jogDeep), center = Offset(ctr.x, ctr.y - R * .25f), radius = R * 1.1f), R, ctr)
            drawCircle(C.line, R, ctr, style = Stroke(1.5f))
            // rim track (progress)
            val rimW = max(3f, R * 0.05f)
            val rr = R - rimW
            drawArc(Color(0x14FFFFFF), 0f, 360f, false, Offset(ctr.x - rr, ctr.y - rr), androidx.compose.ui.geometry.Size(rr * 2, rr * 2), style = Stroke(rimW))
            if (t != null) {
                drawArc(col, -90f, 360f * frac, false, Offset(ctr.x - rr, ctr.y - rr), androidx.compose.ui.geometry.Size(rr * 2, rr * 2), style = Stroke(rimW, cap = StrokeCap.Round))
                for (k in 0 until 8) {
                    val cp = d.hotCues[k]; if (cp < 0) continue
                    val a = Math.toRadians(-90.0 + 360.0 * cp / t.frames)
                    drawCircle(C.cue[k], rimW * 0.95f, Offset(ctr.x + cos(a).toFloat() * rr, ctr.y + sin(a).toFloat() * rr))
                }
            }
            // spinning platter
            val rot = if (t != null) (vp / FRAMES_PER_REV * 2 * PI) else 0.0
            val pr = R * 0.74f
            drawCircle(C.jogPlatter, pr, ctr)
            drawCircle(C.line, pr, ctr, style = Stroke(1f))
            for (k in 0 until 48) {
                val a = rot + k * 2 * PI / 48
                val major = k % 12 == 0
                val r1 = pr * 0.93f; val r2 = pr * (if (major) 0.80f else 0.87f)
                drawLine(if (major) col else Color(0x33FFFFFF), Offset(ctr.x + cos(a).toFloat() * r1, ctr.y + sin(a).toFloat() * r1),
                    Offset(ctr.x + cos(a).toFloat() * r2, ctr.y + sin(a).toFloat() * r2), if (major) 3f else 1.5f, StrokeCap.Round)
            }
            // needle marker
            val na = rot - PI / 2
            drawCircle(col, max(4f, R * 0.045f), Offset(ctr.x + cos(na).toFloat() * pr * 0.66f, ctr.y + sin(na).toFloat() * pr * 0.66f))
            // hub
            drawCircle(C.jogHub, R * 0.46f, ctr)
            drawCircle(C.line, R * 0.46f, ctr, style = Stroke(1f))
            if (d.scratching) drawCircle(col.copy(alpha = 0.15f), pr, ctr)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(u.bpm, style = T.num.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
            Text(u.pct, style = T.cap.copy(fontSize = 9.sp, letterSpacing = 0.sp, color = if (u.synced) C.fx else C.mu))
            if (u.origBpm.isNotEmpty()) Text("ORIG ${u.origBpm}", style = T.cap.copy(fontSize = 8.sp, letterSpacing = 0.4.sp, color = C.dim))
        }
    }
}
