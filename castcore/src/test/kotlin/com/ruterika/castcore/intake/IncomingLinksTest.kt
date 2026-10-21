package com.ruterika.castcore.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingLinksTest {

    @Test
    fun findsTheFirstUrlInSharedText() {
        assertEquals("https://example.com/watch?v=1", IncomingLinks.firstUrl("Look at this: https://example.com/watch?v=1."))
        assertEquals("http://a.b/c", IncomingLinks.firstUrl("(see http://a.b/c) and https://x.y"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", IncomingLinks.firstUrl("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals("rtsp://10.0.0.2:554/live", IncomingLinks.firstUrl("cam rtsp://10.0.0.2:554/live"))
        assertNull(IncomingLinks.firstUrl("no links here"))
        assertNull(IncomingLinks.firstUrl(null))
    }

    @Test
    fun tellsMediaFromPages() {
        assertTrue(IncomingLinks.isMedia("https://cdn.test/v/movie.mp4?token=1"))
        assertTrue(IncomingLinks.isMedia("https://cdn.test/live/index.m3u8"))
        assertTrue(IncomingLinks.isMedia("rtsp://server/app/stream"))
        assertTrue(IncomingLinks.isMedia("https://x.test/play", "application/vnd.apple.mpegurl"))
        assertTrue(IncomingLinks.isMedia("content://media/external/video/1", "video/mp4"))
        assertTrue(IncomingLinks.isMedia("content://provider/doc/42", null, "Holiday.MKV"))
        assertFalse(IncomingLinks.isMedia("https://example.com/watch?v=1"))
        assertFalse(IncomingLinks.isMedia("https://example.com/page.html", "text/html"))
    }

    @Test
    fun recognisesPlaylistFilesAndIptvLists() {
        assertTrue(IncomingLinks.isPlaylistFile("https://x.test/tv.m3u"))
        assertTrue(IncomingLinks.isPlaylistFile("content://p/1", "audio/x-mpegurl"))
        assertTrue(IncomingLinks.isPlaylistFile("content://p/1", null, "list.m3u8"))
        assertFalse(IncomingLinks.isPlaylistFile("https://x.test/a.mp4"))

        val iptv = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"News\",A\nhttp://x/a.ts\n"
        val hls = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n"
        assertTrue(IncomingLinks.looksLikeIptvList(iptv))
        assertFalse(IncomingLinks.looksLikeIptvList(hls))
        assertFalse(IncomingLinks.looksLikeIptvList(master))
    }

    @Test
    fun namesAndTypes() {
        assertEquals("My Movie", IncomingLinks.titleFor("https://cdn.test/files/My%20Movie.mp4?x=1"))
        assertEquals("cdn.test", IncomingLinks.titleFor("https://cdn.test/"))
        assertEquals("clip", IncomingLinks.titleFor("content://p/9", "clip.webm"))
        assertEquals("application/x-mpegURL", IncomingLinks.mediaMimeType("https://a/b.m3u8"))
        assertEquals("video/mp4", IncomingLinks.mediaMimeType("https://a/b", "video/mp4"))
        assertNull(IncomingLinks.mediaMimeType("rtsp://cam/stream"))
    }
}
