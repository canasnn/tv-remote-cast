package com.canasnn.tvremote.cast.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.settings.OpenTabTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns the browser tabs so they survive leaving and re-opening the browser
 * screen (like a real browser). Open tab URLs are remembered for "restore
 * tabs on start".
 */
object BrowserManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private lateinit var appContext: Context

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _current = MutableStateFlow<BrowserTab?>(null)
    val current: StateFlow<BrowserTab?> = _current.asStateFlow()

    /** The app's own theme, for "Dark pages: Automatic". Set by the browser screen. */
    private val _appDarkTheme = MutableStateFlow(false)
    val appDarkTheme: StateFlow<Boolean> = _appDarkTheme.asStateFlow()

    fun setAppDarkTheme(dark: Boolean) {
        if (_appDarkTheme.value == dark) return
        _appDarkTheme.value = dark
        _tabs.value.forEach(BrowserTab::applySettings)
    }

    /** Intent action of home screen shortcuts ("Add to home screen"), with [EXTRA_URL]. */
    const val ACTION_OPEN_URL = "com.canasnn.tvremote.OPEN_BROWSER_URL"
    const val EXTRA_URL = "url"

    /** URL the app was asked to open from outside; the navigation consumes it. */
    private val _externalOpen = MutableStateFlow<String?>(null)
    val externalOpenRequest: StateFlow<String?> = _externalOpen.asStateFlow()
    fun requestExternalOpen(url: String) { forceNewTab = false; _externalOpen.value = url }
    fun consumeExternalOpen() { _externalOpen.value = null }

    /** The next [openFromOutside] uses a new tab whatever the setting says. */
    @Volatile private var forceNewTab = false

    /** Opens [url] in a new browser tab from another screen (e.g. "Test" in settings). */
    fun requestOpenInNewTab(url: String) {
        forceNewTab = true
        _externalOpen.value = url
    }

    /** Page icons by host (normal tabs only, memory only) for tabs whose page has not sent one yet. */
    private val favicons = LruCache<String, Bitmap>(MAX_FAVICONS)

    fun rememberFavicon(url: String, icon: Bitmap) {
        val host = Uri.parse(url).host?.lowercase() ?: return
        favicons.put(host, icon)
    }

    fun faviconFor(url: String): Bitmap? = Uri.parse(url).host?.lowercase()?.let { favicons.get(it) }

    /** Recently closed tabs (newest first), for "Undo". */
    data class ClosedTab(val url: String, val title: String, val incognito: Boolean)
    private val closed = ArrayDeque<ClosedTab>()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        BrowserData.init(appContext)
        scope.launch {
            CastHub.settings.collect {
                _tabs.value.forEach(BrowserTab::applySettings)
                enforceLiveLimit()
            }
        }
    }

    /** URLs of the tabs that were open last time (for restore). */
    fun savedTabUrls(): List<String> =
        prefs().getString(KEY_TABS, null).orEmpty().split('\n').filter { it.startsWith("http") }

    /**
     * Opens a tab. With [lazy] the page is loaded only when the tab is first
     * shown (restored tabs).
     */
    fun newTab(
        url: String?,
        incognito: Boolean = false,
        select: Boolean = true,
        lazy: Boolean = false,
        openedByPage: Boolean = false
    ): BrowserTab {
        // A pop-up window gets its page from the opener, not the homepage.
        val start = url ?: if (openedByPage) null else CastHub.settings.value.homepage.takeIf { it.isNotBlank() }
        val tab = BrowserTab(
            appContext = appContext,
            incognito = incognito,
            scope = scope,
            http = http,
            onPopupWindow = { transport, message ->
                val popup = newTab(url = null, incognito = incognito, openedByPage = true)
                transport.webView = popup.webView
                message.sendToTarget()
                true
            },
            lazyUrl = if (lazy && !select) start else null,
            openedByPage = openedByPage
        )
        _tabs.value = _tabs.value + tab
        if (select) select(tab)
        if (start != null && !(lazy && !select)) tab.load(start)
        if (!select) tab.pause() // background tab: loads, but doesn't play or animate
        enforceLiveLimit()
        persist()
        return tab
    }

    fun select(tab: BrowserTab) {
        val previous = _current.value
        _current.value = tab
        tab.lastShownAt = SystemClock.uptimeMillis()
        if (previous != null && previous !== tab) {
            previous.captureThumbnail() // still on screen: picture for the tab list
            previous.pause()
        }
        tab.resume()
        enforceLiveLimit()
    }

    fun close(tab: BrowserTab) {
        if (tab !in _tabs.value) return
        val remaining = _tabs.value - tab
        tab.url.value.takeIf { it.startsWith("http") }?.let { url ->
            closed.addFirst(ClosedTab(url, tab.title.value, tab.incognito))
            while (closed.size > MAX_CLOSED) closed.removeLast()
        }
        _tabs.value = remaining
        if (_current.value === tab) remaining.lastOrNull()?.let(::select) ?: run { _current.value = null }
        tab.destroy()
        if (tab.incognito && remaining.none { it.incognito }) clearIncognitoData()
        persist()
    }

    fun closeAll() {
        val hadIncognito = _tabs.value.any { it.incognito }
        _tabs.value.forEach { it.destroy() }
        _tabs.value = emptyList()
        _current.value = null
        if (hadIncognito) clearIncognitoData()
        persist()
    }

    val hasClosedTab: Boolean get() = closed.isNotEmpty()

    /** Re-opens the most recently closed tab. */
    fun reopenClosedTab(): BrowserTab? {
        val last = closed.removeFirstOrNull() ?: return null
        return newTab(last.url, last.incognito)
    }

    /** Called when the browser screen opens with [url] (or none) from inside the app. */
    fun open(url: String?): BrowserTab = openWith(url, CastHub.settings.value.openInAppLinksIn)

    /** A page opened from outside the app (home screen shortcut). */
    fun openFromOutside(context: Context, url: String): BrowserTab {
        init(context)
        val target = if (forceNewTab) OpenTabTarget.NEW_TAB else CastHub.settings.value.openExternalLinksIn
        forceNewTab = false
        return openWith(url, target)
    }

    private fun openWith(url: String?, target: OpenTabTarget): BrowserTab {
        val existing = _current.value
        return when {
            existing == null -> newTab(url)
            url == null -> existing
            target == OpenTabTarget.NEW_TAB && existing.url.value.isNotBlank() -> newTab(url, existing.incognito)
            else -> existing.also { it.load(url) }
        }
    }

    fun persist() {
        val urls = _tabs.value.filterNot { it.incognito }.map { it.url.value }.filter { it.startsWith("http") }
        prefs().edit().putString(KEY_TABS, urls.joinToString("\n")).apply()
    }

    /** Keeps at most the configured number of live WebViews; the least recently shown are unloaded. */
    private fun enforceLiveLimit() {
        val s = CastHub.settings.value
        val currentTab = _current.value
        listOf(false to s.maxLiveTabs, true to s.maxLivePrivateTabs).forEach { (incognito, limit) ->
            if (limit <= 0) return@forEach
            val live = _tabs.value.filter { it.incognito == incognito && it.isLive }
            val keep = (listOfNotNull(currentTab?.takeIf { it in live }) +
                live.filter { it !== currentTab }.sortedByDescending { it.lastShownAt }).take(limit)
            live.filter { it !in keep }.forEach { it.dehydrate() }
        }
    }

    /** Settings → "Reset browser data": cookies, cache, site storage, saved logins and permissions. */
    fun resetBrowserData(context: Context) {
        favicons.evictAll()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        GeolocationPermissions.getInstance().clearAll()
        runCatching { WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword() }
        @Suppress("DEPRECATION")
        runCatching { WebViewDatabase.getInstance(context).clearFormData() }
        runCatching { WebView(context.applicationContext).apply { clearCache(true); destroy() } }
    }

    /** Private tabs use their own WebView profile; drop it with the last private tab. */
    private fun clearIncognitoData() {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
            runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(BrowserTab.INCOGNITO_PROFILE) }
        }
    }

    /** Replaces a tab whose renderer died with a fresh one on the same page. */
    fun recover(tab: BrowserTab) {
        val index = _tabs.value.indexOf(tab)
        if (index < 0) return
        val url = tab.url.value.takeIf { it.startsWith("http") }
        val wasCurrent = _current.value === tab
        val fresh = newTab(url, tab.incognito, select = wasCurrent)
        _tabs.value = _tabs.value.filterNot { it === fresh }.toMutableList().also { it[index] = fresh }
        runCatching { tab.destroy() }
        persist()
    }

    private fun prefs() = appContext.getSharedPreferences("browser", Context.MODE_PRIVATE)

    private const val KEY_TABS = "open_tabs"
    private const val MAX_CLOSED = 10
    private const val MAX_FAVICONS = 64
}
