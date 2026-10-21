package com.ruterika.castcore.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One condition of a [RuleMatch]: `field function value`.
 *
 * Fields: `url`, `urlLowercase`, `host`, `path`, `pageUrl`, `pageHost`, `referrer`, `mime`,
 * `header::<Name>`. Functions: `contains`, `notContains`, `startsWith`, `endsWith`, `equals`,
 * `equalsIgnoreCase`, `matchesRegex`, `notMatchesRegex` (Java regex, `find` semantics).
 * With [alternatives] the positive functions hold when any alternative matches and the `not*`
 * functions when none does.
 */
@Serializable
data class RuleCondition(
    val field: String,
    val function: String,
    val value: String? = null,
    val alternatives: List<String> = emptyList(),
)

/** A list of conditions; `type` is `any` (default) or `all`. */
@Serializable
data class RuleMatch(
    val type: String = "any",
    val rules: List<RuleCondition> = emptyList(),
)

/**
 * The simple "URL list" form: true when any list matches. `contains`, `startsWith` and
 * `matchesRegex` look at the lower-cased URL, `endsWith` at the host (`hcaptcha.com` also
 * matches `www.hcaptcha.com`).
 */
@Serializable
data class UrlPatterns(
    val contains: List<String> = emptyList(),
    val startsWith: List<String> = emptyList(),
    val endsWith: List<String> = emptyList(),
    val matchesRegex: List<String> = emptyList(),
)

/**
 * What to do with a request that matched a [SiteRule]. Types:
 * - `mime-type-assign-mpegurl`: list the URL as HLS;
 * - `mime-type-assign` (`mime`): list it with that MIME type;
 * - `add-as-is`: list it (progressive video unless the URL tells otherwise);
 * - `url-replace-substring` (`old`, `new`, optional `mime`): list the rewritten URL;
 * - `headers-remove` (`headers`, comma separated): list it without those request headers;
 * - `headers-nullify`: list it without any header;
 * - `ignore`: never list it.
 */
