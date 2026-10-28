package app.flux.dj

import app.flux.dj.audio.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ------------------------------------------------------------------ beat-grid editing
internal class GridSnap(val bpm: Float, val firstBeat: Double, val map: String?)

internal fun Controller.gridTrack(i: Int): Track? = engine.decks[i].track

internal fun Controller.gridOpen(i: Int) {
    val t = gridTrack(i) ?: return
    gridOrig[i] = GridSnap(t.bpm, t.firstBeat, t.tempoMapString())
    gridAnchor[i] = -1.0
    ui[i].gridMode = 0
    taps[i].clear()
    ui[i].gridEdit = true
    ui[1 - i].gridEdit = false
    refreshGridText(i)
}

internal fun Controller.gridDone(i: Int) {
    ui[i].gridEdit = false
    val t = gridTrack(i) ?: return
    val o = gridOrig[i]
    if (o == null || o.bpm != t.bpm || o.firstBeat != t.firstBeat || o.map != t.tempoMapString()) {
        t.analyzed = true
        saveMeta(t.id, t, true, user = true)
        allItems().firstOrNull { it.id == t.id }?.let { it.bpm = t.bpm; it.analysisSource = "manual" }
        ui[i].analysisSource = "manual"
        libRev++
        toast = "Grid saved for “${t.title}”"
    }
}

private fun Controller.refreshGridText(i: Int) {
    ui[i].gridBpm = gridTrack(i)?.let { if (it.bpm <= 0) "—" else if (it.variable) "~" + String.format("%.2f", it.bpmAt(engine.decks[i].pos)) else String.format("%.2f", it.bpm) } ?: ""
}

/** Keep firstBeat within one bar so bar numbering stays meaningful. */
private fun Controller.normalize(t: Track) {
    if (t.bpm <= 0) return
    if (t.variable) return
    val bar = t.spb * 4
    var fb = t.firstBeat % bar
    if (fb < 0) fb += bar
    t.firstBeat = fb
}

internal fun Controller.gridShiftFrames(i: Int, frames: Double) {
    val t = gridTrack(i) ?: return
    if (t.bpm <= 0) return
    t.shiftGrid(frames); normalize(t)
}

/** Make the beat nearest the playhead beat 1 of a bar. */
internal fun Controller.gridSetDownbeat(i: Int) {
    val t = gridTrack(i) ?: return
    if (t.bpm <= 0) return
    val p = engine.decks[i].pos
    t.setDownbeatAt(p); normalize(t)
    gridAnchor[i] = p   // pin for STRETCH
}

/** Tempo edits need one tempo: a tempo map is flattened to the tempo at the playhead first. */
internal fun Controller.flattenForEdit(i: Int, t: Track) {
    if (!t.variable) return
    t.flattenAt(engine.decks[i].pos)
    toast = "This track had changing tempo — the grid is now one tempo (${String.format("%.2f", t.bpm)} BPM). RESET brings the original back."
}

/** Stretch: the pinned beat stays put, the beat under the playhead follows the finger (changes BPM). */
internal fun Controller.gridStretchFrames(i: Int, dx: Double) {
    val t = gridTrack(i) ?: return
    if (t.bpm <= 0) return
    flattenForEdit(i, t)
    val a = gridAnchorFrames(i)
    val k = Math.round((engine.decks[i].pos - a) / t.spb)
    if (abs(k) < 2) { gridShiftFrames(i, dx); return }   // too close to the pin to stretch meaningfully
    val b = a + k * t.spb
    val nspb = (b + dx - a) / k
    if (nspb <= 0) return
    t.bpm = (60.0 * SR / nspb).toFloat().coerceIn(40f, 250f)
    t.firstBeat = a; normalize(t)
    refreshGridText(i)
}

/** Change tempo while the beat under the playhead stays where it is. */
internal fun Controller.setBpmAnchored(i: Int, bpm: Float) {
    val t = gridTrack(i) ?: return
    flattenForEdit(i, t)
    val nb = bpm.coerceIn(40f, 250f)
    if (t.bpm <= 0) { t.bpm = nb; t.firstBeat = engine.decks[i].pos; normalize(t); refreshGridText(i); return }
    val pos = engine.decks[i].pos
    val k = Math.round((pos - t.firstBeat) / t.spb)
    val anchor = t.firstBeat + k * t.spb
    t.bpm = nb
    t.firstBeat = anchor - k * t.spb
    normalize(t)
    refreshGridText(i)
}

internal fun Controller.gridTap(i: Int) {
    val now = System.currentTimeMillis()
    val l = taps[i]
    if (l.isNotEmpty() && now - l.last() > 2000) l.clear()
    l.add(now); while (l.size > 9) l.removeAt(0)
    if (l.size >= 4) {
        val avg = (l.last() - l.first()).toDouble() / (l.size - 1)
        setBpmAnchored(i, ((60000.0 / avg) * 10).roundToInt() / 10f)
    }
}

internal fun Controller.gridReset(i: Int) {
    val t = gridTrack(i) ?: return
    val o = gridOrig[i] ?: return
    if (o.map != null) t.applyTempoMapString(o.map) else t.clearTempoMap()
    t.bpm = o.bpm; t.firstBeat = o.firstBeat
    refreshGridText(i)
}
