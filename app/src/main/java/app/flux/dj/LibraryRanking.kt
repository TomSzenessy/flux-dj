package app.flux.dj

import app.flux.dj.audio.Keys
import kotlin.math.abs
import kotlin.math.min

/** Tempo distance in %, treating half/double time as a match (e.g. 87 ↔ 174). */
internal fun tempoDiffPct(a: Float, b: Float): Float =
    minOf(abs(a / b - 1f), abs(a * 2f / b - 1f) * 1.1f, abs(a / 2f / b - 1f) * 1.1f) * 100f

/** Lower is better. null = not analysed yet. */
internal fun relevanceOf(it: LibItem, r: RefMeta): Float? {
    if (it.bpm <= 0f) return null
    val kd = Keys.distance(it.key, r.key)
    val kp = when (kd) { 0 -> 0f; 1 -> 2f; 2 -> 6f; 9 -> 30f; else -> 20f }
    val bd = tempoDiffPct(it.bpm, r.bpm)
    val bp = bd * 2.5f + (if (bd > 8f) 40f else 0f)
    val ed = if (it.energy > 0 && r.energy > 0) it.energy - r.energy else 0
    val ep = if (ed in 0..2) 0f else abs(ed).toFloat()
    val bonus = (if (it.pattern.isNotEmpty() && it.pattern == r.pattern) 2f else 0f) +
        (if (it.vibe.isNotEmpty() && it.vibe == r.vibe) 1f else 0f)
    return kp + bp + ep - bonus
}

internal fun reasonsFor(it: LibItem, r: RefMeta): String {
    val parts = ArrayList<String>()
    when (Keys.distance(it.key, r.key)) { 0 -> parts.add("same key"); 1 -> parts.add("harmonic"); 2 -> parts.add("energy lift") }
    parts.add(String.format("%+.1f%% tempo", (it.bpm / r.bpm - 1f) * 100f))
    if (it.energy > 0 && r.energy > 0 && it.energy - r.energy in 1..2) parts.add("builds energy")
    if (it.vibe.isNotEmpty() && it.vibe == r.vibe) parts.add("same vibe")
    return parts.joinToString(" · ")
}

/** Where a track comes from, for the source filter. */
internal fun LibItem.sourceGroup(): String = when (sourceKind) {
    "plex" -> "plex"
    "drive" -> "drive"
    else -> "local"
}
