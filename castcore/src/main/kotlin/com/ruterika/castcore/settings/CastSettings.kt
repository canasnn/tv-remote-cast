package com.ruterika.castcore.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class SearchEngine(val label: String, val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    YANDEX("Yandex", "https://yandex.com.tr/search/?text=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s"),
    /** One of [CastSettings.customSearchEngines], chosen by [CastSettings.customSearchEngineId]. */
    CUSTOM("Custom", "")
}

/** A search engine the user added: [template] holds `%s` for the query. */
@Serializable
data class CustomSearchEngine(val id: String, val name: String, val template: String)

/** Building and checking search URL templates (`https://…?q=%s`). */
object SearchTemplates {
    const val PLACEHOLDER = "%s"
    /** Query used by the "Test" button and to check that a template gives a valid URL. */
    const val SAMPLE_QUERY = "test"
    /** Id of the engine made from the old single `customSearchTemplate` field. */
    const val LEGACY_ID = "legacy"

    private val WEB_URL = Regex("^https?://[^/?#\\s]+", RegexOption.IGNORE_CASE)

    /** True for an http(s) address with a host. */
    fun isWeb(template: String): Boolean = WEB_URL.containsMatchIn(template.trim())

    /**
     * [query] put into [template]: in place of every `%s`, or added at the end when the template
     * has none (templates saved by older versions), so the search still goes to that engine.
     */
    fun build(template: String, query: String): String {
        val t = template.trim()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return if (t.contains(PLACEHOLDER)) t.replace(PLACEHOLDER, encoded) else t + encoded
    }

    /** Why [template] can't be saved, or null when it is fine. */
    fun templateError(template: String): String? {
        val t = template.trim()
        return when {
            t.isEmpty() -> "Enter the search address"
            !isWeb(t) -> "The address must start with http:// or https://"
            !t.contains(PLACEHOLDER) -> "Put %s where the search words go, e.g. https://example.com/search?q=%s"
            runCatching { java.net.URI(build(t, SAMPLE_QUERY)).host }.getOrNull().isNullOrBlank() -> "This is not a valid web address"
            else -> null
        }
    }

    /** Why [name] can't be used (blank or taken), or null. [editingId] is the engine being edited. */
    fun nameError(name: String, engines: List<CustomSearchEngine>, editingId: String? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Enter a name"
        val builtIn = SearchEngine.entries.filter { it != SearchEngine.CUSTOM }.map { it.label }
        val taken = builtIn.any { it.equals(n, ignoreCase = true) } ||
            engines.any { it.id != editingId && it.name.trim().equals(n, ignoreCase = true) }
        return if (taken) "There is already a search engine with this name" else null
    }
}

@Serializable
enum class TabRestore(val label: String) { ASK("Ask"), ALWAYS("Always"), NEVER("Never") }

/** What a tap on a detected video does. */
@Serializable
enum class VideoTapAction(val label: String) {
    ASK("Show options"),
    CAST("Cast to TV"),
    PLAY_ON_PHONE("Play on phone")
}

@Serializable
enum class SubtitleCharsetPref(val label: String, val charset: String?) {
    AUTO("Automatic", null),
    UTF8("UTF-8", "UTF-8"),
    TURKISH("Turkish (Windows-1254)", "windows-1254"),
    WESTERN("Western (Windows-1252)", "windows-1252"),
    CYRILLIC("Cyrillic (Windows-1251)", "windows-1251"),
    ARABIC("Arabic (Windows-1256)", "windows-1256"),
    CENTRAL_EU("Central European (Windows-1250)", "windows-1250")
}

@Serializable
enum class ResumeMode(val label: String) { ASK("Ask"), ALWAYS("Always"), NEVER("Never") }

@Serializable
enum class CastRepeat(val label: String) { OFF("Off"), ONE("Repeat one"), ALL("Repeat all") }

@Serializable
enum class SubtitleEdge(val label: String) { NONE("None"), OUTLINE("Outline"), DROP_SHADOW("Drop shadow"), RAISED("Raised"), DEPRESSED("Depressed") }

