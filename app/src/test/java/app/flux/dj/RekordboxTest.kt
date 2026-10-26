package app.flux.dj

import app.flux.dj.audio.Keys
import app.flux.dj.audio.SR
import app.flux.dj.audio.Track
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class RekordboxTest {
    private val file = File(System.getProperty("user.home"), "collection.xml")

    @Test fun keysParse() {
        assertEquals("9A", Keys.camelot(Rekordbox.parseKey("9A")))
        assertEquals("11B", Keys.camelot(Rekordbox.parseKey("11B")))
        assertEquals("Am", Keys.name(Rekordbox.parseKey("Am")))
        assertEquals("F#m", Keys.name(Rekordbox.parseKey("F#m")))
        assertEquals("Eb", Keys.name(Rekordbox.parseKey("Eb")))
        assertEquals(-1, Rekordbox.parseKey(""))
    }

    @Test fun parsesUserCollection() {
        assumeTrue(file.exists())
        val lib = file.inputStream().use { Rekordbox.parse(it) }
        println("${lib.product}: ${lib.tracks.size} tracks, ${lib.playlists.size} playlists")
        lib.playlists.forEach { println("  ${it.path} (${it.trackIds.size})") }
        assertEquals(232, lib.tracks.size)
        assertTrue(lib.playlists.size >= 15)
        assertEquals(230, lib.tracks.values.count { it.key >= 0 })
        assertEquals(234, lib.tracks.values.sumOf { it.hotCues.size })
        // every playlist entry points to a collection track
        assertTrue(lib.playlists.all { p -> p.trackIds.all { it in lib.tracks } })
        val major = lib.tracks.values.first { it.name.startsWith("Major Tom") }
        assertEquals("Peter Schilling - Major Tom (Coming Home)", major.fileStem)
    }

    @Test fun tempoMapsAreConsistent() {
        assumeTrue(file.exists())
        val lib = file.inputStream().use { Rekordbox.parse(it) }
        var variable = 0; var worst = 0.0
        for (rb in lib.tracks.values) {
            if (rb.tempoSecs.isEmpty()) continue
            val t = Track(rb.id, rb.name, rb.artist, ShortArray(2 * SR * maxOf(1, rb.totalSec)))
            t.setTempoMap(rb.tempoSecs, rb.tempoBpms, rb.firstBattito)
            if (rb.tempoBpms.toSet().size > 1) variable++
            // every rekordbox marker must sit on a whole beat, and beat 1 where rekordbox says (Battito)
            for (i in rb.tempoSecs.indices) {
                val b = t.beatIndex(rb.tempoSecs[i] * SR)
                val err = abs(b - Math.round(b))
                worst = maxOf(worst, err)
            }
            // round trip position ↔ beat
            for (s in listOf(1.0, rb.totalSec / 2.0, rb.totalSec - 2.0)) {
                val p = s * SR
                assertEquals(p, t.beatPos(t.beatIndex(p)), 1.0)
            }
            // serialisation round trip
            val t2 = Track("x", "", "", ShortArray(4)); t2.applyTempoMapString(t.tempoMapString()!!)
            assertEquals(t.beatIndex(rb.totalSec * SR / 3.0), t2.beatIndex(rb.totalSec * SR / 3.0), 1e-6)
        }
        println("variable-tempo tracks: $variable, worst marker off-beat: ${"%.4f".format(worst)} beats")
        assertEquals(32, variable)
        assertTrue("markers must fall on beats", worst < 0.05)
    }

    @Test fun matchesByFileTitleAndLength() {
        assumeTrue(file.exists())
        val lib = file.inputStream().use { Rekordbox.parse(it) }
        // simulate the tablet: same files, MediaStore titles/durations, some with slightly different spelling
        val cands = lib.tracks.values.mapIndexed { n, t ->
            when (n % 3) {
                0 -> Rekordbox.Candidate("ms:$n", t.name, t.artist, t.totalSec + 1, t.fileStem)          // file name
                1 -> Rekordbox.Candidate("ms:$n", t.name.uppercase(), t.artist, t.totalSec, "x$n")      // title + artist
                else -> Rekordbox.Candidate("ms:$n", t.name, "", t.totalSec - 2, "${t.artist} - ${t.name}") // artist - title stem
            }
        }
        val m = Rekordbox.match(lib, cands)
        println("matched ${m.size} / ${lib.tracks.size}")
        assertTrue(m.size >= 228)
        assertEquals(m.size, m.values.toSet().size)   // never two rekordbox tracks on one file
    }
}
