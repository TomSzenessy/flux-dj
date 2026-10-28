package app.flux.dj

import org.junit.Assert.*
import org.junit.Test

class LibraryRankingTest {
    private fun item(bpm: Float, key: Int, energy: Int = 5, vibe: String = "House") =
        LibItem("id:$bpm:$key", "t", "a", 0, null, -1, bpm, key, energy, "4/4 kick", vibe, sourceKind = "plex")

    @Test fun halfAndDoubleTimeAreTempoMatches() {
        assertEquals(0f, tempoDiffPct(87f, 174f), 0.01f)
        assertEquals(0f, tempoDiffPct(174f, 87f), 0.01f)
        assertEquals(21.875f, tempoDiffPct(100f, 128f), 0.2f)
    }

    @Test fun relevancePrefersSameKeyAndTempo() {
        val ref = RefMeta(128f, 9, 5, "House", "4/4 kick", "ref")
        val perfect = relevanceOf(item(128f, 9), ref)
        val clash = relevanceOf(item(128f, 3), ref)
        val slow = relevanceOf(item(100f, 9), ref)
        assertNotNull(perfect)
        assertTrue("perfect match ranks best", perfect!! <= 0f)
        assertTrue("key clash ranks worse", clash!! > perfect)
        assertTrue("tempo gap ranks worse", slow!! > perfect)
        assertNull("unanalysed track has no rank", relevanceOf(item(0f, 9), ref))
    }

    @Test fun reasonsAreHumanReadable() {
        val ref = RefMeta(128f, 9, 5, "House", "4/4 kick", "ref")
        val text = reasonsFor(item(128f, 9), ref)
        assertTrue(text.contains("same key"))
        assertTrue(text.contains("tempo"))
    }

    @Test fun sourceGroupsMapToFilters() {
        assertEquals("local", LibItem("x", "t", "a", 0, null, -1, sourceKind = "import").sourceGroup())
        assertEquals("plex", LibItem("x", "t", "a", 0, null, -1, sourceKind = "plex").sourceGroup())
        assertEquals("drive", LibItem("x", "t", "a", 0, null, -1, sourceKind = "drive").sourceGroup())
    }
}
