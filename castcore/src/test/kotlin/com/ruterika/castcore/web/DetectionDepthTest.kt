package com.ruterika.castcore.web

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Rule engine wiring, web position, DASH, failed URLs and subtitle query parameters. */
class DetectionDepthTest {

    private val page = "https://site.example/watch/42"
    private val ua = mapOf("User-Agent" to "TestUA")

    private fun collector(
        rules: SiteRules = SiteRules.default(),
        failed: (String) -> Boolean = { false },
        config: PageMediaCollector.Config = PageMediaCollector.Config(),
    ) = PageMediaCollector(AdBlocker.default(), config, rules = { rules }, isFailedUrl = failed, clock = { 1L }).also { it.onPageStarted(page) }

    @Test
    fun ignoreRulesDropStubs() {
        val c = collector()
        c.onRequest("https://ok.ru/res/i/video/stub.mp4", headers = ua)
        c.onJsMessage("""{"type":"media","items":[{"url":"https://s.glbimg.com/vi/p3/black.mp4","kind":"video","src":"tag"}]}""")
        c.onRequest("https://cdn.site.example/v/movie.mp4", headers = ua)
        assertEquals(listOf("https://cdn.site.example/v/movie.mp4"), c.items.value.map { it.url })
    }

    @Test
    fun captchaPagesAreNotScanned() {
        val c = collector()
        c.onPageStarted("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/turnstile")
        assertTrue(c.isPageExcluded)
        c.onRequest("https://cdn.site.example/v/movie.mp4", headers = ua)
        c.onJsMessage("""{"type":"media","items":[{"url":"https://cdn.site.example/v/b.mp4","kind":"video"}]}""")
        assertTrue(c.items.value.isEmpty())
        c.onPageStarted(page)
        // captcha requests on a normal page are skipped too
        c.onRequest("https://www.google.com/recaptcha/api2/video.mp4", headers = ua)
        assertTrue(c.items.value.isEmpty())
        assertTrue(c.pendingProbeRequests().isEmpty())
    }

    @Test
    fun interventionsAndHeaderRulesApply() {
        val c = collector(config = PageMediaCollector.Config(acceptLanguage = "tr-TR"))
        c.onRequest("https://cdn.example.net/hls/abc/master.txt", headers = ua + ("Referer" to page))
        c.onRequest("https://a.fusevideo.io/v/movie.mp4", headers = ua + ("Referer" to page))
        val hls = c.items.value.first { it.url.endsWith("master.txt") }
        assertEquals(MediaClass.HLS, hls.mediaClass)
        assertEquals(page, hls.headers["Referer"])
        val fuse = c.items.value.first { it.url.contains("fusevideo") }
        assertEquals("tr-TR", fuse.headers["Accept-Language"])
        // the disguised playlist gets fetched like any HLS
        assertTrue(c.pendingProbeRequests().any { it.kind == ProbeKind.PLAYLIST && it.url.endsWith("master.txt") })
    }

    @Test
    fun keepListSurvivesNavigation() {
        val c = collector()
        c.onPageStarted("https://hianime.to/watch/show-1")
        c.onRequest("https://cdn.example/a/movie.mp4", headers = ua)
        c.onPageStarted("https://hianime.to/watch/show-1?ep=2")
        assertEquals(1, c.items.value.size)
        c.onPageStarted("https://site.example/other")
        c.onPageStarted("https://site.example/third")
        assertTrue(c.items.value.isEmpty())
    }

    @Test
    fun webPositionOgMetaAndValidation() {
        val c = collector()
        c.onJsMessage(
            """{"type":"media","top":true,"ogTitle":"Movie title","ogImage":"https://img.site.example/p.jpg",
               "items":[{"url":"https://cdn.site.example/v/a.mp4","kind":"video","src":"tag","durationMs":600000,"positionMs":125000.0},
                        {"url":"https://live.example/live/s.m3u8","kind":"hls","validate":true}]}"""
        )
        val a = c.items.value.first { it.url.endsWith("a.mp4") }
        assertEquals(125_000L, a.webPositionMs)
        assertEquals("Movie title", a.title)
        assertEquals("https://img.site.example/p.jpg", a.posterUrl)
        // a newer position replaces the old one
        c.onJsMessage("""{"type":"media","items":[{"url":"https://cdn.site.example/v/a.mp4","kind":"video","src":"tag","positionMs":130000}]}""")
        assertEquals(130_000L, c.items.value.first { it.url.endsWith("a.mp4") }.webPositionMs)
        // the webrtc guess is dropped when it does not load as a playlist
        assertTrue(c.items.value.any { it.url.endsWith("s.m3u8") })
        c.onProbeFailed("https://live.example/live/s.m3u8")
        assertFalse(c.items.value.any { it.url.endsWith("s.m3u8") })
    }

