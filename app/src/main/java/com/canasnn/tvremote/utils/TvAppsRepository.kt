package com.canasnn.tvremote.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** An app installed on the TV, as reported by our TV receiver. */
data class TvApp(val component: String, val label: String) {
    val packageName: String get() = component.substringBefore('/')
    val id: String get() = ID_PREFIX + component

    companion object {
        const val ID_PREFIX = "tvapp:"
        /** Quick-launch "intent" of a TV app; opened through the receiver. */
        const val URI_PREFIX = "tvapp-launch:"
    }
}

/**
 * The TV's launcher apps as reported by our TV receiver (no ADB). Launching
 * goes through the receiver too (see ReceiverManager.launchApp), so apps
 * without a web link open directly.
 */
object TvAppsRepository {

    private const val PREFS = "tv_apps"
    private const val KEY_APPS = "apps"
    private const val KEY_SEEN = "seen_ids"

    private lateinit var prefs: SharedPreferences
    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps: StateFlow<List<TvApp>> = _apps.asStateFlow()


    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _apps.value = decode(prefs.getString(KEY_APPS, null))
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** App list reported by our TV receiver (no ADB needed). */
    fun setFromReceiver(apps: List<com.ruterika.protocol.TvAppInfo>) {
        if (!::prefs.isInitialized || apps.isEmpty()) return
        val list = apps.map { TvApp(it.component, it.label.ifBlank { labelFor(it.component.substringBefore('/')) }) }
            .sortedBy { it.label.lowercase() }
        if (list == _apps.value) return
        _apps.value = list
        prefs.edit().putString(KEY_APPS, encode(list)).apply()
        Timber.i("[TvApps] ${list.size} TV apps from receiver")
    }

    /** Ids of TV apps that have already been offered to the quick-launch strip. */
    fun seenIds(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    fun markSeen(ids: Collection<String>) {
        prefs.edit().putStringSet(KEY_SEEN, seenIds() + ids).apply()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun encode(list: List<TvApp>) =
        list.joinToString("\n") { "${it.component}\t${it.label}" }

    private fun decode(raw: String?): List<TvApp> =
        raw.orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].contains('/')) TvApp(parts[0], parts[1]) else null
        }

    private fun labelFor(pkg: String): String = KNOWN[pkg]
        ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private val KNOWN = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.netflix.ninja" to "Netflix",
        "com.instantbits.cast.receiver" to "Web Video Caster",
        "com.limelight" to "Moonlight",
        "com.android.vending" to "Play Store",
        "com.android.tv.settings" to "Ayarlar",
        "com.mediatek.wwtv.tvcenter" to "Canlı TV",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.wbd.stream" to "HBO Max",
        "com.hbo.hbonow" to "HBO Max",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.spotify.tv.android" to "Spotify",
        "com.dogan.puhutv.tv" to "puhutv",
        "tv.puhutv.androidtv" to "puhutv",
        "com.exxen.android.tv" to "Exxen",
        "com.turkcell.ott" to "TV+",
        "com.turkcell.tvplus.atv" to "TV+",
        "com.trt.tabii.android.tv" to "tabii",
        "org.videolan.vlc" to "VLC",
        "com.plexapp.android" to "Plex",
        "com.stremio.one" to "Stremio",
        "org.xbmc.kodi" to "Kodi",
        "com.twitch.android.app" to "Twitch",
    )
}
