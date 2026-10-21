package com.ruterika.castcore.web

/**
 * Well-known, maintained block lists the app can download and keep current.
 * They are fetched from their publishers (not bundled); all are updated at least daily.
 */
object AdBlockPresets {

    data class Preset(val name: String, val url: String, val description: String)

    val HAGEZI_MULTI = Preset(
        "HaGeZi Multi (recommended)",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/multi-onlydomains.txt",
        "Ads, trackers, malware and scams worldwide · updated several times a day · rarely breaks sites"
    )

    val all: List<Preset> = listOf(
        HAGEZI_MULTI,
        Preset(
            "HaGeZi Pro (stricter)",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
            "More aggressive; a site may occasionally break"
        ),
        Preset(
            "OISD small",
            "https://small.oisd.nl/",
            "Ads and trackers only, \"block, don't break\" · updated daily"
        ),
        Preset(
            "StevenBlack hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "Classic unified hosts list"
        )
    )

    /** The preset behind [url], or null for a custom address. */
    fun of(url: String): Preset? = all.firstOrNull { it.url == url.trim() }
}
