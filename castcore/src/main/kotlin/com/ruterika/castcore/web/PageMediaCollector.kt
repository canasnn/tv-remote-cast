package com.ruterika.castcore.web

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Resource type of a WebView request, when the caller knows it. */
enum class ResourceType { DOCUMENT, SUBFRAME, MEDIA, XHR, SCRIPT, STYLESHEET, IMAGE, FONT, OTHER }

/**
 * Collects playable media for one browser tab.
 *
 * Feed it every WebView request ([onRequest]), every message posted by the injected
 * `detector.js` ([onJsMessage]), navigation ([onPageStarted]) and the results of the network
 * checks it asks for through [pendingProbes] ([onHeadProbeResult], [onPlaylistLoaded]).
 * [items] then holds the playable media, best first:
 * HLS master > HLS media > DASH > progressive video > audio, then by resolution, size, bitrate.
 *
 * Segments and images are never listed; subtitles are attached to the listed items; ads (ad
 * hosts, or non-live clips shorter than [Config.adDurationThresholdMs]) are moved to [adItems];
 * HLS media playlists that belong to an already parsed master are merged into the master.
 *
 * Header rule for every item: `Referer` = the request's Referer if present, else the page (or
 * frame) URL; `Origin` = the request's Origin, else the origin of that Referer; `User-Agent` =
 * the request's UA (or the last one seen / [Config.defaultUserAgent]); `Cookie` only when the
 * caller supplies it (the `cookie` argument or [cookieProvider], e.g. backed by CookieManager).
 *
 * All methods are thread safe (WebView calls them from several threads).
 *
 * [SiteRules] (from [rules]) add per-site interventions, an ignore list, captcha/auth pages
 * where nothing is detected, header rules and "don't clean" sites.
 *
 * @param adBlocker matcher used to flag ad hosts (can be replaced later through [adBlocker]).
 * @param config behaviour switches.
 * @param cookieProvider optional lookup of the cookie header for a media URL.
 * @param rules the site rules to apply (read on every use, so updates apply at once).
 * @param isFailedUrl URLs that failed to play before; such items are listed last.
 * @param clock time source for [DetectedMedia.detectedAt].
 */
