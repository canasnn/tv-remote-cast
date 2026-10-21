package com.ruterika.castcore.iptv

import kotlinx.serialization.Serializable

/** When IPTV lists are downloaded again. */
@Serializable
enum class IptvUpdateMode(val label: String, val description: String) {
    MANUAL("Manual", "Show the saved copy; press Update to check for changes"),
    AUTOMATIC("Automatic", "Check for changes every time a list is opened"),
    SUBLISTS("Sub-lists only", "Check nested lists when opened; main lists only on Update")
}

/** Order of the IPTV lists on the Cast hub. */
@Serializable
enum class IptvListSort(val label: String) {
    DATE_ADDED_ASC("Date added (oldest first)"),
    DATE_ADDED_DESC("Date added (newest first)"),
    NAME_ASC("Name (A to Z)"),
    NAME_DESC("Name (Z to A)")
}

/** What channels are ordered by inside a list. */
@Serializable
enum class IptvSortBy(val label: String) {
    UNSORTED("Unsorted"),
    NAME("Name"),
    EPISODE("Season and episode")
}

/** Channel order: [by], direction, and whether groups are mixed in with channels. */
data class IptvSort(
    val by: IptvSortBy = IptvSortBy.UNSORTED,
    val ascending: Boolean = true,
    val mixGroups: Boolean = false,
)
