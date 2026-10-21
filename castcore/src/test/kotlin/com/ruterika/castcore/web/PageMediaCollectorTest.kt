package com.ruterika.castcore.web

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PageMediaCollectorTest {

    private val page = "https://site.example/watch/42"
    private val ua = "Mozilla/5.0 (Linux; Android 14) TestUA"
    private var now = 1_000L
    private lateinit var collector: PageMediaCollector

    private fun newCollector(
        config: PageMediaCollector.Config = PageMediaCollector.Config(),
        cookies: ((String) -> String?)? = null,
    ) = PageMediaCollector(AdBlocker.default(), config, cookies) { now++ }

    @Before
    fun setUp() {
        collector = newCollector()
        collector.onPageStarted(page)
    }

    private fun req(url: String, headers: Map<String, String> = mapOf("User-Agent" to ua), cookie: String? = null) =
        collector.onRequest(url, "GET", headers, page, cookie = cookie)

    private fun urls() = collector.items.value.map { it.url }

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Deutsch",LANGUAGE="de",URI="audio/de.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="a",SUBTITLES="s"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="a",SUBTITLES="s"
        720p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="a",SUBTITLES="s"
        1080p/index.m3u8?tok=1
    """.trimIndent()

    private fun vodMedia(seconds: Int) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        repeat(seconds / 10) { append("#EXTINF:10.0,\nseg-$it.ts\n") }
        append("#EXT-X-ENDLIST\n")
    }

    private val liveMedia = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n100.ts\n#EXTINF:6,\n101.ts\n"

    @Test
    fun ordersBestFirstAndHidesSegmentsAndImages() {
        req("https://cdn.site.example/a/song.mp3")
        req("https://cdn.site.example/v/clip.mp4")
        req("https://cdn.site.example/hls/seg-1-v1-a1.ts")
        req("https://cdn.site.example/img/poster.jpg")
        req("https://cdn.site.example/dash/stream.mpd")
        req("https://cdn.site.example/hls/master.m3u8")
        req("https://cdn.site.example/app.js")
        assertEquals(
            listOf(
                "https://cdn.site.example/hls/master.m3u8",
                "https://cdn.site.example/dash/stream.mpd",
                "https://cdn.site.example/v/clip.mp4",
                "https://cdn.site.example/a/song.mp3",
            ),
            urls(),
        )
        val items = collector.items.value
        assertEquals(MediaClass.HLS, items[0].mediaClass)
        assertEquals("application/x-mpegURL", items[0].mimeType)
        assertEquals(DetectionSource.REQUEST, items[0].source)
        assertEquals(WebUrls.stableId("https://cdn.site.example/hls/master.m3u8"), items[0].id)
    }

    @Test
    fun progressiveSortedByResolutionThenSize() {
        req("https://cdn.site.example/v/a.mp4")
        req("https://cdn.site.example/v/b.mp4")
        req("https://cdn.site.example/v/c.mp4")
        collector.onHeadProbeResult("https://cdn.site.example/v/a.mp4", "video/mp4", 10_000_000)
        collector.onHeadProbeResult("https://cdn.site.example/v/b.mp4", "video/mp4", 90_000_000)
        val js = """{"type":"media","pageUrl":"$page","pageTitle":"T","items":[
            {"url":"https://cdn.site.example/v/c.mp4","kind":"video","src":"tag","width":1920,"height":1080,"durationMs":600000}]}"""
        assertTrue(collector.onJsMessage(js))
        assertEquals(
            listOf("https://cdn.site.example/v/c.mp4", "https://cdn.site.example/v/b.mp4", "https://cdn.site.example/v/a.mp4"),
            urls(),
        )
        assertEquals(90_000_000L, collector.items.value[1].contentLength)
    }

    @Test
    fun adsAreHidden() {
        req("https://s0.2mdn.net/ads/creative.mp4")
        req("https://pubads.g.doubleclick.net/ondemand/hls/ad.m3u8")
        req("https://cdn.site.example/movie.mp4")
        val js = """{"type":"media","items":[{"url":"https://cdn.site.example/preroll.mp4","kind":"video","src":"tag","durationMs":15000}]}"""
        collector.onJsMessage(js)
        assertEquals(listOf("https://cdn.site.example/movie.mp4"), urls())
        val ads = collector.adItems.value.map { it.url }.toSet()
        assertEquals(
            setOf(
                "https://s0.2mdn.net/ads/creative.mp4",
                "https://pubads.g.doubleclick.net/ondemand/hls/ad.m3u8",
                "https://cdn.site.example/preroll.mp4",
            ),
            ads,
        )
        assertTrue(collector.adItems.value.all { it.isLikelyAd })
        // ad playlists are never fetched
        assertFalse(collector.pendingProbeRequests().any { it.url.contains("2mdn") || it.url.contains("doubleclick") })
    }

    @Test
    fun shortHlsVodIsAdButLiveIsNot() {
        req("https://cdn.site.example/ad/index.m3u8")
        req("https://cdn.site.example/live/chunklist.m3u8")
        collector.onPlaylistLoaded("https://cdn.site.example/ad/index.m3u8", vodMedia(20))
        collector.onPlaylistLoaded("https://cdn.site.example/live/chunklist.m3u8", liveMedia)
        assertEquals(listOf("https://cdn.site.example/live/chunklist.m3u8"), urls())
        val live = collector.items.value.single()
        assertTrue(live.isLive)
        assertEquals(false, live.isMasterPlaylist)
        assertEquals(listOf("https://cdn.site.example/ad/index.m3u8"), collector.adItems.value.map { it.url })
        assertEquals(20_000L, collector.adItems.value.single().durationMs)
    }

    @Test
    fun masterSwallowsItsMediaPlaylists() {
        val masterUrl = "https://cdn.site.example/vod/movie/master.m3u8?sig=1"
        req(masterUrl)
        req("https://cdn.site.example/vod/movie/720p/index.m3u8")
        assertEquals(2, urls().size)
        assertTrue(collector.pendingProbeRequests().any { it.url == masterUrl && it.kind == ProbeKind.PLAYLIST })

        collector.onPlaylistLoaded(masterUrl, master)
        val m = collector.items.value.single()
        assertEquals(masterUrl, m.url)
        assertEquals(true, m.isMasterPlaylist)
        assertEquals(3, m.variants.size)
        assertEquals(1080, m.height)
        assertEquals(1920, m.width)
        assertEquals(5_000_000L, m.bitrate)
        assertEquals(listOf("de"), m.audioTracks.map { it.language })
        assertEquals("https://cdn.site.example/vod/movie/subs/en.m3u8", m.subtitles.single().url)
        // one variant is fetched to learn duration/live
        assertTrue(collector.pendingProbeRequests().any { it.url == "https://cdn.site.example/vod/movie/360p/index.m3u8" })
        assertFalse(collector.pendingProbeRequests().any { it.url == masterUrl })

        // later requests for variant / rendition playlists (token may differ) are attached, not listed
        req("https://cdn.site.example/vod/movie/1080p/index.m3u8?tok=2")
        req("https://cdn.site.example/vod/movie/audio/de.m3u8")
        assertEquals(listOf(masterUrl), urls())

        collector.onPlaylistLoaded("https://cdn.site.example/vod/movie/360p/index.m3u8", vodMedia(5400))
        val updated = collector.items.value.single()
        assertEquals(5_400_000L, updated.durationMs)
        assertFalse(updated.isLive)
        assertFalse(updated.isLikelyAd)
    }

    @Test
    fun parsedMediaPlaylistInfoIsCarriedToMaster() {
        val masterUrl = "https://cdn.site.example/live/master.m3u8"
        req(masterUrl)
        req("https://cdn.site.example/live/720p/index.m3u8")
        collector.onPlaylistLoaded("https://cdn.site.example/live/720p/index.m3u8", liveMedia)
        assertEquals(2, urls().size)
        collector.onPlaylistLoaded(masterUrl, master.replace("vod", "live"))
        val m = collector.items.value.single()
        assertTrue(m.isLive)
        assertTrue(m.isMasterPlaylist == true)
    }

    @Test
    fun capturesHeadersFromRequestAndPage() {
        req(
            "https://cdn.other.example/v/movie.mp4",
            headers = mapOf("user-agent" to ua, "referer" to "https://player.example/embed/9", "Cookie" to "ignored=1", "Accept" to "*/*"),
        )
        req("https://cdn.site.example/v/movie2.mp4")
        val byUrl = collector.items.value.associateBy { it.url }
        val h1 = byUrl.getValue("https://cdn.other.example/v/movie.mp4").headers
        assertEquals(
            // Page request headers are kept (minus transport ones); the request's Cookie is replaced by the cookie store's.
            mapOf("Accept" to "*/*", "Referer" to "https://player.example/embed/9", "Origin" to "https://player.example", "User-Agent" to ua),
            h1,
        )
        val h2 = byUrl.getValue("https://cdn.site.example/v/movie2.mp4").headers
        assertEquals(page, h2["Referer"])
        assertEquals("https://site.example", h2["Origin"])
        assertEquals(ua, h2["User-Agent"])
        assertNull(h2["Cookie"])
    }

    @Test
    fun cookieOnlyWhenCallerProvidesIt() {
        req("https://cdn.site.example/v/a.mp4", cookie = "session=abc")
        assertEquals("session=abc", collector.items.value.single().headers["Cookie"])

        val withProvider = newCollector(cookies = { url -> if (url.contains("cdn.site.example")) "sid=1" else null })
        withProvider.onPageStarted(page)
        withProvider.onRequest("https://cdn.site.example/v/b.mp4", headers = mapOf("User-Agent" to ua))
        withProvider.onRequest("https://cdn.elsewhere.example/v/c.mp4", headers = mapOf("User-Agent" to ua))
        val byUrl = withProvider.items.value.associateBy { it.url }
        assertEquals("sid=1", byUrl.getValue("https://cdn.site.example/v/b.mp4").headers["Cookie"])
        assertNull(byUrl.getValue("https://cdn.elsewhere.example/v/c.mp4").headers["Cookie"])
    }

    @Test
    fun jsMessageWithTagSubtitlesPosterAndMse() {
        val js = """
            {"type":"media","pageTitle":"Great Movie","pageUrl":"$page","top":true,"mse":true,"items":[
              {"url":"blob:https://site.example/1-2-3","kind":"video","src":"tag"},
              {"url":"https://cdn.site.example/v/movie.webm","kind":"video","src":"tag","mime":"video/webm",
               "title":null,"poster":"https://cdn.site.example/p.jpg","durationMs":7200000.4,"width":1280,"height":720,
               "subtitles":[{"url":"https://cdn.site.example/s/tr.vtt","lang":"tr","label":"Turkce"}],"unknownField":1}
            ]}
        """.trimIndent()
        assertTrue(collector.onJsMessage(js))
        assertTrue(collector.mediaSourceInUse.value)
        val item = collector.items.value.single()
        assertEquals("https://cdn.site.example/v/movie.webm", item.url)
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, item.mediaClass)
        assertEquals("video/webm", item.mimeType)
        assertEquals("Great Movie", item.title)
        assertEquals("https://cdn.site.example/p.jpg", item.posterUrl)
        assertEquals(7_200_000L, item.durationMs)
        assertEquals(720, item.height)
        assertEquals(DetectionSource.JS_VIDEO_TAG, item.source)
        assertEquals(DetectedSubtitle("https://cdn.site.example/s/tr.vtt", "tr", "Turkce", "text/vtt"), item.subtitles.single())
        // Media on another origin (cdn.) gets the page origin as Referer, like a browser.
        assertEquals("https://site.example/", item.headers["Referer"])
    }

    @Test
    fun jsPlayerConfigKindsAndFrameReferer() {
        val frame = "https://embed.player.example/e/abc"
        val js = """{"type":"media","top":false,"pageTitle":"frame","pageUrl":"$frame","items":[
            {"url":"https://cdn.player.example/x/playlist","kind":"hls","mime":"hls","src":"player","title":"Episode 1"},
            {"url":"https://cdn.player.example/x/sub.srt","kind":"subtitle","lang":"en"}]}"""
        collector.onJsMessage(js)
        val item = collector.items.value.single()
        assertEquals(MediaClass.HLS, item.mediaClass)
        assertEquals(DetectionSource.JS_PLAYER_CONFIG, item.source)
        assertEquals("Episode 1", item.title)
        // Cross-origin media found by the script: Referer is the frame origin only, no Origin header.
        assertEquals("https://embed.player.example/", item.headers["Referer"])
        assertEquals(null, item.headers["Origin"])
        assertEquals("https://cdn.player.example/x/sub.srt", item.subtitles.single().url)
        assertEquals("application/x-subrip", item.subtitles.single().mime)
    }

    /** Message shape captured from detector.js running in a real browser. */
    @Test
    fun parsesRealDetectorOutput() {
        val js = """{"type":"media","items":[{"kind":"video","src":"tag","title":"Tag Video","poster":"https://site.example/poster.jpg","durationMs":null,"width":null,"height":null,"live":false,"subtitles":[{"url":"https://site.example/subs/en.vtt","lang":"en","label":"English"}],"url":"https://site.example/media/movie.mp4","mime":"video/mp4"},{"url":"https://cdn.example.com/og/clip.mp4","kind":"video","mime":"video/mp4","title":null,"poster":null,"durationMs":null,"src":"player","subtitles":[]},{"url":"https://cdn.example.com/ld/master.m3u8","kind":"hls","mime":null,"title":"LD Video","poster":"https://cdn.example.com/t.jpg","durationMs":3723000,"src":"player","subtitles":[]}],"pageTitle":"Smoke Page","pageUrl":"https://site.example/watch/42","top":true,"mse":true}"""
        assertTrue(collector.onJsMessage(js))
        val items = collector.items.value
        assertEquals(
            listOf("https://cdn.example.com/ld/master.m3u8", "https://site.example/media/movie.mp4", "https://cdn.example.com/og/clip.mp4"),
            items.map { it.url },
        )
        assertEquals("LD Video", items[0].title)
        assertEquals(3_723_000L, items[0].durationMs)
        assertEquals("Tag Video", items[1].title)
        assertEquals(DetectionSource.JS_VIDEO_TAG, items[1].source)
        assertEquals("Smoke Page", items[2].title)
        assertTrue(collector.mediaSourceInUse.value)
    }

    @Test
    fun requestAndJsForSameUrlAreMerged() {
        collector.onJsMessage("""{"type":"media","items":[{"url":"https://cdn.site.example/v/m.mp4#t=5","kind":"video","src":"tag","title":"Tag title","width":640,"height":360}]}""")
        req("https://cdn.site.example/v/m.mp4", headers = mapOf("User-Agent" to ua, "Referer" to "https://site.example/frame"))
        val item = collector.items.value.single()
        assertEquals("Tag title", item.title)
        assertEquals(360, item.height)
        assertEquals("https://site.example/frame", item.headers["Referer"])
    }

    @Test
    fun subtitleRequestsAttachToItemsAndAreNotListed() {
        req("https://cdn.site.example/subs/en.vtt")
        assertTrue(collector.items.value.isEmpty())
        req("https://cdn.site.example/v/movie.mkv")
        val item = collector.items.value.single()
        assertEquals("https://cdn.site.example/subs/en.vtt", item.subtitles.single().url)
        assertEquals("text/vtt", item.subtitles.single().mime)
    }

    @Test
    fun maybeUrlsAreProbed() = runBlocking {
        val probes = mutableListOf<ProbeRequest>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            collector.pendingProbes.take(2).toList(probes)
        }
        req("https://cdn.site.example/video/12345")
        req("https://cdn.site.example/stream/abc")
        req("https://cdn.site.example/video/12345") // deduped
        withTimeout(2_000) { job.join() }
        assertEquals(listOf("https://cdn.site.example/video/12345", "https://cdn.site.example/stream/abc"), probes.map { it.url })
        assertTrue(probes.all { it.kind == ProbeKind.HEAD })
        assertEquals(page, probes[0].headers["Referer"])
        assertTrue(collector.items.value.isEmpty())

        collector.onHeadProbeResult("https://cdn.site.example/video/12345", "video/mp4", 123_456_789)
        collector.onHeadProbeResult("https://cdn.site.example/stream/abc", "text/html; charset=utf-8", 1000)
        val item = collector.items.value.single()
        assertEquals("https://cdn.site.example/video/12345", item.url)
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, item.mediaClass)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(123_456_789L, item.contentLength)
        assertTrue(collector.pendingProbeRequests().isEmpty())
    }

    @Test
    fun guessedJsItemIsCorrectedOrDroppedByProbe() {
        val js = """{"type":"media","items":[
            {"url":"https://cdn.site.example/play/abc","kind":"video","src":"tag"},
            {"url":"https://cdn.site.example/play/def","kind":"video","src":"tag"}]}"""
        collector.onJsMessage(js)
        assertEquals(2, collector.items.value.size)
        assertTrue(collector.pendingProbeRequests().map { it.url }.containsAll(listOf("https://cdn.site.example/play/abc", "https://cdn.site.example/play/def")))
        collector.onHeadProbeResult("https://cdn.site.example/play/abc", "application/vnd.apple.mpegurl", null)
        collector.onHeadProbeResult("https://cdn.site.example/play/def", "text/html", null)
        val item = collector.items.value.single()
        assertEquals(MediaClass.HLS, item.mediaClass)
        assertTrue(collector.pendingProbeRequests().any { it.url == "https://cdn.site.example/play/abc" && it.kind == ProbeKind.PLAYLIST })
    }

    @Test
    fun navigationClearsUnlessSameDocumentOrKeep() {
        req("https://cdn.site.example/v/a.mp4")
        collector.onPageStarted("$page#comments")
        assertEquals(1, urls().size)
        collector.onPageStarted("https://site.example/other")
        assertTrue(urls().isEmpty())
        assertFalse(collector.mediaSourceInUse.value)

        val keep = newCollector(PageMediaCollector.Config(keepOnNavigation = true))
        keep.onPageStarted(page)
        keep.onRequest("https://cdn.site.example/v/a.mp4")
        keep.onPageStarted("https://site.example/other")
        assertEquals(1, keep.items.value.size)
        keep.clear()
        assertTrue(keep.items.value.isEmpty())
    }

    @Test
    fun ignoresNonGetAndMalformedInput() {
        collector.onRequest("https://cdn.site.example/v/a.mp4", "POST")
        collector.onRequest("blob:https://site.example/xyz")
        collector.onRequest("data:video/mp4;base64,AAA")
        assertTrue(urls().isEmpty())
        assertFalse(collector.onJsMessage("not json"))
        assertFalse(collector.onJsMessage("[1,2]"))
        assertTrue(collector.onJsMessage("{}"))
        assertTrue(urls().isEmpty())
    }

    @Test
    fun pageTitleFallbackAndManualAdd() {
        collector.onPageTitle("Page Title")
        req("https://cdn.site.example/v/a.mp4")
        assertEquals("Page Title", collector.items.value.single().title)
        val manual = collector.addManual("https://manual.example/watch?id=1", title = "Mine")
        assertNotNull(manual)
        assertEquals(DetectionSource.MANUAL, manual!!.source)
        assertEquals("Mine", manual.title)
        assertTrue(collector.pendingProbeRequests().any { it.url == "https://manual.example/watch?id=1" })
        assertNull(collector.addManual("ftp://x/y"))
    }

    @Test
    fun maxItemsIsEnforced() {
        val c = newCollector(PageMediaCollector.Config(maxItems = 3))
        c.onPageStarted(page)
        (1..5).forEach { c.onRequest("https://cdn.site.example/v/$it.mp4") }
        assertEquals(3, c.items.value.size)
        assertFalse(c.items.value.any { it.url.endsWith("/1.mp4") })
    }

    @Test
    fun detectedMediaIsSerializable() {
        req("https://cdn.site.example/hls/master.m3u8")
        collector.onPlaylistLoaded("https://cdn.site.example/hls/master.m3u8", master)
        val item = collector.items.value.single()
        val json = Json.encodeToString(DetectedMedia.serializer(), item)
        assertEquals(item, Json.decodeFromString(DetectedMedia.serializer(), json))
        assertEquals(item.id, DetectedMedia.idFor("HTTPS://CDN.SITE.EXAMPLE/hls/master.m3u8#x"))
    }
}
