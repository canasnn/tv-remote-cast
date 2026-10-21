package com.canasnn.tvremote.cast.browser

import android.content.Context
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.web.AdBlockListStore
import com.ruterika.castcore.web.AdBlocker
import com.ruterika.castcore.web.FailedUrlStore
import com.ruterika.castcore.web.SiteRules
import com.ruterika.castcore.web.SiteRulesStore
import com.ruterika.protocol.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Data the browser's video detection shares across tabs: the site rules (embedded + optional
 * download), the ad-block host lists (embedded + downloads + allow list) and the "failed to
 * play" URL table. Works with built-in defaults until [init] runs.
 */
object BrowserData {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false

    private var siteRulesStore: SiteRulesStore? = null
    private var adBlockStore: AdBlockListStore? = null
    private var failedStore: FailedUrlStore? = null

    /** The site rules in use. */
    val siteRules: SiteRules get() = siteRulesStore?.rules?.value ?: SiteRules.default()

    /** The ad-block matcher in use. */
    val adBlocker: AdBlocker get() = adBlockStore?.blocker?.value ?: AdBlocker.default()

    val rulesStore: SiteRulesStore? get() = siteRulesStore
    val adBlockLists: AdBlockListStore? get() = adBlockStore

    /** True when [url] failed to play on the TV within the last 30 days. */
    fun isFailed(url: String): Boolean = failedStore?.isFailed(url) ?: false

    /** Number of remembered failed URLs. */
    val failedCount: Int get() = failedStore?.size ?: 0

    fun clearFailed() {
        failedStore?.clear()
        republishTabs()
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val dir = File(context.applicationContext.filesDir, "browser_data")
        val http = BrowserManager.http
        siteRulesStore = SiteRulesStore(File(dir, "site_rules.json"), http)
        failedStore = FailedUrlStore(File(dir, "failed_urls.json"))
        val lists = AdBlockListStore(dir, http)
        adBlockStore = lists

        scope.launch {
            lists.load(CastHub.settings.value.adBlockAllowList)
            refreshTabs()
            autoUpdate()
        }
        // The allow list is edited in settings: rebuild the matcher when it changes.
        scope.launch {
            CastHub.settings.map { it.adBlockAllowList }.distinctUntilChanged().drop(1).collect { allow ->
                lists.setAllowList(allow)
                refreshTabs()
            }
        }
        // Remember URLs that fail on the TV (listed last for 30 days), forget them once they play.
        scope.launch {
            CastHub.status.collect { st ->
                val item = CastHub.session.value?.current(st) ?: return@collect
                val store = failedStore ?: return@collect
                when (st.state) {
                    PlayerState.ERROR -> if (!store.isFailed(item.url)) {
                        store.markFailed(item.url)
                        republishTabs()
                    }
                    PlayerState.PLAYING -> if (store.isFailed(item.url)) {
                        store.markWorking(item.url)
                        republishTabs()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Downloads the site rules from the settings URL. */
    suspend fun updateSiteRules(): SiteRulesStore.UpdateResult {
        val store = siteRulesStore ?: return SiteRulesStore.UpdateResult.Failed("Not ready")
        val url = CastHub.settings.value.siteRulesUrl.trim()
        if (url.isEmpty()) return SiteRulesStore.UpdateResult.Failed("No update address set")
        return store.update(url)
    }

    fun resetSiteRules() {
        siteRulesStore?.reset()
    }

    /** Downloads the ad-block update list and the custom lists. */
    suspend fun updateAdBlockLists(force: Boolean = false): AdBlockListStore.UpdateResult {
        val store = adBlockStore ?: return AdBlockListStore.UpdateResult.Failed("Not ready")
        val s = CastHub.settings.value
        val urls = listOf(s.adBlockUpdateUrl) + s.adBlockCustomListUrls
        val result = store.update(urls.filter { it.isNotBlank() }, force)
        if (result is AdBlockListStore.UpdateResult.Updated) refreshTabs()
        return result
    }

    /** Settings "Reset ad blocking": back to the built-in list (the allow list stays). */
    fun resetAdBlockLists() {
        scope.launch {
            adBlockStore?.reset()
            refreshTabs()
        }
    }

    /** Called when the browser opens: a long-running app still gets the daily list check. */
    fun refreshIfStale() {
        if (!initialized) return
        scope.launch { autoUpdate() }
    }

    private val updating = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun autoUpdate() {
        if (!updating.compareAndSet(false, true)) return
        try { autoUpdateOnce() } finally { updating.set(false) }
    }

    private suspend fun autoUpdateOnce() {
        val s = CastHub.settings.value
        if (s.siteRulesUrl.isNotBlank()) {
            val store = siteRulesStore
            if (store != null && System.currentTimeMillis() - store.updatedAt > RULES_MAX_AGE_MS) {
                runCatching { Timber.i("[Browser] Site rules update: ${updateSiteRules()}") }
            }
        }
        val lists = adBlockStore ?: return
        val hasLists = s.adBlockUpdateUrl.isNotBlank() || s.adBlockCustomListUrls.any { it.isNotBlank() }
        val wanted = (listOf(s.adBlockUpdateUrl) + s.adBlockCustomListUrls).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val otherLists = lists.info.value.sources != wanted
        val stale = System.currentTimeMillis() - lists.info.value.updatedAt > ADBLOCK_MAX_AGE_MS
        if (hasLists && (otherLists || (s.adBlockAutoUpdate && stale))) {
            runCatching { Timber.i("[Browser] Ad block lists update: ${updateAdBlockLists()}") }
        }
    }

    /** New matcher: tabs pick it up through their settings refresh. */
    private fun refreshTabs() {
        scope.launch(Dispatchers.Main) { BrowserManager.tabs.value.forEach { it.applySettings() } }
    }

    private fun republishTabs() {
        BrowserManager.tabs.value.forEach { it.collector.republish() }
    }

    private const val RULES_MAX_AGE_MS = 24L * 60 * 60 * 1000
    /** Lists are checked once a day (a check costs almost nothing when they did not change). */
    private const val ADBLOCK_MAX_AGE_MS = 24L * 60 * 60 * 1000
}
