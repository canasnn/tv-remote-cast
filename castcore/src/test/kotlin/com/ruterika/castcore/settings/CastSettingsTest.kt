package com.ruterika.castcore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CastSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolvesAddressBarInput() {
        val s = CastSettings()
        assertEquals("https://example.com", s.resolveInput("example.com"))
        assertEquals("https://example.com/a?b=1", s.resolveInput("example.com/a?b=1"))
        assertEquals("http://x.y", s.resolveInput("http://x.y"))
        assertEquals("https://192.168.1.5:8080", s.resolveInput("192.168.1.5:8080"))
        assertEquals("https://www.google.com/search?q=film+izle", s.resolveInput("film izle"))
        assertEquals("https://duckduckgo.com/?q=a", s.copy(searchEngine = SearchEngine.DUCKDUCKGO).resolveInput("a"))
        assertEquals("https://s.test/?q=a", s.copy(searchEngine = SearchEngine.CUSTOM, customSearchTemplate = "https://s.test/?q=%s").resolveInput("a"))
    }

    @Test
    fun buildsSearchUrlsFromCustomEngines() {
        val engines = listOf(
            CustomSearchEngine("a", "Wiki", "https://en.wikipedia.org/w/index.php?search=%s"),
            CustomSearchEngine("b", "Twice", "https://t.test/%s?again=%s")
        )
        val s = CastSettings(searchEngine = SearchEngine.CUSTOM, customSearchEngines = engines, customSearchEngineId = "a")
        assertEquals("https://en.wikipedia.org/w/index.php?search=film+izle", s.searchUrl("film izle"))
        assertEquals("Wiki", s.searchEngineName)
        assertEquals("https://t.test/a%26b?again=a%26b", s.copy(customSearchEngineId = "b").searchUrl("a&b"))
        // Address bar input goes through the same engine.
        assertEquals("https://en.wikipedia.org/w/index.php?search=x", s.resolveInput("x"))
        // Built-in engines ignore the custom list.
        assertEquals("https://www.bing.com/search?q=x", s.copy(searchEngine = SearchEngine.BING).searchUrl("x"))
        assertEquals("Bing", s.copy(searchEngine = SearchEngine.BING).searchEngineName)
        // No id yet: the first custom engine.
        assertEquals("Wiki", s.copy(customSearchEngineId = "").searchEngineName)
        // A template without %s still searches that site (never Google).
        val noPlaceholder = CastSettings(
            searchEngine = SearchEngine.CUSTOM,
            customSearchEngines = listOf(CustomSearchEngine("c", "Old", "https://old.test/find?q=")),
            customSearchEngineId = "c"
        )
        assertEquals("https://old.test/find?q=a+b", noPlaceholder.searchUrl("a b"))
        assertEquals("https://old.test/find?q=a", CastSettings(searchEngine = SearchEngine.CUSTOM, customSearchTemplate = "https://old.test/find?q=").searchUrl("a"))
        // The selected engine was deleted: Google.
        assertEquals("https://www.google.com/search?q=a", s.copy(customSearchEngineId = "gone").searchUrl("a"))
    }

    @Test
    fun validatesCustomSearchTemplates() {
        assertNull(SearchTemplates.templateError("https://example.com/search?q=%s"))
        assertNull(SearchTemplates.templateError("  http://192.168.1.2:8080/?q=%s "))
        assertNotNull(SearchTemplates.templateError(""))
        assertNotNull(SearchTemplates.templateError("example.com/search?q=%s"))
        assertNotNull(SearchTemplates.templateError("ftp://example.com/%s"))
        assertNotNull(SearchTemplates.templateError("javascript:alert(%s)"))
        assertEquals(
            "Put %s where the search words go, e.g. https://example.com/search?q=%s",
            SearchTemplates.templateError("https://example.com/search?q=")
        )
        assertNotNull(SearchTemplates.templateError("https://exa mple.com/?q=%s"))

        val engines = listOf(CustomSearchEngine("a", "Wiki", "https://w.test/?q=%s"))
        assertNull(SearchTemplates.nameError("Mine", engines))
        assertNotNull(SearchTemplates.nameError(" ", engines))
        assertNotNull(SearchTemplates.nameError("wiki ", engines))
        assertNull(SearchTemplates.nameError("Wiki", engines, editingId = "a"))
        assertNotNull(SearchTemplates.nameError("google", engines))
    }

    @Test
    fun migratesTheOldCustomTemplate() {
        // In use: becomes the selected engine called "Custom".
        val inUse = CastSettings(searchEngine = SearchEngine.CUSTOM, customSearchTemplate = " https://s.test/?q=%s ").migrated()
        assertEquals("", inUse.customSearchTemplate)
        assertEquals(listOf(CustomSearchEngine(SearchTemplates.LEGACY_ID, "Custom", "https://s.test/?q=%s")), inUse.customSearchEngines)
        assertEquals(SearchTemplates.LEGACY_ID, inUse.customSearchEngineId)
        assertEquals(SearchEngine.CUSTOM, inUse.searchEngine)
        assertEquals("https://s.test/?q=a", inUse.searchUrl("a"))
        assertEquals(inUse, inUse.migrated())

        // Not in use: kept in the list, the current engine stays.
        val unused = CastSettings(searchEngine = SearchEngine.BING, customSearchTemplate = "https://s.test/?q=%s").migrated()
        assertEquals(1, unused.customSearchEngines.size)
        assertEquals(SearchEngine.BING, unused.searchEngine)
        assertEquals("", unused.customSearchEngineId)

        // A template without %s is kept (and still searches there).
        val noPlaceholder = CastSettings(searchEngine = SearchEngine.CUSTOM, customSearchTemplate = "https://s.test/?q=").migrated()
        assertEquals("https://s.test/?q=a", noPlaceholder.searchUrl("a"))

        // Already in the list (same template): not added twice; a taken name gets a number.
        val dup = CastSettings(
            customSearchTemplate = "https://s.test/?q=%s",
            customSearchEngines = listOf(CustomSearchEngine("x", "Custom", "https://s.test/?q=%s"))
        ).migrated()
        assertEquals(1, dup.customSearchEngines.size)
        val taken = CastSettings(
            customSearchTemplate = "https://new.test/?q=%s",
            customSearchEngines = listOf(CustomSearchEngine("x", "Custom", "https://s.test/?q=%s"))
        ).migrated()
        assertEquals(listOf("Custom", "Custom 2"), taken.customSearchEngines.map { it.name })

        // Nothing to migrate.
        assertEquals(CastSettings(), CastSettings().migrated())
    }

    @Test
    fun storeMigratesOldSettingsFiles() {
        val file = tmp.root.resolve("settings.json").apply {
            writeText("""{"searchEngine":"CUSTOM","customSearchTemplate":"https://s.test/?q=%s","skipBackSeconds":45}""")
        }
        val loaded = CastSettingsStore(file).current
        assertEquals(45, loaded.skipBackSeconds)
        assertEquals("Custom", loaded.searchEngineName)
        assertEquals("https://s.test/?q=a", loaded.searchUrl("a"))
        assertEquals("", loaded.customSearchTemplate)
        // Saved in the new form with the next change.
        CastSettingsStore(file).update { it.copy(skipForwardSeconds = 10) }
        val text = file.readText()
        assertTrue(text.contains("customSearchEngines"))
        assertFalse(text.contains("customSearchTemplate"))
    }

    @Test
    fun persistsChanges() {
        val file = tmp.root.resolve("settings.json")
        CastSettingsStore(file).update { it.copy(skipBackSeconds = 45, subtitleLanguages = listOf("de")) }
        val reloaded = CastSettingsStore(file).current
        assertEquals(45, reloaded.skipBackSeconds)
        assertEquals(listOf("de"), reloaded.subtitleLanguages)
    }

    @Test
    fun controllerOptionsHaveDefaultsAndPersist() {
        val defaults = CastSettings()
        assertEquals(true, defaults.sendTitleToTv)
        assertEquals(false, defaults.pauseOnCall)
        assertEquals(false, defaults.resumeAfterCall)
        assertEquals(true, defaults.showMiniController)
        assertEquals(true, defaults.showHelpTips)
        assertEquals(true, defaults.askVideoPlayed)
        assertEquals(false, defaults.batteryHintDismissed)

        val file = tmp.root.resolve("settings.json")
        CastSettingsStore(file).update { it.copy(sendTitleToTv = false, pauseOnCall = true, resumeAfterCall = true, showHelpTips = false) }
        val reloaded = CastSettingsStore(file).current
        assertEquals(false, reloaded.sendTitleToTv)
        assertEquals(true, reloaded.pauseOnCall)
        assertEquals(true, reloaded.resumeAfterCall)
        assertEquals(false, reloaded.showHelpTips)
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        val file = tmp.root.resolve("settings.json").apply { writeText("{oops") }
        assertEquals(CastSettings(), CastSettingsStore(file).current)
    }
}
