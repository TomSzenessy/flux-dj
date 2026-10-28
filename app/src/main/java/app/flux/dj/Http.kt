package app.flux.dj

import java.io.IOException
import java.net.URL

/** Transport policy shared by Plex and remote downloads. */
internal object Http {
    /** Loopback and RFC1918/link-local hosts are the user's own network, not a public network. */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        if (h.endsWith(".local")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        return when {
            n[0] == 10 -> true
            n[0] == 127 -> true
            n[0] == 169 && n[1] == 254 -> true
            n[0] == 172 && n[1] in 16..31 -> true
            n[0] == 192 && n[1] == 168 -> true
            else -> false
        }
    }

    /** Refuse a token-bearing cleartext request to a public host. */
    fun requireSafe(url: String) {
        val u = try { URL(url) } catch (e: Exception) { throw IOException("Bad URL: $url", e) }
        val scheme = u.protocol?.lowercase()
        if (scheme != "http" && scheme != "https") throw IOException("Unsupported URL scheme: $scheme")
        if (scheme == "http" && !isPrivateHost(u.host)) {
            throw IOException("Refusing cleartext request to a public host (${u.host}); use https")
        }
    }

    /**
     * Resolve a redirect without carrying headers to another host. Same-host http↔https is
     * allowed; a cross-host or public-cleartext target is refused.
     */
    fun resolveRedirect(base: String, location: String?): String {
        if (location.isNullOrBlank()) throw IOException("Redirect without a Location")
        val next = URL(URL(base), location)
        requireSafe(next.toString())
        val from = URL(base)
        if (!from.host.equals(next.host, ignoreCase = true)) {
            throw IOException("Refusing a redirect to another host (${next.host})")
        }
        return next.toString()
    }
}
