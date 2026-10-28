package app.flux.dj.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

@Composable
fun rememberHaptic(): (Int) -> Unit {
    val v = LocalView.current
    return remember(v) { { c: Int -> v.performHapticFeedback(c) } }
}

/** Rotary knob: vertical drag, double-tap to reset, haptic detent at default. */
@Composable
fun Knob(
    value: Float, onChange: (Float) -> Unit, color: Color, label: String,
    modifier: Modifier = Modifier, default: Float = 0.5f, bipolar: Boolean = true,
) {
    val described = if (label.isBlank()) "Knob" else label
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    val haptic = rememberHaptic()
    var lastTap by remember { mutableLongStateOf(0L) }
    Box(
        modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = described
                stateDescription = "${(value * 100).toInt()}%"
                role = Role.Button
            }
            .pointerInput(default) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = cur
                    var total = 0f
                    var moved = false
                    val sens = 190.dp.toPx()
                    var lastV = start
                    drag(down.id) { ch ->
                        total += -(ch.positionChange().y)
                        if (abs(total) > 6f) moved = true
                        if (moved) {
                            val nv = (start + total / sens).coerceIn(0f, 1f)
                            val snapped = if (abs(nv - default) < 0.018f) default else nv
                            if ((lastV - default) * (snapped - default) < 0f || (snapped == default && lastV != default)) haptic(HapticFeedbackConstants.CLOCK_TICK)
                            lastV = snapped
                            cb(snapped)
                        }
                        ch.consume()
                    }
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 320) { cb(default); haptic(HapticFeedbackConstants.CONTEXT_CLICK); lastTap = 0 } else lastTap = now
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val c = center
            val rOut = s / 2f - 1.5.dp.toPx()
            val sw = max(2.5f, s * 0.07f)
            val arcR = rOut - sw / 2
            val topLeft = Offset(c.x - arcR, c.y - arcR)
            val sz = Size(arcR * 2, arcR * 2)
            // body
            drawCircle(Brush.radialGradient(listOf(C.knobHi, C.knobLo), center = Offset(c.x, c.y - s * .15f), radius = s * .7f), radius = rOut - sw - 1f, center = c)
            drawCircle(C.line, radius = rOut - sw - 1f, center = c, style = Stroke(1f))
            // track
            drawArc(Color(0x22FFFFFF), 135f, 270f, false, topLeft, sz, style = Stroke(sw, cap = StrokeCap.Round))
            val a0 = if (bipolar) 270f else 135f
            val a1 = 135f + 270f * value
            val sweep = a1 - a0
            val on = abs(value - default) > 0.004f
            if (on) drawArc(color, if (sweep >= 0) a0 else a1, abs(sweep), false, topLeft, sz, style = Stroke(sw, cap = StrokeCap.Round))
            // pointer
            val ang = Math.toRadians(a1.toDouble())
            val r1 = rOut - sw - 1f
            drawLine(if (on) color else C.tx, Offset(c.x + cos(ang).toFloat() * r1 * .35f, c.y + sin(ang).toFloat() * r1 * .35f),
                Offset(c.x + cos(ang).toFloat() * r1 * .88f, c.y + sin(ang).toFloat() * r1 * .88f), strokeWidth = max(2f, s * 0.055f), cap = StrokeCap.Round)
        }
        if (label.isNotEmpty()) {
            Text(label, Modifier.align(Alignment.Center).offset(y = 0.dp), style = T.cap.copy(fontSize = 7.sp, letterSpacing = 0.sp, color = C.labelDim))
        }
    }
}

