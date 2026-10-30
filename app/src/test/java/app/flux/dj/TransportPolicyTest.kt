package app.flux.dj

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.validation.Schema

class XmlAndTransportTest {
    @Test fun xmlParsesANormalDocument() {
        val doc = Xml.parse(ByteArrayInputStream("""<DJ_PLAYLISTS><PRODUCT Name="rekordbox" Version="7"/></DJ_PLAYLISTS>""".toByteArray()))
        assertEquals("DJ_PLAYLISTS", doc.documentElement.tagName)
    }

    /** Android ships a factory that throws from every unimplemented JAXP setter (gh-#41). */
    @Test fun hardeningDoesNotThrowWhereAndroidRefusesASetting() {
        val f = AndroidLikeFactory()
        assertSame(f, Xml.configure(f))
        assertTrue("XInclude is still requested", "xinclude" in f.attempted)
        assertTrue("secure processing is still requested",
            "feature:http://javax.xml.XMLConstants/feature/secure-processing" in f.attempted)
        assertEquals("both external-access attributes are still requested", 2, f.attempted.count { it.startsWith("attr:") })
        assertFalse(f.isNamespaceAware)
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

/**
 * Mirrors `org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl` on Android: only
 * namespaces and validation exist, every other JAXP feature/attribute/XInclude call
 * falls through to the base class, which throws. Configuration must stay best-effort.
 */
private class AndroidLikeFactory : DocumentBuilderFactory() {
    /** every optional setting the code still asks for, whether or not the platform honours it */
    val attempted = ArrayList<String>()

    override fun newDocumentBuilder(): DocumentBuilder = throw ParserConfigurationException("no builder")
    override fun setAttribute(name: String, value: Any?) { attempted.add("attr:$name"); throw IllegalArgumentException(name) }
    override fun getAttribute(name: String): Any = throw IllegalArgumentException(name)
    override fun setFeature(name: String, value: Boolean) { attempted.add("feature:$name"); throw ParserConfigurationException(name) }
    override fun getFeature(name: String): Boolean = throw ParserConfigurationException(name)
    override fun setSchema(schema: Schema?) { throw UnsupportedOperationException() }
    override fun getSchema(): Schema = throw UnsupportedOperationException()
    override fun setXIncludeAware(state: Boolean) {
        attempted.add("xinclude")
        throw UnsupportedOperationException("This parser does not support specification \"Unknown\" version \"0.0\"")
    }
    override fun isXIncludeAware(): Boolean = throw UnsupportedOperationException()
}
