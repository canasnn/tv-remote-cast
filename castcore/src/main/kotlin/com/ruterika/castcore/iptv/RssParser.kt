package com.ruterika.castcore.iptv

import java.io.Reader
import java.io.StringReader

/**
 * Parser for RSS/XML IPTV lists:
 *
 * ```
 * <rss><channel><title>My TV</title>
 *   <item><title>Channel 1</title><category>News</category>
 *     <enclosure url="http://host/ch1.m3u8" type="application/x-mpegURL"/>
 *     <media:thumbnail url="http://host/ch1.png"/></item>
 *   <item><title>More</title><enclosure url="http://host/more.rss" type="application/rss+xml"/></item>
 * </channel></rss>
 * ```
 *
 * The stream of an item is its `<enclosure>`, `<media:content>` (also inside `<media:group>`),
 * or, failing that, its `<link>` when that does not look like a web page. Items that link to
 * another feed (an RSS/Atom/XML type or a `.rss`/`.xml` address) or to an M3U/W3U list become
 * nested lists (`type="playlist"`). The logo comes from `<media:thumbnail>`, `<itunes:image>`,
 * `<image>` or an `<img>` in the description, and the group from `<category>`.
 *
 * Every entry carries the title of its feed channel in the [CHANNEL_ATTRIBUTE] attribute, so the
 * "keep the feed's own channel" option ([IptvIndexOptions.keepRssRootChannel]) can show it as a
 * folder without parsing again. RSS 1.0 (RDF) and Atom feeds are read too.
 *
 * The XML reader is a small tolerant one (pure JVM, no external entities or DTDs are ever
 * loaded): broken markup, unknown entities and unclosed tags do not stop the parse.
 */
object RssParser {

    /** Attribute holding the title of the feed channel an entry came from. */
    const val CHANNEL_ATTRIBUTE = "rss-channel"