/** Vertical fader, relative drag. value 0..1, top = 1. */
@Composable
fun VFader(
    value: Float, onChange: (Float) -> Unit, color: Color, modifier: Modifier = Modifier,
    default: Float? = null, ticks: Boolean = false, thumbH: Dp = 34.dp, label: String = "Fader",
) {
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    var lastTap by remember { mutableLongStateOf(0L) }
    val haptic = rememberHaptic()
    Canvas(
        modifier
            .semantics { contentDescription = label; stateDescription = "${(value * 100).toInt()}%" }
            .pointerInput(default) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val th = thumbH.toPx()
                    val travel = (size.height - th).coerceAtLeast(1f)
                    val start = cur
                    var total = 0f
                    var moved = false
                    // if touch lands off the thumb, jump there first
                    val thumbTop = (1f - start) * travel
                    val hit = down.position.y in thumbTop..(thumbTop + th)
                    var base = start
                    if (!hit) { base = (1f - ((down.position.y - th / 2) / travel)).coerceIn(0f, 1f); cb(base) }
                    var lastV = base
                    drag(down.id) { ch ->
                        total += -ch.positionChange().y
                        if (abs(total) > 4f) moved = true
                        var nv = (base + total / travel).coerceIn(0f, 1f)
                        if (default != null && abs(nv - default) < 0.02f) nv = default
                        if (default != null && nv == default && lastV != default) haptic(HapticFeedbackConstants.CLOCK_TICK)
                        lastV = nv
                        cb(nv)
                        ch.consume()
                    }
                    if (!moved && hit && default != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 320) { cb(default); lastTap = 0 } else lastTap = now
                    }
                }
            }
    ) {
        val w = size.width
        val th = thumbH.toPx()
        val travel = size.height - th
        val rr = CornerRadius(w / 2, w / 2)
        // slot
        val slotW = max(4.dp.toPx(), w * 0.14f)
        drawRoundRect(C.s2, Offset(0f, 0f), size, rr)
        drawRoundRect(C.line, Offset(0f, 0f), size, rr, style = Stroke(1f))
        drawRoundRect(C.well, Offset((w - slotW) / 2, th / 2), Size(slotW, travel), CornerRadius(slotW / 2, slotW / 2))
        val y = (1f - value) * travel
        // active fill
        drawRoundRect(color.copy(alpha = 0.55f), Offset((w - slotW) / 2, y + th / 2), Size(slotW, travel - y), CornerRadius(slotW / 2, slotW / 2))
        if (ticks) for (i in 0..10) {
            val ty = th / 2 + travel * i / 10f
            drawLine(Color(0x33FFFFFF), Offset(1f, ty), Offset(w * 0.14f, ty), 1f)
            drawLine(Color(0x33FFFFFF), Offset(w - w * 0.14f, ty), Offset(w - 1f, ty), 1f)
        }
        // thumb
        val pad = 2.dp.toPx()
        drawRoundRect(Brush.verticalGradient(listOf(C.faderHi, C.faderLo), y, y + th), Offset(pad, y), Size(w - pad * 2, th), CornerRadius(9.dp.toPx()))
        drawRoundRect(Color(0x33FFFFFF), Offset(pad, y), Size(w - pad * 2, th), CornerRadius(9.dp.toPx()), style = Stroke(1f))
        drawLine(color, Offset(pad + 6.dp.toPx(), y + th / 2), Offset(w - pad - 6.dp.toPx(), y + th / 2), 2.5f, StrokeCap.Round)
    }
}

/** Horizontal crossfader. value 0 (A) .. 1 (B). */
@Composable
fun HFader(value: Float, onChange: (Float) -> Unit, onStart: () -> Unit = {}, modifier: Modifier = Modifier, label: String = "Crossfader") {
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    val st by rememberUpdatedState(onStart)
    var lastTap by remember { mutableLongStateOf(0L) }
    val haptic = rememberHaptic()
    Canvas(
        modifier.semantics { contentDescription = label; stateDescription = "${(value * 100).toInt()}%" }
            .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                st()
                val th = 60.dp.toPx()
                val travel = (size.width - th).coerceAtLeast(1f)
                val thumbL = cur * travel
                val hit = down.position.x in thumbL..(thumbL + th)
                var base = cur
                if (!hit) { base = ((down.position.x - th / 2) / travel).coerceIn(0f, 1f); cb(base) }
                var total = 0f; var moved = false
                var lastV = base
                drag(down.id) { ch ->
                    total += ch.positionChange().x
                    if (abs(total) > 4f) moved = true
                    var nv = (base + total / travel).coerceIn(0f, 1f)
                    if (abs(nv - 0.5f) < 0.025f) nv = 0.5f
                    if (nv == 0.5f && lastV != 0.5f) haptic(HapticFeedbackConstants.CLOCK_TICK)
                    lastV = nv
                    cb(nv); ch.consume()
                }
                if (!moved && hit) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 320) { cb(0.5f); lastTap = 0 } else lastTap = now
                }
            }
        }
    ) {
        val h = size.height
        val th = 60.dp.toPx()
        val travel = size.width - th
        val rr = CornerRadius(h / 2, h / 2)
        drawRoundRect(C.s2, Offset.Zero, size, rr)
        drawRoundRect(C.line, Offset.Zero, size, rr, style = Stroke(1f))
        val slotH = max(4.dp.toPx(), h * 0.12f)
        drawRoundRect(C.well, Offset(th / 2, (h - slotH) / 2), Size(travel, slotH), CornerRadius(slotH / 2))
        drawLine(Color(0x55FFFFFF), Offset(size.width / 2, h * 0.2f), Offset(size.width / 2, h * 0.8f), 1f)
        val x = value * travel
        drawRoundRect(Brush.horizontalGradient(listOf(C.faderHi, C.faderLo), x, x + th), Offset(x, 2.dp.toPx()), Size(th, h - 4.dp.toPx()), CornerRadius(10.dp.toPx()))
        drawRoundRect(Color(0x33FFFFFF), Offset(x, 2.dp.toPx()), Size(th, h - 4.dp.toPx()), CornerRadius(10.dp.toPx()), style = Stroke(1f))
        val mix = Color(
            red = C.a.red + (C.b.red - C.a.red) * value, green = C.a.green + (C.b.green - C.a.green) * value, blue = C.a.blue + (C.b.blue - C.a.blue) * value
        )
        drawLine(mix, Offset(x + th / 2, h * 0.28f), Offset(x + th / 2, h * 0.72f), 3f, StrokeCap.Round)
    }
}

