package app.flux.dj

import app.flux.dj.audio.SR
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Dry run of the import against a MediaStore dump from the real tablet (skipped when the dump isn't there). */
class TabletMatchTest {
    @Test fun dryRun() {
        val xml = File(System.getProperty("user.home"), "collection.xml")
        val ms = File(System.getenv("FLUX_MS_DUMP") ?: "/nonexistent")
        assumeTrue(xml.exists() && ms.exists())
        val lib = xml.inputStream().use { Rekordbox.parse(it) }
        val re = Regex("_id=(\\d+), title=(.*), artist=(.*), duration=(\\d+), _display_name=(.*), is_music=(\\d)")
        val cands = ms.readLines().mapNotNull { re.find(it) }.filter { it.groupValues[6] == "1" && it.groupValues[4].toLong() > 30000 }.map {
            val g = it.groupValues
            Rekordbox.Candidate("ms:${g[1]}", g[2], g[3].takeIf { a -> a != "<unknown>" } ?: "", (g[4].toLong() / 1000).toInt(), g[5].substringBeforeLast('.'))
        }
        val m = Rekordbox.match(lib, cands)
        println("tablet tracks: ${cands.size}, rekordbox: ${lib.tracks.size}, matched: ${m.size}")
        lib.tracks.values.filter { it.id !in m }.forEach { println("  missing: ${it.artist} – ${it.name} (${it.fileStem})") }
        // compare beat phase with FLUX's own earlier analysis (mp3 offset check)
        val prefs = File(System.getenv("FLUX_ANALYSIS") ?: "/nonexistent")
        if (prefs.exists()) {
            val metaRe = Regex("<string name=\"(ms:\\d+)\">([^<]*)</string>")
            val meta = metaRe.findAll(prefs.readText()).associate { it.groupValues[1] to it.groupValues[2].split("|") }
            val diffs = ArrayList<Double>()
            for ((rbId, libId) in m) {
                val t = lib.tracks[rbId]!!; val p = meta[libId] ?: continue
                if (t.tempoSecs.size != 1 || p.size < 3) continue
                val ourBpm = p[0].toDouble(); val ourFb = p[2].toDouble(); if (ourFb < 0 || kotlin.math.abs(ourBpm - t.tempoBpms[0]) > 0.3) continue
                val spbMs = 60000.0 / t.tempoBpms[0]
                var d = (ourFb / SR * 1000 - t.tempoSecs[0] * 1000) % spbMs
                if (d > spbMs / 2) d -= spbMs; if (d < -spbMs / 2) d += spbMs
                diffs.add(d)
            }
            diffs.sort()
            if (diffs.isNotEmpty()) println("FLUX vs rekordbox beat offset over ${diffs.size} tracks: median ${"%.1f".format(diffs[diffs.size / 2])} ms, " +
                "middle half ${"%.1f".format(diffs[diffs.size / 4])}…${"%.1f".format(diffs[diffs.size * 3 / 4])} ms")
        }
    }
}
