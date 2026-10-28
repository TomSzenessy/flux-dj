package app.flux.dj

import app.flux.dj.audio.Keys
import app.flux.dj.audio.SR
import app.flux.dj.audio.Track
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RekordboxTest {
    private fun fixture(): RbLibrary =
        javaClass.getResourceAsStream("/collection.xml")!!.use { Rekordbox.parse(it) }

    @Test fun keysParse() {
        assertEquals("9A", Keys.camelot(Rekordbox.parseKey("9A")))
        assertEquals("11B", Keys.camelot(Rekordbox.parseKey("11B")))
        assertEquals("Am", Keys.name(Rekordbox.parseKey("Am")))
        assertEquals("F#m", Keys.name(Rekordbox.parseKey("F#m")))
        assertEquals("Eb", Keys.name(Rekordbox.parseKey("Eb")))
        assertEquals("F#m", Keys.name(Rekordbox.parseKey("F♯m")))
        assertEquals(-1, Rekordbox.parseKey(""))
    }

    @Test fun parsesTheFixture() {
        val lib = fixture()
        assertEquals("rekordbox 7.0.0", lib.product)
        assertEquals("duplicate TrackID is reported and last one wins", 4, lib.tracks.size)
        assertEquals(1, lib.duplicateTracks)
        assertEquals(1, lib.duplicateCues)
        assertEquals(1, lib.playlists.size)
        assertEquals("Sets / Warmup", lib.playlists[0].path)
        assertEquals(listOf("1", "2", "3"), lib.playlists[0].trackIds)

        val first = lib.tracks.getValue("1")
        assertEquals("Test Artist - First Light", first.fileStem)
        assertEquals(2, first.hotCues.size)
        assertEquals(listOf(8.0), first.memoryCues)
        assertEquals(16, first.key)                      // 9A = E minor
        assertEquals("Replaced Duplicate", lib.tracks.getValue("4").name)
        assertEquals("ramp", lib.tracks.values.first { it.id == "2" }.tempoBpms.toSet().size, 3)
    }

    @Test fun tempoMapsAreConsistent() {
        val lib = fixture()
        var variable = 0
        var worst = 0.0
        for (rb in lib.tracks.values) {
            if (rb.tempoSecs.isEmpty()) continue
            val t = Track(rb.id, rb.name, rb.artist, ShortArray(2 * SR * maxOf(1, rb.totalSec)))
            t.setTempoMap(rb.tempoSecs, rb.tempoBpms, rb.firstBattito)
            if (rb.tempoBpms.toSet().size > 1) variable++
            for (i in rb.tempoSecs.indices) {
                val b = t.beatIndex(rb.tempoSecs[i] * SR)
                worst = maxOf(worst, abs(b - Math.round(b)))
            }
            for (s in listOf(1.0, rb.totalSec / 2.0, rb.totalSec - 2.0)) {
                val p = s * SR
                assertEquals(p, t.beatPos(t.beatIndex(p)), 1.0)
            }
            val t2 = Track("x", "", "", ShortArray(4)); t2.applyTempoMapString(t.tempoMapString()!!)
            assertEquals(t.beatIndex(rb.totalSec * SR / 3.0), t2.beatIndex(rb.totalSec * SR / 3.0), 1e-6)
        }
        assertEquals(1, variable)
        assertTrue("markers must fall on beats (worst $worst)", worst < 0.05)
    }

    @Test fun matchesByFileTitleAndLength() {
        val lib = fixture()
        val cands = lib.tracks.values.mapIndexed { n, t ->
            when (n % 3) {
                0 -> Rekordbox.Candidate("ms:$n", t.name, t.artist, t.totalSec + 1, t.fileStem)
                1 -> Rekordbox.Candidate("ms:$n", t.name.uppercase(), t.artist, t.totalSec, "x$n")
                else -> Rekordbox.Candidate("ms:$n", t.name, "", t.totalSec - 2, "${t.artist} - ${t.name}")
            }
        }
        val m = Rekordbox.match(lib, cands)
        assertEquals("every fixture track matches", lib.tracks.size, m.size)
        assertEquals("one file per rekordbox track", m.size, m.values.toSet().size)
    }
}
