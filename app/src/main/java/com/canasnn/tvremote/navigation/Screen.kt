package com.canasnn.tvremote.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Welcome : Screen("welcome")
    data object Main : Screen("main")
    data object Remote : Screen("remote")
    data object Cast : Screen("cast")
    data object Discovery : Screen("discovery")
    data object Settings : Screen("settings")
    data object RemoteControls : Screen("remote_controls")
    data object Appearance : Screen("appearance")
    data object TipsSupport : Screen("tips_support")
    data object CastPlayer : Screen("cast_player")
    data object ManageShortcuts : Screen("manage_shortcuts")
    data object QuickLaunchOrder : Screen("quick_launch_order")
    data object CustomizeLayout : Screen("customize_layout")
    data object Browser : Screen("browser?url={url}") {
        fun create(url: String? = null) = if (url.isNullOrBlank()) "browser" else "browser?url=" + android.net.Uri.encode(url)
    }
    data object CastSettings : Screen("cast_settings")
    data object CastHelp : Screen("cast_help")
    data object SubtitleSearch : Screen("subtitle_search")
    data object IptvSource : Screen("iptv_source/{id}") {
        fun create(id: String) = "iptv_source/$id"
    }
    data object Library : Screen("library/{section}") {
        fun create(section: String) = "library/$section"
    }
    data object Playlist : Screen("playlist/{id}") {
        fun create(id: String) = "playlist/$id"
    }
}
