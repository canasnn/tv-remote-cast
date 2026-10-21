package com.ruterika.castcore.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorScriptTest {

    @Test
    fun scriptLoadsFromClasspath() {
        val js = DetectorScript.load()
        assertTrue(js.isNotBlank())
        assertTrue(js.contains(DetectorScript.BRIDGE_NAME))
        assertTrue(js.contains("window.RuterikaBridge") || js.contains("W.RuterikaBridge"))
        assertSame(DetectorScript.source, DetectorScript.source)
    }

    @Test
    fun scriptIsSmallAndExportsHooks() {
        val js = DetectorScript.source
        val bytes = js.toByteArray(Charsets.UTF_8).size
        assertTrue("detector.js is $bytes bytes", bytes < 13 * 1024)
        assertTrue(js.contains("__ruterikaPauseAll"))
        assertTrue(js.contains("__ruterikaScan"))
        assertTrue(js.contains("MutationObserver"))
        assertTrue(js.contains("JSON.stringify"))
        assertTrue(js.contains("'media'"))
        // guarded bridge access and blob handling
        assertTrue(js.contains("typeof b.post === 'function'"))
        assertTrue(js.contains("blob"))
        assertTrue(DetectorScript.PAUSE_ALL_JS.contains("__ruterikaPauseAll"))
        // no modern syntax beyond ES2017 that old WebViews choke on
        assertFalse(js.contains("?."))
        assertFalse(js.contains("??"))
        assertFalse(js.contains("catch {"))
    }

    @Test
    fun scriptHasParityHooks() {
        val js = DetectorScript.source
        // ad skipping, SPA re-scan, web page position, extra extractors
        assertTrue(js.contains("__ruterikaSkipAds"))
        assertTrue(js.contains("pushState") && js.contains("popstate"))
        assertTrue(js.contains("positionMs"))
        assertTrue(js.contains("hlsManifestUrl"))
        assertTrue(js.contains("playable_url"))
        assertTrue(js.contains("playerConfig"))
        assertTrue(js.contains("videourl_mp4"))
        assertTrue(js.contains("webrtc"))
        assertTrue(js.contains("og:image"))
        assertTrue(DetectorScript.SKIP_ALL_ADS_JS.contains("__ruterikaSkipAds(1)"))
        assertTrue(DetectorScript.SKIP_LIKELY_ADS_JS.contains("__ruterikaSkipAds(2)"))
    }

    @Test
    fun optionalSnippets() {
        assertTrue(DetectorScript.DISABLE_DASH_JS.contains("isTypeSupported"))
        assertTrue(DetectorScript.DISABLE_DASH_JS.contains("youtube"))
        val fix = DetectorScript.sandboxFixJs(listOf("Example.ORG", "it's"))
        assertTrue(fix.contains("'example.org'"))
        assertTrue(fix.contains("'it\\'s'"))
        assertTrue(fix.contains("removeAttribute('sandbox')"))
        assertFalse(fix.contains("?."))
    }
}
