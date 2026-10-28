package app.flux.dj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.flux.dj.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.dj.*
import app.flux.dj.Controller
import app.flux.dj.audio.Curve

fun Modifier.pointerInput2(onTap: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); val up = waitForUpOrCancellation(); if (up != null) { up.consume(); onTap() } }
}

@Composable
fun MixerPanel(c: Controller, modifier: Modifier = Modifier) {
    val v = c.view
    val shape = RoundedCornerShape(D.r16)
    Column(
        modifier.clip(shape).background(Brush.verticalGradient(listOf(C.s1, C.deep))).border(1.dp, C.line, shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PhaseMeter(c, Modifier.fillMaxWidth().height(22.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Strip(c, 0, Modifier.weight(1f).fillMaxHeight())
            Strip(c, 1, Modifier.weight(1f).fillMaxHeight())
        }
        HFader(c.xfader, { c.setXf(it) }, onStart = { c.cancelTransition() }, modifier = Modifier.fillMaxWidth().height(48.dp), label = "Crossfader")
        if (v.eq) Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Knob(c.outVolume, { c.doOutVolume(it) }, C.ok, "VOL", Modifier.size(48.dp), default = 1f, bipolar = false) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Knob(c.master, { c.doMaster(it) }, C.tx, "MST", Modifier.size(48.dp), default = 0.8f, bipolar = false) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Knob(c.hpMix, { c.doHpMix(it) }, C.fx, "CUE·M", Modifier.size(48.dp), default = 0f, bipolar = false) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Knob(c.hpLevel, { c.doHpLevel(it) }, C.fx, "HP", Modifier.size(48.dp), default = 0.8f, bipolar = false) }
            Chip(when (c.curve) { Curve.SMOOTH -> "SMOOTH"; Curve.SHARP -> "SHARP"; Curve.CUT -> "CUT" }, false, C.fx, Modifier.weight(1.6f).height(28.dp)) {
                c.doCurve(Curve.entries[(c.curve.ordinal + 1) % 3])
            }
        }
    }
}

@Composable
private fun Strip(c: Controller, i: Int, modifier: Modifier) {
    val col = C.deck(i)
    val s = c.ch[i]
    val v = c.view
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (v.eq) BoxWithConstraints(Modifier.width(46.dp).fillMaxHeight()) {
                val ks = minOf(48.dp, maxHeight / 5.1f)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(s.trim, { c.setTrim(i, it) }, C.tx, "TRIM", Modifier.size(ks))
                    Knob(s.hi, { c.setHi(i, it) }, col, "HI", Modifier.size(ks))
                    Knob(s.mid, { c.setMid(i, it) }, col, "MID", Modifier.size(ks))
                    Knob(s.lo, { c.setLo(i, it) }, col, "LO", Modifier.size(ks))
                    Knob(s.color, { c.setColor(i, it) }, C.fx, "FX", Modifier.size(ks))
                }
            }
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val meter = @Composable { if (v.meters) VuMeter({ c.engine.chans[i].meter }, c.tick, Modifier.width(8.dp).fillMaxHeight().padding(vertical = 2.dp), 16) }
                if (i == 0) meter()
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { VFader(s.fader, { c.setFader(i, it) }, col, Modifier.width(48.dp).fillMaxHeight(), ticks = true, thumbH = 40.dp, label = "Channel ${c.deckName(i)} fader") }
                if (i == 1) meter()
            }
        }
        PillButton(stringResource(R.string.cue), s.cue, C.fx, Modifier.fillMaxWidth().height(28.dp), solid = true) { c.toggleCue(i) }
    }
}

/** Beat-match helper: where deck B's beat sits relative to deck A's (centre = in phase). */
@Composable
private fun PhaseMeter(c: Controller, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.phase), style = T.cap.copy(fontSize = F.f8, letterSpacing = 0.6.sp))
        androidx.compose.foundation.Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)) {
            c.tick.longValue
            val diff = c.phaseDiff
            val w = size.width; val h = size.height
            drawRoundRect(C.s2, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
            for (k in 1..3) drawLine(C.gridLine, androidx.compose.ui.geometry.Offset(w * k / 4, 2f), androidx.compose.ui.geometry.Offset(w * k / 4, h - 2f), 1f)
            drawLine(C.gridStrong, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, h), 1.5f)
            if (diff != null) {
                val x = w / 2 + diff * w
                val good = kotlin.math.abs(diff) < 0.03f
                val colr = if (good) C.ok else C.fx
                drawRoundRect(colr.copy(alpha = .25f), androidx.compose.ui.geometry.Offset(minOf(x, w / 2), 2f), androidx.compose.ui.geometry.Size(kotlin.math.abs(x - w / 2), h - 4f), androidx.compose.ui.geometry.CornerRadius(3f))
                drawRoundRect(colr, androidx.compose.ui.geometry.Offset(x - 3f, 0f), androidx.compose.ui.geometry.Size(6f, h), androidx.compose.ui.geometry.CornerRadius(3f))
            }
        }
        val d = c.phaseDiff
        Text(if (d == null) "—" else if (kotlin.math.abs(d) < 0.03f) "LOCKED" else String.format("%+.0f%%", d * 100), style = T.cap.copy(fontSize = F.f8, letterSpacing = 0.3.sp, color = if (d != null && kotlin.math.abs(d) < 0.03f) C.ok else C.mu), modifier = Modifier.width(44.dp))
    }
}
