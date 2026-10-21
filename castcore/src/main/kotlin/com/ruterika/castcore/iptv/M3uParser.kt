package com.ruterika.castcore.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Reader
import java.io.StringReader

/**
 * Parser for extended M3U / M3U8 IPTV playlists.
 *
 * Understands `#EXTM3U` header attributes, `#EXTINF` attributes (quoted with `"` or `'`, or
 * unquoted) and titles containing commas, `#EXTGRP`, `#EXTVLCOPT` HTTP options, `#KODIPROP`
 * stream headers, `#EXTHTTP` JSON headers and Kodi style `url|Header=Value&...` suffixes.
 * Lines without a preceding `#EXTINF` are treated as bare entries titled by their last URL
 * path segment. The parser is line based and never holds more than one entry of state, so
 * [forEachEntry] can stream very large playlists.
 */
object M3uParser {

    private const val READ_BUFFER_CHARS = 64 * 1024

    /** Parses a playlist held in memory. */
    fun parse(text: String): M3uPlaylist = parse(StringReader(text))

    /** Parses a playlist from [reader]. The reader is consumed but not closed. */
    fun parse(reader: Reader): M3uPlaylist {
        val entries = ArrayList<M3uEntry>()
        val header = forEachEntry(reader) { entries.add(it) }
        return M3uPlaylist(header, entries)
    }

