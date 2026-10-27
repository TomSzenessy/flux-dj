package app.flux.dj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.Controller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val MODES = listOf("CUES", "LOOP", "ROLL", "JUMP", "SMPL", "KEY")
private val LOOP_B = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32f)
private val ROLL_B = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f)
private val JUMP_B = floatArrayOf(-8f, -4f, -2f, -1f, 1f, 2f, 4f, 8f)

fun beatLabel(b: Float): String = when {
    b == 0.125f -> "⅛"; b == 0.25f -> "¼"; b == 0.5f -> "½"
    b == b.toInt().toFloat() -> b.toInt().toString()
    else -> b.toString()
}

@Composable
fun DeckPanel(c: Controller, i: Int, onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val col = C.deck(i)
    val v = c.view
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.clip(shape).background(Brush.verticalGradient(listOf(C.s1, Color(0xFF0A0B10)))).border(1.dp, C.line, shape).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val jog = @Composable { m: Modifier -> if (v.jog) JogColumn(c, i, onLoad, m) }
        val pads = @Composable { m: Modifier -> if (v.pads || v.loops) PadColumn(c, i, m) }
        val pitch = @Composable { PitchColumn(c, i, Modifier.width(36.dp).fillMaxHeight()) }
        if (i == 0) {
            jog(Modifier.weight(1.35f).fillMaxHeight()); pads(Modifier.weight(1.15f).fillMaxHeight()); pitch()
        } else {
            pitch(); pads(Modifier.weight(1.15f).fillMaxHeight()); jog(Modifier.weight(1.35f).fillMaxHeight())
        }
        if (!v.jog && !v.pads && !v.loops) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun JogColumn(c: Controller, i: Int, onLoad: () -> Unit, modifier: Modifier) {
    val u = c.ui[i]; val d = c.engine.decks[i]
    val col = C.deck(i)
    BoxWithConstraints(modifier) {
        val tH = 46.dp; val chipsH = 26.dp
        val showTitle = !c.view.waves
        val jogS = minOf(maxWidth, maxHeight - tH - chipsH - 12.dp - (if (showTitle) 40.dp else 0.dp)).coerceAtLeast(70.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            if (showTitle) Column(Modifier.fillMaxWidth().pointerInput2 { onLoad() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(u.title, style = T.tab.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), maxLines = 1)
                Text(listOf(u.keyText, if (c.showRemain) u.remain else u.time).filter { it.isNotEmpty() }.joinToString("  ·  "), style = T.cap.copy(letterSpacing = 0.2.sp, color = col), maxLines = 1)
            }
            Jog(c, i, Modifier.size(jogS))
            Row(Modifier.fillMaxWidth().height(tH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // skip to start
                PressBox(Modifier.width(36.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)), onDown = { d.toStart() }) { p ->
                    Box(Modifier.fillMaxSize().background(if (p) C.s4 else C.s3).border(1.dp, C.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { SkipStartIcon(col) }
                }
                // CUE (momentary)
                PressBox(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)), onDown = { c.cuePad(i) }, onUp = { d.cueUp() }) { p ->
                    Box(Modifier.fillMaxSize().background(if (p) col.copy(alpha = .3f) else C.s3).border(1.dp, if (p) col else C.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("CUE", style = T.tab.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = if (p) col else Color(0xFFFF9F5A)))
                    }
                }
                PressBox(Modifier.weight(1.25f).fillMaxHeight().clip(RoundedCornerShape(12.dp)), onDown = { c.togglePlayPad(i) }) { p ->
                    Box(Modifier.fillMaxSize().background(if (u.playing) col else if (p) C.s4 else C.s3).border(1.dp, if (u.playing) col else C.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        PlayIcon(u.playing, if (u.playing) Color.Black else col)
                    }
                }
                PillButton("SYNC", u.synced, C.fx, Modifier.weight(1f).fillMaxHeight(), solid = true) { c.toggleSync(i) }
            }
            Row(Modifier.fillMaxWidth().height(chipsH), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip("PITCH LOCK", u.keyLock, col, Modifier.weight(1.7f).fillMaxHeight()) { c.toggleKeyLock(i) }
                Chip("SLIP", u.slip, col, Modifier.weight(0.85f).fillMaxHeight()) { c.toggleSlip(i) }
                Chip("SNAP", c.quantize, col, Modifier.weight(1f).fillMaxHeight()) { c.toggleQuantize() }
            }
        }
    }
}

@Composable
private fun SkipStartIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        drawRect(color, Offset(size.width * 0.06f, size.height * 0.12f), Size(size.width * 0.14f, size.height * 0.76f))
        val p = Path().apply { moveTo(size.width * 0.92f, size.height * 0.1f); lineTo(size.width * 0.3f, size.height * 0.5f); lineTo(size.width * 0.92f, size.height * 0.9f); close() }
        drawPath(p, color)
    }
}

@Composable
private fun PlayIcon(playing: Boolean, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        if (playing) {
            val w = size.width * 0.28f
            drawRoundRect(color, Offset(size.width * 0.12f, size.height * 0.1f), Size(w, size.height * 0.8f), androidx.compose.ui.geometry.CornerRadius(2f))
            drawRoundRect(color, Offset(size.width * 0.6f, size.height * 0.1f), Size(w, size.height * 0.8f), androidx.compose.ui.geometry.CornerRadius(2f))
        } else {
            val p = Path().apply {
                moveTo(size.width * 0.18f, size.height * 0.08f); lineTo(size.width * 0.92f, size.height * 0.5f); lineTo(size.width * 0.18f, size.height * 0.92f); close()
            }
            drawPath(p, color)
        }
    }
}

