package com.canasnn.tvremote.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class HomeTab(val label: String, val icon: ImageVector) {
    Remote("Remote", Icons.Filled.Tv),
    Cast("Cast", Icons.Filled.Cast)
}

enum class RemotePadMode(val label: String, val icon: ImageVector) {
    Touchpad("Touchpad", Icons.Filled.TouchApp),
    DPad("D-pad", Icons.Filled.GridView),
    // NumberPad is intentionally disabled and may be reintroduced in a future release.
    NumberPad("Number", Icons.Filled.Apps)
}

enum class CastTab(val label: String, val icon: ImageVector) {
    Web("Web", Icons.Filled.Language),
    Videos("Videos", Icons.Filled.VideoLibrary),
    Photos("Photos", Icons.Filled.PhotoLibrary),
    Audio("Audio", Icons.Filled.Headphones),
    Iptv("IPTV", Icons.Filled.LiveTv),
    Link("Link", Icons.Filled.Link),
    Library("Library", Icons.Filled.CollectionsBookmark)
}

enum class MediaKind(val label: String, val placeholderIcon: ImageVector) {
    Photo("Photo", Icons.Filled.PhotoLibrary),
    Video("Video", Icons.Filled.VideoLibrary),
    Audio("Audio", Icons.Filled.GraphicEq)
}

data class DeviceUiModel(
    val id: String,
    val name: String,
    val type: String,
    val ipAddress: String,
    val isPaired: Boolean,
    val supportsCast: Boolean,
    val accent: Color,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false
)

data class MediaItemUi(
    val id: Long,
    val title: String,
    val subtitle: String,
    val uri: String,
    val thumbnailUri: String? = null,  // video thumbnail, album art, or photo uri
    val mimeType: String,
    val kind: MediaKind,
    val durationMs: Long = 0L,
    val collectionId: String? = null,
    val sizeBytes: Long = 0L,
    val dateModifiedMs: Long = 0L
)

data class QuickAppChip(
    val name: String,
    val accent: Color
)

data class RemoteControlItem(
    val label: String,
    val icon: ImageVector
)

fun RemotePadMode.next(): RemotePadMode {
    val nextIndex = (ordinal + 1) % RemotePadMode.entries.size
    return RemotePadMode.entries[nextIndex]
}

fun RemotePadMode.primaryMode(): RemotePadMode = when (this) {
    RemotePadMode.NumberPad -> RemotePadMode.DPad
    else -> this
}

/**
 * Toggles between [RemotePadMode.DPad] and [RemotePadMode.Touchpad] only.
 * Number pad is intentionally disabled and may be reintroduced in a future release.
 */
fun RemotePadMode.toggleNumberPad(primaryMode: RemotePadMode): RemotePadMode = when (this) {
    RemotePadMode.DPad -> RemotePadMode.Touchpad
    RemotePadMode.Touchpad -> RemotePadMode.DPad
    // NumberPad is intentionally disabled — fall back to DPad.
    RemotePadMode.NumberPad -> RemotePadMode.DPad
}

/** A phone file or link as a castable item (content:// is served by the local server). */
fun MediaItemUi.toSavedMedia(): com.ruterika.castcore.library.SavedMedia =
    com.ruterika.castcore.library.SavedMedia(
        url = uri,
        title = title,
        mimeType = mimeType.takeUnless { it.endsWith("/*") }
            ?: when (kind) {
                MediaKind.Audio -> "audio/*"
                MediaKind.Photo -> "image/jpeg"
                else -> null
            },
        posterUrl = thumbnailUri?.takeIf { it.startsWith("http") },
        durationMs = durationMs
    )
