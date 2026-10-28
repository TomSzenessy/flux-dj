package app.flux.dj

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class XmlAndTransportTest {
    @Test fun xmlParsesANormalDocument() {
        val doc = Xml.parse(ByteArrayInputStream("""<DJ_PLAYLISTS><PRODUCT Name="rekordbox" Version="7"/></DJ_PLAYLISTS>""".toByteArray()))
        assertEquals("DJ_PLAYLISTS", doc.documentElement.tagName)
    }

    @Test fun xmlRefusesADoctype() {
        val payload = """<!DOCTYPE lolz [<!ENTITY lol "lol">]><DJ_PLAYLISTS>&lol;</DJ_PLAYLISTS>"""
        try {
            Xml.parse(ByteArrayInputStream(payload.toByteArray()))
            fail("a doctype must be refused")
        } catch (_: Exception) {
            // expected: parser rejects doctype / entity expansion
        }
    }

    @Test fun privateHostsAreRecognised() {
        assertTrue(Http.isPrivateHost("192.168.1.20"))
        assertTrue(Http.isPrivateHost("10.0.0.5"))
        assertTrue(Http.isPrivateHost("172.16.4.2"))
        assertTrue(Http.isPrivateHost("localhost"))
        assertFalse(Http.isPrivateHost("plex.example.com"))
        assertFalse(Http.isPrivateHost("8.8.8.8"))
    }

    @Test fun cleartextToAPublicHostIsRefused() {
        try { Http.requireSafe("http://example.com/identity"); fail("public cleartext must be refused") }
        catch (_: IOException) {}
        Http.requireSafe("http://192.168.1.20:32400/identity")
        Http.requireSafe("https://example.com/identity")
    }

    @Test fun redirectsCannotLeaveTheHost() {
        assertEquals("https://plex.example.com/x", Http.resolveRedirect("https://plex.example.com/a", "/x"))
        try { Http.resolveRedirect("https://plex.example.com/a", "https://evil.example/x"); fail("cross-host redirect must be refused") }
        catch (_: IOException) {}
    }
}