@Composable
private fun PadColumn(c: Controller, i: Int, modifier: Modifier) {
    val u = c.ui[i]; val d = c.engine.decks[i]
    val col = C.deck(i)
    val v = c.view
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (v.pads) {
            // mode selector
            Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(10.dp)).background(C.s2).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                MODES.forEachIndexed { k, name ->
                    val on = u.padMode == k
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(if (on) C.s4 else Color.Transparent)
                        .pointerInputTap { u.padMode = k }, contentAlignment = Alignment.Center) {
                        Text(name, style = T.cap.copy(fontSize = 8.sp, letterSpacing = 0.sp, color = if (on) col else C.mu, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
                    }
                }
            }
            // pad grid
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in 0..3) Pad(c, i, row * 4 + k, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        if (v.loops) {
            Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("÷2", false, C.fx, Modifier.weight(1f).fillMaxHeight()) { d.loopScale(0.5f) }
                PillButton("LOOP ${beatLabel(u.loopBeats)}", u.loopOn, C.fx, Modifier.weight(2.2f).fillMaxHeight(), solid = true) { c.loopTogglePad(i) }
                Chip("×2", false, C.fx, Modifier.weight(1f).fillMaxHeight()) { d.loopScale(2f) }
            }
        }
    }
}

private fun Modifier.pointerInputTap(onTap: () -> Unit): Modifier = this.then(
    Modifier.pointerInput2(onTap)
)

@Composable
private fun Pad(c: Controller, i: Int, k: Int, modifier: Modifier) {
    val u = c.ui[i]; val d = c.engine.decks[i]
    val deckCol = C.deck(i)
    val mode = u.padMode
    var label = ""
    var sub = ""
    var color = deckCol
    var lit = false
    var empty = false
    when (mode) {
        0 -> { color = C.cue[k]; label = "${k + 1}"; empty = !u.cues[k]; sub = if (empty) "" else "CUE" }
        1 -> { color = C.fx; label = beatLabel(LOOP_B[k]); sub = "BEAT"; lit = u.loopOn && abs(u.loopBeats - LOOP_B[k]) < 0.001f }
        2 -> { color = C.cue[2]; label = beatLabel(ROLL_B[k]); sub = "ROLL" }
        3 -> { color = C.cue[1]; val b = JUMP_B[k]; label = (if (b > 0) "+" else "−") + beatLabel(abs(b)); sub = "BAR" }
        4 -> { color = C.cue[4]; label = c.sampleNames[k]; sub = "HOLD" }
        5 -> { color = C.cue[6]; val s = k - 4; label = if (s > 0) "+$s" else if (s < 0) "−${-s}" else "0"; sub = "KEY"; lit = u.keyShift == s }
    }
    PressBox(
        modifier.clip(RoundedCornerShape(12.dp)),
        onDown = {
            when (mode) {
                0 -> c.hotCuePad(i, k)
                1 -> c.loopPad(i, LOOP_B[k])
                2 -> c.rollPad(i, ROLL_B[k])
                3 -> c.beatJumpPad(i, JUMP_B[k] * 4f)
                4 -> c.engine.triggerSample(k)
                5 -> d.keyShift = k - 4
            }
        },
        onUp = { when (mode) { 0 -> d.hotCueUp(k); 2 -> d.rollEnd() } },
        onLong = when (mode) { 0 -> ({ d.hotCueClear(k) }); 4 -> ({ c.sampleEdit = k }); else -> null },
    ) { p ->
        val base = if (empty) Color(0xFF0F121B) else color.copy(alpha = if (lit) 0.95f else 0.17f)
        Box(
            Modifier.fillMaxSize()
                .background(if (p) color.copy(alpha = 0.85f) else base)
                .border(1.dp, if (empty) Color(0x22FFFFFF) else color.copy(alpha = if (p || lit) 1f else 0.5f), RoundedCornerShape(12.dp))
        ) {
            val tc = if (p || lit) Color.Black else if (empty) C.dim else color
            Text(label, Modifier.align(Alignment.Center), style = T.tab.copy(color = tc, fontWeight = FontWeight.Bold, fontSize = if (label.length > 2) 10.sp else 17.sp, letterSpacing = 0.sp), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            if (sub.isNotEmpty()) Text(sub, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp), style = T.cap.copy(fontSize = 6.5.sp, letterSpacing = 0.3.sp, color = tc.copy(alpha = 0.7f)), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun PitchColumn(c: Controller, i: Int, modifier: Modifier) {
    val u = c.ui[i]
    val col = C.deck(i)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("TEMPO", style = T.cap.copy(fontSize = 7.5.sp, letterSpacing = 0.5.sp))
        NudgeBtn("+", col) { down -> c.engine.decks[i].bend = if (down) 0.04f else 0f }
        VFader((u.tempo + 1f) / 2f, { c.pitch(i, it * 2f - 1f) }, col, Modifier.weight(1f).fillMaxWidth(), default = 0.5f, ticks = true, thumbH = 30.dp)
        NudgeBtn("−", col) { down -> c.engine.decks[i].bend = if (down) -0.04f else 0f }
        Chip("±${(u.range * 100).toInt()}", false, col, Modifier.fillMaxWidth().height(24.dp)) { c.cycleRange(i) }
    }
}

/** Hold-to-nudge pitch bend (like pushing the platter edge). */
@Composable
private fun NudgeBtn(label: String, color: Color, onChange: (Boolean) -> Unit) {
    PressBox(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(9.dp)), onDown = { onChange(true) }, onUp = { onChange(false) }) { p ->
        Box(Modifier.fillMaxSize().background(if (p) color.copy(alpha = .35f) else C.s3).border(1.dp, if (p) color else C.line, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Text(label, style = T.tab.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (p) color else C.mu))
        }
    }
}