class PageMediaCollector(
    adBlocker: AdBlocker = AdBlocker.default(),
    config: Config = Config(),
    private val cookieProvider: ((url: String) -> String?)? = null,
    private val rules: () -> SiteRules = { SiteRules.default() },
    private val isFailedUrl: (url: String) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Ad host matcher; may be replaced (updated lists). */
    @Volatile
    var adBlocker: AdBlocker = adBlocker

    /** Behaviour switches; may be changed at any time (settings screen). */
    @Volatile
    var config: Config = config
        set(value) {
            field = value
            synchronized(lock) { publishLocked() }
        }

    /** Collector options. */
    data class Config(
        /** Keep detected items when the tab navigates to another page. */
        val keepOnNavigation: Boolean = false,
        /** Non-live media shorter than this is considered an ad. */
        val adDurationThresholdMs: Long = 30_000,
        /** Maximum number of tracked items per page (oldest dropped first). */
        val maxItems: Int = 200,
        /** Maximum HEAD probes requested per page. */
        val maxHeadProbes: Int = 100,
        /** User-Agent used when no request carried one. */
        val defaultUserAgent: String? = null,
        /** Ask for HLS playlists / DASH manifests to be fetched (variants, live, duration, DRM). */
        val fetchPlaylists: Boolean = true,
        /** `Accept-Language` added by header rules that ask for it. */
        val acceptLanguage: String? = null,
    )

    private class Entry(
        var media: DetectedMedia,
        var adHost: Boolean,
        /** Class came from the JS `kind` hint, not from the URL or MIME type. */
        var classGuessed: Boolean,
        val seq: Long,
        /** A guessed playlist (TCPlayer webrtc → HLS): dropped unless it loads as a playlist. */
        var needsValidation: Boolean = false,
    )

    private class PendingHead(
        val headers: Map<String, String>,
        val pageUrl: String?,
        val source: DetectionSource,
        val adHost: Boolean,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val pageSubtitles = LinkedHashMap<String, DetectedSubtitle>()
    /** pathKey(child playlist) -> normalized key of its master. */
    private val childToMaster = HashMap<String, String>()
    private val requestedProbes = HashSet<String>()
    private val pendingHead = LinkedHashMap<String, PendingHead>()
    private val pendingPlaylists = LinkedHashMap<String, ProbeRequest>()
    private var headProbeCount = 0
    private var seq = 0L
    private var currentPageUrl: String? = null
    private var pageTitle: String? = null
    /** `og:title` / `og:image` of the top page (title and poster fallbacks). */
    private var pageMetaTitle: String? = null
    private var pagePoster: String? = null
    private var lastUserAgent: String? = null

    private val _items = MutableStateFlow<List<DetectedMedia>>(emptyList())
    private val _adItems = MutableStateFlow<List<DetectedMedia>>(emptyList())
    private val _mediaSourceInUse = MutableStateFlow(false)
    private val _probes = MutableSharedFlow<ProbeRequest>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Playable items of the current page, best first (ads, segments, attached playlists hidden). */
    val items: StateFlow<List<DetectedMedia>> = _items.asStateFlow()

    /** Items classified as likely ads (hidden from [items]). */
    val adItems: StateFlow<List<DetectedMedia>> = _adItems.asStateFlow()

    /**
     * `true` once the page reported a MediaSource/blob: player. Such streams are only visible
     * through their manifest requests, so the UI can suggest pressing play.
     */
    val mediaSourceInUse: StateFlow<Boolean> = _mediaSourceInUse.asStateFlow()

    /**
     * Network checks requested by the collector. Emissions are not replayed: subscribe before
     * feeding requests, or call [pendingProbeRequests] to catch up.
     */
    val pendingProbes: SharedFlow<ProbeRequest> = _probes.asSharedFlow()

    /** Current page URL (last [onPageStarted]). */
    val pageUrl: String? get() = synchronized(lock) { currentPageUrl }

    /** Probes requested but not answered yet. */
    fun pendingProbeRequests(): List<ProbeRequest> = synchronized(lock) {
        pendingHead.keys.map { url -> ProbeRequest(url, pendingHead.getValue(url).headers, ProbeKind.HEAD) } +
            pendingPlaylists.values
    }

    /** True when the current page is a captcha / sign-in page (nothing is detected there). */
    val isPageExcluded: Boolean get() = rules().isNoDetect(synchronized(lock) { currentPageUrl })

    /**
     * Called when the main frame starts loading [url]. Clears everything unless
     * [Config.keepOnNavigation] is set, the previous page is a "don't clean" site, or only the
     * fragment changed.
     */
    fun onPageStarted(url: String) {
        synchronized(lock) {
            val previous = currentPageUrl
            val sameDocument = previous != null && WebUrls.stripFragment(previous) == WebUrls.stripFragment(url)
            currentPageUrl = url
            if (!config.keepOnNavigation && !sameDocument && !rules().keepsList(previous)) resetLocked()
            if (WebUrls.isHttp(url) && !rules().isNoDetect(url)) addQuerySubtitlesLocked(url)
            publishLocked()
        }
    }

    /** Sorts and publishes the lists again (e.g. after a URL failed to play). */
    fun republish() {
        synchronized(lock) { publishLocked() }
    }

    private fun resetLocked() {
        entries.clear()
        pageSubtitles.clear()
        childToMaster.clear()
        requestedProbes.clear()
        pendingHead.clear()
        pendingPlaylists.clear()
        headProbeCount = 0
        pageTitle = null
        pageMetaTitle = null
        pagePoster = null
        _mediaSourceInUse.value = false
    }

    /** Updates the page title used for items without their own title. */
    fun onPageTitle(title: String?) {
        synchronized(lock) {
            pageTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            publishLocked()
        }
    }

    /** Clears all items and state (keeps the page URL). */
    fun clear() {
        synchronized(lock) {
            resetLocked()
            publishLocked()
        }
    }

    /**
     * Reports a request made by the WebView.
     *
     * @param url requested URL.
     * @param method HTTP method; only GET/HEAD are considered.
     * @param headers request headers as reported by the WebView.
     * @param pageUrl URL of the top-level page (defaults to the last [onPageStarted] URL).
     * @param resourceType resource type if the caller knows it.
     * @param cookie Cookie header for [url] (e.g. from CookieManager); overrides [cookieProvider].
     */
    fun onRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        pageUrl: String? = null,
        resourceType: ResourceType? = null,
        cookie: String? = null,
    ) {
        if (!WebUrls.isHttp(url)) return
        val m = method.uppercase()
        if (m != "GET" && m != "HEAD") return
        val r = rules()
        synchronized(lock) {
            headerValue(headers, "User-Agent")?.let { lastUserAgent = it }
            val page = pageUrl ?: currentPageUrl
            if (r.isNoDetect(url) || r.isNoDetect(page)) return
            addQuerySubtitlesLocked(url)
            val adHost = adBlocker.isBlocked(url)
            r.intervention(RuleContext(url, page, null, headers))?.let { hit ->
                if (hit.ignore || hit.mediaClass == null || adHost) return
                val built = if (hit.dropAllHeaders) emptyMap() else buildHeaders(hit.url, headers, page, cookie, requestSeen = true)
                    .filterKeys { k -> hit.removeHeaders.none { it.equals(k, true) } }
                upsertLocked(
                    url = hit.url, cls = hit.mediaClass, classGuessed = false, mime = hit.mime, headers = built,
                    headersAuthoritative = true, pageUrl = page, source = DetectionSource.REQUEST, adHost = false,
                )
                publishLocked()
                return
            }
            var cls = MediaUrlClassifier.classify(url, null, null)
            if (cls == MediaClass.NONE && resourceType == ResourceType.MEDIA) cls = MediaClass.MAYBE
            val built = buildHeaders(url, headers, page, cookie)
            when (cls) {
                MediaClass.NONE, MediaClass.IMAGE, MediaClass.SEGMENT -> return
                MediaClass.SUBTITLE -> {
                    if (!adHost) addPageSubtitle(url, null)
                }
                MediaClass.MAYBE -> {
                    if (adHost || resourceType in NON_MEDIA_RESOURCES) return
                    requestHeadLocked(url, PendingHead(built, page, DetectionSource.REQUEST, adHost))
                }
                else -> {
                    upsertLocked(
                        url = url, cls = cls, classGuessed = false, mime = null, headers = built,
                        headersAuthoritative = true, pageUrl = page, source = DetectionSource.REQUEST, adHost = adHost,
                    )
                }
            }
            publishLocked()
        }
    }

    /**
     * Handles a message posted by `detector.js` through the bridge. Malformed messages are ignored.
     *
     * @return `true` if the message was understood.
     */
    fun onJsMessage(json: String): Boolean {
        val msg = try {
            JSON.decodeFromString(JsMessage.serializer(), json)
        } catch (_: Exception) {
            return false
        }
        synchronized(lock) {
            if (msg.mse) _mediaSourceInUse.value = true
            val framePage = msg.pageUrl?.takeIf { WebUrls.isHttp(it) } ?: currentPageUrl
            val r = rules()
            if (r.isNoDetect(currentPageUrl) || r.isNoDetect(framePage)) return true
            if (msg.top && !msg.pageTitle.isNullOrBlank()) pageTitle = msg.pageTitle.trim()
            if (msg.top) {
                msg.ogTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { pageMetaTitle = it }
                msg.ogImage?.takeIf { WebUrls.isHttp(it) && !WebUrls.pathOf(it).lowercase().endsWith(".svg") }?.let { pagePoster = it }
            }
            if (framePage != null && framePage != currentPageUrl) addQuerySubtitlesLocked(framePage)
            if (msg.type == "media") {
                msg.items.forEach { handleJsItem(it, framePage) }
            }
            publishLocked()
        }
        return true
    }

    /**
     * Result of a [ProbeKind.HEAD] probe. [mime] is the response Content-Type, [contentLength]
     * the size in bytes (-1/null if unknown). Pass `mime = null` when the probe failed.
     */
    fun onHeadProbeResult(url: String, mime: String?, contentLength: Long?) {
        synchronized(lock) {
            val key = WebUrls.normalize(url)
            val pending = pendingHead.remove(key)
            val length = contentLength?.takeIf { it > 0 }
            val cls = if (mime == null) MediaClass.MAYBE else MediaUrlClassifier.classify(url, mime, null)
            val existing = entries[key]
            if (existing != null) {
                when {
                    cls.isPlayable -> {
                        existing.media = existing.media.copy(
                            mediaClass = if (existing.classGuessed || cls != MediaClass.PROGRESSIVE_VIDEO) cls else existing.media.mediaClass,
                            mimeType = MediaUrlClassifier.normalizeMime(mime) ?: existing.media.mimeType,
                            contentLength = length ?: existing.media.contentLength,
                        )
                        existing.classGuessed = false
                        if (cls == MediaClass.HLS) requestPlaylistLocked(url, existing.media.headers)
                    }
                    cls == MediaClass.MAYBE -> Unit // unknown: keep the JS guess
                    existing.classGuessed -> entries.remove(key)
                }
            } else if (pending != null) {
                when {
                    cls.isPlayable -> {
                        upsertLocked(
                            url = url, cls = cls, classGuessed = false, mime = MediaUrlClassifier.normalizeMime(mime),
                            headers = pending.headers, headersAuthoritative = true, pageUrl = pending.pageUrl,
                            source = pending.source, adHost = pending.adHost, contentLength = length,
                        )
                    }
                    cls == MediaClass.SUBTITLE -> addPageSubtitle(url, null, MediaUrlClassifier.normalizeMime(mime))
                }
            }
            publishLocked()
        }
    }

    /** Call when a probe could not be performed, so it is no longer reported as pending. */
    fun onProbeFailed(url: String) {
        synchronized(lock) {
            val key = WebUrls.normalize(url)
            pendingHead.remove(key)
            val wasPlaylist = pendingPlaylists.remove(key) != null
            if (wasPlaylist && entries[key]?.needsValidation == true) {
                entries.remove(key)
                publishLocked()
            }
        }
    }

    /**
     * Result of a [ProbeKind.PLAYLIST] probe: the HLS playlist [body] downloaded from [url].
     * Masters get their variants/renditions and swallow their child playlists; media playlists
     * provide live/VOD, duration and encryption (to their master when they belong to one).
     */
    fun onPlaylistLoaded(url: String, body: String) {
        val playlist = HlsPlaylistParser.parseOrNull(body, url)
        synchronized(lock) {
            val key = WebUrls.normalize(url)
            pendingPlaylists.remove(key)
            if (playlist == null) {
                if (entries[key]?.needsValidation == true) {
                    entries.remove(key)
                    publishLocked()
                }
                return
            }
            entries[key]?.needsValidation = false
            if (playlist.isMaster) applyMasterLocked(key, url, playlist) else applyMediaLocked(key, url, playlist)
            publishLocked()
        }
    }

    /**
     * Result of a [ProbeKind.DASH] probe: the MPD [body] downloaded from [url]. Gives the item its
     * duration, live flag, DRM flag and video qualities (as [DetectedMedia.variants] pointing to
     * the manifest itself).
     */
    fun onDashManifestLoaded(url: String, body: String) {
        val manifest = DashManifestParser.parseOrNull(body)
        synchronized(lock) {
            val key = WebUrls.normalize(url)
            pendingPlaylists.remove(key)
            val entry = entries[key] ?: return
            if (manifest == null) return
            val m = entry.media
            val best = manifest.videoRepresentations.firstOrNull()
            val qualities = manifest.qualities(m.url)
            entry.media = m.copy(
                mediaClass = MediaClass.DASH,
                mimeType = m.mimeType ?: "application/dash+xml",
                isLive = manifest.isLive || m.isLive,
                durationMs = manifest.durationMs ?: m.durationMs,
                isDrmProtected = m.isDrmProtected || manifest.isDrmProtected,
                isEncrypted = m.isEncrypted || manifest.isDrmProtected,
                variants = if (qualities.size > 1) qualities else m.variants,
                width = best?.width ?: m.width,
                height = best?.height ?: m.height,
                bitrate = best?.bandwidth?.takeIf { it > 0 } ?: m.bitrate,
            )
            entry.classGuessed = false
            publishLocked()
        }
    }

    /**
     * Adds a URL entered by the user. Unknown types are assumed to be progressive video and
     * probed.
     *
     * @return the created (or existing) item, or `null` for non-http URLs.
     */
    fun addManual(url: String, title: String? = null, headers: Map<String, String> = emptyMap()): DetectedMedia? {
        if (!WebUrls.isHttp(url)) return null
        synchronized(lock) {
            var cls = MediaUrlClassifier.classify(url, null, null)
            var guessed = false
            if (!cls.isPlayable) {
                cls = MediaClass.PROGRESSIVE_VIDEO
                guessed = true
            }
            val built = buildHeaders(url, headers, currentPageUrl, headerValue(headers, "Cookie"))
            upsertLocked(
                url = url, cls = cls, classGuessed = guessed, mime = null, headers = built, headersAuthoritative = true,
                pageUrl = currentPageUrl, source = DetectionSource.MANUAL, adHost = false, title = title,
            )
            if (guessed) requestHeadLocked(url, null)
            publishLocked()
            return entries[WebUrls.normalize(url)]?.let { decorateLocked(it) }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun handleJsItem(item: JsItem, framePage: String?) {
        val rawUrl = item.url?.trim() ?: return
        if (!WebUrls.isHttp(rawUrl)) {
            if (rawUrl.startsWith("blob:", ignoreCase = true)) _mediaSourceInUse.value = true
            return
        }
        val url = rawUrl
        val kind = item.kind?.lowercase()
        val adHost = adBlocker.isBlocked(url)
        if (kind == "subtitle" || kind == "subtitles" || kind == "captions") {
            if (!adHost) addPageSubtitle(url, item.lang, item.mime, item.label ?: item.title)
            return
        }
        val hintMime = when (item.mime?.lowercase()) {
            "hls", "m3u8" -> "application/x-mpegurl"
            "dash", "mpd" -> "application/dash+xml"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> item.mime
        }
        var cls = MediaUrlClassifier.classify(url, hintMime, null)
        var guessed = false
        when (cls) {
            MediaClass.SEGMENT, MediaClass.IMAGE -> return
            MediaClass.SUBTITLE -> {
                if (!adHost) addPageSubtitle(url, item.lang, hintMime, item.label)
                return
            }
            MediaClass.MAYBE, MediaClass.NONE -> {
                cls = when (kind) {
                    "hls" -> MediaClass.HLS
                    "dash" -> MediaClass.DASH
                    "audio" -> MediaClass.AUDIO
                    "video" -> MediaClass.PROGRESSIVE_VIDEO
                    else -> return
                }
                guessed = kind == "audio" || kind == "video"
            }
            else -> Unit
        }
        val source = if (item.src == "tag") DetectionSource.JS_VIDEO_TAG else DetectionSource.JS_PLAYER_CONFIG
        val headers = buildHeaders(url, emptyMap(), framePage, null)
        val isNew = entries[WebUrls.normalize(url)] == null
        val subtitles = item.subtitles.mapNotNull { s ->
            val su = s.url?.trim()?.takeIf { WebUrls.isHttp(it) } ?: return@mapNotNull null
            DetectedSubtitle(
                url = su,
                language = s.lang?.takeIf { it.isNotBlank() },
                label = s.label?.takeIf { it.isNotBlank() },
                mime = s.mime ?: MediaUrlClassifier.guessMime(su, MediaClass.SUBTITLE),
            )
        }
        upsertLocked(
            url = url, cls = cls, classGuessed = guessed, mime = MediaUrlClassifier.normalizeMime(hintMime)?.takeIf { it.contains('/') },
            headers = headers, headersAuthoritative = false, pageUrl = framePage, source = source, adHost = adHost,
            title = item.title?.trim()?.takeIf { it.isNotEmpty() },
            posterUrl = item.poster?.takeIf { WebUrls.isHttp(it) },
            durationMs = item.durationMs?.takeIf { it > 0 && it.isFinite() }?.toLong(),
            width = item.width?.takeIf { it > 0 }, height = item.height?.takeIf { it > 0 },
            subtitles = subtitles, isLive = item.live,
            webPositionMs = item.positionMs?.takeIf { it >= 0 && it.isFinite() }?.toLong(),
        )
        if (item.validate && isNew) entries[WebUrls.normalize(url)]?.needsValidation = true
        if (guessed && !adHost) requestHeadLocked(url, null)
    }

    private fun upsertLocked(
        url: String,
        cls: MediaClass,
        classGuessed: Boolean,
        mime: String?,
        headers: Map<String, String>,
        headersAuthoritative: Boolean,
        pageUrl: String?,
        source: DetectionSource,
        adHost: Boolean,
        title: String? = null,
        posterUrl: String? = null,
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
        subtitles: List<DetectedSubtitle> = emptyList(),
        isLive: Boolean = false,
        contentLength: Long? = null,
        webPositionMs: Long? = null,
    ) {
        val key = WebUrls.normalize(url)
        if (entries[key] == null && rules().shouldIgnore(url, pageUrl, mime)) return
        // A media playlist of an already parsed master: attach instead of listing.
        if (cls == MediaClass.HLS && childToMaster[WebUrls.pathKey(url)]?.let { it != key && entries.containsKey(it) } == true) return

        val existing = entries[key]
        if (existing == null) {
            val media = DetectedMedia(
                id = WebUrls.stableId(url),
                url = url,
                mediaClass = cls,
                mimeType = mime ?: MediaUrlClassifier.guessMime(url, cls),
                title = title,
                pageUrl = pageUrl,
                headers = headers,
                durationMs = durationMs,
                width = width,
                height = height,
                contentLength = contentLength,
                posterUrl = posterUrl,
                subtitles = subtitles,
                detectedAt = clock(),
                source = source,
                isLive = isLive,
                webPositionMs = webPositionMs,
            )
            entries[key] = Entry(media, adHost, classGuessed, seq++)
            while (entries.size > config.maxItems) {
                val oldest = entries.keys.first()
                entries.remove(oldest)
            }
            if (cls == MediaClass.HLS && !adHost) requestPlaylistLocked(url, headers)
            if (cls == MediaClass.DASH && !adHost) requestDashLocked(url, headers)
            return
        }
        val old = existing.media
        val newClass = when {
            existing.classGuessed && !classGuessed -> cls
            else -> old.mediaClass
        }
        if (existing.classGuessed && !classGuessed) existing.classGuessed = false
        val mergedHeaders = when {
            headersAuthoritative -> {
                val cookie = headers["Cookie"] ?: old.headers["Cookie"]
                if (cookie != null) headers + ("Cookie" to cookie) else headers
            }
            else -> headers + old.headers
        }
        existing.media = old.copy(
            mediaClass = newClass,
            mimeType = old.mimeType ?: mime,
            title = old.title ?: title,
            headers = mergedHeaders,
            pageUrl = old.pageUrl ?: pageUrl,
            durationMs = old.durationMs ?: durationMs,
            width = maxOrNull(old.width, width),
            height = maxOrNull(old.height, height),
            contentLength = old.contentLength ?: contentLength,
            posterUrl = old.posterUrl ?: posterUrl,
            subtitles = (old.subtitles + subtitles).distinctBy { WebUrls.normalize(it.url) },
            isLive = old.isLive || isLive,
            webPositionMs = webPositionMs ?: old.webPositionMs,
        )
        existing.adHost = existing.adHost || adHost
        if (newClass == MediaClass.HLS && !existing.adHost) requestPlaylistLocked(url, mergedHeaders)
        if (newClass == MediaClass.DASH && !existing.adHost) requestDashLocked(url, mergedHeaders)
    }

    private fun applyMasterLocked(key: String, url: String, playlist: HlsPlaylist) {
        val children = playlist.referencedPlaylistUrls.map { WebUrls.pathKey(it) }.toSet()
        var childLive: Boolean? = null
        var childDuration: Long? = null
        var childAd = false
        children.forEach { childToMaster[it] = key }
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val (k, e) = iter.next()
            if (k != key && WebUrls.pathKey(e.media.url) in children) {
                if (e.media.isMasterPlaylist == false) {
                    childLive = e.media.isLive
                    childDuration = childDuration ?: e.media.durationMs
                }
                childAd = childAd || e.adHost
                iter.remove()
            }
        }
        val entry = entries[key] ?: return
        val best = playlist.bestVariant
        val subtitleTracks = playlist.subtitleRenditions.filter { r -> r.url != null }.map { r ->
            DetectedSubtitle(
                url = r.url!!,
                language = r.language,
                label = r.name,
                mime = "application/x-mpegURL",
            )
        }
        val m = entry.media
        entry.media = m.copy(
            mediaClass = MediaClass.HLS,
            mimeType = "application/x-mpegURL",
            isMasterPlaylist = true,
            variants = playlist.variants,
            audioTracks = playlist.audioRenditions,
            width = best?.width ?: m.width,
            height = best?.height ?: m.height,
            bitrate = best?.bandwidth?.takeIf { b -> b > 0 } ?: m.bitrate,
            subtitles = (m.subtitles + subtitleTracks).distinctBy { s -> WebUrls.normalize(s.url) },
            isEncrypted = m.isEncrypted || playlist.isEncrypted,
            isDrmProtected = m.isDrmProtected || playlist.encryption?.isDrm == true,
            isLive = childLive ?: m.isLive,
            durationMs = m.durationMs ?: childDuration,
        )
        entry.adHost = entry.adHost || childAd
        entry.classGuessed = false
        // Load one media playlist to learn live/VOD and duration of the whole presentation.
        if (childLive == null && config.fetchPlaylists) {
            playlist.variants.firstOrNull()?.let { v -> requestPlaylistLocked(v.url, m.headers) }
        }
    }

    private fun applyMediaLocked(key: String, url: String, playlist: HlsPlaylist) {
        val masterKey = childToMaster[WebUrls.pathKey(url)]?.takeIf { it != key }
        val target = masterKey?.let { entries[it] } ?: entries[key] ?: return
        if (masterKey != null && entries.containsKey(masterKey)) entries.remove(key)
        val m = target.media
        target.media = m.copy(
            isMasterPlaylist = if (target === entries[key]) false else m.isMasterPlaylist,
            isLive = playlist.isLive == true,
            durationMs = if (playlist.isLive == true) m.durationMs else (playlist.durationMs ?: m.durationMs),
            isEncrypted = m.isEncrypted || playlist.isEncrypted,
            isDrmProtected = m.isDrmProtected || playlist.encryption?.isDrm == true,
            mimeType = "application/x-mpegURL",
        )
        target.classGuessed = false
    }

    private fun requestHeadLocked(url: String, pending: PendingHead?) {
        if (rules().isNoDetect(url)) return
        val key = WebUrls.normalize(url)
        if (!requestedProbes.add("H$key")) return
        if (headProbeCount >= config.maxHeadProbes) return
        headProbeCount++
        val headers = pending?.headers ?: entries[key]?.media?.headers ?: emptyMap()
        pendingHead[key] = pending ?: PendingHead(headers, currentPageUrl, DetectionSource.REQUEST, false)
        _probes.tryEmit(ProbeRequest(url, headers, ProbeKind.HEAD))
    }

    private fun requestPlaylistLocked(url: String, headers: Map<String, String>) {
        if (!config.fetchPlaylists) return
        val key = WebUrls.normalize(url)
        if (!requestedProbes.add("P$key")) return
        val req = ProbeRequest(url, headers, ProbeKind.PLAYLIST)
        pendingPlaylists[key] = req
        _probes.tryEmit(req)
    }

    private fun requestDashLocked(url: String, headers: Map<String, String>) {
        if (!config.fetchPlaylists) return
        val key = WebUrls.normalize(url)
        if (!requestedProbes.add("D$key")) return
        val req = ProbeRequest(url, headers, ProbeKind.DASH)
        pendingPlaylists[key] = req
        _probes.tryEmit(req)
    }

    /** Subtitle files passed in the query of [url] (`?sub=…vtt`) become page subtitles. */
    private fun addQuerySubtitlesLocked(url: String) {
        MediaUrlClassifier.subtitleUrlsInQuery(url).forEach { sub ->
            if (!adBlocker.isBlocked(sub)) addPageSubtitle(sub, null)
        }
    }

    private fun addPageSubtitle(url: String, language: String?, mime: String? = null, label: String? = null) {
        val key = WebUrls.normalize(url)
        val existing = pageSubtitles[key]
        pageSubtitles[key] = DetectedSubtitle(
            url = url,
            language = existing?.language ?: language?.takeIf { it.isNotBlank() },
            label = existing?.label ?: label?.takeIf { it.isNotBlank() },
            mime = existing?.mime ?: mime ?: MediaUrlClassifier.guessMime(url, MediaClass.SUBTITLE),
        )
    }

    /**
     * Headers the TV must send. Requests keep everything the page sent except
     * transport headers.
     * Script-found media (no request seen) gets a Referer like a browser would:
     * the full page URL on the same origin, only the origin cross-origin.
     */
    private fun buildHeaders(
        url: String,
        request: Map<String, String>,
        pageUrl: String?,
        cookie: String?,
        requestSeen: Boolean = request.isNotEmpty(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        request.forEach { (k, v) -> if (k.lowercase() !in DROPPED_HEADERS && v.isNotBlank()) out[k] = v }
        listOf("Referer", "Origin", "User-Agent", "Cookie").forEach { name -> out.keys.firstOrNull { it.equals(name, true) }?.let(out::remove) }
        val fromRequest = requestSeen
        val page = pageUrl?.takeIf { WebUrls.isHttp(it) }
        val referer = headerValue(request, "Referer") ?: page?.let {
            if (fromRequest || WebUrls.originOf(it) == WebUrls.originOf(url)) it else WebUrls.originOf(it)?.let { o -> "$o/" }
        }
        if (referer != null) out["Referer"] = referer
        val origin = headerValue(request, "Origin") ?: if (fromRequest) WebUrls.originOf(referer) else null
        if (origin != null) out["Origin"] = origin
        val ua = headerValue(request, "User-Agent") ?: lastUserAgent ?: config.defaultUserAgent
        if (ua != null) out["User-Agent"] = ua
        val c = cookie ?: cookieProvider?.let { p -> runCatching { p(url) }.getOrNull() }
        if (!c.isNullOrBlank()) out["Cookie"] = c
        // A Referer equal to the media URL itself is never what a player sends.
        if (referer != null && WebUrls.normalize(referer) == WebUrls.normalize(url)) out.remove("Referer")
        return rules().applyHeaderRules(url, page, out, config.acceptLanguage)
    }

    private fun decorateLocked(e: Entry): DetectedMedia {
        val m = e.media
        val subs = if (pageSubtitles.isEmpty()) m.subtitles else (m.subtitles + pageSubtitles.values).distinctBy { WebUrls.normalize(it.url) }
        val shortClip = !m.isLive && m.durationMs != null && m.durationMs < config.adDurationThresholdMs
        return m.copy(
            title = m.title ?: pageMetaTitle ?: pageTitle,
            posterUrl = m.posterUrl ?: pagePoster,
            subtitles = subs,
            isLikelyAd = e.adHost || shortClip,
            previouslyFailed = runCatching { isFailedUrl(m.url) }.getOrDefault(false),
        )
    }

    private fun publishLocked() {
        val decorated = entries.values.map { it to decorateLocked(it) }
        val (ads, visible) = decorated.partition { it.second.isLikelyAd }
        _items.value = visible.sortedWith(ORDER).map { it.second }
        _adItems.value = ads.sortedWith(ORDER).map { it.second }
    }

    private fun maxOrNull(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    @Serializable
    private data class JsSubtitle(
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val mime: String? = null,
    )

    @Serializable
    private data class JsItem(
        val url: String? = null,
        val kind: String? = null,
        val mime: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val durationMs: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
        val live: Boolean = false,
        val lang: String? = null,
        val label: String? = null,
        val src: String? = null,
        val subtitles: List<JsSubtitle> = emptyList(),
        /** The element's `currentTime` in ms (web page position). */
        val positionMs: Double? = null,
        /** Guessed playlist URL that must load as HLS to be kept. */
        val validate: Boolean = false,
    )

    @Serializable
    private data class JsMessage(
        val type: String = "",
        val items: List<JsItem> = emptyList(),
        val pageTitle: String? = null,
        val pageUrl: String? = null,
        val top: Boolean = true,
        val mse: Boolean = false,
        val ogTitle: String? = null,
        val ogImage: String? = null,
    )

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        val NON_MEDIA_RESOURCES = setOf(
            ResourceType.SCRIPT, ResourceType.STYLESHEET, ResourceType.IMAGE, ResourceType.FONT,
            ResourceType.DOCUMENT, ResourceType.SUBFRAME,
        )

        fun headerValue(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

        fun rank(m: DetectedMedia): Int = when (m.mediaClass) {
            MediaClass.HLS -> when (m.isMasterPlaylist) {
                true -> 0
                null -> 1
                false -> 2
            }
            MediaClass.DASH -> 3
            MediaClass.PROGRESSIVE_VIDEO -> 4
            MediaClass.AUDIO -> 5
            else -> 6
        }

        val ORDER: Comparator<Pair<Entry, DetectedMedia>> = compareBy<Pair<Entry, DetectedMedia>> { it.second.previouslyFailed }
            .thenBy { rank(it.second) }
            .thenByDescending { it.second.height ?: 0 }
            .thenByDescending { it.second.width ?: 0 }
            .thenByDescending { it.second.contentLength ?: 0L }
            .thenByDescending { it.second.bitrate ?: 0L }
            .thenByDescending { it.second.durationMs ?: 0L }
            .thenBy { it.first.seq }
    }
}

/** Transport-level headers that must not be replayed by the TV player. */
private val DROPPED_HEADERS = setOf(
    "range", "host", "content-length", "connection", "accept-encoding", "if-range",
    "if-none-match", "if-modified-since", "upgrade-insecure-requests", "te", "keep-alive"
)
