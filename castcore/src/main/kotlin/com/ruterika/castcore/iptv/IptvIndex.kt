package com.ruterika.castcore.iptv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI

/** Options baked into an [IptvIndex]; when one changes the index has to be rebuilt. */
data class IptvIndexOptions(
    /** Use `pltv-subgroup` as a second folder level. */
    val considerSubgroups: Boolean = true,
    /** Show `tvg-name` instead of the `#EXTINF` title when it is set. */
    val renameWithTvgName: Boolean = false,
    /** RSS lists: show the feed's own `<channel>` as a folder holding its items. */
    val keepRssRootChannel: Boolean = false,
) {
    /** Short tag used in cache file names (unchanged for the old options, so existing caches stay valid). */
    val tag: String get() = (if (considerSubgroups) "s" else "n") + (if (renameWithTvgName) "t" else "o") + (if (keepRssRootChannel) "r" else "")
}

/** A folder (group) of an indexed list. Node 0 is the root of the list. */
class IptvNode internal constructor(
    val id: Int,
    val name: String,
    /** Parent node id, -1 for the root. */
    val parent: Int,
    /** Source position of the first channel seen in this group (for unsorted mixing). */
    internal val firstPosition: Int,
) {
    internal val children = IntList()
    internal val channels = IntList()

    /** Channels in this group and all of its subgroups. */
    var totalChannels: Int = 0
        internal set

    /** Number of direct subgroups. */
    val groupCount: Int get() = children.size

    /** Number of channels directly in this group. */
    val channelCount: Int get() = channels.size
}

internal class IntList(capacity: Int = 4) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = value
    }

    operator fun get(i: Int): Int = data[i]
    fun toIntArray(): IntArray = data.copyOf(size)
}

private class LongList(capacity: Int = 1024) {
    private var data = LongArray(capacity)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toLongArray(): LongArray = data.copyOf(size)
}

/**
 * A parsed IPTV list stored on disk so that lists with hundreds of thousands of channels stay
 * cheap: entries live in a data file and are read on demand (with a small cache), while only
 * compact arrays (offsets, folded search keys, season/episode, group of each entry) and the
 * group tree are kept in memory.
 *
 * Rows returned by [level] and [search] are plain ints: a channel row is the entry index
 * (`>= 0`), a group row is `-(nodeId + 1)` (see [isGroupRow], [groupId]).
 */
