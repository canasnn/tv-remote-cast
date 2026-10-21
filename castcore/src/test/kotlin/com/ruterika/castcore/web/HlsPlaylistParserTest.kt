package com.ruterika.castcore.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="audio/en/index.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Turkce, Dublaj",LANGUAGE="tr",URI="/abs/audio/tr.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",FORCED=NO,URI="subs/en.m3u8"
        #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1",INSTREAM-ID="CC1"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=700000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",FRAME-RATE=25.000,AUDIO="aud",SUBTITLES="subs"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",FRAME-RATE=29.97,AUDIO="aud",SUBTITLES="subs",VIDEO-RANGE=SDR
        https://other.example.net/1080p/index.m3u8?token=x
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",AUDIO="aud"

        ../720p/index.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,RESOLUTION=640x360,URI="iframes.m3u8"
    """.trimIndent()

    private val base = "https://cdn.example.com/vod/movie/master.m3u8?sig=abc"

    @Test
    fun parsesMasterVariants() {
        val p = HlsPlaylistParser.parse(master, base)
        assertEquals(HlsPlaylistType.MASTER, p.type)
        assertTrue(p.isMaster)
        assertNull(p.isLive)
        assertEquals(3, p.variants.size)

        val v360 = p.variants[0]
        assertEquals("https://cdn.example.com/vod/movie/360p/index.m3u8", v360.url)
        assertEquals(800_000L, v360.bandwidth)
        assertEquals(700_000L, v360.averageBandwidth)
        assertEquals(640, v360.width)
        assertEquals(360, v360.height)
        assertEquals("avc1.4d401e,mp4a.40.2", v360.codecs)
        assertEquals(25.0, v360.frameRate!!, 0.001)
        assertEquals("aud", v360.audioGroup)
        assertEquals("subs", v360.subtitleGroup)
        assertEquals("360p", v360.label)

        assertEquals("https://other.example.net/1080p/index.m3u8?token=x", p.variants[1].url)
        assertEquals("SDR", p.variants[1].videoRange)
        assertEquals(29.97, p.variants[1].frameRate!!, 0.001)
        assertEquals("https://cdn.example.com/vod/720p/index.m3u8", p.variants[2].url)
        assertEquals(1080, p.bestVariant?.height)
        assertTrue(p.hasVideo)
        assertFalse(p.isEncrypted)
    }

    @Test
    fun parsesRenditions() {
        val p = HlsPlaylistParser.parse(master, base)
        assertEquals(2, p.audioRenditions.size)
        val en = p.audioRenditions[0]
        assertEquals(HlsRenditionType.AUDIO, en.type)
        assertEquals("aud", en.groupId)
        assertEquals("English", en.name)
        assertEquals("en", en.language)
        assertEquals("2", en.channels)
        assertTrue(en.isDefault)
        assertTrue(en.autoSelect)
        assertEquals("https://cdn.example.com/vod/movie/audio/en/index.m3u8", en.url)
        val tr = p.audioRenditions[1]
        assertEquals("Turkce, Dublaj", tr.name) // comma inside quotes
        assertEquals("https://cdn.example.com/abs/audio/tr.m3u8", tr.url)

        assertEquals(2, p.subtitleRenditions.size)
        val subs = p.subtitleRenditions[0]
        assertEquals(HlsRenditionType.SUBTITLES, subs.type)
        assertEquals("https://cdn.example.com/vod/movie/subs/en.m3u8", subs.url)
        assertFalse(subs.forced)
        val cc = p.subtitleRenditions[1]
        assertEquals(HlsRenditionType.CLOSED_CAPTIONS, cc.type)
        assertNull(cc.url)

        assertEquals(
            listOf(
                "https://cdn.example.com/vod/movie/360p/index.m3u8",
                "https://other.example.net/1080p/index.m3u8?token=x",
                "https://cdn.example.com/vod/720p/index.m3u8",
                "https://cdn.example.com/vod/movie/audio/en/index.m3u8",
                "https://cdn.example.com/abs/audio/tr.m3u8",
                "https://cdn.example.com/vod/movie/subs/en.m3u8",
            ),
            p.referencedPlaylistUrls,
        )
    }

    @Test
    fun parsesVodMediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:9.009,
            seg-1-v1-a1.ts
            #EXTINF:9.009,title
            seg-2-v1-a1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4.5,
            /root/seg-3-v1-a1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, "https://cdn.example.com/a/b/index.m3u8")
        assertEquals(HlsPlaylistType.MEDIA, p.type)
        assertEquals(false, p.isLive)
        assertEquals(22518L, p.durationMs)
        assertEquals(10, p.targetDurationSec)
        assertEquals(3, p.segmentCount)
        assertEquals("VOD", p.playlistType)
        assertEquals("https://cdn.example.com/a/b/seg-1-v1-a1.ts", p.segmentUrls[0])
        assertEquals("https://cdn.example.com/root/seg-3-v1-a1.ts", p.segmentUrls[2])
        assertTrue(p.variants.isEmpty())
        assertTrue(p.hasVideo)
    }

    @Test
    fun parsesLiveMediaPlaylistWithEncryption() {
        val text = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXT-X-MEDIA-SEQUENCE:1234\r\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/k1.key\",IV=0x1234\r\n" +
            "#EXTINF:6.0,\r\n1234.ts\r\n#EXTINF:6.0,\r\n1235.ts\r\n"
        val p = HlsPlaylistParser.parse(text, "https://live.example.com/ch1/chunklist.m3u8")
        assertEquals(HlsPlaylistType.MEDIA, p.type)
        assertEquals(true, p.isLive)
        assertNull(p.durationMs)
        assertTrue(p.isEncrypted)
        val enc = assertNotNullAndGet(p.encryption)
        assertEquals("AES-128", enc.method)
        assertEquals("https://live.example.com/ch1/keys/k1.key", enc.keyUrl)
        assertFalse(enc.isDrm)
    }

    @Test
    fun eventPlaylistWithoutEndlistIsLive() {
        val text = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n"
        assertEquals(true, HlsPlaylistParser.parse(text, "https://x.example.com/e.m3u8").isLive)
    }

    @Test
    fun keyMethodNoneAndDrm() {
        val none = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
        assertFalse(HlsPlaylistParser.parse(none, "https://x.example.com/a.m3u8").isEncrypted)

        val fairplay = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\",KEYFORMAT=\"com.apple.streamingkeydelivery\",KEYFORMATVERSIONS=\"1\"\n" +
            "#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
        val p = HlsPlaylistParser.parse(fairplay, "https://x.example.com/a.m3u8")
        assertEquals("SAMPLE-AES", p.encryption?.method)
        assertEquals("skd://key-id", p.encryption?.keyUrl)
        assertTrue(p.encryption!!.isDrm)

        val sessionKey = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,AAAA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8\n"
        val m = HlsPlaylistParser.parse(sessionKey, "https://x.example.com/m.m3u8")
        assertTrue(m.isMaster)
        assertTrue(m.encryption!!.isDrm)
    }

    @Test
    fun audioOnlyDetection() {
        val audioMaster = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\nlo.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\"\nhi.m3u8\n"
        assertFalse(HlsPlaylistParser.parse(audioMaster, "https://r.example.com/live.m3u8").hasVideo)

        val unknownCodecs = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nlo.m3u8\n"
        assertTrue(HlsPlaylistParser.parse(unknownCodecs, "https://r.example.com/live.m3u8").hasVideo)

        val audioMedia = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1.aac\n#EXTINF:10,\nseg2.aac\n"
        val p = HlsPlaylistParser.parse(audioMedia, "https://r.example.com/radio/chunks.m3u8")
        assertFalse(p.hasVideo)
        assertEquals("128 kbps", HlsVariant(url = "x", bandwidth = 128_000).label)
    }

    @Test
    fun relativeUriResolution() {
        val b = "https://cdn.example.com/a/b/c/master.m3u8?x=1"
        assertEquals("https://cdn.example.com/a/b/c/v.m3u8", WebUrls.resolve(b, "v.m3u8"))
        assertEquals("https://cdn.example.com/a/b/v.m3u8", WebUrls.resolve(b, "../v.m3u8"))
        assertEquals("https://cdn.example.com/v.m3u8", WebUrls.resolve(b, "/v.m3u8"))
        assertEquals("https://other.example.com/v.m3u8", WebUrls.resolve(b, "//other.example.com/v.m3u8"))
        assertEquals("http://abs.example.com/v.m3u8", WebUrls.resolve(b, "http://abs.example.com/v.m3u8"))
        assertEquals("https://cdn.example.com/a/b/c/v.m3u8?t=2", WebUrls.resolve(b, "v.m3u8?t=2"))
        assertEquals("https://cdn.example.com/a/b/c/master.m3u8?y=2", WebUrls.resolve(b, "?y=2"))
        assertEquals("https://cdn.example.com/v.m3u8", WebUrls.resolve("https://cdn.example.com", "v.m3u8"))
        assertEquals("https://cdn.example.com/a/b/c/my%20file.m3u8", WebUrls.resolve(b, "my file.m3u8"))
    }

    @Test
    fun bomAndBlankLinesTolerated() {
        val text = "﻿#EXTM3U\n\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=320x180\n\n  low.m3u8  \n"
        val p = HlsPlaylistParser.parse(text, "https://x.example.com/dir/m.m3u8")
        assertEquals(1, p.variants.size)
        assertEquals("https://x.example.com/dir/low.m3u8", p.variants[0].url)
    }

    @Test
    fun invalidInput() {
        assertNull(HlsPlaylistParser.parseOrNull("<html>nope</html>", "https://x.example.com/a.m3u8"))
        assertFalse(HlsPlaylistParser.isHlsPlaylist("{\"a\":1}"))
        try {
            HlsPlaylistParser.parse("", "https://x.example.com/a.m3u8")
            throw AssertionError("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun attributeParser() {
        val a = HlsPlaylistParser.parseAttributes("BANDWIDTH=1,CODECS=\"a,b\",resolution=10x20 , NAME=\"x\"")
        assertEquals("1", a["BANDWIDTH"])
        assertEquals("a,b", a["CODECS"])
        assertEquals("10x20", a["RESOLUTION"])
        assertEquals("x", a["NAME"])
        assertEquals(true, HlsPlaylistParser.codecsContainVideo("hvc1.2.4.L123.B0,ec-3"))
        assertEquals(false, HlsPlaylistParser.codecsContainVideo("mp4a.40.2,ec-3"))
        assertNull(HlsPlaylistParser.codecsContainVideo(null))
    }

    private fun <T : Any> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
