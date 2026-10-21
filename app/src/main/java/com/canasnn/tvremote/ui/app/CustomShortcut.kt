package com.canasnn.tvremote.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * A user-defined app shortcut that can be launched via the Android TV Remote Protocol.
 *
 * Intent URI formats (based on Home Assistant ATV Remote docs):
 *   - App link:  https://www.netflix.com/title   (opens deep link on TV)
 *   - Intent:    intent://com.netflix.ninja#Intent;scheme=https;package=com.netflix.ninja;end
 *   - Package:   market://details?id=com.netflix.ninja   (rarely useful via ATV remote)
 *
 * @param id           Stable unique identifier (UUID string).
 * @param label        Human-readable label shown in the strip and settings.
 * @param mark         Short mark (1–3 chars, e.g. emoji or initials) shown on the button.
 * @param accentArgb   ARGB int representation of the button accent color.
 * @param intentUri    The Intent URI / deep-link URL to send as a RemoteAppLinkLaunchRequest.
 * @param isBuiltIn    If true, cannot be deleted (reserved for future use).
 */
data class CustomShortcut(
    val id: String,
    val label: String,
    val mark: String,
    val accentArgb: Int,
    val intentUri: String,
    val isBuiltIn: Boolean = false
) {
    val accentColor: Color get() = Color(accentArgb)

    companion object {
        /**
         * Validate that an intent URI is well-formed enough to send to the TV.
         * Returns null on success, or an error message string on failure.
         */
        fun validateIntentUri(uri: String): String? {
            val trimmed = uri.trim()
            if (trimmed.isBlank()) return "Intent URI cannot be empty."
            val allowedPrefixes = listOf("https://", "http://", "intent://", "market://")
            if (allowedPrefixes.none { trimmed.startsWith(it, ignoreCase = true) }) {
                return "URI must start with https://, http://, intent://, or market://"
            }
            if (trimmed.length > 2048) return "URI is too long (max 2048 characters)."
            return null
        }
    }
}
