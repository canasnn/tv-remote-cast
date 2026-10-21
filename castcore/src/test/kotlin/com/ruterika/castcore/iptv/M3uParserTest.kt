package com.ruterika.castcore.iptv

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3uParserTest {

    private val turkishPlaylist = """
        #EXTM3U url-tvg="http://epg.example.com/guide.xml.gz" x-tvg-url="http://epg2.example.com/a.xml,http://epg3.example.com/b.xml" tvg-shift=0
        #EXTINF:-1 tvg-id="trt1.tr" tvg-name="TRT 1 HD" tvg-logo="http://logo.example.com/trt1,hd.png" tvg-chno="1" group-title="Ulusal" tvg-language="Turkish" tvg-country="TR",TRT 1 HD
        http://stream.example.com/live/trt1/index.m3u8
        #EXTINF:-1 tvg-id='showtv.tr' tvg-name='Show TV' group-title='Ulusal',Show TV
        http://stream.example.com/live/showtv.ts
        #EXTINF:-1 tvg-id=atv.tr tvg-chno=3 group-title=Ulusal,ATV
        http://stream.example.com/live/atv.ts
        #EXTINF:-1 tvg-id="ntvspor.tr" group-title="Spor",NTV Spor, Canlı Maç Özeti
        http://stream.example.com/live/ntvspor.ts
        #EXTINF:-1 tvg-name="Çocuk Kanalı" group-title="Çocuk",Minika Çocuk
        http://stream.example.com/live/minika.ts
    """.trimIndent()

    @Test
    fun parsesHeaderAttributesAndEpgUrls() {
        val playlist = M3uParser.parse(turkishPlaylist)
        assertEquals("http://epg.example.com/guide.xml.gz", playlist.headerAttributes["url-tvg"])
        assertEquals("0", playlist.headerAttributes["tvg-shift"])
        assertEquals(
            listOf(
                "http://epg.example.com/guide.xml.gz",
                "http://epg2.example.com/a.xml",
                "http://epg3.example.com/b.xml",
            ),
            playlist.epgUrls,
        )
    }

    @Test
    fun parsesDoubleQuotedAttributesIncludingCommasInValues() {
        val entry = M3uParser.parse(turkishPlaylist).entries[0]
        assertEquals("TRT 1 HD", entry.title)
        assertEquals("http://stream.example.com/live/trt1/index.m3u8", entry.url)
        assertEquals("trt1.tr", entry.tvgId)
        assertEquals("TRT 1 HD", entry.tvgName)
        assertEquals("http://logo.example.com/trt1,hd.png", entry.logo)
        assertEquals(1, entry.channelNumber)
        assertEquals("Ulusal", entry.group)
        assertEquals("Turkish", entry.language)
        assertEquals("TR", entry.country)
        assertNull(entry.durationSeconds)
        assertTrue(entry.headers.isEmpty())
    }

    @Test
    fun parsesSingleQuotedAndUnquotedAttributes() {
        val entries = M3uParser.parse(turkishPlaylist).entries
        assertEquals("showtv.tr", entries[1].tvgId)
        assertEquals("Show TV", entries[1].tvgName)
        assertEquals("Ulusal", entries[1].group)
        assertEquals("atv.tr", entries[2].tvgId)
        assertEquals(3, entries[2].channelNumber)
        assertEquals("Ulusal", entries[2].group)
        assertEquals("ATV", entries[2].title)
    }

    @Test
    fun keepsCommasAndTurkishCharactersInTitles() {
        val entries = M3uParser.parse(turkishPlaylist).entries
        assertEquals("NTV Spor, Canlı Maç Özeti", entries[3].title)
        assertEquals("Minika Çocuk", entries[4].title)
        assertEquals("Çocuk", entries[4].group)
        assertEquals("Çocuk Kanalı", entries[4].tvgName)
    }

    @Test
    fun parsesPositiveDurationAndTreatsLiveAsNull() {
        val text = """
            #EXTM3U
            #EXTINF:5400 tvg-name="Film",Film
            http://x/film.mp4
            #EXTINF:0,Live
            http://x/live.ts
            #EXTINF:12.7,Clip
            http://x/clip.mp4
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals(5400L, entries[0].durationSeconds)
        assertNull(entries[1].durationSeconds)
        assertEquals(12L, entries[2].durationSeconds)
    }

    @Test
    fun keepsAllAttributesIncludingCatchup() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" catchup="shift" catchup-days="7" catchup-source="http://x/{utc}.ts" Custom-Attr="Yes",Kanal
            http://x/kanal.ts
        """.trimIndent()
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("shift", entry.catchup)
        assertEquals(7, entry.catchupDays)
        assertEquals("http://x/{utc}.ts", entry.catchupSource)
        assertEquals("Yes", entry.attributes["custom-attr"])
        assertEquals("x", entry.attributes["tvg-id"])
    }

    @Test
    fun extGrpAppliesToNextEntryAndGroupTitleWins() {
        val text = """
            #EXTM3U
            #EXTGRP:Haber
            #EXTINF:-1,CNN Türk
            http://x/cnnturk.ts
            #EXTINF:-1,Halk TV
            http://x/halktv.ts
            #EXTINF:-1 group-title="Spor",beIN Sports 1
            #EXTGRP:Haber
            http://x/bein1.ts
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals("Haber", entries[0].group)
        assertNull(entries[1].group)
        assertEquals("Spor", entries[2].group)
    }

    @Test
    fun extVlcOptBecomesHeaders() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Kanal A
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (SMART-TV; Linux)
            #EXTVLCOPT:http-referrer=https://ref.example.com/
            #EXTVLCOPT:http-origin=https://origin.example.com
            #EXTVLCOPT:http-cookie=session=abc; theme=dark
            #EXTVLCOPT:network-caching=1000
            http://x/a.m3u8
            #EXTINF:-1,Kanal B
            http://x/b.m3u8
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals(
            mapOf(
                "User-Agent" to "Mozilla/5.0 (SMART-TV; Linux)",
                "Referer" to "https://ref.example.com/",
                "Origin" to "https://origin.example.com",
                "Cookie" to "session=abc; theme=dark",
            ),
            entries[0].headers,
        )
        assertTrue("options must not leak into the next entry", entries[1].headers.isEmpty())
    }

    @Test
    fun extVlcOptBeforeExtInfStillApplies() {
        val text = """
            #EXTM3U
            #EXTVLCOPT:http-referrer=https://ref.example.com/
            #EXTINF:-1,Kanal
            http://x/a.m3u8
        """.trimIndent()
        assertEquals("https://ref.example.com/", M3uParser.parse(text).entries.single().headers["Referer"])
    }

    @Test
    fun kodiPropStreamHeaders() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Kanal
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F20&Referer=https://r.example.com/&X-Custom=1
            http://x/manifest.mpd
        """.trimIndent()
        val headers = M3uParser.parse(text).entries.single().headers
        assertEquals("Kodi/20", headers["User-Agent"])
        assertEquals("https://r.example.com/", headers["Referer"])
        assertEquals("1", headers["X-Custom"])
    }

    @Test
    fun extHttpJsonHeaders() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Kanal
            #EXTHTTP:{"User-Agent":"TiviMate/4.7","cookie":"a=b"}
            http://x/a.ts
        """.trimIndent()
        val headers = M3uParser.parse(text).entries.single().headers
        assertEquals("TiviMate/4.7", headers["User-Agent"])
        assertEquals("a=b", headers["Cookie"])
    }

    @Test
    fun pipeHeadersAreSplitFromUrl() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Kanal
            http://x/y.m3u8?token=1|User-Agent=abc%20def&Referer=https://def.example.com/&referrer=https://last.example.com/
        """.trimIndent()
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("http://x/y.m3u8?token=1", entry.url)
        assertEquals("abc def", entry.headers["User-Agent"])
        assertEquals("https://last.example.com/", entry.headers["Referer"])
        assertEquals(2, entry.headers.size)
    }

    @Test
    fun headerPrecedencePipeOverVlcOptOverAttributeOverPlaylist() {
        val text = """
            #EXTM3U user-agent="PlaylistUA"
            #EXTINF:-1 user-agent="AttrUA" http-referrer="https://attr.example.com/",A
            http://x/a.ts
            #EXTINF:-1 user-agent="AttrUA",B
            #EXTVLCOPT:http-user-agent=VlcUA
            http://x/b.ts
            #EXTINF:-1,C
            #EXTVLCOPT:http-user-agent=VlcUA
            http://x/c.ts|User-Agent=PipeUA
            #EXTINF:-1,D
            http://x/d.ts
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals("AttrUA", entries[0].headers["User-Agent"])
        assertEquals("https://attr.example.com/", entries[0].headers["Referer"])
        assertEquals("VlcUA", entries[1].headers["User-Agent"])
        assertEquals("PipeUA", entries[2].headers["User-Agent"])
        assertEquals("PlaylistUA", entries[3].headers["User-Agent"])
    }

    @Test
    fun stripsKodiFormattingTags() {
        val text = """
            #EXTM3U
            #EXTINF:-1,[B][COLOR gold]TRT[/COLOR][/B] [I]Belgesel[/I]
            http://x/a.ts
            #EXTINF:-1,[COLOR=FFFF0000]Kırmızı[/COLOR][CR]Kanal
            http://x/b.ts
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals("TRT Belgesel", entries[0].title)
        assertEquals("Kırmızı Kanal", entries[1].title)
    }

    @Test
    fun handlesBomCrLfBlankLinesAndComments() {
        val text = "﻿#EXTM3U x-tvg-url=\"http://e/x.xml\"\r\n\r\n" +
            "# just a comment\r\n" +
            "#EXTINF:-1 tvg-id=\"a\",Kanal A\r\n" +
            "   \r\n" +
            "http://x/a.ts\r\n" +
            "#EXT-X-SOMETHING:ignored\r\n" +
            "#EXTINF:-1,Kanal B\r\n" +
            "http://x/b.ts   \r\n"
        val playlist = M3uParser.parse(text)
        assertEquals("http://e/x.xml", playlist.headerAttributes["x-tvg-url"])
        assertEquals(listOf("Kanal A", "Kanal B"), playlist.entries.map { it.title })
        assertEquals(listOf("http://x/a.ts", "http://x/b.ts"), playlist.entries.map { it.url })
    }

    @Test
    fun bareUrlsUseLastPathSegmentAsTitle() {
        val text = """
            http://x/movies/The.Movie.2020.mkv
            http://x/series/Dizi%20Adi/S02E05.mp4?token=abc
            http://x/live/
            udp://@239.0.0.1:1234
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals("The.Movie.2020.mkv", entries[0].title)
        assertEquals("S02E05.mp4", entries[1].title)
        assertEquals(2, entries[1].season)
        assertEquals(5, entries[1].episode)
        assertEquals("live", entries[2].title)
        assertEquals("@239.0.0.1:1234", entries[3].title)
        assertTrue(M3uParser.parse(text).headerAttributes.isEmpty())
    }

    @Test
    fun emptyTitleFallsBackToTvgNameThenUrl() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Kanal 7",
            http://x/kanal7.ts
            #EXTINF:-1 tvg-id="x"
            http://x/beyaz.ts
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals("Kanal 7", entries[0].title)
        assertEquals("beyaz.ts", entries[1].title)
    }

    @Test
    fun extInfWithoutUrlIsDroppedAndDoesNotLeak() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="Lost",Orphan
            #EXTINF:-1,Real
            http://x/real.ts
        """.trimIndent()
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("Real", entry.title)
        assertNull(entry.group)
    }

    @Test
    fun detectsSeasonAndEpisode() {
        assertEquals(1 to 2, M3uParser.detectEpisode("Kurtlar Vadisi S01E02"))
        assertEquals(10 to 125, M3uParser.detectEpisode("Show.s10.e125.1080p"))
        assertEquals(3 to 7, M3uParser.detectEpisode("Dizi 3x07 Final"))
        assertEquals(2 to 14, M3uParser.detectEpisode("Yalı Çapkını 2. Sezon 14. Bölüm"))
        assertNull(M3uParser.detectEpisode("Video 1920x1080"))
        assertNull(M3uParser.detectEpisode("TRT 1 HD"))
        assertNull(M3uParser.detectEpisode("Film 2020"))
    }

    @Test
    fun entriesCarrySeasonEpisode() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="Diziler",Muhteşem Yüzyıl S04E12
            http://x/s4e12.mkv
            #EXTINF:-1,Haber
            http://x/haber.ts
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals(4, entries[0].season)
        assertEquals(12, entries[0].episode)
        assertNull(entries[1].season)
        assertNull(entries[1].episode)
    }

    @Test
    fun unterminatedQuoteDoesNotSwallowTitle() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Broken group-title=\"X\",Title\nhttp://x/a.ts\n"
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("http://x/a.ts", entry.url)
        assertTrue(entry.title.isNotEmpty())
    }

    @Test
    fun streamingParseMatchesStringParse() {
        val streamed = ArrayList<M3uEntry>()
        val header = M3uParser.forEachEntry(StringReader(turkishPlaylist)) { streamed.add(it) }
        val parsed = M3uParser.parse(turkishPlaylist)
        assertEquals(parsed.entries, streamed)
        assertEquals(parsed.headerAttributes, header)
    }

    @Test
    fun modelsRoundTripThroughJson() {
        val playlist = M3uParser.parse(turkishPlaylist)
        val json = Json.encodeToString(M3uPlaylist.serializer(), playlist)
        assertEquals(playlist, Json.decodeFromString(M3uPlaylist.serializer(), json))
    }

    @Test
    fun parsesTenThousandEntriesQuickly() {
        val text = buildString {
            append("#EXTM3U url-tvg=\"http://epg/x.xml\" user-agent=\"PerfUA\"\n")
            repeat(10_000) { i ->
                append("#EXTINF:-1 tvg-id=\"ch$i.tr\" tvg-name=\"Kanal $i\" tvg-logo=\"http://logo/$i.png\" ")
                append("group-title=\"Grup ${i % 50}\",[B]Kanal $i[/B] S01E${i % 100}\n")
                if (i % 3 == 0) append("#EXTVLCOPT:http-referrer=https://ref/$i\n")
                append("http://stream.example.com/live/user/pass/$i.ts\n")
            }
        }
        M3uParser.parse(text)
        val start = System.nanoTime()
        val playlist = M3uParser.parse(text)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, playlist.entries.size)
        assertEquals("Kanal 9999 S01E99", playlist.entries.last().title)
        assertEquals("PerfUA", playlist.entries.last().headers["User-Agent"])
        assertEquals("https://ref/9999", playlist.entries[9999].headers["Referer"])
        assertTrue("parsing took ${elapsedMs}ms", elapsedMs < 1000)
    }
}
