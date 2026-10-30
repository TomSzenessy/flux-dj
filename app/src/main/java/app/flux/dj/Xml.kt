package app.flux.dj

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.Locator
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One hardened XML entry point for rekordbox collections and Plex responses.
 *
 * Both documents can come from a shared cloud folder or a server, so doctypes,
 * external entities and external DTDs are refused. The Android parser supports
 * only a fraction of the JAXP flags, so every one is applied best-effort, with
 * `isExpandEntityReferences = false` as the floor.
 */
internal object Xml {
    fun parse(input: InputStream): Document = factory().newDocumentBuilder().parse(refuseDoctype(input))

    fun factory(): DocumentBuilderFactory = configure(DocumentBuilderFactory.newInstance())

    /**
     * Apply the hardened settings to any factory. Android's built-in factory
     * (`org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl`) implements only the
     * namespace and validation features; it inherits the JAXP base class for the rest,
     * and that base throws `UnsupportedOperationException` — so a strict chain failed on
     * every device while passing on the JVM. Nothing here is mandatory: XInclude is off
     * by default, and the doctype/entity features are re-checked at parse time.
     */
    internal fun configure(f: DocumentBuilderFactory): DocumentBuilderFactory = f.apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        setXIncludeAwareSafely(false)
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

    private fun DocumentBuilderFactory.setXIncludeAwareSafely(value: Boolean) {
        try { isXIncludeAware = value } catch (_: Exception) {}
    }

    private fun DocumentBuilderFactory.setAttributeSafely(name: String, value: String) {
        try { setAttribute(name, value) } catch (_: Exception) {}
    }

    /** How much of the prolog is checked for a doctype; well past any real rekordbox/Plex header. */
    private const val PROLOG_BYTES = 64 * 1024

    private val DOCTYPE = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)

    /**
     * `disallow-doctype-decl` is one of the flags Android ignores, so the prolog is also
     * inspected here. A doctype can only appear before the root element, and the first
     * 64 KiB covers it; anything else was never a legitimate collection or Plex response.
     */
    private fun refuseDoctype(input: InputStream): InputStream {
        val head = ByteArray(PROLOG_BYTES)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        if (hasDoctype(head, n)) throw SAXParseException("DOCTYPE is not allowed", null as Locator?)
        return SequenceInputStream(ByteArrayInputStream(head, 0, n), input)
    }

    private fun hasDoctype(buf: ByteArray, len: Int): Boolean {
        outer@ for (i in 0..len - DOCTYPE.size) {
            for (j in DOCTYPE.indices) {
                val c = buf[i + j].toInt() and 0xff
                val upper = if (c in 'a'.code..'z'.code) c - 32 else c
                if (upper != DOCTYPE[j].toInt()) continue@outer
            }
            return true
        }
        return false
    }
}


/** First direct child with this tag; used by the rekordbox and Plex readers. */
internal fun Element.child(tag: String): Element? {
    val nl = childNodes
    for (i in 0 until nl.length) {
        val n = nl.item(i)
        if (n is Element && n.tagName == tag) return n
    }
    return null
}

/** All direct children with this tag. */
internal fun Element.children(tag: String): List<Element> {
    val nl = childNodes
    val out = ArrayList<Element>()
    for (i in 0 until nl.length) {
        val n = nl.item(i)
        if (n is Element && n.tagName == tag) out.add(n)
    }
    return out
}
