package com.ruterika.castcore.web

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SiteRulesTest {

    private val rules = SiteRules.default()
    private val page = "https://site.example/watch/1"

    @Test
    fun embeddedRulesLoad() {
        assertTrue(rules.version >= 1)
        assertTrue(rules.document.interventions.isNotEmpty())
        assertTrue(rules.document.ignore.isNotEmpty())
        assertTrue(rules.adRedirect.size >= 3)
        assertTrue(rules.sandboxFixPages.isNotEmpty())
    }

    @Test
    fun userAgentRulesMatchHostsAndSubdomains() {
        val r = SiteRules.parse(
            """{"userAgents":[
                {"name":"Tablet site","hosts":["tablet.example",".Other.Example"],"userAgentKey":"safari_ipad"},
                {"name":"Own UA","hosts":["own.example"],"userAgent":"Custom UA/1.0"},
                {"name":"No UA","hosts":["empty.example"]},
                {"name":"No host","hosts":[" "],"userAgentKey":"chrome_windows"}
            ]}"""
        )
        assertTrue(r.hasUserAgentRules)
        assertEquals("Tablet site", r.userAgentRuleFor("https://tablet.example/watch")?.name)
        assertEquals("Tablet site", r.userAgentRuleFor("https://www.tablet.example/")?.name)
        assertEquals("Tablet site", r.userAgentRuleFor("http://m.other.example:8080/a")?.name)
        assertEquals("Custom UA/1.0", r.userAgentRuleFor("https://own.example")?.userAgent)
        assertNull(r.userAgentRuleFor("https://nottablet.example/"))
        assertNull(r.userAgentRuleFor("https://tablet.example.evil.test/"))
        assertNull(r.userAgentRuleFor("https://empty.example/"))
        assertNull(r.userAgentRuleFor(null))
        assertNull(r.userAgentRuleFor("about:blank"))
        // The embedded rules parse with the field; no rule means no automatic UA.
        assertFalse(SiteRules.EMPTY.hasUserAgentRules)
        assertNull(SiteRules.EMPTY.userAgentRuleFor("https://tablet.example/"))
        assertNotNull(rules.document.userAgents)
    }

    @Test
    fun captchaAndSignInAreExcluded() {
        assertTrue(rules.isNoDetect("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/orchestrate"))
        assertTrue(rules.isNoDetect("https://www.google.com/recaptcha/api2/anchor?k=x"))
        assertTrue(rules.isNoDetect("https://newassets.hcaptcha.com/captcha/v1/abc"))
        assertTrue(rules.isNoDetect("https://accounts.google.com/o/oauth2/auth?client_id=1"))
        assertFalse(rules.isNoDetect("https://cdn.site.example/v/movie.mp4"))
        assertFalse(rules.isNoDetect(null))
    }

    @Test
    fun ignoreListDropsStubs() {
        assertTrue(rules.shouldIgnore("https://ok.ru/res/i/video/stub.mp4", page, null))
        assertTrue(rules.shouldIgnore("https://s.glbimg.com/vi/p3/black.mp4", page, null))
        assertTrue(rules.shouldIgnore("https://x.cdninstagram.com/v/t50/a_video_dashinit.mp4?x=1", page, null))
        assertTrue(rules.shouldIgnore("https://vkvd1.okcdn.ru/a.webm", "https://vk.com/video1", null))
        assertFalse(rules.shouldIgnore("https://vkvd1.okcdn.ru/a.webm", page, null))
        assertFalse(rules.shouldIgnore("https://cdn.site.example/movie.mp4", page, null))
    }

    @Test
    fun interventionsAssignTypes() {
        val kaltura = rules.intervention(RuleContext("https://cdnapisec.kaltura.com/p/1/sp/100/playManifest/entryId/0_x/format/applehttp/protocol/https/flavorIds/a", page))
        assertEquals(MediaClass.HLS, kaltura?.mediaClass)
        val dash = rules.intervention(RuleContext("https://cdnapisec.kaltura.com/p/1/playManifest/entryId/0_x/format/mpegdash/protocol/https", page))
        assertEquals(MediaClass.DASH, dash?.mediaClass)
        val txt = rules.intervention(RuleContext("https://cdn.example.net/hls/abc/master.txt", page))
        assertEquals(MediaClass.HLS, txt?.mediaClass)
        val wistia = rules.intervention(RuleContext("https://embed-ssl.wistia.com/deliveries/0123abcd.bin", page))
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, wistia?.mediaClass)
        assertNull(rules.intervention(RuleContext("https://cdn.example.net/app.js", page)))
    }

    @Test
    fun customDocumentFunctionsAndInterventions() {
        val json = """
            {
              "version": 3,
              "interventions": [
                { "name": "rewrite", "match": { "rules": [ { "field": "url", "function": "endsWith", "value": "/thumbnails.vtt" } ] },
                  "intervention": { "type": "url-replace-substring", "params": { "old-value": "/thumbnails.vtt", "new-value": "/master.m3u8" } } },
                { "name": "no referer", "match": { "type": "all", "rules": [
                    { "field": "host", "function": "equals", "value": "media.example" },
                    { "field": "header::X-Kind", "function": "equalsIgnoreCase", "value": "VIDEO" } ] },
                  "intervention": { "type": "headers-remove", "params": { "headers": "Referer, Origin" } } },
                { "name": "broken", "match": { "rules": [ { "field": "url", "function": "matchesRegex", "value": "([" } ] },
                  "intervention": { "type": "ignore" } },
                { "name": "drop", "match": { "rules": [ { "field": "path", "function": "notContains", "value": "/" } ] },
                  "intervention": { "type": "ignore" } }
              ]
            }
        """.trimIndent()
        val r = SiteRules.parse(json)
        assertEquals(3, r.version)
        val rewritten = r.intervention(RuleContext("https://a.example/v/1/thumbnails.vtt", page))
        assertEquals("https://a.example/v/1/master.m3u8", rewritten?.url)
        assertEquals(MediaClass.HLS, rewritten?.mediaClass)
        val removed = r.intervention(RuleContext("https://media.example/x", page, null, mapOf("x-kind" to "video")))
        assertEquals(setOf("Referer", "Origin"), removed?.removeHeaders)
        // the invalid regex never matches; the next rule still works
        assertNull(r.intervention(RuleContext("https://other.example/a", page)))
        assertTrue(r.intervention(RuleContext("https://other.example", page))?.ignore == true)
        assertNull(SiteRules.parseOrNull("{ not json"))
    }

    @Test
    fun headerRules() {
        val tw = rules.applyHeaderRules("https://video.twimg.com/a.mp4", page, mapOf("Referer" to page, "User-Agent" to "UA"), "tr-TR")
        assertFalse(tw.containsKey("Referer"))
        assertEquals("UA", tw["User-Agent"])
        val origin = rules.applyHeaderRules("https://x.nuuuppp.cloud/a.m3u8", page, mapOf("Referer" to "https://player.example/embed/1"), null)
        assertEquals("https://player.example", origin["Origin"])
        val lang = rules.applyHeaderRules("https://a.fusevideo.io/m.m3u8", page, emptyMap(), "tr-TR,tr;q=0.9")
        assertEquals("tr-TR,tr;q=0.9", lang["Accept-Language"])
        val untouched = mapOf("Referer" to page)
        assertSame(untouched, rules.applyHeaderRules("https://cdn.site.example/a.mp4", page, untouched, "tr"))
    }

    @Test
    fun keepListAndSandboxPages() {
        assertTrue(rules.keepsList("https://hianime.to/watch/x"))
        assertFalse(rules.keepsList("https://site.example/a"))
        assertFalse(rules.keepsList(null))
        assertTrue(rules.needsSandboxFix("https://ppv.st/live/1"))
        assertFalse(rules.needsSandboxFix(page))
    }

    @Test
    fun storeUpdatesCachesAndResets() = runBlocking {
        val dir = Files.createTempDirectory("rules").toFile()
        val cache = File(dir, "rules.json")
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":99,"keepList":{"contains":["keep.example"]}}"""))
        server.enqueue(MockResponse().setBody("""{"version":99,"keepList":{"contains":["keep.example"]}}"""))
        server.enqueue(MockResponse().setBody("nope"))
        server.enqueue(MockResponse().setBody("""{"version":0}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        try {
            val store = SiteRulesStore(cache, OkHttpClient())
            assertFalse(store.usesDownloaded)
            val url = server.url("/rules.json").toString()
            assertEquals(SiteRulesStore.UpdateResult.Updated(99), store.update(url))
            assertTrue(store.rules.value.keepsList("https://keep.example/a"))
            assertEquals(SiteRulesStore.UpdateResult.Unchanged, store.update(url))
            assertTrue(store.update(url) is SiteRulesStore.UpdateResult.Failed) // not JSON
            assertTrue(store.update(url) is SiteRulesStore.UpdateResult.Failed) // older than built-in
            assertTrue(store.update(url) is SiteRulesStore.UpdateResult.Failed) // HTTP 500
            assertEquals(99, store.rules.value.version)
            // A new store picks up the cache.
            val again = SiteRulesStore(cache, OkHttpClient())
            assertEquals(99, again.rules.value.version)
            again.reset()
            assertFalse(cache.exists())
            assertEquals(SiteRules.default().version, again.rules.value.version)
            assertTrue(again.update("ftp://x") is SiteRulesStore.UpdateResult.Failed)
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }
}