@Serializable
data class RuleIntervention(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

/** A per-site intervention: requests matching [match] are handled by [intervention]. */
@Serializable
data class SiteRule(
    val name: String = "",
    val match: RuleMatch,
    val intervention: RuleIntervention,
)

/** Media matching [match] (fields `url`, `pageUrl`, `mime`...) is never listed (player stubs...). */
@Serializable
data class IgnoreRule(
    val name: String = "",
    val match: RuleMatch,
)

/** Header edits for media URLs matching [match] (applied to the headers the TV will send). */
@Serializable
data class HeaderRule(
    val name: String = "",
    val match: RuleMatch,
    /** Headers to set (existing values are replaced). */
    val add: Map<String, String> = emptyMap(),
    /** Header names to drop. */
    val remove: List<String> = emptyList(),
    /** Add `Origin` = origin of the `Referer` when missing. */
    val originFromReferer: Boolean = false,
    /** Add the phone's `Accept-Language` when missing. */
    val acceptLanguage: Boolean = false,
)

/**
 * Automatic user agent for pages on [hosts]. A host also covers its
 * subdomains (`example.com` matches `www.example.com`). The UA is [userAgent] when set, otherwise
 * the built-in UA [userAgentKey] (see `com.ruterika.castcore.browser.UserAgents`).
 */
@Serializable
data class UserAgentRule(
    val name: String = "",
    val hosts: List<String> = emptyList(),
    val userAgentKey: String = "",
    val userAgent: String = "",
)

/** The JSON document behind [SiteRules] (embedded default, optionally replaced by a download). */
@Serializable
data class SiteRulesDocument(
    /** Higher wins; a cached download older than the embedded file is ignored. */
    val version: Int = 0,
    val interventions: List<SiteRule> = emptyList(),
    val ignore: List<IgnoreRule> = emptyList(),
    /** Captcha / sign-in pages and requests: no detection, no probes. */
    val noDetect: UrlPatterns = UrlPatterns(),
    val headers: List<HeaderRule> = emptyList(),
    /** "Don't clean": the video list is kept when leaving a page that matches. */
    val keepList: UrlPatterns = UrlPatterns(),
    /** Top pages whose iframes get their `sandbox` attribute removed (only `contains` is used in pages). */
    val sandboxFix: UrlPatterns = UrlPatterns(),
    /** Ad-redirect URL rules, see [AdRedirectRules]. */
    val adRedirect: List<String> = emptyList(),
    /** Per-site user agents; a tab where the user picked a UA keeps it. */
    val userAgents: List<UserAgentRule> = emptyList(),
)

/** Values a rule can look at. */
class RuleContext(
    val url: String,
    val pageUrl: String? = null,
    val mime: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    fun field(name: String): String? {
        val n = name.lowercase()
        if (n.startsWith("header::")) {
            val h = name.substring(8)
            return headers.entries.firstOrNull { it.key.equals(h, ignoreCase = true) }?.value
        }
        return when (n) {
            "url", "fullurl", "videourl" -> url
            "urllowercase" -> url.lowercase()
            "host", "requestedhost" -> WebUrls.hostOf(url)
            "path" -> WebUrls.pathOf(url)
            "pageurl" -> pageUrl
            "pagehost" -> pageUrl?.let { WebUrls.hostOf(it) }
            "referrer", "referer" -> headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            "mime", "mimetype" -> mime
            else -> null
        }
    }
}

/** Outcome of a matched [SiteRule] for one request. */
data class InterventionResult(
    val ruleName: String,
    /** The URL to list (rewritten by `url-replace-substring`). */
    val url: String,
    /** Class to list it as; `null` when [ignore] is set. */
    val mediaClass: MediaClass?,
    val mime: String? = null,
    val removeHeaders: Set<String> = emptySet(),
    val dropAllHeaders: Boolean = false,
    val ignore: Boolean = false,
)

/**
 * Compiled site rules (per-site interventions, ignore list, captcha/auth exclusions, header
 * rules, "don't clean" sites, sandbox fix pages and ad-redirect rules). Immutable and thread
 * safe. Invalid regular expressions only disable the condition that uses them.
 */
class SiteRules private constructor(val document: SiteRulesDocument) {

    val version: Int get() = document.version

    private val interventions = document.interventions.map { CompiledMatch(it.match) to it }
    private val ignore = document.ignore.map { CompiledMatch(it.match) }
    private val headerRules = document.headers.map { CompiledMatch(it.match) to it }
    private val noDetect = CompiledPatterns(document.noDetect)
    private val keepList = CompiledPatterns(document.keepList)
    private val sandbox = CompiledPatterns(document.sandboxFix)

    /** Ad-redirect URL rules of this document. */
    val adRedirect: AdRedirectRules = AdRedirectRules.parse(document.adRedirect)

    /** Lower-cased substrings of the pages that need the iframe sandbox fix. */
    val sandboxFixPages: List<String> get() = document.sandboxFix.contains.map { it.lowercase() }

    /** True for captcha / sign-in URLs: they are not probed and pages on them are not scanned. */
    fun isNoDetect(url: String?): Boolean = url != null && noDetect.matches(url)

    /** True when the list must be kept when leaving [previousPageUrl]. */
    fun keepsList(previousPageUrl: String?): Boolean = previousPageUrl != null && keepList.matches(previousPageUrl)

    /** True when [pageUrl] needs its iframes' `sandbox` attribute removed. */
    fun needsSandboxFix(pageUrl: String?): Boolean = pageUrl != null && sandbox.matches(pageUrl)

    /** True when [url] matches the ad-redirect rules. */
    fun isAdRedirect(url: String): Boolean = adRedirect.matches(url)

    /** Rules with lower-cased hosts (no leading dot); rules without a host or a UA are dropped. */
    private val userAgentRules: List<Pair<List<String>, UserAgentRule>> = document.userAgents.mapNotNull { rule ->
        val hosts = rule.hosts.map { it.trim().lowercase().trimStart('.') }.filter { it.isNotEmpty() }
        if (hosts.isEmpty() || (rule.userAgent.isBlank() && rule.userAgentKey.isBlank())) null else hosts to rule
    }

    /** True when there is at least one per-site user agent rule. */
    val hasUserAgentRules: Boolean get() = userAgentRules.isNotEmpty()

    /** The first per-site user agent rule for the host of [url], if any. */
    fun userAgentRuleFor(url: String?): UserAgentRule? {
        if (url == null || userAgentRules.isEmpty()) return null
        val host = WebUrls.hostOf(url)?.lowercase()?.trimEnd('.') ?: return null
        return userAgentRules.firstOrNull { (hosts, _) -> hosts.any { host == it || host.endsWith(".$it") } }?.second
    }

    /** True when media at [url] must not be listed. */
    fun shouldIgnore(url: String, pageUrl: String?, mime: String?): Boolean {
        if (ignore.isEmpty()) return false
        val ctx = RuleContext(url, pageUrl, mime)
        return ignore.any { it.matches(ctx) }
    }

    /** The first intervention matching the request, if any. */
    fun intervention(ctx: RuleContext): InterventionResult? {
        for ((match, rule) in interventions) {
            if (!match.matches(ctx)) continue
            return toResult(rule, ctx) ?: continue
        }
        return null
    }

    /**
     * Applies the header rules to [headers] (the headers the TV will send for [url]).
     * [acceptLanguage] is used by rules asking for it.
     */
    fun applyHeaderRules(url: String, pageUrl: String?, headers: Map<String, String>, acceptLanguage: String?): Map<String, String> {
        if (headerRules.isEmpty()) return headers
        var out: MutableMap<String, String>? = null
        for ((match, rule) in headerRules) {
            val ctx = RuleContext(url, pageUrl, null, out ?: headers)
            if (!match.matches(ctx)) continue
            val m = out ?: LinkedHashMap(headers).also { out = it }
            rule.remove.forEach { name -> m.keys.filter { it.equals(name, ignoreCase = true) }.forEach(m::remove) }
            rule.add.forEach { (k, v) ->
                m.keys.filter { it.equals(k, ignoreCase = true) }.forEach(m::remove)
                m[k] = v
            }
            if (rule.originFromReferer && m.keys.none { it.equals("Origin", true) }) {
                val referer = m.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                WebUrls.originOf(referer)?.let { m["Origin"] = it }
            }
            if (rule.acceptLanguage && !acceptLanguage.isNullOrBlank() && m.keys.none { it.equals("Accept-Language", true) }) {
                m["Accept-Language"] = acceptLanguage
            }
        }
        return out ?: headers
    }

    private fun toResult(rule: SiteRule, ctx: RuleContext): InterventionResult? {
        val p = rule.intervention.params
        fun byUrl(url: String, fallback: MediaClass): MediaClass =
            MediaUrlClassifier.classify(url, null, null).takeIf { it.isPlayable } ?: fallback
        return when (rule.intervention.type.lowercase()) {
            "mime-type-assign-mpegurl" -> InterventionResult(rule.name, ctx.url, MediaClass.HLS, "application/x-mpegURL")
            "mime-type-assign" -> {
                val mime = p["mime-type"] ?: p["mime"] ?: return null
                val cls = MediaUrlClassifier.classify(ctx.url, mime, null).takeIf { it.isPlayable } ?: return null
                InterventionResult(rule.name, ctx.url, cls, mime)
            }
            "add-as-is" -> InterventionResult(rule.name, ctx.url, byUrl(ctx.url, MediaClass.PROGRESSIVE_VIDEO))
            "url-replace-substring" -> {
                val old = p["old-value"] ?: p["old"] ?: return null
                val new = p["new-value"] ?: p["new"] ?: return null
                if (!ctx.url.contains(old)) return null
                val url = ctx.url.replaceFirst(old, new)
                val mime = p["mime-type"] ?: p["mime"]
                val cls = if (mime != null) MediaUrlClassifier.classify(url, mime, null) else byUrl(url, MediaClass.HLS)
                if (!cls.isPlayable) return null
                InterventionResult(rule.name, url, cls, mime)
            }
            "headers-remove", "mime-type-and-headers-remove" -> InterventionResult(
                rule.name, ctx.url, byUrl(ctx.url, MediaClass.PROGRESSIVE_VIDEO),
                removeHeaders = (p["headers"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
            "headers-nullify", "mime-type-and-headers-nullify" ->
                InterventionResult(rule.name, ctx.url, byUrl(ctx.url, MediaClass.PROGRESSIVE_VIDEO), dropAllHeaders = true)
            "ignore" -> InterventionResult(rule.name, ctx.url, null, ignore = true)
            else -> null
        }
    }

    private class CompiledCondition(c: RuleCondition) {
        private val field = c.field
        private val function = c.function.lowercase()
        private val values: List<String> = (listOfNotNull(c.value) + c.alternatives)
        private val regexes: List<Regex>? =
            if (function == "matchesregex" || function == "notmatchesregex") values.mapNotNull { runCatching { Regex(it) }.getOrNull() } else null
        private val valid = values.isNotEmpty() && function in KNOWN_FUNCTIONS && (regexes == null || regexes.size == values.size)

        fun matches(ctx: RuleContext): Boolean {
            if (!valid) return false
            val v = ctx.field(field)
            return when (function) {
                "contains" -> v != null && values.any { v.contains(it) }
                "notcontains" -> v == null || values.none { v.contains(it) }
                "startswith" -> v != null && values.any { v.startsWith(it) }
                "endswith" -> v != null && values.any { v.endsWith(it) }
                "equals" -> v != null && values.any { v == it }
                "equalsignorecase" -> v != null && values.any { v.equals(it, ignoreCase = true) }
                "matchesregex" -> v != null && regexes!!.any { it.containsMatchIn(v) }
                "notmatchesregex" -> v == null || regexes!!.none { it.containsMatchIn(v) }
                else -> false
            }
        }
    }

    private class CompiledMatch(m: RuleMatch) {
        private val all = m.type.equals("all", ignoreCase = true)
        private val conditions = m.rules.map(::CompiledCondition)

        fun matches(ctx: RuleContext): Boolean = when {
            conditions.isEmpty() -> false
            all -> conditions.all { it.matches(ctx) }
            else -> conditions.any { it.matches(ctx) }
        }
    }

    private class CompiledPatterns(p: UrlPatterns) {
        private val contains = p.contains.map { it.lowercase() }
        private val startsWith = p.startsWith.map { it.lowercase() }
        private val endsWith = p.endsWith.map { it.lowercase().trimStart('.') }
        private val regexes = p.matchesRegex.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        private val empty = contains.isEmpty() && startsWith.isEmpty() && endsWith.isEmpty() && regexes.isEmpty()

        fun matches(url: String): Boolean {
            if (empty) return false
            val u = url.lowercase()
            if (contains.any { u.contains(it) } || startsWith.any { u.startsWith(it) }) return true
            if (endsWith.isNotEmpty()) {
                val host = WebUrls.hostOf(u)
                if (host != null && endsWith.any { host == it || host.endsWith(".$it") }) return true
            }
            return regexes.any { it.containsMatchIn(u) }
        }
    }

    companion object {
        /** Classpath location of the embedded default rules. */
        const val DEFAULT_RESOURCE: String = "web/site_rules.json"

        private val KNOWN_FUNCTIONS = setOf(
            "contains", "notcontains", "startswith", "endswith", "equals", "equalsignorecase",
            "matchesregex", "notmatchesregex",
        )

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /** Rules that never match anything. */
        val EMPTY: SiteRules = SiteRules(SiteRulesDocument())

        private val defaultInstance: SiteRules by lazy {
            loadDefaultText()?.let { parseOrNull(it) } ?: EMPTY
        }

        /** The embedded default rules (loaded once). */
        fun default(): SiteRules = defaultInstance

        /** Builds rules from a document. */
        fun of(document: SiteRulesDocument): SiteRules = SiteRules(document)

        /** Parses a rules JSON document; throws on malformed JSON. */
        fun parse(json: String): SiteRules = SiteRules(JSON.decodeFromString(SiteRulesDocument.serializer(), json))

        /** Like [parse] but returns `null` for malformed input. */
        fun parseOrNull(json: String): SiteRules? = try {
            parse(json)
        } catch (_: Exception) {
            null
        }

        /** Reads the embedded rules text, or `null` if missing. */
        fun loadDefaultText(): String? {
            val cl = SiteRules::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            return cl.getResourceAsStream(DEFAULT_RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