    private val FEED_TYPES = listOf("rss", "atom", "xml", "rdf")
    private val FEED_EXTENSIONS = setOf("rss", "xml", "atom", "rdf")
    private val WEB_PAGE_EXTENSIONS = setOf("html", "htm", "shtml", "xhtml", "asp", "aspx", "jsp")
    private val IMG_SRC = Regex("<img\\s[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** Parses a feed held in memory. */
    fun parse(text: String): M3uPlaylist = parse(StringReader(text))

    /** Parses a feed from [reader]. The reader is consumed but not closed. */
    fun parse(reader: Reader): M3uPlaylist {
        val entries = ArrayList<M3uEntry>()
        val header = forEachEntry(reader) { entries.add(it) }
        return M3uPlaylist(header, entries)
    }

    /**
     * Streams the entries of an RSS/Atom document to [onEntry].
     *
     * @return header attributes (`name`, `image` when present).
     * @throws NotAnM3uPlaylistException when the content is not a feed.
     */
    fun forEachEntry(reader: Reader, onEntry: (M3uEntry) -> Unit): Map<String, String> {
        val document = TolerantXml.parse(reader.readText())
        val root = document.children.firstOrNull { it.name in ROOT_NAMES }
            ?: throw NotAnM3uPlaylistException("Content is not an RSS feed")
        val header = LinkedHashMap<String, String>()
        when (root.name) {
            "feed" -> atom(root, header, onEntry)
            "channel" -> rssChannel(root, root.childrenNamed("item"), header, onEntry)
            else -> {
                val channels = root.childrenNamed("channel")
                // RSS 1.0 keeps the items next to the channel instead of inside it.
                val looseItems = root.childrenNamed("item")
                if (channels.isEmpty()) {
                    rssChannel(null, looseItems, header, onEntry)
                } else {
                    channels.forEachIndexed { i, channel ->
                        val items = channel.childrenNamed("item") + if (i == 0) looseItems else emptyList()
                        rssChannel(channel, items, header, onEntry)
                    }
                }
            }
        }
        return header
    }

    /** Whether [head] (the start of a document) is an RSS, RDF or Atom feed. */
    fun looksLikeFeed(head: String): Boolean = TolerantXml.firstElementName(head) in ROOT_NAMES

    private val ROOT_NAMES = setOf("rss", "rdf:rdf", "feed", "channel")

    private fun rssChannel(
        channel: XmlNode?,
        items: List<XmlNode>,
        header: MutableMap<String, String>,
        onEntry: (M3uEntry) -> Unit,
    ) {
        val channelTitle = channel?.childText("title")?.let(M3uParser::stripFormatting)?.ifEmpty { null }
        if (channelTitle != null && "name" !in header) header["name"] = channelTitle
        channel?.let(::logoOf)?.let { if ("image" !in header) header["image"] = it }
        for (item in items) rssItem(item, channelTitle)?.let(onEntry)
    }

    private fun rssItem(item: XmlNode, channelTitle: String?): M3uEntry? {
        val media = mediaOf(item)
        val link = media?.first ?: item.childrenNamed("link").firstNotNullOfOrNull { it.attr("href") ?: it.text() }
            ?.takeUnless(::looksLikeWebPage)
            ?: return null
        val category = item.childrenNamed("category").firstNotNullOfOrNull { it.text() ?: it.attr("term") }
        val duration = (item.childText("itunes:duration") ?: media?.third)?.let(::parseDuration)
        return entry(item.childText("title"), link, media?.second, logoOf(item), category, duration, channelTitle)
    }

    private fun atom(feed: XmlNode, header: MutableMap<String, String>, onEntry: (M3uEntry) -> Unit) {
        val feedTitle = feed.childText("title")?.let(M3uParser::stripFormatting)?.ifEmpty { null }
        feedTitle?.let { header["name"] = it }
        (feed.childText("logo") ?: feed.childText("icon") ?: logoOf(feed))?.let { header["image"] = it }
        for (node in feed.childrenNamed("entry")) {
            val links = node.childrenNamed("link")
            val enclosure = links.firstOrNull { it.attr("rel").equals("enclosure", ignoreCase = true) && it.attr("href") != null }
            val content = node.child("content")?.takeIf { it.attr("src") != null }
            val media = mediaOf(node)
            val (url, type) = when {
                enclosure != null -> enclosure.attr("href")!! to enclosure.attr("type")
                content != null -> content.attr("src")!! to content.attr("type")
                media != null -> media.first to media.second
                else -> {
                    val link = links.firstOrNull { (it.attr("rel") ?: "alternate").equals("alternate", ignoreCase = true) } ?: links.firstOrNull()
                    val href = link?.attr("href")?.takeUnless(::looksLikeWebPage) ?: continue
                    href to link.attr("type")
                }
            }
            val category = node.childrenNamed("category").firstNotNullOfOrNull { it.attr("label") ?: it.attr("term") ?: it.text() }
            entry(node.childText("title"), url, type, logoOf(node), category, media?.third?.let(::parseDuration), feedTitle)?.let(onEntry)
        }
    }

    /** First media `(url, type, duration)` of an item: `<enclosure>`, `<media:content>` or a `<media:group>`. */
    private fun mediaOf(item: XmlNode): Triple<String, String?, String?>? {
        for (child in item.children) {
            when (child.name) {
                "enclosure" -> child.attr("url")?.let { return Triple(it, child.attr("type"), null) }
                "media:content" -> child.attr("url")?.let { return Triple(it, child.attr("type"), child.attr("duration")) }
                "media:group" -> child.childrenNamed("media:content").firstOrNull { it.attr("url") != null }?.let {
                    return Triple(it.attr("url")!!, it.attr("type"), it.attr("duration"))
                }
            }
        }
        return null
    }

    private fun logoOf(node: XmlNode): String? {
        node.child("media:thumbnail")?.attr("url")?.let { return it }
        node.child("media:group")?.child("media:thumbnail")?.attr("url")?.let { return it }
        node.child("media:content")?.child("media:thumbnail")?.attr("url")?.let { return it }
        node.child("itunes:image")?.let { (it.attr("href") ?: it.attr("url") ?: it.text())?.let { url -> return url } }
        node.child("image")?.let { image ->
            (image.attr("url") ?: image.attr("href") ?: image.childText("url") ?: image.text()?.takeIf { "://" in it })?.let { return it }
        }
        node.child("media:content")?.takeIf { it.attr("medium") == "image" || it.attr("type")?.startsWith("image/") == true }
            ?.attr("url")?.let { return it }
        for (name in listOf("description", "content:encoded", "summary", "content")) {
            val description = node.child(name) ?: continue
            description.find("img")?.attr("src")?.let { return it }
            description.text()?.let { html -> IMG_SRC.find(html)?.groupValues?.get(1)?.let { return TolerantXml.decode(it) } }
        }
        return null
    }

    private fun entry(
        rawTitle: String?,
        rawUrl: String,
        type: String?,
        logo: String?,
        group: String?,
        duration: Long?,
        channelTitle: String?,
    ): M3uEntry? {
        val (url, headers) = M3uParser.splitPipeHeaders(rawUrl.trim())
        if (url.isEmpty()) return null
        val title = rawTitle?.let(M3uParser::stripFormatting)?.ifEmpty { null } ?: M3uParser.titleFromUrl(url)
        val attributes = LinkedHashMap<String, String>()
        if (isFeed(url, type)) attributes["type"] = "playlist"
        channelTitle?.let { attributes[CHANNEL_ATTRIBUTE] = it }
        val episode = M3uParser.detectEpisode(title)
        return M3uEntry(
            title = title,
            url = url,
            durationSeconds = duration?.takeIf { it > 0 },
            logo = logo?.trim()?.ifEmpty { null },
            group = group?.let(M3uParser::stripFormatting)?.ifEmpty { null },
            headers = headers,
            attributes = attributes.ifEmpty { emptyMap() },
            season = episode?.first,
            episode = episode?.second,
        )
    }

    /** Whether an item points at another feed (or list) rather than at a stream. */
    private fun isFeed(url: String, type: String?): Boolean {
        val t = type?.lowercase()?.trim().orEmpty()
        if (t.isNotEmpty() && !t.startsWith("video") && !t.startsWith("audio") && FEED_TYPES.any { it in t }) return true
        val ext = PlaylistFiles.extensionOf(url)
        return ext in FEED_EXTENSIONS
    }

    private fun looksLikeWebPage(url: String): Boolean {
        val bare = url.trim().substringBefore('|').substringBefore('#').substringBefore('?')
        if (bare.isEmpty()) return true
        // A bare site address ("https://example.com/") is a home page, not a stream.
        if ("://" in bare && bare.substringAfter("://").substringAfter('/', "").isEmpty()) return true
        return PlaylistFiles.extensionOf(bare) in WEB_PAGE_EXTENSIONS
    }

    /** `3600`, `59:30` or `1:02:03` in seconds. */
    private fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0L
        for (part in parts) total = total * 60 + (part.trim().substringBefore('.').toLongOrNull() ?: return null)
        return total
    }
}

