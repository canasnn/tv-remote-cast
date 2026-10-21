package com.ruterika.castcore.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    private val list = """
        # hosts style
        0.0.0.0 ads.example.com
        127.0.0.1   tracker.example.org   metrics.example.org
        0.0.0.0 localhost
        ::1 ip6.example.net
        ! adblock style
        [Adblock Plus 2.0]
        ||popads.example^
        ||video-ads.example.net^${'$'}third-party
        ||example.net/path/ads^
        @@||good.ads.example.com^
        # plain
        doubleclick.example
        Upper.Example.IO   # trailing comment
        not a domain line
        *.wild.example
        192.168.1.1
    """.trimIndent()

    private val blocker = AdBlocker.fromLists(list)

    @Test
    fun parsesAllFormats() {
        val parsed = AdBlocker.parseList(list)
        assertEquals(
            setOf(
                "ads.example.com", "tracker.example.org", "metrics.example.org", "ip6.example.net",
                "popads.example", "video-ads.example.net", "doubleclick.example", "upper.example.io",
            ),
            parsed.blocked,
        )
        assertEquals(setOf("good.ads.example.com"), parsed.allowed)
    }

    @Test
    fun matchesHostAndSubdomains() {
        assertTrue(blocker.isBlocked("https://ads.example.com/x.mp4"))
        assertTrue(blocker.isBlocked("https://cdn.ads.example.com:8443/x.mp4"))
        assertTrue(blocker.isBlocked("https://a.b.c.popads.example/"))
        assertTrue(blocker.isBlocked("upper.example.io"))
        assertTrue(blocker.isBlocked("HTTPS://UPPER.EXAMPLE.IO/A"))
        assertTrue(blocker.isBlocked("https://user:pw@tracker.example.org/p"))
        assertTrue(blocker.isBlocked("https://ads.example.com./trailing-dot"))
        assertFalse(blocker.isBlocked("https://example.com/video.mp4"))
        assertFalse(blocker.isBlocked("https://notads.example.com/video.mp4"))
        assertFalse(blocker.isBlocked("https://example.net/path/ads/v.mp4"))
        assertFalse(blocker.isBlocked("not a url"))
        assertFalse(blocker.isBlocked(""))
    }

    @Test
    fun allowlistWinsAtSameOrMoreSpecificLevel() {
        assertFalse(blocker.isBlocked("https://good.ads.example.com/v.m3u8"))
        assertFalse(blocker.isBlocked("https://cdn.good.ads.example.com/v.m3u8"))
        assertTrue(blocker.isBlocked("https://bad.ads.example.com/v.m3u8"))

        // allowing a parent does not unblock a more specific blocked subdomain
        val b = AdBlocker.Builder().block("ads.site.com").allow("site.com").build()
        assertTrue(b.isBlocked("https://ads.site.com/a"))
        assertFalse(b.isBlocked("https://www.site.com/a"))

        val c = blocker.withAllowed(listOf("ads.example.com"))
        assertFalse(c.isBlocked("https://ads.example.com/x"))
        val d = AdBlocker.EMPTY.withBlocked(listOf("x.example"))
        assertTrue(d.isBlocked("https://y.x.example/"))
    }

    @Test
    fun defaultListLoadsFromClasspath() {
        assertNotNull(AdBlocker.loadDefaultListText())
        val def = AdBlocker.default()
        assertTrue("default list too small: ${def.blockedCount}", def.blockedCount >= 140)
        listOf(
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://pagead2.googlesyndication.com/pagead/show_ads.js",
            "https://adservice.google.com/adsid/integrator.js",
            "https://imasdk.googleapis.com/js/sdkloader/ima3.js",
            "https://s0.2mdn.net/ads/video.mp4",
            "https://c.popads.net/pop.js",
            "https://main.exoclick.com/tag.php",
            "https://syndication.exosrv.com/splash.php",
            "https://cdn.propellerads.com/x",
        ).forEach { assertTrue(it, def.isBlocked(it)) }
        listOf(
            "https://www.google.com/search",
            "https://fonts.googleapis.com/css",
            "https://www.youtube.com/watch?v=1",
            "https://vimeo.com/123",
            "https://cdn.jsdelivr.net/npm/hls.js",
        ).forEach { assertFalse(it, def.isBlocked(it)) }
        assertTrue(AdBlocker.default() === def)
    }

    @Test
    fun lookupIsFastForLargeLists() {
        val big = buildString { for (i in 0 until 50_000) append("0.0.0.0 ad").append(i).append(".example.com\n") }
        val b = AdBlocker.fromLists(big)
        assertEquals(50_000, b.blockedCount)
        val start = System.nanoTime()
        repeat(100_000) { b.isBlockedHost("deep.sub.domain.ad49999.example.com") }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(b.isBlockedHost("deep.sub.domain.ad49999.example.com"))
        assertTrue("too slow: ${ms}ms", ms < 2_000)
    }
}