    /**
     * Streams entries from [reader] to [onEntry] without materialising the full list.
     * The reader is consumed but not closed.
     *
     * @return the merged `#EXTM3U` header attributes.
     */
    fun forEachEntry(reader: Reader, onEntry: (M3uEntry) -> Unit): Map<String, String> {
        val lines = reader as? BufferedReader ?: BufferedReader(reader, READ_BUFFER_CHARS)
        val state = ParseState()
        while (true) {
            val raw = lines.readLine() ?: break
            val line = raw.trimStart('﻿').trim()
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                state.directive(line)
            } else {
                state.buildEntry(line)?.let(onEntry)
            }
        }
        return state.header
    }

    /**
     * Splits a Kodi style `url|Key=Value&Key2=Value2` string into the bare URL and its
     * headers (percent-encoded values are decoded, header names canonicalised).
     */
    fun splitPipeHeaders(rawUrl: String): Pair<String, Map<String, String>> {
        val pipe = rawUrl.indexOf('|')
        if (pipe < 0) return rawUrl.trim() to emptyMap()
        val headers = LinkedHashMap<String, String>()
        parseHeaderPairs(rawUrl.substring(pipe + 1), headers)
        return rawUrl.substring(0, pipe).trim() to headers
    }

    /**
     * Detects season/episode numbers in [title] (`S01E02`, `S1 E2`, `1x02`,
     * `1. Sezon 2. Bölüm`). Returns null when no pattern matches.
     */
    fun detectEpisode(title: String): Pair<Int, Int>? {
        if (title.none { it.isDigit() }) return null
        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(title) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
            return season to episode
        }
        return null
    }

    /** Removes Kodi label formatting tags (`[B]`, `[I]`, `[COLOR red]`, `[CR]`, ...) and collapses whitespace. */
    fun stripFormatting(title: String): String {
        val stripped = if ('[' in title) {
            KODI_TAG.replace(title) { if (it.value.equals("[CR]", ignoreCase = true)) " " else "" }
        } else {
            title
        }
        return collapseWhitespace(stripped)
    }

    private val KODI_TAG = Regex(
        """\[/?(?:B|I|COLOR(?:[\s=][^\]]*)?|UPPERCASE|LOWERCASE|CAPITALIZE|LIGHT|CR)\]""",
        RegexOption.IGNORE_CASE,
    )

    private val EPISODE_PATTERNS = listOf(
        Regex("""(?<![A-Za-z0-9])S(\d{1,3})[\s._-]*E(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE),
        Regex("""(?<![A-Za-z0-9])(\d{1,2})[xX](\d{1,3})(?![A-Za-z0-9])"""),
        Regex("""(\d{1,3})\.?\s*Sezon\s*(\d{1,4})\.?\s*B[öo]l[üu]m""", RegexOption.IGNORE_CASE),
    )

    private val json = Json { isLenient = true }

    private class ParseState {
        val header = LinkedHashMap<String, String>()
        private var extinf: String? = null
        private var pendingGroup: String? = null
        private val pendingHeaders = LinkedHashMap<String, String>()

        fun directive(line: String) {
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> extinf = line
                line.startsWith("#EXTM3U", ignoreCase = true) ->
                    scanAttributes(line, "#EXTM3U".length, header, stopAtComma = false)
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingGroup = line.substring("#EXTGRP:".length).trim().takeIf { it.isNotEmpty() }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOption(line.substring("#EXTVLCOPT:".length))
                line.startsWith("#KODIPROP:", ignoreCase = true) -> kodiProp(line.substring("#KODIPROP:".length))
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> extHttp(line.substring("#EXTHTTP:".length))
            }
        }

        private fun vlcOption(option: String) {
            val eq = option.indexOf('=')
            if (eq <= 0) return
            val name = when (option.substring(0, eq).trim().lowercase()) {
                "http-user-agent" -> "User-Agent"
                "http-referrer", "http-referer" -> "Referer"
                "http-origin" -> "Origin"
                "http-cookie" -> "Cookie"
                else -> return
            }
            val value = unquote(option.substring(eq + 1).trim())
            if (value.isNotEmpty()) pendingHeaders[name] = value
        }

        private fun kodiProp(prop: String) {
            val eq = prop.indexOf('=')
            if (eq <= 0) return
            when (prop.substring(0, eq).trim().lowercase()) {
                "inputstream.adaptive.stream_headers",
                "inputstream.adaptive.manifest_headers",
                -> parseHeaderPairs(prop.substring(eq + 1), pendingHeaders)
            }
        }

        private fun extHttp(body: String) {
            val obj = runCatching { json.parseToJsonElement(body.trim()) as? JsonObject }.getOrNull() ?: return
            for ((key, value) in obj) {
                val text = (value as? JsonPrimitive)?.content?.trim().orEmpty()
                if (key.isNotBlank() && text.isNotEmpty()) pendingHeaders[canonicalHeaderName(key)] = text
            }
        }

        fun buildEntry(rawUrl: String): M3uEntry? {
            val (url, pipeHeaders) = splitPipeHeaders(rawUrl)
            val info = extinf
            val attributes = LinkedHashMap<String, String>()
            var duration: Long? = null
            var rawTitle = ""
            if (info != null) {
                val parsed = parseExtinf(info, attributes)
                duration = parsed.first
                rawTitle = parsed.second
            }
            val group = attributes["group-title"].nonBlank() ?: pendingGroup

            val headers = LinkedHashMap<String, String>()
            headerValue(header, USER_AGENT_KEYS)?.let { headers["User-Agent"] = it }
            headerValue(header, REFERER_KEYS)?.let { headers["Referer"] = it }
            headerValue(attributes, USER_AGENT_KEYS)?.let { headers["User-Agent"] = it }
            headerValue(attributes, REFERER_KEYS)?.let { headers["Referer"] = it }
            headerValue(attributes, ORIGIN_KEYS)?.let { headers["Origin"] = it }
            headers.putAll(pendingHeaders)
            headers.putAll(pipeHeaders)

            extinf = null
            pendingGroup = null
            pendingHeaders.clear()
            if (url.isEmpty()) return null

            val tvgName = attributes["tvg-name"].nonBlank()
            val title = stripFormatting(rawTitle).ifEmpty { tvgName?.let(::stripFormatting).orEmpty() }
                .ifEmpty { titleFromUrl(url) }
            val episode = detectEpisode(title)
            return M3uEntry(
                title = title,
                url = url,
                durationSeconds = duration,
                tvgId = attributes["tvg-id"].nonBlank(),
                tvgName = tvgName,
                logo = (attributes["tvg-logo"] ?: attributes["logo"]).nonBlank(),
                group = group,
                channelNumber = (attributes["tvg-chno"] ?: attributes["channel-number"])?.trim()?.toIntOrNull(),
                headers = headers.ifEmpty { emptyMap() },
                attributes = attributes.ifEmpty { emptyMap() },
                season = episode?.first,
                episode = episode?.second,
            )
        }
    }

    private val USER_AGENT_KEYS = listOf("user-agent", "http-user-agent")
    private val REFERER_KEYS = listOf("http-referrer", "http-referer", "referer", "referrer")
    private val ORIGIN_KEYS = listOf("http-origin", "origin")

    private fun headerValue(source: Map<String, String>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { source[it].nonBlank() }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** Returns the duration and the raw title; fills [into] with the attributes. */
    private fun parseExtinf(line: String, into: MutableMap<String, String>): Pair<Long?, String> {
        val n = line.length
        var i = "#EXTINF:".length
        while (i < n && line[i].isWhitespace()) i++
        val durationStart = i
        while (i < n && !line[i].isWhitespace() && line[i] != ',') i++
        val duration = line.substring(durationStart, i).toDoubleOrNull()?.takeIf { it > 0 }?.toLong()
        val comma = scanAttributes(line, i, into, stopAtComma = true)
        val title = if (comma < n) line.substring(comma + 1).trim() else ""
        return duration to title
    }

    /**
     * Scans `key="value" key2='value' key3=value` pairs starting at [from], storing them with
     * lower-cased keys. Returns the index of the first top-level comma when [stopAtComma],
     * otherwise the line length.
     */
    private fun scanAttributes(s: String, from: Int, into: MutableMap<String, String>, stopAtComma: Boolean): Int {
        val n = s.length
        var i = from
        while (i < n) {
            val c = s[i]
            if (c == ',' && stopAtComma) return i
            if (c.isWhitespace()) {
                i++
                continue
            }
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace() && !(stopAtComma && s[i] == ',')) i++
            if (i >= n || s[i] != '=') continue
            val key = s.substring(keyStart, i).lowercase()
            i++
            val value: String
            val quote = if (i < n) s[i] else ' '
            val close = if (quote == '"' || quote == '\'') s.indexOf(quote, i + 1) else -1
            if (close >= 0) {
                value = s.substring(i + 1, close)
                i = close + 1
            } else {
                if (quote == '"' || quote == '\'') i++
                val valueStart = i
                while (i < n && !s[i].isWhitespace() && !(stopAtComma && s[i] == ',')) i++
                value = s.substring(valueStart, i)
            }
            if (key.isNotEmpty()) into[key] = value.trim()
        }
        return n
    }

    private fun parseHeaderPairs(spec: String, into: MutableMap<String, String>) {
        for (pair in spec.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = percentDecode(pair.substring(0, eq)).trim()
            val value = percentDecode(pair.substring(eq + 1)).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) into[canonicalHeaderName(name)] = value
        }
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0]) {
            value.substring(1, value.length - 1).trim()
        } else {
            value
        }

    internal fun canonicalHeaderName(name: String): String =
        when (val lower = name.trim().lowercase()) {
            "user-agent", "http-user-agent", "useragent" -> "User-Agent"
            "referer", "referrer", "http-referer", "http-referrer" -> "Referer"
            "origin", "http-origin" -> "Origin"
            "cookie", "http-cookie" -> "Cookie"
            else -> lower.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        }

    internal fun titleFromUrl(url: String): String {
        val base = url.substringBefore('#').substringBefore('?')
        val schemeEnd = base.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = if (schemeEnd >= 0) base.indexOf('/', authorityStart).let { if (it < 0) base.length else it } else 0
        val segment = base.substring(pathStart).trimEnd('/').substringAfterLast('/')
        return percentDecode(segment).trim()
            .ifEmpty { base.substring(authorityStart, pathStart).trim() }
            .ifEmpty { url }
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hi = if (c == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
            if (hi >= 0 && lo >= 0) {
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (bytes.size() > 0) {
                    out.append(bytes.toString(Charsets.UTF_8.name()))
                    bytes.reset()
                }
                out.append(c)
                i++
            }
        }
        if (bytes.size() > 0) out.append(bytes.toString(Charsets.UTF_8.name()))
        return out.toString()
    }

    private fun collapseWhitespace(s: String): String {
        val out = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (c.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
        return out.toString()
    }
}
