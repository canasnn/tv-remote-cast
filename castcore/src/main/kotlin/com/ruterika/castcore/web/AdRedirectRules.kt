package com.ruterika.castcore.web

/**
 * Ad-redirect URL rules, one per line:
 *
 * ```
 * # comment
 * path::contains::/click;;qp::setContainsAnyIgnoreCase::[zoneid,campaignid]
 * ```
 *
 * A rule is a list of conditions joined by `;;`; all must hold. A condition is
 * `field::function::argument` where
 * - field is `host`, `path`, `url` or `qp` (the set of query parameter names);
 * - function is `contains`, `notContains`, `startsWith`, `endsWith`, `equals`, `matchesRegex`
 *   (Java regex, `find`), `setContainsAll[IgnoreCase]` or `setContainsAny[IgnoreCase]`
 *   (argument `[a,b,c]`, for `qp`).
 *
 * For `qp` the string functions hold when any parameter name satisfies them. Unknown fields,
 * functions or bad regexes make the whole line invalid (it is skipped).
 */
class AdRedirectRules private constructor(private val rules: List<List<Condition>>) {

    /** Number of valid rules. */
    val size: Int get() = rules.size

    /** True when [url] matches at least one rule. */
    fun matches(url: String): Boolean {
        if (rules.isEmpty() || !WebUrls.isHttp(url)) return false
        val parts = Parts(url)
        return rules.any { rule -> rule.all { it.matches(parts) } }
    }

    private class Parts(val url: String) {
        val host: String by lazy { WebUrls.hostOf(url).orEmpty() }
        val path: String by lazy { WebUrls.pathOf(url) }
        val names: List<String> by lazy { WebUrls.queryParams(url).map { it.first } }
    }

    private class Condition(val field: String, val function: String, val text: String, val set: List<String>, val regex: Regex?) {
        fun matches(p: Parts): Boolean {
            if (field == "qp") {
                return when (function) {
                    "setcontainsall" -> set.all { it in p.names }
                    "setcontainsallignorecase" -> set.all { s -> p.names.any { it.equals(s, ignoreCase = true) } }
                    "setcontainsany" -> set.any { it in p.names }
                    "setcontainsanyignorecase" -> set.any { s -> p.names.any { it.equals(s, ignoreCase = true) } }
                    "notcontains" -> p.names.none { it.contains(text) }
                    else -> p.names.any { stringMatch(it) }
                }
            }
            val value = when (field) {
                "host" -> p.host
                "path" -> p.path
                else -> p.url
            }
            return if (function == "notcontains") !value.contains(text) else stringMatch(value)
        }

        private fun stringMatch(v: String): Boolean = when (function) {
            "contains" -> v.contains(text)
            "startswith" -> v.startsWith(text)
            "endswith" -> v.endsWith(text)
            "equals" -> v == text
            "matchesregex" -> regex!!.containsMatchIn(v)
            else -> false
        }
    }

    companion object {
        val EMPTY = AdRedirectRules(emptyList())

        private val FIELDS = setOf("host", "path", "url", "qp")
        private val STRING_FUNCTIONS = setOf("contains", "notcontains", "startswith", "endswith", "equals", "matchesregex")
        private val SET_FUNCTIONS = setOf("setcontainsall", "setcontainsallignorecase", "setcontainsany", "setcontainsanyignorecase")

        /** Parses rule lines (blank lines and `#` comments are skipped). */
        fun parse(lines: Iterable<String>): AdRedirectRules =
            AdRedirectRules(lines.flatMap { it.lines() }.mapNotNull { parseLine(it) })

        /** Parses a whole rules text. */
        fun parse(text: String): AdRedirectRules = parse(text.lines())

        /** True when [line] is a valid rule (not a comment, blank or invalid). */
        fun isValidRule(line: String): Boolean = parseLine(line) != null

        /** Parses one rule; `null` for comments, blank or invalid lines. */
        private fun parseLine(line: String): List<Condition>? {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) return null
            val conditions = l.split(";;").map { part ->
                val pieces = part.trim().split("::", limit = 3)
                if (pieces.size != 3) return null
                val field = pieces[0].trim().lowercase()
                val function = pieces[1].trim().lowercase()
                val arg = pieces[2].trim()
                if (field !in FIELDS) return null
                when (function) {
                    in SET_FUNCTIONS -> {
                        if (field != "qp") return null
                        val set = arg.removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (set.isEmpty()) return null
                        Condition(field, function, arg, set, null)
                    }
                    in STRING_FUNCTIONS -> {
                        val regex = if (function == "matchesregex") runCatching { Regex(arg) }.getOrNull() ?: return null else null
                        if (arg.isEmpty()) return null
                        Condition(field, function, arg, emptyList(), regex)
                    }
                    else -> return null
                }
            }
            return conditions.takeIf { it.isNotEmpty() }
        }
    }
}
