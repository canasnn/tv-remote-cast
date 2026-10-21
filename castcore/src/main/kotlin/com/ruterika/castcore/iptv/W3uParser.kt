package com.ruterika.castcore.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Reader
import java.io.StringReader

/**
 * Parser for W3U (Wiseplay style JSON) lists:
 *
 * ```
 * { "name": "...", "image": "...",
 *   "groups":   [ { "name": "...", "image": "...", "url": "...", "groups": [...], "stations": [...] } ],
 *   "stations": [ { "name": "...", "image": "...", "url": "...", "referer": "...", "userAgent": "..." } ] }
 * ```
 *
 * Groups nest to any depth and become the entry's [M3uEntry.group] plus [M3uEntry.subgroups].
 * A group that only has a `url` (no stations or groups) is a link to another list and becomes
 * an entry with `type="playlist"`. Stations without a url are skipped.
 */
object W3uParser {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Parses a list held in memory. */
    fun parse(text: String): M3uPlaylist = parse(StringReader(text))

    /** Parses a list from [reader]. The reader is consumed but not closed. */
    fun parse(reader: Reader): M3uPlaylist {
        val entries = ArrayList<M3uEntry>()
        val header = forEachEntry(reader) { entries.add(it) }
        return M3uPlaylist(header, entries)
    }

    /**
     * Streams the entries of a W3U document to [onEntry].
     *
     * @return header attributes (`name`, `image`, `author`, `url-tvg` when present).
     * @throws NotAnM3uPlaylistException when the content is not a W3U document.
     */
    fun forEachEntry(reader: Reader, onEntry: (M3uEntry) -> Unit): Map<String, String> {
        val root = try {
            json.parseToJsonElement(reader.readText().trimStart('﻿'))
        } catch (e: Exception) {
            throw NotAnM3uPlaylistException("Content is not a valid W3U (JSON) list: ${e.message}")
        }
        val header = LinkedHashMap<String, String>()
        when (root) {
            is JsonObject -> {
                root.text("name")?.let { header["name"] = it }
                root.text("image")?.let { header["image"] = it }
                root.text("author")?.let { header["author"] = it }
                (root.text("epg") ?: root.text("url-tvg"))?.let { header["url-tvg"] = it }
                walk(root, emptyList(), onEntry)
            }
            is JsonArray -> walkGroups(root, emptyList(), onEntry)
            else -> throw NotAnM3uPlaylistException("Content is not a valid W3U (JSON) list")
        }
        return header
    }

    private fun walk(node: JsonObject, path: List<String>, onEntry: (M3uEntry) -> Unit) {
        (node["stations"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { station(it, path)?.let(onEntry) }
        }
        (node["groups"] as? JsonArray)?.let { walkGroups(it, path, onEntry) }
    }

    private fun walkGroups(groups: JsonArray, path: List<String>, onEntry: (M3uEntry) -> Unit) {
        for (element in groups) {
            val group = element as? JsonObject ?: continue
            val name = group.text("name") ?: "N/A"
            val hasChildren = (group["stations"] as? JsonArray).orEmpty().isNotEmpty() ||
                (group["groups"] as? JsonArray).orEmpty().isNotEmpty()
            val url = group.text("url")
            if (!hasChildren && url != null) {
                onEntry(entry(name, url, group.text("image"), path, emptyMap(), mapOf("type" to "playlist")))
            } else {
                walk(group, path + name, onEntry)
            }
        }
    }

    private fun station(obj: JsonObject, path: List<String>): M3uEntry? {
        val rawUrl = obj.text("url") ?: return null
        val (url, pipeHeaders) = M3uParser.splitPipeHeaders(rawUrl)
        if (url.isEmpty()) return null
        val headers = LinkedHashMap<String, String>()
        (obj.text("userAgent") ?: obj.text("user-agent") ?: obj.text("useragent"))?.let { headers["User-Agent"] = it }
        (obj.text("referer") ?: obj.text("referrer"))?.let { headers["Referer"] = it }
        obj.text("origin")?.let { headers["Origin"] = it }
        headers.putAll(pipeHeaders)
        val attributes = LinkedHashMap<String, String>()
        obj.text("type")?.let { attributes["type"] = it }
        obj.text("epgId")?.let { attributes["tvg-id"] = it }
        val title = obj.text("name")?.let(M3uParser::stripFormatting)?.ifEmpty { null } ?: M3uParser.titleFromUrl(url)
        return entry(title, url, obj.text("image") ?: obj.text("icon"), path, headers, attributes)
    }

    private fun entry(
        title: String,
        url: String,
        logo: String?,
        path: List<String>,
        headers: Map<String, String>,
        attributes: Map<String, String>,
    ): M3uEntry {
        val episode = M3uParser.detectEpisode(title)
        return M3uEntry(
            title = title,
            url = url,
            tvgId = attributes["tvg-id"],
            logo = logo,
            group = path.firstOrNull(),
            headers = headers.ifEmpty { emptyMap() },
            attributes = attributes.ifEmpty { emptyMap() },
            season = episode?.first,
            episode = episode?.second,
            subgroups = if (path.size > 1) path.subList(1, path.size).toList() else emptyList(),
        )
    }

    private fun JsonObject.text(key: String): String? =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" })?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
