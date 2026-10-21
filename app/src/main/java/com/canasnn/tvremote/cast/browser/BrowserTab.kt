package com.canasnn.tvremote.cast.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.browser.UserAgents
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.DarkPages
import com.ruterika.castcore.settings.VideoAdMode
import com.ruterika.castcore.web.DetectorScript
import com.ruterika.castcore.web.MediaProber
import com.ruterika.castcore.web.PageMediaCollector
import com.ruterika.castcore.web.ProbeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.UUID

/**
 * One browser tab: a WebView that outlives screen changes (kept in a
 * [MutableContextWrapper] so it can be re-attached to the current activity),
 * plus the media it detected.
 *
 * The WebView itself is created on first use and can be unloaded
 * ([dehydrate]) to save memory; it comes back with its history when the tab
 * is shown again (lazy-loaded restored tabs work the same way).
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(
    private val appContext: Context,
    val incognito: Boolean,
    scope: CoroutineScope,
    http: OkHttpClient,
    private val onPopupWindow: (WebView.WebViewTransport, Message) -> Boolean,
    /** Set for lazily restored tabs: loaded when the tab is first shown. */
    lazyUrl: String? = null,
    /** Opened by a page (window.open); window.close() closes it. */
    val openedByPage: Boolean = false
) {
    val id: String = UUID.randomUUID().toString()

    private val settings: CastSettings get() = CastHub.settings.value
    private val contextWrapper = MutableContextWrapper(appContext)
    private val themedContext = ThemedContext(contextWrapper)
    private val mainHandler = Handler(Looper.getMainLooper())

    val collector = PageMediaCollector(
        adBlocker = BrowserData.adBlocker,
        config = collectorConfig(settings),
        cookieProvider = { url -> cookieFor(url) },
        rules = { BrowserData.siteRules },
        isFailedUrl = BrowserData::isFailed
    )

    /** Cookie header for [url] from this tab's cookie jar (private tabs have their own). */
    fun cookieFor(url: String): String? = runCatching {
        val manager = if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getProfile(INCOGNITO_PROFILE)?.cookieManager ?: CookieManager.getInstance()
        } else CookieManager.getInstance()
        manager.getCookie(url)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** [headers] with the current cookies for [url] (cookies change after detection, e.g. sign-in). */
    fun withFreshCookie(url: String, headers: Map<String, String>): Map<String, String> {
        val cookie = cookieFor(url) ?: return headers
        return headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } + ("Cookie" to cookie)
    }

    private val _adsBlocked = MutableStateFlow(0)
    /** Requests blocked by the ad blocker on the current page. */
    val adsBlocked: StateFlow<Int> = _adsBlocked.asStateFlow()

    /** Host the user let through with "Open anyway" on a blocked redirect (this tab only). */
    @Volatile private var allowedRedirectHost: String? = null
    private val probedNavigations = java.util.Collections.synchronizedSet(HashSet<String>())
    private val prober = MediaProber(http)
    private val proberJob: Job = prober.attach(scope, collector)
    private val scope = scope

    /** URL of a media element the page started playing ("" when it is a blob). */
    private val _pagePlay = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pagePlay: SharedFlow<String> = _pagePlay.asSharedFlow()

    /** In-page fullscreen video (WebChromeClient custom view). */
    private val _fullscreen = MutableStateFlow<Pair<View, WebChromeClient.CustomViewCallback>?>(null)
    val fullscreen: StateFlow<Pair<View, WebChromeClient.CustomViewCallback>?> = _fullscreen.asStateFlow()

    fun exitFullscreen() {
        _fullscreen.value?.second?.onCustomViewHidden()
        _fullscreen.value = null
    }

    // ── Questions for the user (shown one at a time by the browser UI) ──────

    enum class JsKind(val label: String) { ALERT("alert"), CONFIRM("confirm"), PROMPT("prompt") }

    sealed class Prompt {
        /** Gives the page a negative answer (used when a tab closes with the question open). */
        abstract fun cancel()

        class Ssl(val url: String, val host: String, val reason: String, val details: String, handler: SslErrorHandler) : Prompt() {
            internal val handlers = mutableListOf(handler)
            fun proceed() = handlers.forEach { it.proceed() }
            override fun cancel() = handlers.forEach { it.cancel() }
        }

        class JsDialog(val kind: JsKind, val origin: String, val message: String, val defaultValue: String?, val result: JsResult) : Prompt() {
            override fun cancel() = result.cancel()
        }

        class HttpAuth(val host: String, val realm: String, val handler: HttpAuthHandler) : Prompt() {
            override fun cancel() = handler.cancel()
        }

        /** Camera / microphone / protected media requested by a page. */
        class Permission(val origin: String, val request: PermissionRequest, val resources: List<String>) : Prompt() {
            override fun cancel() = request.deny()
        }

        class Geolocation(val origin: String, val callback: GeolocationPermissions.Callback) : Prompt() {
            override fun cancel() = callback.invoke(origin, false, false)
        }

        class FileChooser(val callback: ValueCallback<Array<Uri>>, val params: WebChromeClient.FileChooserParams) : Prompt() {
            override fun cancel() = callback.onReceiveValue(null)
        }
    }

    private val _prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()

    /** The UI answered [prompt]. */
    fun done(prompt: Prompt) = _prompts.update { it - prompt }

    private fun ask(prompt: Prompt) = _prompts.update { it + prompt }

    /** Short messages for a snackbar, optionally with one action. */
    data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()
    private fun notice(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _notices.tryEmit(Notice(text, actionLabel, action))
    }

    /** "Don't let this page show more dialogs" (until the next page). */
    @Volatile var blockDialogsOnPage = false
    private val sslNotified = mutableSetOf<String>()
    private val retried = mutableSetOf<String>()

    // ── Long-press menu ─────────────────────────────────────────────────────

    data class ContextTarget(val linkUrl: String?, val imageUrl: String?, val linkTitle: String?)
    private val _contextTarget = MutableStateFlow<ContextTarget?>(null)
    val contextTarget: StateFlow<ContextTarget?> = _contextTarget.asStateFlow()
    fun dismissContextMenu() { _contextTarget.value = null }

    // ── Find in page ────────────────────────────────────────────────────────

    /** [active] is 1-based (0 = no match). */
    data class FindResult(val active: Int, val total: Int)
    private val _find = MutableStateFlow(FindResult(0, 0))
    val findResult: StateFlow<FindResult> = _find.asStateFlow()

    fun find(query: String) {
        if (query.isEmpty()) clearFind() else webView.findAllAsync(query)
    }

    fun findNext(forward: Boolean) = webView.findNext(forward)

    fun clearFind() {
        _webView?.clearMatches()
        _find.value = FindResult(0, 0)
    }

    // ── Page state ──────────────────────────────────────────────────────────

    private val _url = MutableStateFlow(lazyUrl.orEmpty())
    val url: StateFlow<String> = _url.asStateFlow()
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()
    /** The page icon: the WebView's, or the one last seen for this site (tab list, shortcuts). */
    private val _favicon = MutableStateFlow(lazyUrl?.let(BrowserManager::faviconFor))
    val favicon: StateFlow<Bitmap?> = _favicon.asStateFlow()

    private val _thumbnail = MutableStateFlow<Bitmap?>(null)
    /** A small picture of the page for the tab list (memory only); null until the tab was shown. */
    val thumbnail: StateFlow<Bitmap?> = _thumbnail.asStateFlow()

    /**
     * Draws the visible part of the page into a small bitmap for the tab list. One scaled
     * software draw; skipped while the WebView is not on screen (nothing to draw then).
     */
    fun captureThumbnail() {
        val view = _webView ?: return
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        if (!_url.value.startsWith("http")) {
            _thumbnail.value = null
            return
        }
        runCatching {
            val scale = THUMB_WIDTH.toFloat() / view.width
            val height = minOf(THUMB_HEIGHT, (view.height * scale).toInt()).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(THUMB_WIDTH, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.scale(scale, scale)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)
            bitmap
        }.onSuccess { _thumbnail.value = it }
            .onFailure { Timber.w(it, "[Browser] Tab thumbnail failed") }
    }

    private val _blockedPopups = MutableStateFlow(0)
    /** Pop-ups blocked on the current page (the UI may offer "allow for this site"). */
    val blockedPopups: StateFlow<Int> = _blockedPopups.asStateFlow()
    private val _blockedPopupUrl = MutableStateFlow<String?>(null)
    /** Target of the last blocked pop-up ("Tap to open"). */
    val blockedPopupUrl: StateFlow<String?> = _blockedPopupUrl.asStateFlow()

    fun openBlockedPopup() {
        val target = _blockedPopupUrl.value ?: return
        _blockedPopupUrl.value = null
        _blockedPopups.value = 0
        BrowserManager.newTab(target, incognito)
    }

    fun dismissBlockedPopups() {
        _blockedPopupUrl.value = null
        _blockedPopups.value = 0
    }

    @Volatile private var currentPageUrl: String? = lazyUrl
    @Volatile private var lastTouchAt = 0L

    /** Per-tab UA choice; null follows the default from settings. */
    var userAgentKey: String? = null
        private set

    val effectiveUserAgentKey: String get() = userAgentKey ?: UserAgents.defaultKey(settings)

    fun setUserAgent(key: String?) {
        userAgentKey = key
        _webView?.let { applySettingsTo(it); it.reload() }
    }

    /** The UA this tab sends on [pageUrl]: its own choice, else the site rule, else the default. */
    private fun userAgentFor(pageUrl: String?): String {
        val webDefault = WebSettings.getDefaultUserAgent(appContext)
        return UserAgents.forPage(userAgentKey, pageUrl, BrowserData.siteRules, settings, webDefault) ?: webDefault
    }

    /** Page loads restarted for a per-site UA since the last finished page. */
    private var uaReloads = 0

    /** Sets the UA for a page on [url] before it loads; true when it changed. */
    private fun applyUserAgentFor(view: WebView, url: String?): Boolean {
        val wanted = userAgentFor(url)
        if (view.settings.userAgentString == wanted) return false
        view.settings.userAgentString = wanted
        return true
    }

    // ── The WebView (created on demand) ─────────────────────────────────────

    private var _webView: WebView? = null
    private var savedState: Bundle? = null
    private var pendingUrl: String? = lazyUrl

    /** When this tab was last shown; the least recently shown tabs are unloaded first. */
    var lastShownAt: Long = SystemClock.uptimeMillis()
        internal set

    val isLive: Boolean get() = _webView != null

    /** The tab's WebView; creates (or restores) it when the tab was unloaded. */
    val webView: WebView get() = _webView ?: createWebView()

    @SuppressLint("ClickableViewAccessibility")
    private fun createWebView(): WebView {
        val view = WebView(themedContext)
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE)
                WebViewCompat.setProfile(view, INCOGNITO_PROFILE)
            }
        }
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            setSupportMultipleWindows(true)
            // Pop-ups are policed in onCreateWindow (gesture, ad host, allow list).
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // Media "downloads" (direct links) are offered as castable videos.
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            collector.addManual(url, name, mapOf("User-Agent" to userAgent))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, detectorScript(), setOf("*"))
        }
        dashScript = null
        sandboxScript = null
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                lastTouchAt = SystemClock.uptimeMillis()
            }
            false
        }
        view.setOnLongClickListener { onLongPress(view) }
        view.setFindListener { active, total, _ ->
            _find.value = FindResult(if (total == 0) 0 else active + 1, total)
        }
        _webView = view
        applySettingsTo(view)

        val state = savedState
        val pending = pendingUrl
        savedState = null
        pendingUrl = null
        val restored = state != null && runCatching { view.restoreState(state) }.getOrNull() != null
        if (!restored) {
            val target = pending ?: _url.value.takeIf { it.startsWith("http") }
            if (target != null) {
                applyUserAgentFor(view, target)
                view.loadUrl(target)
            }
        }
        return view
    }

    /** Frees the WebView; it is rebuilt (with its history) the next time the tab is shown. */
    fun dehydrate() {
        val view = _webView ?: return
        if (_fullscreen.value != null) exitFullscreen()
        cancelPrompts()
        // Kept in memory only (never written to disk, also for private tabs).
        savedState = Bundle().also { view.saveState(it) }
        pendingUrl = _url.value.takeIf { it.startsWith("http") }
        _webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
        _progress.value = 0
    }

    init {
        applySettings()
    }

    /** Re-reads the cast settings (UA, dark pages, cookies). Call when they change. */
    fun applySettings() {
        collector.config = collectorConfig(settings)
        collector.adBlocker = BrowserData.adBlocker
        _webView?.let(::applySettingsTo)
    }

    private var dashScript: Pair<String, ScriptHandler>? = null
    private var sandboxScript: Pair<String, ScriptHandler>? = null

    /**
     * Optional document-start snippets ("Disable DASH", sandbox fix); they apply from the
     * next page load.
     */
    private fun applyPageScripts(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val s = settings
        fun sync(current: Pair<String, ScriptHandler>?, wanted: String?): Pair<String, ScriptHandler>? {
            if (current?.first == wanted) return current
            current?.second?.let { runCatching { it.remove() } }
            if (wanted == null) return null
            return runCatching { wanted to WebViewCompat.addDocumentStartJavaScript(view, wanted, setOf("*")) }.getOrNull()
        }
        dashScript = sync(dashScript, DetectorScript.DISABLE_DASH_JS.takeIf { s.disableDash })
        val pages = BrowserData.siteRules.sandboxFixPages
        sandboxScript = sync(sandboxScript, DetectorScript.sandboxFixJs(pages).takeIf { s.sandboxFix && pages.isNotEmpty() })
    }

    private fun applySettingsTo(view: WebView) {
        val s = settings
        applyUserAgentFor(view, currentPageUrl ?: pendingUrl ?: _url.value)
        view.settings.displayZoomControls = s.showZoomButtons
        view.settings.mediaPlaybackRequiresUserGesture = s.requireTapToPlay
        val dark = when (s.effectiveDarkPages) {
            DarkPages.ON -> true
            DarkPages.OFF -> false
            DarkPages.AUTO -> BrowserManager.appDarkTheme.value
        }
        themedContext.dark = dark
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, dark)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, s.thirdPartyCookies && !incognito)
        applyPageScripts(view)
    }

    fun attach(context: Context) {
        contextWrapper.baseContext = context
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    fun detach(appContext: Context) {
        _webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        contextWrapper.baseContext = appContext
    }

    fun load(url: String) {
        pendingUrl = null
        savedState = null
        retried.clear()
        val view = webView
        applyUserAgentFor(view, url)
        view.loadUrl(url)
    }

    /** Stops timers, media and animations of a tab that is not on screen. */
    fun pause() { _webView?.onPause() }

    fun resume() { _webView?.onResume() }

    /** The browser screen shows this tab: JS timers (process-wide) and the page run again. */
    fun onShown() {
        webView.resumeTimers()
        webView.onResume()
    }

    /** The browser screen went away: pause this page and all WebView timers. */
    fun onHidden() {
        _webView?.let {
            it.onPause()
            it.pauseTimers()
        }
    }

    /** Re-runs the detector in the current page. */
    fun rescan() {
        webView.evaluateJavascript(detectorScript(), null)
        webView.evaluateJavascript(DetectorScript.RESCAN_JS, null)
    }

    fun pausePageMedia() {
        _webView?.evaluateJavascript(
            "try{window.__ruterikaPauseAll&&window.__ruterikaPauseAll();document.querySelectorAll('video,audio').forEach(function(m){m.pause()})}catch(e){}",
            null
        )
    }

    /**
     * Something from this page is being cast: handle the page's own video by the video ad
     * mode. [likelyAd] = the cast item looks like an ad.
     */
    fun onCastStarted(likelyAd: Boolean) {
        val s = settings
        when (s.videoAdMode) {
            VideoAdMode.NONE -> if (s.pausePageVideoOnCast) pausePageMedia()
            // Jump every page video to its end: the page's ad "finishes" and the real video loads.
            VideoAdMode.MANUAL_1 -> _webView?.evaluateJavascript(DetectorScript.SKIP_ALL_ADS_JS, null)
            // Let the page keep playing (its ad finishes on the phone).
            VideoAdMode.MANUAL_2 -> Unit
            VideoAdMode.SMART ->
                if (likelyAd) _webView?.evaluateJavascript(DetectorScript.SKIP_LIKELY_ADS_JS, null)
                else if (s.pausePageVideoOnCast) pausePageMedia()
        }
    }

    fun destroy() {
        proberJob.cancel()
        cancelPrompts()
        _thumbnail.value = null
        val view = _webView ?: return
        _webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun cancelPrompts() {
        val pending = _prompts.value
        _prompts.value = emptyList()
        pending.forEach { runCatching { it.cancel() } }
    }

    private fun updateNavState() {
        val view = _webView ?: return
        _canGoBack.value = view.canGoBack()
        _canGoForward.value = view.canGoForward()
    }

    private fun onLongPress(view: WebView): Boolean {
        val hit = view.hitTestResult
        fun usable(url: String?) = url?.takeIf { it.isNotBlank() && !it.startsWith("javascript:", ignoreCase = true) }
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                val link = usable(hit.extra) ?: return false
                _contextTarget.value = ContextTarget(link, null, null)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                val image = usable(hit.extra) ?: return false
                _contextTarget.value = ContextTarget(null, image, null)
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val image = usable(hit.extra)
                // The link around the image comes back asynchronously.
                val handler = Handler(Looper.getMainLooper()) { message ->
                    val link = usable(message.data.getString("url"))
                    if (link != null || image != null) {
                        _contextTarget.value = ContextTarget(link, image, message.data.getString("title"))
                    }
                    true
                }
                view.requestFocusNodeHref(handler.obtainMessage())
                true
            }
            else -> false
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) {
            if (CastHub.settings.value.advancedDetection) collector.onJsMessage(json)
        }

        @JavascriptInterface
        fun playing(url: String) {
            _pagePlay.tryEmit(url)
        }
    }

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val s = settings
            if (s.adBlock && !request.isForMainFrame) {
                val pageHost = currentPageUrl?.let { Uri.parse(it).host }
                val allowedHere = pageHost != null && s.adBlockAllowHosts.any { pageHost.endsWith(it) }
                val host = request.url.host
                if (!allowedHere && host != allowedRedirectHost && collectorBlocks(url)) {
                    _adsBlocked.update { it + 1 }
                    if (com.canasnn.tvremote.BuildConfig.DEBUG) android.util.Log.d("RuterikaAdBlock", "blocked $host")
                    return EMPTY_RESPONSE()
                }
            }
            collector.onRequest(
                url = url,
                method = request.method,
                headers = request.requestHeaders.orEmpty(),
                pageUrl = currentPageUrl
            )
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") {
                val target = uri.toString()
                if (settings.blockAdRedirects && (scheme == "http" || scheme == "https") && isAdRedirect(target, request)) {
                    val host = uri.host.orEmpty()
                    Timber.i("[Browser] Blocked ad redirect to $host (main frame: ${request.isForMainFrame})")
                    // Ad iframes are blocked silently; a blocked page redirect gets an "Open anyway" bar.
                    if (settings.notifyBlockedAdPopups && request.isForMainFrame) {
                        notice("Redirect blocked: $host", "Open anyway") {
                            allowedRedirectHost = host
                            view.loadUrl(target)
                        }
                    }
                    return true
                }
                // Per-site user agent: the page has to be requested with it, so it is loaded again
                // once the UA is switched (only when the switch changes something).
                if (request.isForMainFrame && (scheme == "http" || scheme == "https") &&
                    BrowserData.siteRules.hasUserAgentRules && uaReloads < MAX_UA_RELOADS && applyUserAgentFor(view, target)
                ) {
                    uaReloads++ // redirect chains between sites with different UAs can't loop
                    Timber.i("[Browser] User agent switched for ${uri.host}")
                    val referer = currentPageUrl?.takeIf { it.startsWith("http") }
                    view.loadUrl(target, listOfNotNull(referer?.let { "Referer" to it }).toMap())
                    return true
                }
                // Redirect targets (main frame and iframes); plain main-frame loads are probed in onPageStarted.
                if (request.isRedirect && (scheme == "http" || scheme == "https")) {
                    probeNavigation(target, request.requestHeaders.orEmpty())
                }
                return false
            }
            if (!request.isForMainFrame) return true
            handleExternalApp(view, uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val previous = currentPageUrl
            currentPageUrl = url
            _url.value = url
            _blockedPopups.value = 0
            _blockedPopupUrl.value = null
            // Keep the old icon while staying on the same site until the new one arrives.
            val sameHost = previous != null && Uri.parse(previous).host == Uri.parse(url).host
            _favicon.value = favicon ?: BrowserManager.faviconFor(url) ?: _favicon.value.takeIf { sameHost }
            // Back/forward don't pass shouldOverrideUrlLoading: the site's UA applies from here on.
            if (BrowserData.siteRules.hasUserAgentRules) applyUserAgentFor(view, url)
            if (previous?.substringBefore('#') != url.substringBefore('#')) {
                blockDialogsOnPage = false
                _adsBlocked.value = 0
                probedNavigations.clear()
                if (Uri.parse(url).host != allowedRedirectHost) allowedRedirectHost = null
            }
            collector.onPageStarted(url)
            updateNavState()
            if (url.startsWith("http")) {
                val from = previous?.takeIf { it.startsWith("http") && it.substringBefore('#') != url.substringBefore('#') }
                probeNavigation(url, listOfNotNull(from?.let { "Referer" to it }).toMap())
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            uaReloads = 0
            _url.value = url
            updateNavState()
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(detectorScript(), null)
            }
            CastHub.library.recordVisit(url, view.title.orEmpty(), incognito)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val previous = currentPageUrl
            _url.value = url
            currentPageUrl = url
            updateNavState()
            if (!isReload && previous != null && previous.substringBefore('#') != url.substringBefore('#')) {
                collector.onPageStarted(url)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer died (crash or memory). Replace the tab instead of crashing the app.
            Timber.w("[Browser] Renderer gone (crash=${detail.didCrash()}), recovering tab")
            if (view !== _webView) return true // an unloaded tab's old WebView
            BrowserManager.recover(this@BrowserTab)
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val url = error.url ?: currentPageUrl.orEmpty()
            val host = Uri.parse(url).host.orEmpty()
            if (settings.ignoresSslFor(host)) {
                handler.proceed()
                if (host.isNotEmpty() && sslNotified.add(host)) notice("Certificate error ignored for $host")
                return
            }
            // One question per host: later errors of the same site wait for the same answer.
            val existing = _prompts.value.filterIsInstance<Prompt.Ssl>().firstOrNull { it.host == host }
            if (existing != null) {
                existing.handlers += handler
                return
            }
            ask(Prompt.Ssl(url, host, sslReason(error), certificateDetails(error.certificate), handler))
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            ask(Prompt.HttpAuth(host, realm.orEmpty(), handler))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val description = error.description?.toString().orEmpty()
            val retryable = error.errorCode in RETRY_CODES || RETRY_ERRORS.any { it in description }
            val target = request.url.toString()
            // One automatic retry.
            if (retryable && target.startsWith("http") && retried.add(target)) {
                Timber.i("[Browser] Retrying $target after $description")
                mainHandler.postDelayed({ if (_webView === view) view.loadUrl(target) }, RETRY_DELAY_MS)
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _progress.value = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            _title.value = title.orEmpty()
            collector.onPageTitle(title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (icon == null) return
            _favicon.value = icon
            // Private tabs don't leave their sites' icons behind.
            if (!incognito) (view.url ?: currentPageUrl)?.let { BrowserManager.rememberFavicon(it, icon) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            _fullscreen.value = view to callback
        }

        override fun onHideCustomView() {
            _fullscreen.value = null
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val s = settings
            val gesture = isUserGesture || SystemClock.uptimeMillis() - lastTouchAt < GESTURE_WINDOW_MS
            if (!gesture && !s.popupsWithoutTap) {
                Timber.i("[Browser] Dropped a pop-up without a tap")
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            if (!s.blockPopups) return onPopupWindow(transport, resultMsg)
            return catchPopup(transport, resultMsg)
        }

        override fun onCloseWindow(window: WebView) {
            if (openedByPage && window === _webView) BrowserManager.close(this@BrowserTab)
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
            jsDialog(JsKind.ALERT, url, message, null, result)

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
            jsDialog(JsKind.CONFIRM, url, message, null, result)

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
            jsDialog(JsKind.PROMPT, url, message, defaultValue, result)

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.filter { it in ASKABLE_RESOURCES }
            if (wanted.isEmpty()) {
                request.deny()
                return
            }
            ask(Prompt.Permission(request.origin.toString(), request, wanted))
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            _prompts.update { list -> list.filterNot { it is Prompt.Permission && it.request === request } }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            ask(Prompt.Geolocation(origin, callback))
        }

        override fun onGeolocationPermissionsHidePrompt() {
            _prompts.update { list -> list.filterNot { it is Prompt.Geolocation } }
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            // Only one chooser at a time: an older unanswered one gets "nothing chosen".
            _prompts.value.filterIsInstance<Prompt.FileChooser>().forEach { it.cancel(); done(it) }
            ask(Prompt.FileChooser(filePathCallback, fileChooserParams))
            return true
        }
    }

    private fun jsDialog(kind: JsKind, url: String, message: String, defaultValue: String?, result: JsResult): Boolean {
        val s = settings
        val blocked = blockDialogsOnPage || when (kind) {
            JsKind.ALERT -> s.blockJsAlerts
            JsKind.CONFIRM -> s.blockJsConfirms
            JsKind.PROMPT -> s.blockJsPrompts
        }
        if (blocked) {
            when (kind) {
                JsKind.ALERT -> result.confirm()
                JsKind.CONFIRM -> result.cancel()
                JsKind.PROMPT -> (result as? JsPromptResult)?.confirm(defaultValue.orEmpty()) ?: result.cancel()
            }
            notice("Blocked ${kind.label} dialog")
            return true
        }
        ask(Prompt.JsDialog(kind, Uri.parse(url).host ?: url, message, defaultValue, result))
        return true
    }

    /**
     * Blocked pop-up: a hidden WebView receives the window, we read where it
     * goes, then decide (ad host → closed silently, allowed site → new tab,
     * otherwise → "Pop-up blocked · Tap to open").
     */
    private fun catchPopup(transport: WebView.WebViewTransport, resultMsg: Message): Boolean {
        val catcher = WebView(appContext)
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { WebViewCompat.setProfile(catcher, INCOGNITO_PROFILE) }
        }
        var finished = false
        fun finish(target: String?) {
            if (finished) return
            finished = true
            mainHandler.post { runCatching { catcher.stopLoading(); catcher.destroy() } }
            if (target != null && target.startsWith("http")) decidePopup(target)
        }
        catcher.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                finish(request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != "about:blank") finish(url)
            }
        }
        transport.webView = catcher
        resultMsg.sendToTarget()
        mainHandler.postDelayed({ finish(null) }, POPUP_WAIT_MS)
        return true
    }

    private fun decidePopup(target: String) {
        val s = settings
        if (collectorBlocks(target)) {
            Timber.i("[Browser] Closed ad pop-up to ${Uri.parse(target).host}")
            if (s.notifyBlockedAdPopups) notice("Blocked an ad pop-up")
            return
        }
        fun allowed(host: String?): Boolean {
            val h = host?.lowercase()?.removePrefix("www.") ?: return false
            return s.allowedPopupHosts.any { h == it || h.endsWith(".$it") }
        }
        val opener = currentPageUrl?.let { Uri.parse(it).host }
        if (allowed(Uri.parse(target).host) || allowed(opener)) {
            BrowserManager.newTab(target, incognito, openedByPage = true)
            return
        }
        _blockedPopupUrl.value = target
        _blockedPopups.value += 1
    }

    /** intent://, market:// and other app links. */
    private fun handleExternalApp(view: WebView, uri: Uri) {
        val intent = runCatching {
            if (uri.scheme.equals("intent", ignoreCase = true)) Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, uri)
        }.getOrNull() ?: return
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val fallback = intent.getStringExtra("browser_fallback_url")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val s = settings
        if (s.blockExternalApps) {
            Timber.i("[Browser] Blocked external app link $uri")
            if (fallback != null) view.loadUrl(fallback)
            if (s.warnBeforeOpeningApps) {
                notice("App launch blocked", "Open") {
                    if (!launchApp(intent)) notice("No app can open this link")
                }
            }
            return
        }
        if (!launchApp(intent)) {
            if (fallback != null) view.loadUrl(fallback) else notice("No app can open this link")
        }
    }

    private fun launchApp(intent: Intent): Boolean {
        val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { appContext.startActivity(launch) }.isSuccess) return true
        // App not installed: its store page, like Chrome does.
        val pkg = intent.`package` ?: return false
        return runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun collectorBlocks(url: String): Boolean = BrowserData.adBlocker.isBlocked(url)

    /**
     * Ad redirect check for every navigation (main frame and iframes): the URL-shape rules
     * always; the ad host list for scripted navigations and iframes (a tapped main-frame link
     * to a listed host still opens). "Open anyway" allows the host for this tab.
     */
    private fun isAdRedirect(url: String, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return false
        if (host == allowedRedirectHost) return false
        if (BrowserData.siteRules.isAdRedirect(url)) return true
        return (!request.isForMainFrame || !request.hasGesture()) && collectorBlocks(url)
    }

    /**
     * HEAD a page start, followed link or redirect target; a media response becomes a castable
     * video and starts the "page video started" flow.
     */
    private fun probeNavigation(url: String, headers: Map<String, String>) {
        if (!settings.advancedDetection) return
        val path = Uri.parse(url).path.orEmpty().lowercase()
        if (path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php") || path.endsWith(".asp") || path.endsWith(".aspx")) return
        if (BrowserData.siteRules.isNoDetect(url) || BrowserData.siteRules.isNoDetect(currentPageUrl)) return
        if (!probedNavigations.add(url.substringBefore('#'))) return
        val ua = _webView?.settings?.userAgentString ?: return
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            ?: currentPageUrl?.takeIf { it.startsWith("http") && it.substringBefore('#') != url.substringBefore('#') }
        val sent = headers.filterKeys { !it.equals("Range", true) && !it.equals("Referer", true) } + ("User-Agent" to ua) +
            listOfNotNull(referer?.let { "Referer" to it }, cookieFor(url)?.let { "Cookie" to it })
        scope.launch {
            val result = prober.head(ProbeRequest(url, sent)) ?: return@launch
            val mime = result.mime?.lowercase().orEmpty()
            val isMedia = mime.startsWith("video/") || mime.startsWith("audio/") || "mpegurl" in mime || "dash+xml" in mime
            val final = result.finalUrl?.takeIf { it != url && MEDIA_PATH.containsMatchIn(Uri.parse(it).path.orEmpty()) }
            val hit = when {
                isMedia -> url
                MEDIA_PATH.containsMatchIn(path) -> url
                final != null -> final
                else -> return@launch
            }
            val stored = mapOf("User-Agent" to ua) + listOfNotNull(referer?.let { "Referer" to it })
            collector.addManual(hit, null, stored) ?: return@launch
            _pagePlay.tryEmit(hit)
        }
    }

    /**
     * The WebView decides dark rendering (and prefers-color-scheme) from its
     * context theme, so the theme follows the "Dark pages" choice.
     */
    private class ThemedContext(base: Context) : ContextWrapper(base) {
        @Volatile var dark = false
        private val light by lazy { newTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        private val darkTheme by lazy { newTheme(android.R.style.Theme_Material_NoActionBar) }
        private fun newTheme(style: Int): Resources.Theme = resources.newTheme().apply { applyStyle(style, true) }
        override fun getTheme(): Resources.Theme = if (dark) darkTheme else light
    }

    companion object {
        const val BRIDGE_NAME = "RuterikaBridge"
        const val INCOGNITO_PROFILE = "incognito"
        private const val GESTURE_WINDOW_MS = 1_500L
        private const val POPUP_WAIT_MS = 10_000L
        private const val RETRY_DELAY_MS = 700L
        /** Tab list picture size in pixels (portrait, top of the visible page). */
        private const val THUMB_WIDTH = 160
        private const val THUMB_HEIGHT = 220
        private const val MAX_UA_RELOADS = 3
        private val RETRY_CODES = setOf(WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO)
        private val RETRY_ERRORS = listOf("ERR_CONNECTION_CLOSED", "ERR_CACHE_MISS", "ERR_CONNECTION_RESET", "ERR_NETWORK_CHANGED")
        private val MEDIA_PATH = Regex("\\.(mp4|m3u8|mpd|mkv|avi|webm|mov)$", RegexOption.IGNORE_CASE)
        private val ASKABLE_RESOURCES = setOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            PermissionRequest.RESOURCE_MIDI_SYSEX
        )

        fun collectorConfig(s: CastSettings) = PageMediaCollector.Config(
            keepOnNavigation = !s.clearListOnNavigation,
            // 0 disables "short clip = ad"; ad hosts are still separated.
            adDurationThresholdMs = if (s.hideShortVideos) 30_000L else 0L,
            acceptLanguage = acceptLanguage()
        )

        /** `Accept-Language` like the WebView sends it: the phone's locales, best first. */
        private fun acceptLanguage(): String {
            val locales = android.os.LocaleList.getDefault()
            return (0 until minOf(locales.size(), 3)).joinToString(",") { i ->
                val tag = locales[i].toLanguageTag()
                if (i == 0) tag else "$tag;q=${"%.1f".format(java.util.Locale.ROOT, 1.0 - i * 0.1)}"
            }.ifBlank { "en-US" }
        }

        fun sslReason(error: SslError): String = when (error.primaryError) {
            SslError.SSL_EXPIRED -> "The certificate has expired."
            SslError.SSL_NOTYETVALID -> "The certificate is not valid yet."
            SslError.SSL_IDMISMATCH -> "The certificate belongs to a different site."
            SslError.SSL_UNTRUSTED -> "The certificate is not issued by a trusted authority."
            SslError.SSL_DATE_INVALID -> "The certificate date is invalid."
            else -> "The certificate is invalid."
        }

        private fun certificateDetails(cert: SslCertificate?): String {
            cert ?: return ""
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
            return buildList {
                cert.issuedTo?.cName?.takeIf { it.isNotBlank() }?.let { add("Issued to: $it") }
                (cert.issuedBy?.oName?.takeIf { it.isNotBlank() } ?: cert.issuedBy?.cName)?.let { add("Issued by: $it") }
                @Suppress("DEPRECATION")
                val from = cert.validNotBeforeDate
                @Suppress("DEPRECATION")
                val until = cert.validNotAfterDate
                if (from != null && until != null) add("Valid: ${format.format(from)} – ${format.format(until)}")
            }.joinToString("\n")
        }

        @Suppress("FunctionName")
        private fun EMPTY_RESPONSE() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        @Volatile private var script: String? = null

        fun detectorScript(): String = script ?: (
            BrowserTab::class.java.classLoader?.getResourceAsStream("web/detector.js")
                ?.bufferedReader()?.use { it.readText() } ?: ""
            ).also { script = it }
    }
}
