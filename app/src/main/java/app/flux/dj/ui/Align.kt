package app.flux.dj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.flux.dj.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.*
import app.flux.dj.Controller
import app.flux.dj.audio.CALIB_BEAT
import app.flux.dj.audio.OutMode
import app.flux.dj.audio.SR
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

private const val RANGE_MS = 450
private const val WINDOW_MS = 4000f        // lanes show four beats (one per second)

/**
 * Timing alignment by ear. The loop has one beat per second (beat 1 = high "ding", 2–4 = low "tock"),
 * so any delay under half a second is unambiguous: you always line up with the nearest beat.
 *  - two outputs: drag the headphone lane until both outputs' beats land together;
 *  - picture: drag until the marker crosses the line exactly when you hear the beat.
 */
@Composable
fun AlignScreen(c: Controller) {
    var lane by remember { mutableStateOf(if (c.outMode == OutMode.SINGLE) 1 else 0) }
    val showPicture = lane == 1 || c.outMode == OutMode.SINGLE
    Box(Modifier.fillMaxSize().background(C.scrimStrong).pointerInput2 { c.cancelAlign() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(0.9f).fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState()).clip(RoundedCornerShape(D.r24)).background(C.s1).border(1.dp, C.fx.copy(alpha = .5f), RoundedCornerShape(D.r24)).pointerInput2 { }.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.timing_alignment), style = T.tab.copy(fontSize = F.f22, fontWeight = FontWeight.Bold, color = C.fx))
            Text(stringResource(R.string.the_room_the_headphones_and_the_picture_are_all_outputs_with_a_delay_flux_can_measure_them_with_the_mic_and_hold_everything_back_to_match_or_you_can_nudge_each_one_by_ear), style = T.tab.copy(color = C.mu, fontSize = F.f13))

            AutoAlignCard(c)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.outMode != OutMode.SINGLE) Chip(stringResource(R.string.headphones_room), lane == 0, C.fx, Modifier.height(48.dp)) { lane = 0 }
                Chip(stringResource(R.string.picture_sound), showPicture && c.outMode == OutMode.SINGLE || lane == 1, C.fx, Modifier.height(48.dp)) { lane = 1 }
            }
            if (showPicture) PictureLane(c, Modifier.fillMaxWidth().height(150.dp)) else CueLanes(c, Modifier.fillMaxWidth().height(150.dp))
            val value = if (showPicture) c.visualTrimMs else c.offsetMs
            fun nudge(delta: Int) {
                if (c.outMode == OutMode.SINGLE || lane == 1) c.setVisualTrim(c.visualTrimMs + delta)
                else c.setOffset((c.offsetMs + delta).coerceIn(-RANGE_MS, RANGE_MS))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (showPicture) "Picture held back ${c.latencyMs} ms" + (if (value != 0) "  ·  fine-tune ${if (value > 0) "+" else ""}$value ms" else "")
                    else when {
                        value == 0 -> "In sync (no delay)"
                        value > 0 -> "Headphones were behind → room waits $value ms"
                        else -> "Headphones were ahead → cue waits ${-value} ms"
                    },
                    style = T.num.copy(fontSize = F.f14, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f)
                )
                for (d in listOf(-10, -1, 1, 10)) Chip(if (d > 0) "+$d" else "$d", false, C.fx, Modifier.height(48.dp).width(52.dp)) { nudge(d) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.cancel), false, C.mu, Modifier.weight(1f).height(46.dp)) { c.cancelAlign() }
                PillButton(stringResource(R.string.reset), false, C.mu, Modifier.weight(1f).height(46.dp)) { if (showPicture) c.setVisualTrim(0) else c.setOffset(0) }
                PillButton(stringResource(R.string.done), true, C.fx, Modifier.weight(2f).height(46.dp), solid = true) { c.stopAlign() }
            }
        }
    }
}

@Composable
private fun AutoAlignCard(c: Controller) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(D.r14)).background(C.s2).border(1.dp, C.ok.copy(alpha = .35f), RoundedCornerShape(D.r14)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.automatic_microphone), style = T.cap.copy(color = C.ok, fontSize = F.f9, letterSpacing = 0.8.sp), modifier = Modifier.weight(1f))
            if (c.autoStep == 5) Chip(stringResource(R.string.redo), false, C.ok, Modifier.height(48.dp)) { c.beginAutoAlign() }
        }
        when (c.autoStep) {
            0 -> {
                Text(stringResource(R.string.put_the_mic_next_to_the_room_output_then_next_to_the_headphones_flux_measures_each_delay_aligns_them_and_holds_the_picture_grid_back_to_the_latest_one_close_the_sheet_to_keep_the_current_timing), style = T.tab.copy(color = C.mu, fontSize = F.f12))
                PillButton(stringResource(R.string.auto_align_everything), true, C.ok, Modifier.fillMaxWidth().height(38.dp), solid = true) { c.beginAutoAlign() }
                Chip(stringResource(R.string.measure_current_output_only), false, C.mu, Modifier.height(48.dp)) { c.measureMaster() }
            }
            1 -> {
                Text(c.autoMsg, style = T.tab.copy(fontSize = F.f12))
                PillButton(stringResource(R.string.measure_speaker), true, C.ok, Modifier.fillMaxWidth().height(38.dp), solid = true) { c.measureMaster() }
                if (!c.micGranted) Text(stringResource(R.string.microphone_access_will_be_requested), style = T.cap.copy(letterSpacing = 0.1.sp, fontSize = F.f10, fontWeight = FontWeight.Normal))
            }
            2, 4 -> {
                Text(c.autoMsg, style = T.tab.copy(fontSize = F.f12))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(D.r3)).background(C.s4)) {
                    Box(Modifier.fillMaxWidth(c.autoProgress.coerceIn(0f, 1f)).fillMaxHeight().background(C.ok))
                }
            }
            3 -> {
                Text(c.autoMsg, style = T.tab.copy(fontSize = F.f12))
                PillButton(stringResource(R.string.measure_headphones), true, C.ok, Modifier.fillMaxWidth().height(38.dp), solid = true) { c.measureCue() }
                Chip(stringResource(R.string.use_room_only), false, C.mu, Modifier.height(48.dp)) { c.finishMasterOnly() }
            }
            5 -> Text(c.autoMsg, style = T.tab.copy(fontSize = F.f12, color = C.ok))
        }
    }
}


