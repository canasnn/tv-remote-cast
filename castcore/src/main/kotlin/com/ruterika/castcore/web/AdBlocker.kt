package com.ruterika.castcore.web

/**
 * Domain based ad/tracker matcher.
 *
 * A host is blocked when it, or any of its parent domains, appears in the block set. Matching is
 * done by walking the host's suffixes (`a.b.example.com`, `b.example.com`, `example.com`, `com`)
 * against hash sets, so a lookup costs O(number of labels) regardless of list size.
 *
 * The most specific matching rule wins; on the same level an allow rule beats a block rule. So
 * allowing `example.com` while blocking `ads.example.com` still blocks the ads subdomain, and
 * allowing `cdn.ads.example.com` un-blocks only that host.
 *
 * Instances are immutable and thread safe. Use [Builder] or [fromLists] to create one and
 * [default] for the bundled list.
 */
class AdBlocker private constructor(
    private val blocked: Set<String>,
    private val allowed: Set<String>,
) {

    /** Number of blocked domain entries. */
    val blockedCount: Int get() = blocked.size

    /** Number of allowlist entries. */
    val allowedCount: Int get() = allowed.size

    /**
     * Returns `true` when [urlOrHost] (an absolute URL or a bare host name) belongs to a blocked
     * domain and is not allowlisted. Unparseable input is never blocked.
     */
    fun isBlocked(urlOrHost: String): Boolean {
        val host = WebUrls.hostOf(urlOrHost) ?: return false
        return isBlockedHost(host)
    }

    /** Same as [isBlocked] but [host] must already be a bare host name. */
    fun isBlockedHost(host: String): Boolean {
        var h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        while (true) {
            if (h in allowed) return false
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Returns a copy with [domains] added to the allowlist (they and their subdomains pass). */
    fun withAllowed(domains: Collection<String>): AdBlocker =
        AdBlocker(blocked, allowed + domains.mapNotNull { normalizeDomain(it) })

    /** Returns a copy with [domains] added to the block list. */
    fun withBlocked(domains: Collection<String>): AdBlocker =
        AdBlocker(blocked + domains.mapNotNull { normalizeDomain(it) }, allowed)

    /** Incremental builder; feed it any number of list texts and single domains. */
    class Builder {
        private val blocked = HashSet<String>()
        private val allowed = HashSet<String>()

        /** Parses a list in hosts, adblock (`||domain^`, `@@||domain^`) or plain-domain format. */
        fun addList(text: String): Builder = apply {
            val parsed = parseList(text)
            blocked += parsed.blocked
            allowed += parsed.allowed
        }

        /** Blocks [domain] and all its subdomains. */
        fun block(domain: String): Builder = apply { normalizeDomain(domain)?.let { blocked += it } }

        /** Allowlists [domain] and all its subdomains. */
        fun allow(domain: String): Builder = apply { normalizeDomain(domain)?.let { allowed += it } }

        /** Creates the immutable matcher. */
        fun build(): AdBlocker = AdBlocker(HashSet(blocked), HashSet(allowed))
    }

    /** Result of [parseList]: block and allow domains found in a list text. */
    data class ParsedList(val blocked: Set<String>, val allowed: Set<String>)

    companion object {
        /** Classpath location of the bundled default list. */
        const val DEFAULT_LIST_RESOURCE: String = "adblock/default_hosts.txt"

        /** A matcher that never blocks anything. */
        val EMPTY: AdBlocker = AdBlocker(emptySet(), emptySet())

        private val defaultInstance: AdBlocker by lazy {
            val text = loadDefaultListText()
            if (text == null) EMPTY else Builder().addList(text).build()
        }

        /** The matcher built from the bundled [DEFAULT_LIST_RESOURCE] (loaded once, lazily). */
        fun default(): AdBlocker = defaultInstance

        /** Builds a matcher from several list texts plus an optional allowlist. */
        fun fromLists(vararg lists: String, allow: Collection<String> = emptyList()): AdBlocker {
            val b = Builder()
            lists.forEach { b.addList(it) }
            allow.forEach { b.allow(it) }
            return b.build()
        }

        /** Reads the bundled default list text from the classpath, or `null` if missing. */
        fun loadDefaultListText(): String? {
            val cl = AdBlocker::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            return cl.getResourceAsStream(DEFAULT_LIST_RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        private val IGNORED_HOSTS = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
            "ip6-loopback", "0.0.0.0", "127.0.0.1",
        )

        /**
         * Parses list [text]. Understands:
         * - hosts format: `0.0.0.0 ads.example.com` / `127.0.0.1 a.com b.com`
         * - adblock domain rules: `||ads.example.com^` (options after `$` are ignored)
         * - adblock exceptions: `@@||cdn.example.com^` (go to the allowlist)
         * - plain domains: `ads.example.com`
         * - comments: lines starting with `#` or `!`, `[Adblock Plus]` headers and trailing ` # ...`.
         *
         * Cosmetic, path or wildcard rules are skipped since only domains can be matched here.
         */
        fun parseList(text: String): ParsedList {
            val blocked = HashSet<String>()
            val allowed = HashSet<String>()
            text.lineSequence().forEach { raw ->
                var line = raw.trim()
                if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return@forEach
                val hash = line.indexOf(" #")
                if (hash >= 0) line = line.substring(0, hash).trim()
                val tab = line.indexOf('\t')
                if (tab >= 0) line = line.replace('\t', ' ')
                when {
                    line.startsWith("@@") -> adblockDomain(line.substring(2))?.let { allowed += it }
                    line.startsWith("||") -> adblockDomain(line)?.let { blocked += it }
                    line.contains(' ') -> {
                        val parts = line.split(' ').filter { it.isNotEmpty() }
                        if (parts.size >= 2 && isIpAddress(parts[0])) {
                            parts.drop(1).forEach { p -> normalizeDomain(p)?.let { blocked += it } }
                        }
                    }
                    else -> normalizeDomain(line)?.let { blocked += it }
                }
            }
            return ParsedList(blocked, allowed)
        }

        private fun adblockDomain(rule: String): String? {
            if (!rule.startsWith("||")) return null
            var r = rule.substring(2)
            val dollar = r.indexOf('$')
            if (dollar >= 0) r = r.substring(0, dollar)
            val caret = r.indexOf('^')
            if (caret >= 0) {
                if (caret != r.length - 1) return null // path after the domain anchor
                r = r.substring(0, caret)
            }
            if (r.endsWith("/")) r = r.dropLast(1)
            return normalizeDomain(r)
        }

        private fun isIpAddress(s: String): Boolean =
            s.contains(':') || (s.isNotEmpty() && s.all { it.isDigit() || it == '.' })

        /** Lowercases and validates a domain; returns `null` for anything that is not a host. */
        internal fun normalizeDomain(domain: String): String? {
            val d = domain.trim().trimStart('.').trimEnd('.').lowercase()
            if (d.isEmpty() || d in IGNORED_HOSTS || !d.contains('.')) return null
            if (d.any { !(it.isLetterOrDigit() || it == '-' || it == '.' || it == '_') }) return null
            if (d.all { it.isDigit() || it == '.' }) return null
            return d
        }
    }
}