/** An element read by [TolerantXml]. Names and attribute names are lower-cased, prefixes kept. */
internal class XmlNode(val name: String, val attributes: Map<String, String>) {
    val children = ArrayList<XmlNode>(4)
    val content = StringBuilder()

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }
    fun attr(name: String): String? = attributes[name]?.trim()?.takeIf { it.isNotEmpty() }
    fun text(): String? = content.toString().trim().takeIf { it.isNotEmpty() }
    fun childText(name: String): String? = child(name)?.text()

    /** First descendant called [name] (depth first). */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }
}

/**
 * A small forgiving XML reader: builds an element tree, decodes the predefined and numeric
 * entities, keeps CDATA as text, skips comments, processing instructions and DOCTYPEs (no
 * external content is ever fetched). Mismatched closing tags are ignored and unclosed ones are
 * closed by their parent's closing tag.
 */
internal object TolerantXml {

    fun parse(s: String): XmlNode {
        val document = XmlNode("#document", emptyMap())
        val stack = arrayListOf(document)
        val n = s.length
        var i = 0
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) {
                appendText(stack.last(), s, i, n)
                break
            }
            if (lt > i) appendText(stack.last(), s, i, lt)
            when {
                s.startsWith("<!--", lt) -> i = s.indexOf("-->", lt + 4).let { if (it < 0) n else it + 3 }
                s.startsWith("<![CDATA[", lt) -> {
                    val end = s.indexOf("]]>", lt + 9)
                    stack.last().content.append(s, lt + 9, if (end < 0) n else end)
                    i = if (end < 0) n else end + 3
                }
                s.startsWith("<?", lt) -> i = s.indexOf("?>", lt + 2).let { if (it < 0) n else it + 2 }
                s.startsWith("<!", lt) -> i = skipDeclaration(s, lt)
                s.startsWith("</", lt) -> {
                    val end = s.indexOf('>', lt)
                    if (end < 0) break
                    val name = s.substring(lt + 2, end).trim().lowercase()
                    val at = stack.indexOfLast { it.name == name }
                    if (at > 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                    i = end + 1
                }
                else -> i = openTag(s, lt, stack)
            }
        }
        return document
    }

    /** Name of the first element of [head], lower-cased, or null. */
    fun firstElementName(head: String): String? {
        val s = head.trimStart('\uFEFF')
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                s.startsWith("<!--", i) -> i = s.indexOf("-->", i + 4).let { if (it < 0) return null else it + 3 }
                s.startsWith("<?", i) -> i = s.indexOf("?>", i + 2).let { if (it < 0) return null else it + 2 }
                s.startsWith("<!", i) -> i = skipDeclaration(s, i)
                c == '<' -> {
                    var end = i + 1
                    while (end < n && isNameChar(s[end])) end++
                    return s.substring(i + 1, end).lowercase().ifEmpty { null }
                }
                else -> return null
            }
        }
        return null
    }

    private fun skipDeclaration(s: String, from: Int): Int {
        var depth = 0
        var i = from + 2
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return s.length
    }

    private fun openTag(s: String, lt: Int, stack: MutableList<XmlNode>): Int {
        val n = s.length
        var i = lt + 1
        while (i < n && isNameChar(s[i])) i++
        if (i == lt + 1 || !(s[lt + 1].isLetter() || s[lt + 1] == '_')) {
            stack.last().content.append('<')
            return lt + 1
        }
        val name = s.substring(lt + 1, i).lowercase()
        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            val c = s[i]
            when {
                c == '>' -> { i++; break }
                c == '/' && i + 1 < n && s[i + 1] == '>' -> { selfClosing = true; i += 2; break }
                c.isWhitespace() || c == '/' -> i++
                else -> {
                    val start = i
                    while (i < n && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
                    val key = s.substring(start, i).lowercase()
                    if (i == start) { i++; continue }
                    var j = i
                    while (j < n && s[j].isWhitespace()) j++
                    if (j < n && s[j] == '=') {
                        j++
                        while (j < n && s[j].isWhitespace()) j++
                        val value: String
                        if (j < n && (s[j] == '"' || s[j] == '\'')) {
                            val close = s.indexOf(s[j], j + 1).let { if (it < 0) n else it }
                            value = s.substring(j + 1, close)
                            i = (close + 1).coerceAtMost(n)
                        } else {
                            val vs = j
                            while (j < n && !s[j].isWhitespace() && s[j] != '>') j++
                            value = s.substring(vs, j)
                            i = j
                        }
                        attributes.putIfAbsent(key, decode(value))
                    } else {
                        attributes.putIfAbsent(key, "")
                    }
                }
            }
        }
        val node = XmlNode(name, attributes)
        stack.last().children.add(node)
        if (!selfClosing) stack.add(node)
        return i
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == ':' || c == '_' || c == '-' || c == '.'

    private fun appendText(node: XmlNode, s: String, from: Int, to: Int) {
        val text = s.substring(from, to)
        if (node.content.isEmpty() && text.isBlank()) return
        node.content.append(decode(text))
    }

    /** Decodes `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;` and numeric references. */
    fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi > i + 1 && semi - i <= 10) {
                    val name = s.substring(i + 1, semi)
                    val decoded: String? = when {
                        name == "amp" -> "&"
                        name == "lt" -> "<"
                        name == "gt" -> ">"
                        name == "quot" -> "\""
                        name == "apos" -> "'"
                        name == "nbsp" -> " "
                        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else null
}
