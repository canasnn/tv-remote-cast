package com.ruterika.castcore.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Small, allocation-light URL helpers used by the web video detector.
 *
 * They deliberately avoid strict parsers for the hot path (every WebView request goes through
 * them) and never throw on malformed input.
 */
object WebUrls {

    /** Removes the `#fragment` part of [url] (if any). */
    fun stripFragment(url: String): String {
        val i = url.indexOf('#')
        return if (i >= 0) url.substring(0, i) else url
    }

    /** Returns the lowercase scheme of [url] (`https`, `blob`, ...) or `null`. */
    fun schemeOf(url: String): String? {
        val i = url.indexOf(':')
        if (i <= 0) return null
        val s = url.substring(0, i)
        return if (s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) s.lowercase() else null
    }

    /** `true` for `http:` and `https:` URLs. */
    fun isHttp(url: String): Boolean {
        val s = schemeOf(url.trim())
        return s == "http" || s == "https"
    }

    /**
     * Returns the lowercase host of an absolute URL without port, userinfo or trailing dot,
     * or `null` if the URL has no authority. A bare host (`ads.example.com`) is returned as is.
     */
    fun hostOf(url: String): String? {
        var s = url.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) {
            s = s.substring(schemeEnd + 3)
        } else if (s.startsWith("//")) {
            s = s.substring(2)
        } else if (s.contains(':') && !s.contains('.')) {
            return null
        }
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var authority = if (end >= 0) s.substring(0, end) else s
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close > 0) authority.substring(0, close + 1) else authority
        } else {
            val colon = authority.indexOf(':')
            if (colon >= 0) authority.substring(0, colon) else authority
        }
        val h = host.trimEnd('.').lowercase()
        return h.ifEmpty { null }
    }

    /** Returns `scheme://host[:port]` of [url] or `null` when it is not an http(s) URL. */
    fun originOf(url: String?): String? {
        if (url == null || !isHttp(url)) return null
        val schemeEnd = url.indexOf("://")
        val rest = url.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var authority = if (end >= 0) rest.substring(0, end) else rest
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.isEmpty()) return null
        return url.substring(0, schemeEnd).lowercase() + "://" + authority.lowercase()
    }

    /** Returns the path of [url] (without query and fragment), `""` when absent. */
    fun pathOf(url: String): String {
        val noFrag = stripFragment(url)
        val q = noFrag.indexOf('?')
        val noQuery = if (q >= 0) noFrag.substring(0, q) else noFrag
        val schemeEnd = noQuery.indexOf("://")
        if (schemeEnd < 0) return noQuery
        val slash = noQuery.indexOf('/', schemeEnd + 3)
        return if (slash >= 0) noQuery.substring(slash) else ""
    }

    /** Returns the raw query string of [url] (without `?`), or `""`. */
    fun queryOf(url: String): String {
        val noFrag = stripFragment(url)
        val q = noFrag.indexOf('?')
        return if (q >= 0) noFrag.substring(q + 1) else ""
    }

    /** Parses the query string into decoded (name, value) pairs; never throws. */
    fun queryParams(url: String): List<Pair<String, String>> {
        val q = queryOf(url)
        if (q.isEmpty()) return emptyList()
        return q.split('&', ';').filter { it.isNotEmpty() }.map { part ->
            val eq = part.indexOf('=')
            val name = if (eq >= 0) part.substring(0, eq) else part
            val value = if (eq >= 0) part.substring(eq + 1) else ""
            decode(name) to decode(value)
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    /**
     * Normalizes [url] for identity purposes: trims, drops the fragment, lowercases scheme and
     * host and removes default ports. The path and query are kept verbatim because stream
     * tokens usually live there.
     */
    fun normalize(url: String): String {
        val s = stripFragment(url.trim())
        val schemeEnd = s.indexOf("://")
        if (schemeEnd < 0) return s
        val scheme = s.substring(0, schemeEnd).lowercase()
        val rest = s.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' }
        var authority = (if (end >= 0) rest.substring(0, end) else rest).lowercase()
        val tail = if (end >= 0) rest.substring(end) else "/"
        if (scheme == "http" && authority.endsWith(":80")) authority = authority.dropLast(3)
        if (scheme == "https" && authority.endsWith(":443")) authority = authority.dropLast(4)
        val fixedTail = if (tail.startsWith("?")) "/$tail" else tail
        return "$scheme://$authority$fixedTail"
    }

    /** Key that ignores the query: `host + path`. Used to match playlists whose tokens rotate. */
    fun pathKey(url: String): String = (hostOf(url) ?: "") + pathOf(url)

    /** Stable short id (16 hex chars of SHA-1) of the [normalize]d url. */
    fun stableId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(normalize(url).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xff
            sb.append(HEX[b shr 4]).append(HEX[b and 0xf])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Resolves [reference] against [base] following RFC 3986 (as browsers do). Absolute
     * references are returned unchanged; protocol-relative ones take the base scheme. Falls back
     * to a lenient manual resolution for URLs containing illegal characters.
     */
    fun resolve(base: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return base
        val refScheme = schemeOf(ref)
        if (refScheme != null && refScheme.length > 1 && refScheme[0].isLetter()) return ref
        base.toHttpUrlOrNull()?.resolve(ref)?.let { return it.toString() }
        try {
            val b = URI(base)
            val fixed = if (b.rawPath.isNullOrEmpty() && b.rawAuthority != null) URI(base.substringBefore('?').substringBefore('#') + "/") else b
            return fixed.resolve(URI(ref)).toString()
        } catch (_: Exception) {
            // fall through to manual resolution
        }
        val baseNoFrag = stripFragment(base)
        val schemeEnd = baseNoFrag.indexOf("://")
        if (schemeEnd < 0) return ref
        if (ref.startsWith("//")) return baseNoFrag.substring(0, schemeEnd + 1) + ref
        val origin = originOf(baseNoFrag) ?: return ref
        if (ref.startsWith("/")) return origin + ref
        if (ref.startsWith("?")) return baseNoFrag.substringBefore('?') + ref
        val path = pathOf(baseNoFrag)
        val dir = path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" }
        return origin + dir + ref
    }
}