/** Generic press surface with down/up/long-press callbacks and a pressed state. */
@Composable
fun PressBox(
    modifier: Modifier = Modifier,
    onDown: () -> Unit = {}, onUp: () -> Unit = {}, onLong: (() -> Unit)? = null,
    haptic: Boolean = true,
    /** Pads need down-trigger; buttons/confirmations must wait for a confirmed up. */
    pressOnDown: Boolean = true,
    label: String? = null,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val d by rememberUpdatedState(onDown); val u by rememberUpdatedState(onUp); val l by rememberUpdatedState(onLong)
    val hp = rememberHaptic()
    Box(
        modifier.semantics {
            role = Role.Button
            if (!label.isNullOrBlank()) contentDescription = label
        }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                if (haptic) hp(HapticFeedbackConstants.VIRTUAL_KEY)
                if (pressOnDown) d()
                val lp = l
                var longFired = false
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                if (lp != null) {
                    up = withTimeoutOrNull(550) { waitForUpOrCancellation() }
                    if (up == null) {
                        lp(); longFired = true
                        hp(HapticFeedbackConstants.LONG_PRESS)
                        up = waitForUpOrCancellation()
                    }
                } else {
                    up = waitForUpOrCancellation()
                }
                pressed = false
                if (pressOnDown) u()
                else if (up != null && !longFired) { d(); u() }
            }
        },
        content = { content(pressed) }
    )
}

@Composable
fun PillButton(
    text: String, on: Boolean, color: Color, modifier: Modifier = Modifier, solid: Boolean = false,
    onClick: () -> Unit,
) {
    PressBox(modifier.clip(RoundedCornerShape(12.dp)), onUp = onClick, pressOnDown = false, label = text) { pressed ->
        val bg = when {
            solid && on -> color
            on -> color.copy(alpha = 0.18f)
            else -> C.s3
        }
        Box(
            Modifier.fillMaxSize()
                .background(if (pressed) bg.copy(alpha = min(1f, bg.alpha + 0.25f)) else bg)
                .border(1.dp, if (on && !solid) color.copy(alpha = 0.8f) else C.line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text, style = T.tab.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.3.sp, color = if (solid && on) Color.Black else if (on) color else C.tx), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean, color: Color = C.fx, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cb by rememberUpdatedState(onClick)
    PressBox(
        modifier.clip(RoundedCornerShape(9.dp)).background(if (on) color else C.s2)
            .semantics { selected = on },
        onUp = { cb() }, pressOnDown = false, haptic = false, label = text,
    ) { _ ->
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = T.tab.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else C.mu), modifier = Modifier.padding(horizontal = 5.dp, vertical = 7.dp), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, color: Color = C.a, label: String = "Toggle") {
    val cur by rememberUpdatedState(on)
    val cb by rememberUpdatedState(onChange)
    Box(
        Modifier.size(48.dp, 48.dp).clip(RoundedCornerShape(16.dp)).background(if (on) color.copy(alpha = .9f) else C.s4)
            .semantics { role = Role.Switch; contentDescription = label; stateDescription = if (on) "On" else "Off" }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); val up = waitForUpOrCancellation(); if (up != null) cb(!cur) } }
    ) {
        Box(Modifier.padding(6.dp).size(36.dp).offset(x = if (on) 6.dp else 0.dp).clip(RoundedCornerShape(10.dp)).background(Color.White))
    }
}

/** Segmented LED meter, vertical. level is 0..1 linear amplitude. */
@Composable
fun VuMeter(level: () -> Float, tick: State<Long>, modifier: Modifier = Modifier, segs: Int = 14) {
    Canvas(modifier) {
        tick.value
        val lv = (level().coerceIn(0f, 1.2f))
        val db = if (lv < 0.001f) -60f else 20f * log10(lv)
        val frac = ((db + 42f) / 42f).coerceIn(0f, 1f)
        val on = (frac * segs).roundToInt()
        val gap = 2.dp.toPx()
        val sh = (size.height - gap * (segs - 1)) / segs
        for (i in 0 until segs) {
            val y = size.height - (i + 1) * sh - i * gap
            val col = when { i >= segs - 2 -> C.bad; i >= segs - 5 -> C.fx; else -> C.ok }
            drawRoundRect(if (i < on) col else C.meterOff, Offset(0f, y), Size(size.width, sh), CornerRadius(1.5f))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = T.cap)
}
