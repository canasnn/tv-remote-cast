package com.canasnn.tvremote.ui.app

import androidx.compose.ui.graphics.Color
import com.canasnn.tvremote.utils.TvApp
import com.canasnn.tvremote.utils.TvAppsRepository

enum class RemoteShelfMode(
    val storageValue: String,
    val label: String,
    val description: String
) {
    Applications(
        storageValue = "applications",
        label = "Applications",
        description = "Show quick-launch apps above the remote"
    ),
    MediaButtons(
        storageValue = "media",
        label = "Media buttons",
        description = "Show playback controls above the remote"
    ),
    None(
        storageValue = "none",
        label = "None",
        description = "Hide the top strip on the remote"
    );

    companion object {
        fun fromStorage(value: String?): RemoteShelfMode? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}

data class RemoteShortcutApp(
    val id: String,
    val launchName: String,
    val label: String,
    val mark: String,
    val accent: Color,
    val accentSecondary: Color = accent,
    val intentUri: String? = null,
    val isCustom: Boolean = false
)

fun CustomShortcut.toRemoteShortcutApp(): RemoteShortcutApp = RemoteShortcutApp(
    id = id,
    launchName = intentUri,
    label = label,
    mark = mark.ifBlank { label.take(2).uppercase() },
    accent = accentColor,
    accentSecondary = accentColor,
    intentUri = intentUri,
    isCustom = true
)

private val defaultRemoteShortcutApps = listOf(
    RemoteShortcutApp(
        id = "youtube",
        launchName = "YouTube",
        label = "YouTube",
        mark = "YT",
        accent = Color(0xFFFF2D20),
        accentSecondary = Color(0xFFFF7A66)
    ),
    RemoteShortcutApp(
        id = "netflix",
        launchName = "Netflix",
        label = "Netflix",
        mark = "N",
        accent = Color(0xFFD61F2C),
        accentSecondary = Color(0xFF5B0D18)
    ),
    RemoteShortcutApp(
        id = "prime_video",
        launchName = "Prime Video",
        label = "Prime Video",
        mark = "PV",
        accent = Color(0xFF1E88FF),
        accentSecondary = Color(0xFF00B8D9)
    ),
    RemoteShortcutApp(
        id = "disney_plus",
        launchName = "Disney+",
        label = "Disney+",
        mark = "D+",
        accent = Color(0xFF113CCF),
        accentSecondary = Color(0xFF01B4E4)
    ),
    RemoteShortcutApp(
        id = "hbo_max",
        launchName = "HBO Max",
        label = "HBO Max",
        mark = "HM",
        accent = Color(0xFF7B31FF),
        accentSecondary = Color(0xFF2C0B63)
    ),
    RemoteShortcutApp(
        id = "apple_tv",
        launchName = "Apple TV",
        label = "Apple TV",
        mark = "AT",
        accent = Color(0xFF2F3C52),
        accentSecondary = Color(0xFF7D91B3)
    ),
    RemoteShortcutApp(
        id = "spotify",
        launchName = "Spotify",
        label = "Spotify",
        mark = "SP",
        accent = Color(0xFF1DB954),
        accentSecondary = Color(0xFF0B6E31)
    ),
    RemoteShortcutApp(
        id = "hulu",
        launchName = "Hulu",
        label = "Hulu",
        mark = "HU",
        accent = Color(0xFF1CE783),
        accentSecondary = Color(0xFF0B8B4B)
    )
)

private val tvAccents = listOf(
    Color(0xFFFF2D20), Color(0xFF1E88FF), Color(0xFF1CE783), Color(0xFF8E5BFF),
    Color(0xFFF4B400), Color(0xFF00B8D9), Color(0xFFD61F2C), Color(0xFFFF4D7A)
)

fun TvApp.toRemoteShortcutApp(): RemoteShortcutApp {
    val accent = tvAccents[(packageName.hashCode() and 0x7fffffff) % tvAccents.size]
    return RemoteShortcutApp(
        id = id,
        launchName = label,
        label = label,
        mark = label.filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "TV" },
        accent = accent,
        accentSecondary = accent,
        intentUri = TvApp.URI_PREFIX + component,
        isCustom = false
    )
}

/** Apps reported by the TV receiver; empty until it has answered once. */
fun tvShortcutApps(): List<RemoteShortcutApp> =
    TvAppsRepository.apps.value.map { it.toRemoteShortcutApp() }

/** Built-in apps: the TV's real app list when known, otherwise the stock list. */
private fun baseShortcutApps(): List<RemoteShortcutApp> =
    tvShortcutApps().ifEmpty { defaultRemoteShortcutApps }

fun defaultRemoteShortcutOrder(): List<String> = baseShortcutApps().map(RemoteShortcutApp::id)

fun getAllKnownShortcutApps(customShortcuts: List<CustomShortcut> = emptyList()): List<RemoteShortcutApp> =
    baseShortcutApps() + customShortcuts.map { it.toRemoteShortcutApp() }

fun decodeRemoteShortcutOrder(
    raw: String?,
    customShortcuts: List<CustomShortcut> = emptyList()
): List<String> {
    val defaultIds = baseShortcutApps().map(RemoteShortcutApp::id)
    val customIds = customShortcuts.map(CustomShortcut::id)
    val allKnownIds = (defaultIds + customIds).toSet()

    if (raw == null) {
        return defaultIds + customIds
    }
    val savedOrder = raw.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { id ->
            if (allKnownIds.isNotEmpty()) id in allKnownIds
            else (id in defaultIds || id.length > 8)
        }
        .distinct()

    val missingCustom = customIds.filter { it !in savedOrder }
    return savedOrder + missingCustom
}

fun encodeRemoteShortcutOrder(order: List<String>): String =
    order.joinToString(",")

fun resolveRemoteShortcutApps(
    order: List<String>,
    customShortcuts: List<CustomShortcut> = emptyList()
): List<RemoteShortcutApp> {
    val allApps = getAllKnownShortcutApps(customShortcuts)
    val appsById = allApps.associateBy(RemoteShortcutApp::id)
    val orderSet = order.toSet()
    val fullOrder = order + customShortcuts.map { it.id }.filter { it !in orderSet }
    return fullOrder.mapNotNull(appsById::get)
}

fun moveRemoteShortcutOrderItem(order: List<String>, fromIndex: Int, direction: Int): List<String> {
    val resolved = order.toMutableList()
    val targetIndex = fromIndex + direction
    if (fromIndex !in resolved.indices || targetIndex !in resolved.indices) {
        return resolved
    }
    val movedItem = resolved.removeAt(fromIndex)
    resolved.add(targetIndex, movedItem)
    return resolved
}

fun getDefaultRemoteShortcutApps(): List<RemoteShortcutApp> = baseShortcutApps()
