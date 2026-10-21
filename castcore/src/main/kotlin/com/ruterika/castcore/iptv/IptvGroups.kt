package com.ruterika.castcore.iptv

import kotlinx.serialization.Serializable
import java.text.Normalizer

/** A named bucket of playlist entries. */
@Serializable
data class IptvGroup(val name: String, val entries: List<M3uEntry>)

/** Grouping, searching and URL classification helpers for IPTV playlists. */
object IptvGroups {

    /** Name of the bucket holding entries without a group. Always placed last. */
    const val UNGROUPED = "Ungrouped"

    /**
     * Groups [entries] by [M3uEntry.group] preserving first-seen group order and entry order.
     * Entries with a missing or blank group go to a trailing [UNGROUPED] bucket.
     */
    fun group(entries: List<M3uEntry>): List<IptvGroup> {
        val buckets = LinkedHashMap<String, MutableList<M3uEntry>>()
        val ungrouped = ArrayList<M3uEntry>()
        for (entry in entries) {
            val name = entry.group?.trim()
            if (name.isNullOrEmpty() || name == UNGROUPED) {
                ungrouped.add(entry)
            } else {
                buckets.getOrPut(name) { ArrayList() }.add(entry)
            }
        }
        val groups = buckets.mapTo(ArrayList(buckets.size + 1)) { (name, list) -> IptvGroup(name, list) }
        if (ungrouped.isNotEmpty()) groups.add(IptvGroup(UNGROUPED, ungrouped))
        return groups
    }

    /**
     * Returns entries whose title (or `tvg-name`) contains every whitespace separated token of
     * [query], ignoring case and diacritics (Turkish `ı/İ/ş/ğ/ü/ö/ç` fold to `i/i/s/g/u/o/c`).
     * A blank query returns [entries] unchanged. Order is preserved.
     */
    fun search(entries: List<M3uEntry>, query: String): List<M3uEntry> {
        val tokens = fold(query).split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return entries
        return entries.filter { entry ->
            val title = fold(entry.title)
            if (tokens.all { it in title }) return@filter true
            val name = entry.tvgName ?: return@filter false
            val folded = fold(name)
            tokens.all { it in folded }
        }
    }

    /** Lower-cases [text] and strips diacritics so that `İSTANBUL`, `istanbul` and `ıstanbul` compare equal. */
    fun fold(text: String): String {
        val sb = StringBuilder(text.length)
        var ascii = true
        for (c in text) {
            val mapped = if (c == 'ı') 'i' else c.lowercaseChar()
            if (mapped.code >= 0x80) ascii = false
            sb.append(mapped)
        }
        if (ascii) return sb.toString()
        val decomposed = Normalizer.normalize(sb, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c)
        }
        return out.toString()
    }

    /**
     * Whether [entry] points at another channel list: `type="playlist"`, or a URL ending in
     * `.m3u`, `.m3u.gz`, `.w3u`, `.rss` or an Xtream Codes `type=m3u` query. `.m3u8` is not counted
     * because nearly every live channel is an HLS `.m3u8` stream.
     */
    fun isNestedList(entry: M3uEntry): Boolean {
        if (entry.attributes["type"]?.trim().equals("playlist", ignoreCase = true)) return true
        val base = entry.url.substringBefore('|').trim().substringBefore('#')
        val path = base.substringBefore('?').lowercase()
        if (path.endsWith(".m3u") || path.endsWith(".m3u.gz") || path.endsWith(".w3u") || path.endsWith(".rss")) return true
        if (path.endsWith(".m3u8")) return false
        return base.substringAfter('?', "").split('&').any { pair ->
            pair.substringBefore('=').equals("type", ignoreCase = true) &&
                pair.substringAfter('=', "").lowercase().startsWith("m3u")
        }
    }

    /**
     * Whether [url] looks like it points at a (possibly nested) playlist: a path ending in
     * `.m3u`, `.m3u8` or `.m3u.gz`, or an Xtream Codes style `type=m3u` / `type=m3u_plus` query.
     *
     * An `.m3u8` URL may equally be an HLS stream; that can only be decided by downloading it
     * (see [PlaylistLoader], which rejects HLS content with [NotAnM3uPlaylistException.isHlsStream]).
     */
    fun looksLikePlaylistUrl(url: String): Boolean {
        val base = url.substringBefore('|').trim()
        val withoutFragment = base.substringBefore('#')
        val path = withoutFragment.substringBefore('?').lowercase()
        if (path.endsWith(".m3u") || path.endsWith(".m3u8") || path.endsWith(".m3u.gz")) return true
        val query = withoutFragment.substringAfter('?', "")
        return query.split('&').any { pair ->
            pair.substringBefore('=').equals("type", ignoreCase = true) &&
                pair.substringAfter('=', "").lowercase().startsWith("m3u")
        }
    }
}
