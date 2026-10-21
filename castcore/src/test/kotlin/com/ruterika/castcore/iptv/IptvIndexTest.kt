package com.ruterika.castcore.iptv

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IptvIndexTest {

    private val dir: File = Files.createTempDirectory("iptvindex").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val sample = """
        #EXTM3U url-tvg="http://epg/guide.xml"
        #EXTINF:-1 tvg-name="TRT 1 HD" group-title="Ulusal",TRT 1
        http://s/trt1.m3u8
        #EXTINF:-1 group-title="Filmler" pltv-subgroup="Aksiyon",Zeta Film
        http://s/zeta.mp4
        #EXTINF:-1 group-title="Filmler" pltv-subgroup="Aksiyon",Alfa Film
        http://s/alfa.mp4
        #EXTINF:-1 group-title="Diziler",Dizi S01E02
        http://s/d12.mp4
        #EXTINF:-1 group-title="Diziler",Dizi S01E01
        http://s/d11.mp4
        #EXTINF:-1 group-title="Diziler",Dizi S02E01
        http://s/d21.mp4
        #EXTINF:-1,Serbest Kanal
        http://s/free.ts
        #EXTINF:-1 type="playlist" group-title="Diğer",Alt Liste
        sub/list.m3u
    """.trimIndent()

    private fun build(text: String = sample, options: IptvIndexOptions = IptvIndexOptions(), baseUrl: String? = "http://host/lists/main.m3u"): IptvIndex =
        runBlocking {
            IptvIndex.build(File(dir, "a.idx"), File(dir, "a.dat"), options, baseUrl) { sink ->
                PlaylistFiles.parse(PlaylistFiles.openReader(text.byteInputStream()), onEntry = sink)
            }
        }

    private fun IptvIndex.titles(rows: IntArray) = rows.map { if (IptvIndex.isGroupRow(it)) "[${node(IptvIndex.groupId(it)).name}]" else entry(it).title }

    @Test
    fun buildsGroupTreeWithSubgroupsAndRootChannels() {
        val index = build()
        assertEquals(8, index.size)
        assertEquals("http://epg/guide.xml", index.headerAttributes["url-tvg"])
        assertEquals(listOf("[Ulusal]", "[Filmler]", "[Diziler]", "[Diğer]", "Serbest Kanal"), index.titles(index.level(0)))
        val filmler = index.findNode(listOf("Filmler"))!!
        assertEquals(listOf("[Aksiyon]"), index.titles(index.level(filmler)))
        assertEquals(2, index.node(filmler).totalChannels)
        val aksiyon = index.findNode(listOf("Filmler", "Aksiyon"))!!
        assertEquals(aksiyon, index.descendSingleGroups(filmler))
        assertEquals(listOf("Filmler", "Aksiyon"), index.pathNames(aksiyon))
        assertEquals(listOf("Zeta Film", "Alfa Film"), index.titles(index.level(aksiyon)))
        assertEquals(8, index.root.totalChannels)
        assertNull(index.findNode(listOf("Yok")))
    }

    @Test
    fun ignoresSubgroupsWhenDisabledAndRenamesWithTvgName() {
        val index = build(options = IptvIndexOptions(considerSubgroups = false, renameWithTvgName = true))
        val filmler = index.findNode(listOf("Filmler"))!!
        assertEquals(listOf("Zeta Film", "Alfa Film"), index.titles(index.level(filmler)))
        val ulusal = index.findNode(listOf("Ulusal"))!!
        assertEquals(listOf("TRT 1 HD"), index.titles(index.level(ulusal)))
    }

    @Test
    fun resolvesRelativeUrlsAndDetectsNestedLists() {
        val index = build()
        val diger = index.findNode(listOf("Diğer"))!!
        val sub = index.entry(index.level(diger).single())
        assertEquals("http://host/lists/sub/list.m3u", sub.url)
        assertTrue(sub.isNestedList)
        assertFalse(index.entry(0).isNestedList)
    }

    @Test
    fun sortsByNameEpisodeAndMixes() {
        val index = build()
        val diziler = index.findNode(listOf("Diziler"))!!
        assertEquals(
            listOf("Dizi S01E01", "Dizi S01E02", "Dizi S02E01"),
            index.titles(index.level(diziler, IptvSort(IptvSortBy.EPISODE)))
        )
        assertEquals(
            listOf("Dizi S02E01", "Dizi S01E02", "Dizi S01E01"),
            index.titles(index.level(diziler, IptvSort(IptvSortBy.NAME, ascending = false)))
        )
        assertEquals(
            listOf("[Diğer]", "[Diziler]", "[Filmler]", "[Ulusal]", "Serbest Kanal"),
            index.titles(index.level(0, IptvSort(IptvSortBy.NAME)))
        )
        assertEquals(
            listOf("[Diğer]", "[Diziler]", "[Filmler]", "Serbest Kanal", "[Ulusal]"),
            index.titles(index.level(0, IptvSort(IptvSortBy.NAME, mixGroups = true)))
        )
        assertEquals(
            listOf("[Diğer]", "Serbest Kanal", "[Diziler]", "[Filmler]", "[Ulusal]"),
            index.titles(index.level(0, IptvSort(IptvSortBy.UNSORTED, ascending = false, mixGroups = true)))
        )
    }

    @Test
    fun searchCoversSubgroupsAndTvgName() {
        val index = build()
        val filmler = index.findNode(listOf("Filmler"))!!
        assertEquals(listOf("Zeta Film", "Alfa Film"), index.titles(index.search(filmler, "film")))
        assertEquals(listOf("Alfa Film", "Zeta Film"), index.titles(index.search(0, "FİLM", IptvSort(IptvSortBy.NAME))))
        assertEquals(listOf("TRT 1"), index.titles(index.search(0, "hd")))
        assertTrue(index.search(filmler, "trt").isEmpty())
        assertArrayEquals(intArrayOf(1, 2), index.channelsUnder(filmler))
    }

    @Test
    fun reopensFromDiskAndRejectsOtherOptions() {
        build().close()
        val options = IptvIndexOptions()
        val reopened = IptvIndex.open(File(dir, "a.idx"), File(dir, "a.dat"), options)
        assertNotNull(reopened)
        reopened!!
        assertEquals(8, reopened.size)
        assertEquals("Alfa Film", reopened.entry(2).title)
        assertEquals("Filmler", reopened.entry(2).group)
        assertEquals(1, reopened.entry(4).season)
        assertEquals(2, reopened.node(reopened.findNode(listOf("Filmler"))!!).totalChannels)
        assertNull(IptvIndex.open(File(dir, "a.idx"), File(dir, "a.dat"), options.copy(renameWithTvgName = true)))
        assertNull(IptvIndex.open(File(dir, "missing.idx"), File(dir, "a.dat"), options))
    }

    @Test
    fun handlesLargeLists() {
        val big = buildString {
            append("#EXTM3U\n")
            repeat(120_000) { append("#EXTINF:-1 group-title=\"G${it % 40}\",Kanal $it\nhttp://s/$it.ts\n") }
        }
        val index = build(big, baseUrl = null)
        assertEquals(120_000, index.size)
        assertEquals(40, index.level(0).size)
        val g7 = index.findNode(listOf("G7"))!!
        assertEquals(3000, index.level(g7).size)
        assertEquals("http://s/119999.ts", index.entry(119_999).url)
        assertEquals(listOf("Kanal 119999"), index.titles(index.search(0, "kanal 119999")))
    }

    @Test
    fun indexesW3uLists() {
        val w3u = """
            { "name": "Liste", "groups": [
                { "name": "Spor", "stations": [ { "name": "Spor 1", "url": "http://s/spor1.m3u8", "image": "http://l/s1.png" } ],
                  "groups": [ { "name": "Futbol", "stations": [ { "name": "Maç", "url": "http://s/mac.ts", "referer": "http://r/" } ] } ] },
                { "name": "Daha", "url": "http://x/more.w3u" } ],
              "stations": [ { "name": "Kök", "url": "http://s/kok.ts" }, { "name": "Boş", "url": "" } ] }
        """.trimIndent()
        val index = build(w3u, baseUrl = null)
        assertEquals(4, index.size)
        assertEquals("Liste", index.headerAttributes["name"])
        val futbol = index.findNode(listOf("Spor", "Futbol"))!!
        val mac = index.entry(index.level(futbol).single())
        assertEquals("Maç", mac.title)
        assertEquals("http://r/", mac.headers["Referer"])
        val more = index.entry(index.level(0).last { it >= 0 && index.entry(it).title == "Daha" })
        assertTrue(more.isNestedList)
    }
}