class IptvIndex private constructor(
    private val dataFile: File,
    val options: IptvIndexOptions,
    val headerAttributes: Map<String, String>,
    private val offsets: LongArray,
    private val keys: Array<String>,
    private val showInfo: IntArray,
    private val entryNode: IntArray,
    private val nodes: Array<IptvNode>,
) : Closeable {

    /** Number of entries (channels and sub-list links). */
    val size: Int get() = offsets.size

    val root: IptvNode get() = nodes[0]

    fun node(id: Int): IptvNode = nodes[id]

    fun isValidNode(id: Int): Boolean = id in nodes.indices

    /** Group node of entry [index]. */
    fun nodeOf(index: Int): Int = entryNode[index]

    private var reader: RandomAccessFile? = null
    private val cache = object : LinkedHashMap<Int, M3uEntry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, M3uEntry>?): Boolean = size > CACHE_SIZE
    }
    @Volatile
    private var groupKeys: Array<String>? = null

    /** Reads entry [index] from disk (cached). Returns a placeholder if the file is gone. */
    @Synchronized
    fun entry(index: Int): M3uEntry {
        cache[index]?.let { return it }
        val entry = try {
            val file = reader ?: RandomAccessFile(dataFile, "r").also { reader = it }
            file.seek(offsets[index])
            val length = file.readInt()
            val bytes = ByteArray(length)
            file.readFully(bytes)
            decode(DataInputStream(ByteArrayInputStream(bytes)))
        } catch (e: IOException) {
            return M3uEntry(title = "N/A", url = "")
        }
        cache[index] = entry
        return entry
    }

    /** Reads several entries. */
    fun entries(indices: IntArray): List<M3uEntry> = indices.map(::entry)

    /** Names from the root down to [nodeId] (empty for the root). */
    fun pathNames(nodeId: Int): List<String> {
        val names = ArrayList<String>()
        var id = nodeId
        while (id > 0) {
            names.add(nodes[id].name)
            id = nodes[id].parent
        }
        return names.asReversed()
    }

    /** Node at [path] (names from the root), or null when a level no longer exists. */
    fun findNode(path: List<String>): Int? {
        var id = 0
        for (name in path) {
            val node = nodes[id]
            var next = -1
            for (i in 0 until node.children.size) {
                if (nodes[node.children[i]].name == name) {
                    next = node.children[i]
                    break
                }
            }
            if (next < 0) return null
            id = next
        }
        return id
    }

    /** Follows levels that hold exactly one group and no channels ("skip single groups"). */
    fun descendSingleGroups(nodeId: Int): Int {
        var id = nodeId
        while (nodes[id].channels.size == 0 && nodes[id].children.size == 1) id = nodes[id].children[0]
        return id
    }

    /** Rows of one level: its groups and channels, arranged by [sort]. */
    fun level(nodeId: Int, sort: IptvSort = IptvSort()): IntArray {
        val node = nodes[nodeId]
        return arrange(node.children.toIntArray(), node.channels.toIntArray(), sort)
    }

    /**
     * Channels under [nodeId] (the group and all of its subgroups) whose title or `tvg-name`
     * contains every word of [query] (case and diacritics are ignored). A blank query returns
     * [level].
     */
    fun search(nodeId: Int, query: String, sort: IptvSort = IptvSort()): IntArray {
        val tokens = IptvGroups.fold(query).split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return level(nodeId, sort)
        val scope = subtreeMask(nodeId)
        val out = IntList(64)
        for (i in keys.indices) {
            if (!scope[entryNode[i]]) continue
            val key = keys[i]
            if (tokens.all { key.contains(it) }) out.add(i)
        }
        return sortChannels(out.toIntArray(), sort)
    }

    /** Every channel under [nodeId] in source order. */
    fun channelsUnder(nodeId: Int): IntArray {
        if (nodeId == 0) return IntArray(size) { it }
        val scope = subtreeMask(nodeId)
        val out = IntList(nodes[nodeId].totalChannels)
        for (i in entryNode.indices) if (scope[entryNode[i]]) out.add(i)
        return out.toIntArray()
    }

    override fun close() {
        synchronized(this) {
            runCatching { reader?.close() }
            reader = null
            cache.clear()
        }
    }

    private fun subtreeMask(nodeId: Int): BooleanArray {
        val mask = BooleanArray(nodes.size)
        // Children always have larger ids than their parent.
        for (id in nodeId until nodes.size) {
            mask[id] = id == nodeId || (nodes[id].parent >= 0 && mask[nodes[id].parent])
        }
        return mask
    }

    private fun groupKey(id: Int): String {
        val existing = groupKeys ?: Array(nodes.size) { IptvGroups.fold(nodes[it].name) }.also { groupKeys = it }
        return existing[id]
    }

    private fun arrange(groups: IntArray, channels: IntArray, sort: IptvSort): IntArray {
        if (!sort.mixGroups || sort.by == IptvSortBy.EPISODE || groups.isEmpty() || channels.isEmpty()) {
            val groupRows = sortGroups(groups, sort).map { groupRow(it) }.toIntArray()
            return groupRows + sortChannels(channels, sort)
        }
        val rows = (groups.map { groupRow(it) } + channels.toList()).toTypedArray()
        val comparator: Comparator<Int> = when (sort.by) {
            IptvSortBy.NAME -> Comparator<Int> { a, b -> rowKey(a).compareTo(rowKey(b)) }.thenBy { rowPosition(it) }
            else -> compareBy { rowPosition(it) }
        }
        rows.sortWith(if (sort.ascending) comparator else comparator.reversed())
        return rows.toIntArray()
    }

    private fun rowKey(row: Int): String = if (row < 0) groupKey(groupId(row)) else keys[row]

    /** Groups sort just before their first channel. */
    private fun rowPosition(row: Int): Long =
        if (row < 0) nodes[groupId(row)].firstPosition * 2L else row * 2L + 1

    private fun sortGroups(groups: IntArray, sort: IptvSort): IntArray {
        val sorted = when (sort.by) {
            IptvSortBy.UNSORTED -> groups
            else -> groups.sortedWith(compareBy<Int> { groupKey(it) }.thenBy { it }).toIntArray()
        }
        return if (sort.ascending) sorted else sorted.reversedArray()
    }

    private fun sortChannels(channels: IntArray, sort: IptvSort): IntArray {
        val sorted = when (sort.by) {
            IptvSortBy.UNSORTED -> channels
            IptvSortBy.NAME -> channels.sortedWith(compareBy<Int> { keys[it] }.thenBy { it }).toIntArray()
            IptvSortBy.EPISODE -> channels.sortedWith(
                compareBy<Int> { showInfo[it].let { v -> if (v < 0) Int.MAX_VALUE else v } }.thenBy { keys[it] }.thenBy { it }
            ).toIntArray()
        }
        return if (sort.ascending) sorted else sorted.reversedArray()
    }

    /** Collects entries while they are written to the data file. */
    private class Builder(private val options: IptvIndexOptions, private val baseUrl: String?) {
        val offsets = LongList()
        val keys = ArrayList<String>()
        val showInfo = IntList(1024)
        val entryNode = IntList(1024)
        val nodes = arrayListOf(IptvNode(0, "", -1, 0))
        private val nodeIds = HashMap<String, Int>()
        private var position = 0L
        private var lastPath: List<String>? = null
        private var lastNode = 0
        private val buffer = ByteArrayOutputStream(512)
        private val bufferOut = DataOutputStream(buffer)

        val count: Int get() = keys.size

        fun add(out: DataOutputStream, raw: M3uEntry) {
            val entry = adjust(raw)
            val node = nodeFor(pathOf(entry))
            buffer.reset()
            encode(bufferOut, entry)
            bufferOut.flush()
            out.writeInt(buffer.size())
            buffer.writeTo(out)
            offsets.add(position)
            position += 4 + buffer.size()
            keys.add(searchKey(entry))
            showInfo.add(if (entry.season != null || entry.episode != null) ((entry.season ?: 0) shl 16) or ((entry.episode ?: 0) and 0xffff) else -1)
            entryNode.add(node)
            nodes[node].channels.add(keys.size - 1)
        }

        private fun pathOf(entry: M3uEntry): List<String> {
            val path = entry.groupPath(options.considerSubgroups)
            if (!options.keepRssRootChannel) return path
            val channel = entry.attributes[RssParser.CHANNEL_ATTRIBUTE]?.trim()?.takeIf { it.isNotEmpty() } ?: return path
            return listOf(channel) + path
        }

        private fun adjust(entry: M3uEntry): M3uEntry {
            var e = entry
            if (options.renameWithTvgName) {
                val name = e.tvgName?.let(M3uParser::stripFormatting)?.takeIf { it.isNotEmpty() }
                if (name != null && name != e.title) {
                    val episode = M3uParser.detectEpisode(name)
                    e = e.copy(title = name, season = episode?.first ?: e.season, episode = episode?.second ?: e.episode)
                }
            }
            if (baseUrl != null) {
                val url = resolve(e.url)
                val logo = e.logo?.let(::resolve)
                if (url != e.url || logo != e.logo) e = e.copy(url = url, logo = logo)
            }
            return e
        }

        private fun resolve(link: String): String {
            if (link.isEmpty() || "://" in link || link.startsWith("data:")) return link
            return runCatching { URI(baseUrl!!).resolve(link.replace(" ", "%20")).toString() }.getOrDefault(link)
        }

        private fun nodeFor(path: List<String>): Int {
            if (path == lastPath) return lastNode
            var id = 0
            for (name in path) {
                val key = "$id\u0001$name"
                id = nodeIds.getOrPut(key) {
                    val node = IptvNode(nodes.size, name, id, keys.size)
                    nodes[id].children.add(node.id)
                    nodes.add(node)
                    node.id
                }
            }
            lastPath = path
            lastNode = id
            return id
        }

        fun finish(dataFile: File, options: IptvIndexOptions, header: Map<String, String>): IptvIndex {
            val array = nodes.toTypedArray()
            computeTotals(array)
            return IptvIndex(
                dataFile, options, header, offsets.toLongArray(), keys.toTypedArray(),
                showInfo.toIntArray(), entryNode.toIntArray(), array,
            )
        }
    }

    companion object {
        private const val MAGIC = 0x49505458 // "IPTX"
        private const val VERSION = 1
        private const val CACHE_SIZE = 512
        private const val CANCEL_CHECK_INTERVAL = 1024
        private const val BUFFER_BYTES = 64 * 1024

        fun isGroupRow(row: Int): Boolean = row < 0
        fun groupId(row: Int): Int = -row - 1
        fun groupRow(nodeId: Int): Int = -nodeId - 1

        /**
         * Parses a list into [dataFile] + [indexFile]. [produce] streams the entries to the sink
         * it is given and returns the header attributes (see [PlaylistFiles.parse]). Relative
         * channel and logo URLs are resolved against [baseUrl]. The index file is written last
         * (atomically), so an interrupted build never leaves a usable but broken index.
         */
        suspend fun build(
            indexFile: File,
            dataFile: File,
            options: IptvIndexOptions,
            baseUrl: String? = null,
            produce: ((M3uEntry) -> Unit) -> Map<String, String>,
        ): IptvIndex {
            val context = currentCoroutineContext()
            val builder = Builder(options, baseUrl?.takeIf { it.startsWith("http", ignoreCase = true) })
            dataFile.parentFile?.mkdirs()
            val header = DataOutputStream(BufferedOutputStream(FileOutputStream(dataFile), BUFFER_BYTES)).use { out ->
                produce { entry ->
                    builder.add(out, entry)
                    if (builder.count % CANCEL_CHECK_INTERVAL == 0) context.ensureActive()
                }
            }
            context.ensureActive()
            val index = builder.finish(dataFile, options, header)
            val tmp = File(indexFile.parentFile, indexFile.name + ".part")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), BUFFER_BYTES)).use { out -> index.writeIndex(out) }
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                if (!tmp.renameTo(indexFile)) throw IOException("Could not save the channel index")
            }
            return index
        }

        /** Opens an index written by [build], or null if it is missing, damaged or built with other [options]. */
        fun open(indexFile: File, dataFile: File, options: IptvIndexOptions): IptvIndex? {
            if (!indexFile.isFile || !dataFile.isFile) return null
            return try {
                DataInputStream(BufferedInputStream(FileInputStream(indexFile), BUFFER_BYTES)).use { input ->
                    read(input, dataFile, options)
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun read(input: DataInputStream, dataFile: File, options: IptvIndexOptions): IptvIndex? {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            if (input.readUTF() != options.tag) return null
            if (input.readLong() != dataFile.length()) return null
            val header = readMap(input)
            val count = input.readInt()
            val offsets = LongArray(count) { input.readLong() }
            val keys = Array(count) { readString(input)!! }
            val showInfo = IntArray(count) { input.readInt() }
            val entryNode = IntArray(count) { input.readInt() }
            val nodeCount = input.readInt()
            val nodes = Array(nodeCount) { id ->
                val name = readString(input)!!
                val parent = input.readInt()
                IptvNode(id, name, parent, input.readInt())
            }
            for (id in 1 until nodeCount) nodes[nodes[id].parent].children.add(id)
            for (i in 0 until count) nodes[entryNode[i]].channels.add(i)
            computeTotals(nodes)
            return IptvIndex(dataFile, options, header, offsets, keys, showInfo, entryNode, nodes)
        }

        private fun computeTotals(nodes: Array<IptvNode>) {
            for (node in nodes) node.totalChannels = node.channels.size
            for (id in nodes.indices.reversed()) {
                val parent = nodes[id].parent
                if (parent >= 0) nodes[parent].totalChannels += nodes[id].totalChannels
            }
        }

        private fun searchKey(entry: M3uEntry): String {
            val title = IptvGroups.fold(entry.title)
            val name = entry.tvgName?.takeIf { it.isNotBlank() && it != entry.title } ?: return title
            return title + '\n' + IptvGroups.fold(name)
        }

        private fun encode(out: DataOutput, e: M3uEntry) {
            writeString(out, e.title)
            writeString(out, e.url)
            out.writeLong(e.durationSeconds ?: Long.MIN_VALUE)
            writeString(out, e.tvgId)
            writeString(out, e.tvgName)
            writeString(out, e.logo)
            writeString(out, e.group)
            out.writeInt(e.channelNumber ?: Int.MIN_VALUE)
            writeMap(out, e.headers)
            writeMap(out, e.attributes)
            out.writeInt(e.season ?: Int.MIN_VALUE)
            out.writeInt(e.episode ?: Int.MIN_VALUE)
            out.writeInt(e.subgroups.size)
            e.subgroups.forEach { writeString(out, it) }
        }

        private fun decode(input: DataInput): M3uEntry {
            val title = readString(input)!!
            val url = readString(input)!!
            val duration = input.readLong().takeIf { it != Long.MIN_VALUE }
            val tvgId = readString(input)
            val tvgName = readString(input)
            val logo = readString(input)
            val group = readString(input)
            val channelNumber = input.readInt().takeIf { it != Int.MIN_VALUE }
            val headers = readMap(input)
            val attributes = readMap(input)
            val season = input.readInt().takeIf { it != Int.MIN_VALUE }
            val episode = input.readInt().takeIf { it != Int.MIN_VALUE }
            val subgroups = List(input.readInt()) { readString(input)!! }
            return M3uEntry(title, url, duration, tvgId, tvgName, logo, group, channelNumber, headers, attributes, season, episode, subgroups)
        }

        private fun writeString(out: DataOutput, value: String?) {
            if (value == null) {
                out.writeInt(-1)
                return
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readString(input: DataInput): String? {
            val length = input.readInt()
            if (length < 0) return null
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun writeMap(out: DataOutput, map: Map<String, String>) {
            out.writeInt(map.size)
            map.forEach { (k, v) ->
                writeString(out, k)
                writeString(out, v)
            }
        }

        private fun readMap(input: DataInput): Map<String, String> {
            val size = input.readInt()
            if (size == 0) return emptyMap()
            val map = LinkedHashMap<String, String>(size * 2)
            repeat(size) { map[readString(input)!!] = readString(input)!! }
            return map
        }
    }

    private fun writeIndex(out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(options.tag)
        out.writeLong(dataFile.length())
        writeMap(out, headerAttributes)
        out.writeInt(size)
        offsets.forEach(out::writeLong)
        keys.forEach { writeString(out, it) }
        showInfo.forEach(out::writeInt)
        entryNode.forEach(out::writeInt)
        out.writeInt(nodes.size)
        for (node in nodes) {
            writeString(out, node.name)
            out.writeInt(node.parent)
            out.writeInt(node.firstPosition)
        }
    }
}
