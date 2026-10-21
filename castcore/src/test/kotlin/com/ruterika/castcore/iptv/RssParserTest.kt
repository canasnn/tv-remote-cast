package com.ruterika.castcore.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files

class RssParserTest {

    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE rss [ <!ENTITY x "ignored"> ]>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Demo TV</title>
    <link>https://example.com/</link>
    <image><url>http://host/logo.png</url><title>Demo</title></image>
    <!-- a comment <item> that is not an item -->
    <item>
      <title>News &amp; Weather</title>
      <category>News</category>
      <enclosure url="http://host/news.m3u8" type="application/x-mpegURL" length="0"/>
      <media:thumbnail url="http://host/news.png"/>
    </item>
    <item>
      <title><![CDATA[Sport <HD>]]></title>
      <category>Sport</category>
      <media:content url="http://host/sport.ts" type="video/mp2t" duration="0"/>
      <itunes:image href="http://host/sport.png"/>
    </item>
    <item>
      <title>More channels</title>
      <enclosure url="http://host/more.php?list=2" type="application/rss+xml"/>
    </item>
    <item>
      <title>Old list</title>
      <link>http://host/old.m3u</link>
    </item>
    <item>
      <title>Episode S01E02</title>
      <link>http://host/vod/ep2.mp4</link>
      <description>&lt;p&gt;&lt;img src="http://host/ep2.jpg" /&gt;Text&lt;/p&gt;</description>
      <itunes:duration>1:02:03</itunes:duration>
    </item>
    <item>
      <title>Just an article</title>
      <link>https://example.com/news/article.html</link>
    </item>
    <item><title>Nothing to play</title></item>
  </channel>
</rss>"""

    @Test
    fun readsRssItems() {
        val list = RssParser.parse(feed)
        assertEquals("Demo TV", list.headerAttributes["name"])
        assertEquals("http://host/logo.png", list.headerAttributes["image"])
        assertEquals(listOf("News & Weather", "Sport <HD>", "More channels", "Old list", "Episode S01E02"), list.entries.map { it.title })

        val news = list.entries[0]
        assertEquals("http://host/news.m3u8", news.url)
        assertEquals("http://host/news.png", news.logo)
        assertEquals("News", news.group)
        assertEquals("Demo TV", news.attributes[RssParser.CHANNEL_ATTRIBUTE])
        assertFalse(news.isNestedList)

        val sport = list.entries[1]
        assertEquals("http://host/sport.ts", sport.url)
        assertEquals("http://host/sport.png", sport.logo)
        assertNull(sport.durationSeconds)

        assertTrue(list.entries[2].isNestedList)
        assertTrue(list.entries[3].isNestedList)

        val episode = list.entries[4]
        assertEquals("http://host/ep2.jpg", episode.logo)
        assertEquals(3723L, episode.durationSeconds)
        assertEquals(1, episode.season)
        assertEquals(2, episode.episode)
        assertFalse(episode.isNestedList)
    }

    @Test
    fun linksToOtherFeedsAreSubLists() {
        val list = RssParser.parse(
            """<rss><channel><title>Root</title>
              <item><title>Sub</title><link>http://host/sub.xml</link></item>
              <item><title>Sub 2</title><link>http://host/sub.rss</link></item>
              <item><title>Movie</title><enclosure url="http://host/m.mp4" type="video/mp4"/></item>
            </channel></rss>"""
        )
        assertEquals(listOf(true, true, false), list.entries.map { it.isNestedList })
    }

    @Test
    fun toleratesBrokenMarkup() {
        val list = RssParser.parse(
            "﻿<rss><channel><title>T &unknown; &#252;</title><item><title>A<br></title>" +
                "<description><img src=http://h/a.png></description><link>http://h/a.ts</link></item>" +
                "<item><title>B</title><link>http://h/b.ts</link></channel>"
        )
        assertEquals("T &unknown; ü", list.headerAttributes["name"])
        assertEquals(listOf("A", "B"), list.entries.map { it.title })
        assertEquals("http://h/a.png", list.entries[0].logo)
    }

    @Test
    fun readsAtomAndRdf() {
        val atom = RssParser.parse(
            """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Atom TV</title>
              <entry><title>One</title><category term="Music"/><link rel="alternate" href="http://h/page.html"/>
                <link rel="enclosure" type="video/mp4" href="http://h/one.mp4"/></entry>
              <entry><title>Page only</title><link href="http://h/page.html"/></entry>
            </feed>"""
        )
        assertEquals("Atom TV", atom.headerAttributes["name"])
        assertEquals(1, atom.entries.size)
        assertEquals("http://h/one.mp4", atom.entries[0].url)
        assertEquals("Music", atom.entries[0].group)

        val rdf = RssParser.parse(
            """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><channel><title>RDF</title></channel>
              <item><title>X</title><link>http://h/x.m3u8</link></item></rdf:RDF>"""
        )
        assertEquals(listOf("X"), rdf.entries.map { it.title })
        assertEquals("RDF", rdf.entries[0].attributes[RssParser.CHANNEL_ATTRIBUTE])
    }

    @Test
    fun detectsFeedsByContent() {
        assertEquals(PlaylistFormat.RSS, PlaylistFiles.detectFormat(feed))
        assertEquals(PlaylistFormat.RSS, PlaylistFiles.detectFormat("<rss><channel/></rss>", nameHint = "http://h/get.php"))
        assertEquals(PlaylistFormat.RSS, PlaylistFiles.detectFormat("<?xml version=\"1.0\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\"/>", lenient = true))
        for (other in listOf("<?xml version=\"1.0\"?><tv><channel id=\"a\"/></tv>", "<!DOCTYPE html><html><body>Error</body></html>")) {
            try {
                PlaylistFiles.detectFormat(other, nameHint = "http://h/list.xml")
                fail(other)
            } catch (e: NotAnM3uPlaylistException) {
                assertTrue(e.message!!.contains("HTML/XML"))
            }
        }
        val entries = ArrayList<M3uEntry>()
        PlaylistFiles.parse(BufferedReader(StringReader(feed)), onEntry = entries::add)
        assertEquals(5, entries.size)
    }

    @Test
    fun keepRootChannelAddsAFolder() = runBlocking {
        val dir = Files.createTempDirectory("rssidx").toFile()
        try {
            suspend fun build(keep: Boolean): IptvIndex {
                val options = IptvIndexOptions(keepRssRootChannel = keep)
                return IptvIndex.build(java.io.File(dir, "${options.tag}.idx"), java.io.File(dir, "${options.tag}.dat"), options) { sink ->
                    RssParser.forEachEntry(StringReader(feed), sink)
                }
            }
            val flat = build(keep = false)
            assertEquals(listOf("News", "Sport"), (0 until flat.root.groupCount).map { flat.node(IptvIndex.groupId(flat.level(0)[it])).name })
            assertEquals(3, flat.root.channelCount)
            val kept = build(keep = true)
            assertEquals(1, kept.root.groupCount)
            assertEquals(0, kept.root.channelCount)
            val channel = kept.findNode(listOf("Demo TV"))!!
            assertEquals(5, kept.node(channel).totalChannels)
            assertEquals(channel, kept.findNode(listOf("Demo TV", "News"))?.let { kept.node(it).parent })
            assertEquals("so", IptvIndexOptions().tag)
            assertEquals("sor", IptvIndexOptions(keepRssRootChannel = true).tag)
            flat.close()
            kept.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
