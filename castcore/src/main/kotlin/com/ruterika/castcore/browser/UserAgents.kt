package com.ruterika.castcore.browser

import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.web.SiteRules
import com.ruterika.castcore.web.UserAgentRule

/**
 * Built-in user agents and how a tab's UA is chosen ("User agent /
 * Desktop mode" picker). A key is one of the constants below, a built-in
 * preset key, or `custom:<id>` for a user-added one.
 */
object UserAgents {

    data class Preset(val key: String, val name: String, val value: String, val desktop: Boolean)

    /** No override: the WebView's own UA. */
    const val NONE = "none"
    /** The device's Chrome UA (the WebView UA without the "wv" markers). */
    const val DEVICE_CHROME = "device_chrome"
    const val CUSTOM_PREFIX = "custom:"
    const val DESKTOP_KEY = "chrome_windows"

    val builtIn: List<Preset> = listOf(
        Preset("chrome_android", "Chrome (Android)", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36", false),
        Preset(DESKTOP_KEY, "Chrome (Windows desktop)", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", true),
        Preset("chrome_mac", "Chrome (Mac desktop)", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", true),
        Preset("safari_iphone", "Safari (iPhone)", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", false),
        Preset("safari_ipad", "Safari (iPad)", "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", false),
        Preset("safari_mac", "Safari (Mac)", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15", true),
        Preset("firefox_android", "Firefox (Android)", "Mozilla/5.0 (Android 14; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0", false),
        Preset("firefox_windows", "Firefox (Windows)", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0", true),
        Preset("edge_windows", "Edge (Windows)", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0", true)
    )

    /** The default key from settings, honouring the older desktop/custom fields. */
    fun defaultKey(s: CastSettings): String = when {
        s.userAgentKey.isNotBlank() -> s.userAgentKey
        s.customUserAgent.isNotBlank() -> LEGACY_CUSTOM
        s.desktopMode -> DESKTOP_KEY
        else -> NONE
    }

    /** UA string for [key]; null means "don't override" (use the WebView default). */
    fun resolve(key: String, s: CastSettings, webViewDefault: String): String? = when {
        key == NONE -> null
        key == DEVICE_CHROME -> deviceChrome(webViewDefault)
        key == LEGACY_CUSTOM -> s.customUserAgent.ifBlank { null }
        key.startsWith(CUSTOM_PREFIX) -> s.customUserAgents.firstOrNull { it.id == key.removePrefix(CUSTOM_PREFIX) }?.value
        else -> builtIn.firstOrNull { it.key == key }?.value
    }

    /**
     * UA a per-site rule asks for: its own string, else its key resolved like a user choice.
     * null = the rule gives none (unknown key or [NONE]).
     */
    fun resolveRule(rule: UserAgentRule, s: CastSettings, webViewDefault: String): String? =
        rule.userAgent.trim().ifBlank { null }
            ?: rule.userAgentKey.trim().takeIf { it.isNotEmpty() }?.let { resolve(it, s, webViewDefault) }

    /**
     * The UA a tab sends on [pageUrl]: the tab's own choice ([tabKey]) when it has one, else the
     * site rule for the page, else the default from settings. null = the WebView default.
     */
    fun forPage(tabKey: String?, pageUrl: String?, rules: SiteRules, s: CastSettings, webViewDefault: String): String? {
        if (tabKey == null) rules.userAgentRuleFor(pageUrl)?.let { rule -> resolveRule(rule, s, webViewDefault)?.let { return it } }
        return resolve(tabKey ?: defaultKey(s), s, webViewDefault)
    }

    fun isDesktop(key: String, s: CastSettings): Boolean = when {
        key.startsWith(CUSTOM_PREFIX) || key == LEGACY_CUSTOM -> {
            val ua = resolve(key, s, "").orEmpty()
            "Mobile" !in ua && "Android" !in ua && "iPhone" !in ua
        }
        else -> builtIn.firstOrNull { it.key == key }?.desktop == true
    }

    fun label(key: String, s: CastSettings): String = when {
        key == NONE -> "WebView default"
        key == DEVICE_CHROME -> "Chrome (this device)"
        key == LEGACY_CUSTOM -> "Custom"
        key.startsWith(CUSTOM_PREFIX) -> s.customUserAgents.firstOrNull { it.id == key.removePrefix(CUSTOM_PREFIX) }?.name ?: "Custom"
        else -> builtIn.firstOrNull { it.key == key }?.name ?: "WebView default"
    }

    /** The WebView UA as the system Chrome would send it. */
    fun deviceChrome(webViewDefault: String): String =
        webViewDefault.replace("; wv)", ")").replace(Regex("Version/\\d+(\\.\\d+)* "), "")

    /** The free-text UA from older versions (settings field `customUserAgent`). */
    const val LEGACY_CUSTOM = "legacy_custom"
}
