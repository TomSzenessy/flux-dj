package app.flux.dj

import org.w3c.dom.Document
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One hardened XML entry point for rekordbox collections and Plex responses.
 *
 * Both documents can come from a shared cloud folder or a server, so doctypes,
 * external entities and external DTDs are refused. The Android parser may not
 * support every JAXP flag on every API level; each is applied best-effort, with
 * `isExpandEntityReferences = false` and secure processing as the floor.
 */
internal object Xml {
    fun parse(input: InputStream): Document = factory().newDocumentBuilder().parse(input)

    fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        isXIncludeAware = false
        setFeatureSafely(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
        setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttributeSafely("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttributeSafely("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        try { setFeature(name, value) } catch (_: Exception) {}
    }

    private fun DocumentBuilderFactory.setAttributeSafely(name: String, value: String) {
        try { setAttribute(name, value) } catch (_: Exception) {}
    }
}