/** None, behind each line, or one box behind the whole caption. */
@Serializable
enum class SubtitleBackground(val label: String) { NONE("None"), NORMAL("Normal"), BOX("Box") }

/** "Force web page dark mode". AUTO follows the app's own theme. */
@Serializable
enum class DarkPages(val label: String) { AUTO("Automatic"), ON("On"), OFF("Off") }

/** Where a page opened from elsewhere goes. */
@Serializable
enum class OpenTabTarget(val label: String) { NEW_TAB("New tab"), CURRENT_TAB("Current tab") }

/** What the app shows when it starts. */
@Serializable
enum class StartScreen(val label: String) {
    REMOTE("Remote"),
    CAST("Cast hub"),
    BROWSER("Browser"),
    PHONE_FILES("Phone files"),
    IPTV("IPTV"),
    RECENT("Recent media"),
    LAST_USED("Last used")
}

/**
 * What happens to the page's own video when something is cast from it.
 * The page side is the detector's `__ruterikaSkipAds` hook.
 */
@Serializable
enum class VideoAdMode(val label: String, val description: String) {
    NONE("None", "Pause the page video when casting"),
    SMART("Smart", "Skip ads on the page when the cast video looks like an ad, otherwise pause the page"),
    MANUAL_1("Manual 1", "Jump every page video to its end so the real video loads"),
    MANUAL_2("Manual 2", "Leave the page playing so its ad can finish")
}

/** A user agent the user added (name + full UA string). */
@Serializable
data class CustomUserAgent(val id: String, val name: String, val value: String)

@Serializable
data class SubtitleLook(
    /** Percent of the default size (100 = media3 default). */
    val sizePercent: Int = 100,
    val textColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000,
    val backgroundStyle: SubtitleBackground = SubtitleBackground.NORMAL,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val edgeColor: Long = 0xFF000000,
    val bold: Boolean = false,
    /** 0 = bottom edge … 100 = top; default 90 (near the bottom). */
    val verticalPosition: Int = 90,
    /** Keep colors/positions defined inside the file (ASS/VTT). */
    val useFileStyles: Boolean = true,
    /** null = default font. */
    val fontFamily: String? = null
) {
    /** Colour behind each line (none for NONE/BOX). */
    val lineBackground: Long get() = if (backgroundStyle == SubtitleBackground.NORMAL) backgroundColor else 0L
    /** Colour of the box behind the whole caption (BOX only). */
    val windowBackground: Long get() = if (backgroundStyle == SubtitleBackground.BOX) backgroundColor else 0L
}

/**
 * Every option of the cast side. One immutable value,
 * persisted as JSON; UI binds to [CastSettingsStore.settings].
 */