    @Test
    fun dashManifestGivesQualitiesDurationAndDrm() {
        val c = collector()
        c.onRequest("https://cdn.example/v/manifest.mpd", headers = ua)
        assertTrue(c.pendingProbeRequests().any { it.kind == ProbeKind.DASH })
        c.onDashManifestLoaded(
            "https://cdn.example/v/manifest.mpd",
            """<MPD mediaPresentationDuration="PT10M"><Period><AdaptationSet mimeType="video/mp4">
               <ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"/>
               <Representation bandwidth="1000" width="640" height="360"/><Representation bandwidth="3000" width="1920" height="1080"/>
               </AdaptationSet></Period></MPD>"""
        )
        val item = c.items.value.single()
        assertEquals(MediaClass.DASH, item.mediaClass)
        assertEquals(600_000L, item.durationMs)
        assertTrue(item.isDrmProtected)
        assertEquals(1080, item.height)
        assertEquals(listOf(1080, 360), item.variants.map { it.height })
        assertTrue(c.pendingProbeRequests().none { it.kind == ProbeKind.DASH })
    }

    @Test
    fun failedUrlsGoLast() {
        val failed = mutableSetOf<String>()
        val c = collector(failed = { it in failed })
        c.onRequest("https://cdn.example/master.m3u8", headers = ua)
        c.onRequest("https://cdn.example/movie.mp4", headers = ua)
        assertEquals("https://cdn.example/master.m3u8", c.items.value.first().url)
        failed += "https://cdn.example/master.m3u8"
        c.republish()
        assertEquals("https://cdn.example/movie.mp4", c.items.value.first().url)
        assertTrue(c.items.value.last().previouslyFailed)
    }

    @Test
    fun subtitlesFromQueryParameters() {
        val subs = MediaUrlClassifier.subtitleUrlsInQuery(
            "https://player.example/embed.php?file=https://cdn.example/a.m3u8&sub=https://cdn.example/subs/en.vtt&c1_file=/subs/tr.srt&c1_label=Turkce&poster=https://x/p.jpg"
        )
        assertEquals(listOf("https://cdn.example/subs/en.vtt", "https://player.example/subs/tr.srt"), subs)
        assertTrue(MediaUrlClassifier.subtitleUrlsInQuery("https://a.example/v.mp4?sub=https://a.example/video.m3u8").isEmpty())
        assertTrue(MediaUrlClassifier.subtitleUrlsInQuery("https://a.example/watch?id=1").isEmpty())
        assertEquals(
            listOf("https://s.example/x.ass"),
            MediaUrlClassifier.subtitleUrlsInQuery("https://a.example/play?track=https%3A%2F%2Fs.example%2Fx.ass")
        )
        val c = collector()
        c.onPageStarted("https://player.example/embed.php?vtt=https://cdn.example/subs/de.vtt")
        c.onRequest("https://cdn.example/movie.mp4", headers = ua)
        assertEquals(listOf("https://cdn.example/subs/de.vtt"), c.items.value.single().subtitles.map { it.url })
    }

    @Test
    fun failedUrlStoreTtlAndPersistence() {
        val dir = Files.createTempDirectory("failed").toFile()
        try {
            var now = 1_000L
            val file = File(dir, "failed.json")
            val store = FailedUrlStore(file, ttlMs = 10_000L) { now }
            store.markFailed("https://CDN.example/a.mp4#t=1")
            assertTrue(store.isFailed("https://cdn.example/a.mp4"))
            assertFalse(store.isFailed("https://cdn.example/b.mp4"))
            val reloaded = FailedUrlStore(file, ttlMs = 10_000L) { now }
            assertTrue(reloaded.isFailed("https://cdn.example/a.mp4"))
            now += 10_000L
            assertFalse(reloaded.isFailed("https://cdn.example/a.mp4"))
            assertEquals(0, reloaded.size)
            reloaded.markFailed("https://cdn.example/c.mp4")
            reloaded.markWorking("https://cdn.example/c.mp4")
            assertFalse(reloaded.isFailed("https://cdn.example/c.mp4"))
            reloaded.markFailed("https://cdn.example/d.mp4")
            reloaded.clear()
            assertEquals(0, reloaded.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun proberFetchesDashAndReportsFinalUrl() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.mp4"))
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Content-Length", "10"))
        server.enqueue(MockResponse().setBody("<MPD type=\"static\"></MPD>"))
        server.enqueue(MockResponse().setBody("<html></html>"))
        server.start()
        try {
            val prober = MediaProber(OkHttpClient())
            val head = prober.head(ProbeRequest(server.url("/go").toString()))
            assertEquals("video/mp4", head?.mime)
            assertTrue(head?.finalUrl!!.endsWith("/final.mp4"))
            assertTrue(prober.fetchDashManifest(ProbeRequest(server.url("/m.mpd").toString()))!!.contains("MPD"))
            assertNull(prober.fetchDashManifest(ProbeRequest(server.url("/n.mpd").toString())))
        } finally {
            server.shutdown()
        }
    }
}
