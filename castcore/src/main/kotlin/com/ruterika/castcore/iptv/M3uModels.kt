package com.ruterika.castcore.iptv

import kotlinx.serialization.Serializable

/**
 * A parsed M3U/M3U8 playlist.
 *
 * @property headerAttributes attributes declared on the `#EXTM3U` line (keys lower-cased),
 *   e.g. `url-tvg`, `x-tvg-url`, `user-agent`.
 * @property entries playable entries in playlist order.
 */
@Serializable
data class M3uPlaylist(
    val headerAttributes: Map<String, String> = emptyMap(),
    val entries: List<M3uEntry> = emptyList(),
) {
    /** EPG (XMLTV) URLs advertised by the header via `url-tvg` / `x-tvg-url` (comma separated lists are split). */
    val epgUrls: List<String>
        get() = listOfNotNull(headerAttributes["url-tvg"], headerAttributes["x-tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/**
 * A single playlist entry.
 *
 * @property title display title with Kodi formatting tags removed; falls back to `tvg-name`
 *   or the last URL path segment.
 * @property url stream URL with any Kodi style `|Header=Value` suffix removed.
 * @property durationSeconds declared `#EXTINF` duration, or null when unknown/live (`-1`, `0`).
 * @property group `group-title` attribute, or the preceding `#EXTGRP` value.
 * @property headers HTTP headers required to play the stream (canonical names such as
 *   `User-Agent`, `Referer`, `Origin`, `Cookie`).
 * @property attributes every `#EXTINF` attribute, keys lower-cased.
 * @property season season number detected from the title (`S01E02`, `1x02`, `1. Sezon 2. Bölüm`).
 * @property episode episode number detected from the title.
 * @property subgroups extra group levels below [group] (nested W3U groups).
 */
@Serializable
data class M3uEntry(
    val title: String,
    val url: String,
    val durationSeconds: Long? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    val group: String? = null,
    val channelNumber: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val attributes: Map<String, String> = emptyMap(),
    val season: Int? = null,
    val episode: Int? = null,
    val subgroups: List<String> = emptyList(),
) {
    /** `pltv-subgroup` attribute (Perfect Player style second group level). */
    val subgroup: String? get() = attributes["pltv-subgroup"]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Folder path of this entry: [group], then [subgroup] (when [considerSubgroups]), then
     * [subgroups]. Empty for entries without a group.
     */
    fun groupPath(considerSubgroups: Boolean = true): List<String> {
        val top = group?.trim()?.takeIf { it.isNotEmpty() } ?: return subgroups
        val path = ArrayList<String>(2 + subgroups.size)
        path.add(top)
        if (considerSubgroups) subgroup?.let(path::add)
        path.addAll(subgroups)
        return path
    }

    /** Whether this entry is a link to another list (opened as a sub-list rather than played). */
    val isNestedList: Boolean get() = IptvGroups.isNestedList(this)

    /** `tvg-language` attribute. */
    val language: String? get() = attributes["tvg-language"]?.takeIf { it.isNotBlank() }

    /** `tvg-country` attribute. */
    val country: String? get() = attributes["tvg-country"]?.takeIf { it.isNotBlank() }

    /** Catch-up mode (`catchup` / `catchup-type`), e.g. `default`, `append`, `shift`, `flussonic`. */
    val catchup: String? get() = (attributes["catchup"] ?: attributes["catchup-type"])?.takeIf { it.isNotBlank() }

    /** Number of catch-up days (`catchup-days`), if declared. */
    val catchupDays: Int? get() = attributes["catchup-days"]?.trim()?.toIntOrNull()

    /** Catch-up URL template (`catchup-source`), if declared. */
    val catchupSource: String? get() = attributes["catchup-source"]?.takeIf { it.isNotBlank() }
}