@Serializable
data class CastSettings(
    // Browser
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    /** Older single custom template; moved into [customSearchEngines] by [migrated]. */
    val customSearchTemplate: String = "",
    val customSearchEngines: List<CustomSearchEngine> = emptyList(),
    /** The custom engine in use when [searchEngine] is [SearchEngine.CUSTOM]. */
    val customSearchEngineId: String = "",
    val homepage: String = "",
    val desktopMode: Boolean = false,
    val customUserAgent: String = "",
    val blockPopups: Boolean = true,
    val adBlock: Boolean = true,
    val blockExternalApps: Boolean = true,
    val blockAdRedirects: Boolean = true,
    val forceDarkPages: Boolean = false,
    val thirdPartyCookies: Boolean = true,
    val restoreTabs: TabRestore = TabRestore.ASK,
    /** Browsing history older than this many days is dropped (0 = off). */
    val historyAutoDeleteDays: Int = 0,
    val allowedPopupHosts: Set<String> = emptySet(),
    val adBlockAllowHosts: Set<String> = emptySet(),
    /** Default UA for tabs (see [com.ruterika.castcore.browser.UserAgents]); "" = legacy fields above. */
    val userAgentKey: String = "",
    val customUserAgents: List<CustomUserAgent> = emptyList(),
    /** null = not chosen yet, falls back to [forceDarkPages]. */
    val darkPages: DarkPages? = null,
    val showZoomButtons: Boolean = false,
    val requireTapToPlay: Boolean = true,
    /** Ask the search engine for suggestions while typing in the address bar. */
    val searchSuggestions: Boolean = true,
    /** Let pages open pop-ups without a tap (they still go through the pop-up blocker). */
    val popupsWithoutTap: Boolean = false,
    val notifyBlockedAdPopups: Boolean = true,
    /** With [blockExternalApps]: show "App launch blocked — Open" instead of blocking silently. */
    val warnBeforeOpeningApps: Boolean = true,
    val blockJsAlerts: Boolean = false,
    val blockJsConfirms: Boolean = false,
    val blockJsPrompts: Boolean = false,
    /** Hosts whose certificate errors are ignored. */
    val sslIgnoredHosts: Set<String> = emptySet(),
    val sslIgnoreAllHosts: Boolean = false,
    /** Pages opened from inside the app (bookmarks, history, start page). */
    val openInAppLinksIn: OpenTabTarget = OpenTabTarget.NEW_TAB,
    /** Pages opened from outside (home screen shortcuts). */
    val openExternalLinksIn: OpenTabTarget = OpenTabTarget.NEW_TAB,
    val lazyLoadRestoredTabs: Boolean = true,
    /** Live WebViews kept in memory; older tabs are unloaded. 0 = unlimited. */
    val maxLiveTabs: Int = 3,
    val maxLivePrivateTabs: Int = 3,
    // Video detection
    val advancedDetection: Boolean = true,
    val autoShowVideoList: Boolean = true,
    val clearListOnNavigation: Boolean = true,
    val hideShortVideos: Boolean = false,
    val videoTapAction: VideoTapAction = VideoTapAction.ASK,
    val pausePageVideoOnCast: Boolean = true,
    /** When a page video starts, cast it (or show its list). */
    val castOnPagePlay: Boolean = true,
    val videoAdMode: VideoAdMode = VideoAdMode.NONE,
    /** "Video ad detected" question before casting an item that looks like an ad. */
    val askBeforeCastingAds: Boolean = true,
    /** Hide MediaSource from pages so players fall back to plain HLS/MP4 URLs (not on YouTube). */
    val disableDash: Boolean = false,
    /** Remove the `sandbox` attribute of iframes on the pages listed in the site rules. */
    val sandboxFix: Boolean = true,
    /** Where newer site rules (JSON) are downloaded from; "" = built-in rules only. */
    val siteRulesUrl: String = "",
    // Ad block lists
    /** A bigger hosts list to download and keep updated; "" = built-in list only. */
    /** Big, maintained block list on top of the built-in one (see AdBlockPresets). */
    val adBlockUpdateUrl: String = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/multi-onlydomains.txt",
    /** Extra block lists (hosts or adblock-domain format). */
    val adBlockCustomListUrls: List<String> = emptyList(),
    /** Hosts that are never blocked. */
    val adBlockAllowList: Set<String> = emptySet(),
    /** Download the lists again once a week. */
    val adBlockAutoUpdate: Boolean = true,
    // Player / casting
    val skipBackSeconds: Int = 5,
    val skipForwardSeconds: Int = 5,
    val resumePlayback: Boolean = true,
    val resumeMode: ResumeMode = ResumeMode.ASK,
    val repeat: CastRepeat = CastRepeat.OFF,
    val openControllerOnPlay: Boolean = true,
    val showAlreadyPlayingDialog: Boolean = true,
    val lockscreenArtwork: Boolean = true,
    /** Photo slideshow interval in seconds, 0 = manual. */
    val slideshowSeconds: Int = 5,
    /** Swipe the now-playing photo to show the previous / next one. */
    val photoSwipe: Boolean = true,
    val defaultSpeed: Float = 1f,
    val autoplayNext: Boolean = true,
    val preferredAudioLanguage: String = "",
    /** Off = the TV gets no media title. */
    val sendTitleToTv: Boolean = true,
    /** Pause the TV while the phone rings or a call is active. */
    val pauseOnCall: Boolean = false,
    /** Resume the TV after a call that paused it (only with [pauseOnCall]). */
    val resumeAfterCall: Boolean = false,
    /** Mini player on the cast screens while something plays on the TV. */
    val showMiniController: Boolean = true,
    /** Tips banner on the controller. */
    val showHelpTips: Boolean = true,
    /** Ask "Did the video play?" a few seconds after it starts. */
    val askVideoPlayed: Boolean = true,
    /** The user closed the battery optimisation hint (controller and mini controller). */
    val batteryHintDismissed: Boolean = false,
    // Phone (in-app) player
    val phoneBackgroundPlay: Boolean = true,
    val phoneGestures: Boolean = true,
    val phonePlayer: PhonePlayerSettings = PhonePlayerSettings(),
    // Subtitles
    val autoLoadDetectedSubtitles: Boolean = true,
    val subtitleLanguages: List<String> = listOf("tr", "en"),
    val subtitleCharset: SubtitleCharsetPref = SubtitleCharsetPref.AUTO,
    val subtitleLook: SubtitleLook = SubtitleLook(),
    val autoSearchOpenSubtitles: Boolean = false,
    /** Show the subtitle chooser before each video is cast. */
    val promptSubtitles: Boolean = false,
    /** OpenSubtitles takes the name literally (`no_guessit`). */
    val subtitleExactAlways: Boolean = false,
    /** Keep OpenSubtitles searches for the name autocomplete. */
    val rememberSubtitleSearches: Boolean = true,
    /** Start the OpenSubtitles name field from the media title. */
    val fillSubtitleNameWithTitle: Boolean = true,
    val openSubtitlesApiKey: String = "",
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    /** Login token (the password is not kept once logged in). */
    val openSubtitlesToken: String = "",
    /** VIP accounts get their own API host after login; empty = default. */
    val openSubtitlesBaseUrl: String = "",
    // IPTV
    /** When lists are downloaded again (replaces the old `iptvRefreshOnOpen` switch). */
    val iptvUpdateMode: com.ruterika.castcore.iptv.IptvUpdateMode = com.ruterika.castcore.iptv.IptvUpdateMode.AUTOMATIC,
    val iptvDefaultUserAgent: String = "",
    /** Enter a level automatically when it only holds one group. */
    val iptvSkipSingleGroups: Boolean = true,
    /** Use `pltv-subgroup` as a second folder level. */
    val iptvConsiderSubgroups: Boolean = true,
    /** Show `tvg-name` instead of the title after the comma. */
    val iptvRenameWithTvgName: Boolean = false,
    /** RSS lists: show the feed's own `<channel>` as a folder. */
    val iptvKeepRssRootChannel: Boolean = false,
    val iptvChannelSort: com.ruterika.castcore.iptv.IptvSortBy = com.ruterika.castcore.iptv.IptvSortBy.UNSORTED,
    val iptvChannelSortAscending: Boolean = true,
    val iptvMixGroupsAndChannels: Boolean = false,
    val iptvListSort: com.ruterika.castcore.iptv.IptvListSort = com.ruterika.castcore.iptv.IptvListSort.DATE_ADDED_ASC,
    // Downloads
    val downloadWifiOnly: Boolean = true,
    /** The one-time "I have the rights to download this" confirmation. */
    val downloadConsentGiven: Boolean = false,
    /** Save HLS streams as the original .ts segments joined together instead of converting to MP4. */
    val downloadHlsAsTs: Boolean = false,
    /** Also save the video's first subtitle next to the downloaded file. */
    val downloadSubtitles: Boolean = false,
    // General (app shell)
    val startScreen: StartScreen = StartScreen.REMOTE,
    /** Screen transition animations between app screens. */
    val transitionAnimations: Boolean = true,
    /** Web pages other apps send to us open in the in-app browser (otherwise they go on to another browser). */
    val openLinksInApp: Boolean = true,
    // Privacy
    /** "always do this" of "Erase all data and exit": erase [eraseOnExitItems] every time the app is left. */
    val eraseOnExit: Boolean = false,
    /** Names of the app's erase options (its `PrivacyItem` enum). */
    val eraseOnExitItems: Set<String> = emptySet()
) {
    val effectiveDarkPages: DarkPages get() = darkPages ?: if (forceDarkPages) DarkPages.ON else DarkPages.OFF

    /** True when certificate errors on [host] are ignored (exact host or a parent domain in the list). */
    fun ignoresSslFor(host: String?): Boolean {
        if (sslIgnoreAllHosts) return true
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return sslIgnoredHosts.any { h == it || h.endsWith(".$it") }
    }

    /** The custom engine in use (only meaningful when [searchEngine] is [SearchEngine.CUSTOM]). */
    val selectedCustomSearchEngine: CustomSearchEngine?
        get() = customSearchEngines.firstOrNull { it.id == customSearchEngineId }
            ?: customSearchEngines.firstOrNull()?.takeIf { customSearchEngineId.isBlank() }

    /** Name of the search engine in use, for the UI. */
    val searchEngineName: String
        get() = if (searchEngine == SearchEngine.CUSTOM) selectedCustomSearchEngine?.name ?: SearchEngine.CUSTOM.label else searchEngine.label

    /** Template of the search engine in use. */
    val searchTemplate: String
        get() {
            if (searchEngine != SearchEngine.CUSTOM) return searchEngine.template
            val custom = selectedCustomSearchEngine?.template ?: customSearchTemplate
            // A custom template without %s still searches there (the query is added at the end);
            // only a missing engine or an address that is not a web address uses Google.
            return custom.trim().takeIf { SearchTemplates.isWeb(it) } ?: SearchEngine.GOOGLE.template
        }

    fun searchUrl(query: String): String = SearchTemplates.build(searchTemplate, query)

    /**
     * Settings of older versions brought up to date: the single `customSearchTemplate` becomes a
     * custom engine called "Custom" (selected when it was in use).
     */
    fun migrated(): CastSettings {
        val legacy = customSearchTemplate.trim()
        if (legacy.isEmpty()) return if (customSearchTemplate.isEmpty()) this else copy(customSearchTemplate = "")
        val existing = customSearchEngines.firstOrNull { it.template.trim() == legacy }
        val engine = existing ?: CustomSearchEngine(
            id = SearchTemplates.LEGACY_ID.takeIf { id -> customSearchEngines.none { it.id == id } } ?: java.util.UUID.randomUUID().toString(),
            name = freeEngineName(SearchEngine.CUSTOM.label),
            template = legacy
        )
        val selectIt = searchEngine == SearchEngine.CUSTOM && customSearchEngines.none { it.id == customSearchEngineId }
        return copy(
            customSearchTemplate = "",
            customSearchEngines = if (existing != null) customSearchEngines else customSearchEngines + engine,
            customSearchEngineId = if (selectIt) engine.id else customSearchEngineId
        )
    }

    /** [base], or "[base] 2", "[base] 3"… when the name is taken. */
    private fun freeEngineName(base: String): String =
        (sequenceOf(base) + (2..99).asSequence().map { "$base $it" })
            .firstOrNull { SearchTemplates.nameError(it, customSearchEngines) == null } ?: base

    /** Turns address-bar input into a URL: a URL, a bare domain, or a search. */
    fun resolveInput(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return homepage
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) return text
        val looksLikeHost = !text.contains(' ') &&
            (Regex("^[^\\s/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(text) || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(text))
        return if (looksLikeHost) "https://$text" else searchUrl(text)
    }
}

class CastSettingsStore(private val file: File) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<CastSettings> = _settings.asStateFlow()

    val current: CastSettings get() = _settings.value

    fun update(transform: (CastSettings) -> CastSettings) {
        val updated = _settings.updateAndGet(transform)
        save(updated)
    }

    fun reset() = update { CastSettings() }

    @Synchronized
    private fun save(settings: CastSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CastSettings.serializer(), settings))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): CastSettings =
        runCatching { json.decodeFromString(CastSettings.serializer(), file.readText()).migrated() }.getOrNull() ?: CastSettings()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