private val numPaint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 30f; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER }

/** Beat markers of the 1-per-second loop, positioned relative to [pos] (frames) around the centre line. */
private fun DrawScope.beats(pos: Double, shiftFrames: Double, y: Float, laneH: Float, col: Color) {
    val w = size.width; val mid = w / 2
    if (w <= 0f) return
    val framesPerPx = SR * WINDOW_MS / 1000f / w
    if (framesPerPx <= 0f) return
    val first = floor((pos - mid * framesPerPx - shiftFrames) / CALIB_BEAT).toLong()
    var k = first
    while (true) {
        val x = mid + ((k * CALIB_BEAT + shiftFrames - pos) / framesPerPx).toFloat()
        if (x > w) break
        if (x > -20) {
            val n = (Math.floorMod(k, 4L) + 1).toInt()
            val one = n == 1
            // the ding (beat 1) is tall and bright, the tocks are short
            val hh = if (one) laneH * 0.42f else laneH * 0.22f
            drawRoundRect(if (one) C.fx else col, Offset(x - 4f, y + laneH / 2 - hh), Size(8f, hh * 2), CornerRadius(4f))
            drawIntoCanvas { cv -> numPaint.color = (if (one) C.fx else col).toArgb(); cv.nativeCanvas.drawText("$n", x + 18f, y + laneH - 8f, numPaint) }
        }
        k++
    }
}

private fun Modifier.dragMs(startOf: () -> Int, onMs: (Int) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = startOf()
        var tot = 0f
        val pxPerMs = size.width / WINDOW_MS
        drag(down.id) { ch -> tot += ch.positionChange().x; onMs((start + tot / pxPerMs).roundToInt()); ch.consume() }
    }
}

@Composable
private fun CueLanes(c: Controller, modifier: Modifier) {
    val masterName = if (c.outMode == OutMode.SPLIT) "Master (right)" else c.routedTo.ifEmpty { "Master" }
    val cueName = if (c.outMode == OutMode.SPLIT) "Cue (left)" else c.routedCue.ifEmpty { "Headphones" }
    Box(modifier.clip(RoundedCornerShape(D.r14)).background(C.deep).border(1.dp, C.line, RoundedCornerShape(D.r14))) {
        Canvas(Modifier.fillMaxSize().dragMs({ c.offsetMs }) { c.setOffset(it.coerceIn(-RANGE_MS, RANGE_MS)) }) {
            c.tick.longValue
            val w = size.width; val h = size.height
            val laneH = h * 0.38f; val y1 = h * 0.07f; val y2 = h * 0.55f
            drawRoundRect(C.a.copy(alpha = 0.07f), Offset(8f, y1), Size(w - 16f, laneH), CornerRadius(10f))
            drawRoundRect(C.b.copy(alpha = 0.07f), Offset(8f, y2), Size(w - 16f, laneH), CornerRadius(10f))
            val pos = c.engine.calibPos.toDouble()
            beats(pos, 0.0, y1, laneH, C.a)
            beats(pos, c.offsetMs * SR / 1000.0, y2, laneH, C.b)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(w / 2, 0f), Offset(w / 2, h), 2f)
        }
        Text(masterName, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 4.dp), style = T.cap.copy(color = C.a, letterSpacing = 0.4.sp))
        Text(cueName, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 2.dp), style = T.cap.copy(color = C.b, letterSpacing = 0.4.sp))
        Text(stringResource(R.string.drag_the_lower_lane), Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 2.dp), style = T.cap.copy(color = C.dim))
    }
}

@Composable
private fun PictureLane(c: Controller, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(D.r14)).background(C.deep).border(1.dp, C.line, RoundedCornerShape(D.r14))) {
        Canvas(Modifier.fillMaxSize().dragMs({ c.visualTrimMs }) { c.setVisualTrim(it) }) {
            c.tick.longValue
            val w = size.width; val h = size.height
            val laneH = h * 0.6f; val y = h * 0.2f
            drawRoundRect(C.a.copy(alpha = 0.07f), Offset(8f, y), Size(w - 16f, laneH), CornerRadius(10f))
            // what you see = engine position minus the output delay (+ your fine-tune)
            val delayFrames = (c.engine.visualLatencyMs + c.visualTrimMs).coerceAtLeast(0f) / 1000.0 * SR
            val pos = c.engine.calibPos - delayFrames
            beats(pos, 0.0, y, laneH, C.a)
            // flash when a beat crosses the line on screen
            val since = ((pos % CALIB_BEAT) + CALIB_BEAT) % CALIB_BEAT / SR * 1000.0
            val flash = exp(-since / 80.0).toFloat()
            drawCircle(Color.White.copy(alpha = 0.15f + 0.6f * flash), 14f + 10f * flash, Offset(w / 2, y - 14f))
            drawLine(Color.White.copy(alpha = 0.85f), Offset(w / 2, 0f), Offset(w / 2, h), 2f)
        }
        Text(stringResource(R.string.drag), Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 4.dp), style = T.cap.copy(color = C.dim))
    }
}
