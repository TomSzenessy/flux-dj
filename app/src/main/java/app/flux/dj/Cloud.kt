package app.flux.dj

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A track that lives on a remote server. [url] is streamed straight by the decoder. */
class RemoteTrack(
    val id: String, val title: String, val artist: String, val durMs: Long,
    val url: String, val fileStem: String,
)

class PlexPin(val id: Long, val code: String, val expiresInSec: Int = 1800)
class PlexServer(val name: String, val uri: String)
class PlexSection(val key: String, val title: String)

/**
 * Minimal Plex client — Plexamp is just a Plex client, so signing in to Plex gives FLUX the same
 * library and the original files. No SDK: HttpURLConnection + org.json/XML, because the app has no
 * third-party dependencies.
 */
private const val MAX_RESPONSE_CHARS = 32 * 1024 * 1024
private const val MAX_TRACKS = 20_000

class PlexClient(
    private val clientId: String,
    private val product: String = "FLUX",
    private val version: String = "1.0",
) {
    private val common = mapOf(
        "X-Plex-Product" to product,
        "X-Plex-Version" to version,
        "X-Plex-Client-Identifier" to clientId,
        "X-Plex-Device" to "Android",
        "X-Plex-Device-Name" to "FLUX",
    )

    /** One-tap sign-in URL: if the user is already logged in to Plex, this links the PIN immediately. */
    fun authUrl(code: String): String =
        "https://app.plex.tv/auth#?clientID=$clientId&code=${java.net.URLEncoder.encode(code, "UTF-8")}" +
            "&context%5Bdevice%5D%5Bproduct%5D=FLUX&context%5Bdevice%5D%5Bplatform%5D=Android"

    private fun JSONObject.str(name: String, fallback: String = ""): String {
        val v = opt(name)
        return if (v is String && v != "null") v else fallback
    }

    private fun http(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null): String {
        var target = url
        for (attempt in 0..5) {
            Http.requireSafe(target)
            val c = URL(target).openConnection() as HttpURLConnection
            var redirect: String? = null
            try {
                c.requestMethod = method
                c.connectTimeout = 12_000
                c.readTimeout = 25_000
                // Follow redirects manually: a custom token header must never travel to another host.
                c.instanceFollowRedirects = false
                (common + headers).forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null || method == "POST") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body?.size ?: 0)
                    if (body != null) c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code in 300..399 && method == "GET") {
                    redirect = Http.resolveRedirect(target, c.getHeaderField("Location"))
                } else {
                    val text = (if (code in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()?.use { readCapped(it) } ?: ""
                    if (code !in 200..299) throw IOException("Plex HTTP $code${if (text.isNotBlank()) ": ${text.take(120)}" else ""}")
                    return text
                }
            } finally { c.disconnect() }
            target = redirect ?: throw IOException("Redirect without a target")
        }
        throw IOException("Too many redirects")
    }

    private fun readCapped(reader: java.io.Reader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var total = 0
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_RESPONSE_CHARS) throw IOException("Plex response is too large")
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    private fun xml(url: String, token: String): Document {
        val body = http(url, headers = mapOf("X-Plex-Token" to token, "Accept" to "application/xml"))
        return Xml.parse(body.byteInputStream())
    }

    // ------------------------------------------------------------------ sign-in
    /** Create a PIN; the user enters [code] at plex.tv/link. */
    fun requestPin(): PlexPin? {
        val o = JSONObject(http("https://plex.tv/api/v2/pins?strong=true", "POST", mapOf("Accept" to "application/json")))
        val id = o.optLong("id", 0)
        // Plex currently issues a long, case-sensitive one-time code (often 26 chars). Keep it exact.
        val code = o.str("code").trim()
        val expires = o.optInt("expiresIn", 1800).takeIf { it in 30..7200 } ?: 1800
        return if (id > 0 && code.isNotEmpty()) PlexPin(id, code, expires) else null
    }

    /** null while the user hasn't linked yet; the token once they have. */
    fun pollPin(id: Long): String? {
        val o = JSONObject(http("https://plex.tv/api/v2/pins/$id", headers = mapOf("Accept" to "application/json")))
        return o.str("authToken").takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ discovery
    /** Servers owned/shared with this account, with every reachable connection candidate. */
    fun servers(token: String): List<PlexServer> {
        val arr = JSONArray(http("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1",
            headers = mapOf("X-Plex-Token" to token, "Accept" to "application/json")))
        val out = ArrayList<PlexServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.str("provides").split(",").contains("server")) continue
            val conns = o.optJSONArray("connections") ?: continue
            class C(val uri: String, val local: Boolean, val relay: Boolean)
            val cs = ArrayList<C>()
            for (j in 0 until conns.length()) {
                val c = conns.optJSONObject(j) ?: continue
                val uri = c.str("uri").trimEnd('/')
                if (uri.isEmpty()) continue
                cs.add(C(uri, c.optBoolean("local"), c.optBoolean("relay")))
            }
            // Probe in order: direct public/plex.direct first, then LAN, relays last. A Docker-internal
            // "local" address is often listed but unreachable from the tablet, so we test each one.
            val ordered = cs.sortedWith(compareBy({ it.relay }, { it.local }, { it.uri.length }))
            val name = o.str("name", "Plex")
            ordered.forEach { out.add(PlexServer(name, it.uri)) }
        }
        return out
    }

    /** True when a server URI answers /identity quickly (used to skip dead Docker/relay addresses). */
    fun reachable(uri: String, token: String): Boolean {
        try { Http.requireSafe("$uri/identity") } catch (_: Exception) { return false }
        val c = try { URL("$uri/identity").openConnection() as HttpURLConnection } catch (_: Exception) { return false }
        return try {
            c.connectTimeout = 4000; c.readTimeout = 4000; c.instanceFollowRedirects = false
            (common + mapOf("X-Plex-Token" to token, "Accept" to "application/xml")).forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.responseCode in 200..299
        } catch (_: Exception) { false } finally { c.disconnect() }
    }

    /** First candidate that actually works from this network. */
    fun workingServer(token: String): PlexServer? = servers(token).firstOrNull { reachable(it.uri, token) }

    fun sections(server: String, token: String): List<PlexSection> {
        val doc = xml("$server/library/sections", token)
        return doc.documentElement.children("Directory").mapNotNull { d ->
            if (d.getAttribute("type") != "artist") return@mapNotNull null
            val title = d.getAttribute("title")
            // Audiobook/spoken-word libraries are type "artist" too; keep them out of a DJ library.
            val spoken = listOf("hörbuch", "hoerbuch", "horbuch", "audiobook", "audiobooks", "podcast",
                "spoken", "hörspiel", "hoerspiel", "horspiel", "bücher", "buecher", "books")
            if (spoken.any { title.lowercase(java.util.Locale.ROOT).contains(it) }) return@mapNotNull null
            PlexSection(d.getAttribute("key"), title)
        }
    }

    fun tracks(server: String, token: String, sectionKey: String): List<RemoteTrack> {
        val out = ArrayList<RemoteTrack>()
        var start = 0
        val page = 2000
        while (start < MAX_TRACKS) {
            val doc = xml("$server/library/sections/$sectionKey/all?type=10&X-Plex-Container-Start=$start&X-Plex-Container-Size=$page", token)
            val batch = doc.documentElement.children("Track").mapNotNull { t ->
            val key = t.getAttribute("ratingKey").ifEmpty { return@mapNotNull null }
            val title = t.getAttribute("title").ifEmpty { "Untitled" }
            val dur = t.getAttribute("duration").toLongOrNull() ?: 0L
            // Music libraries occasionally contain audiobook parts. They are huge and useless on a
            // deck, so never make them streamable (a 550 MB file takes forever to attach).
            if (dur > 20 * 60 * 1000L) return@mapNotNull null
            if (Regex("(?i)(chapter|kapitel|audiobook|h(ö|oe)rbuch|\\bpt\\s*\\d+\\b)").containsMatchIn(title)) return@mapNotNull null
            val media = t.child("Media")
            val part = media?.child("Part")
            val partKey = part?.getAttribute("key").orEmpty()
            val url = if (partKey.isNotEmpty()) server + partKey else "$server/library/metadata/$key/file"
            val file = part?.getAttribute("file") ?: ""
            val stem = file.substringAfterLast('/').substringBeforeLast('.').ifEmpty { title }
            RemoteTrack(
                "plex:$key", title,
                t.getAttribute("grandparentTitle").ifEmpty { t.getAttribute("originalTitle") },
                dur, url, stem,
            )
            }
            out.addAll(batch)
            if (batch.size < page) break
            start += page
        }
        return out
    }
}

private fun Element.child(tag: String): Element? {
    val nl = childNodes
    for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && n.tagName == tag) return n }
    return null
}

private fun Element.children(tag: String): List<Element> {
    val nl = childNodes; val out = ArrayList<Element>()
    for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && n.tagName == tag) out.add(n) }
    return out
}
