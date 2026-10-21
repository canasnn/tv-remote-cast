package com.ruterika.castcore.browser

import com.ruterika.castcore.library.Bookmark
import com.ruterika.castcore.library.HistoryEntry
import com.ruterika.castcore.library.LibraryData
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.CustomUserAgent
import com.ruterika.castcore.settings.DarkPages
import com.ruterika.castcore.settings.SearchEngine
import com.ruterika.castcore.web.SiteRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHelpersTest {

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"

    @Test
    fun siteUserAgentAppliesUnlessTheTabHasItsOwn() {
        val rules = SiteRules.parse(
            """{"userAgents":[
                {"hosts":["tablet.example"],"userAgentKey":"safari_ipad"},
                {"hosts":["own.example"],"userAgent":"Own UA"},
                {"hosts":["bad.example"],"userAgentKey":"no_such_key"}
            ]}"""
        )
        val s = CastSettings(userAgentKey = UserAgents.DESKTOP_KEY)
        val ipad = UserAgents.builtIn.first { it.key == "safari_ipad" }.value
        val desktop = UserAgents.builtIn.first { it.key == UserAgents.DESKTOP_KEY }.value
        // No choice on the tab: the site rule wins over the default from settings.
        assertEquals(ipad, UserAgents.forPage(null, "https://www.tablet.example/v/1", rules, s, webViewUa))
        assertEquals("Own UA", UserAgents.forPage(null, "https://own.example/", rules, s, webViewUa))
        // Other sites and unknown keys: the default from settings.
        assertEquals(desktop, UserAgents.forPage(null, "https://other.example/", rules, s, webViewUa))
        assertEquals(desktop, UserAgents.forPage(null, "https://bad.example/", rules, s, webViewUa))
        // The user picked a UA for the tab: it is kept on every site.
        assertNull(UserAgents.forPage(UserAgents.NONE, "https://tablet.example/", rules, s, webViewUa))
        assertEquals(desktop, UserAgents.forPage(UserAgents.DESKTOP_KEY, "https://tablet.example/", rules, CastSettings(), webViewUa))
        // No rules: unchanged behaviour.
        assertNull(UserAgents.forPage(null, "https://tablet.example/", SiteRules.EMPTY, CastSettings(), webViewUa))
    }

    @Test
    fun resolvesUserAgents() {
        val s = CastSettings()
        assertEquals(UserAgents.NONE, UserAgents.defaultKey(s))
        assertNull(UserAgents.resolve(UserAgents.NONE, s, webViewUa))
        assertEquals(UserAgents.DESKTOP_KEY, UserAgents.defaultKey(s.copy(desktopMode = true)))
        assertEquals("UA1", UserAgents.resolve(UserAgents.defaultKey(s.copy(customUserAgent = "UA1")), s.copy(customUserAgent = "UA1"), webViewUa))
        val custom = s.copy(customUserAgents = listOf(CustomUserAgent("a", "Mine", "My UA")), userAgentKey = "custom:a")
        assertEquals("My UA", UserAgents.resolve(UserAgents.defaultKey(custom), custom, webViewUa))
        assertEquals("Mine", UserAgents.label("custom:a", custom))
        assertTrue(UserAgents.isDesktop(UserAgents.DESKTOP_KEY, s))
        assertFalse(UserAgents.isDesktop("safari_iphone", s))
        val chrome = UserAgents.deviceChrome(webViewUa)
        assertFalse("; wv" in chrome)
        assertFalse("Version/4.0" in chrome)
        assertTrue(chrome.contains("Chrome/140.0.0.0 Mobile Safari"))
    }

    @Test
    fun localSuggestionsPreferPrefixAndBookmarks() {
        val data = LibraryData(
            bookmarks = listOf(Bookmark("https://www.youtube.com/", "YouTube", 1)),
            history = listOf(
                HistoryEntry("https://example.com/you", "Example", 3),
                HistoryEntry("https://youtu.be/x", "Short", 2),
                HistoryEntry("https://www.youtube.com/", "YouTube", 1)
            )
        )
        val result = AddressSuggestions.local("you", data)
        assertEquals(listOf("https://www.youtube.com/", "https://youtu.be/x", "https://example.com/you"), result.map { it.url })
        assertEquals(AddressSuggestions.Kind.BOOKMARK, result.first().kind)
        assertTrue(AddressSuggestions.local(" ", data).isEmpty())
    }

    @Test
    fun parsesOpenSearchSuggestions() {
        assertEquals(listOf("film izle", "film"), AddressSuggestions.parseOpenSearch("""["fil",["film izle","film"],[],{"x":1}]"""))
        assertTrue(AddressSuggestions.parseOpenSearch("not json").isEmpty())
        assertTrue(AddressSuggestions.remoteUrl(CastSettings(searchEngine = SearchEngine.BING), "a b").contains("osjson.aspx?query=a+b"))
    }

    @Test
    fun sslIgnoreAndDarkModeFallbacks() {
        val s = CastSettings(sslIgnoredHosts = setOf("example.com"))
        assertTrue(s.ignoresSslFor("www.example.com"))
        assertTrue(s.ignoresSslFor("cdn.example.com"))
        assertFalse(s.ignoresSslFor("example.org"))
        assertTrue(CastSettings(sslIgnoreAllHosts = true).ignoresSslFor("any.host"))
        assertEquals(DarkPages.OFF, CastSettings().effectiveDarkPages)
        assertEquals(DarkPages.ON, CastSettings(forceDarkPages = true).effectiveDarkPages)
        assertEquals(DarkPages.AUTO, CastSettings(darkPages = DarkPages.AUTO).effectiveDarkPages)
    }
}
