package com.canasnn.tvremote.cast.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Switch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.download.Downloads
import com.ruterika.castcore.library.DownloadItem
import com.ruterika.castcore.library.DownloadStatus
import com.ruterika.castcore.library.SavedMedia

/**
 * "Download" entry point: asks once for the rights confirmation and the
 * notification permission, then queues [SavedMedia].
 */
@Composable
fun rememberDownloadStarter(): (SavedMedia) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<SavedMedia?>(null) }
    var live by remember { mutableStateOf<SavedMedia?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun start(media: SavedMedia) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        Downloads.enqueue(context, media)
        Toast.makeText(context, "Added to downloads (Library → Downloads)", Toast.LENGTH_SHORT).show()
    }

    pending?.let { media ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Download permission") },
            text = { Text("Only download media you own or have permission from the owner to download. Do you have permission to download this?") },
            confirmButton = {
                TextButton(onClick = {
                    CastHub.settingsStore.update { it.copy(downloadConsentGiven = true) }
                    pending = null
                    start(media)
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("No") } }
        )
    }
    fun proceed(media: SavedMedia) {
        if (CastHub.settings.value.downloadConsentGiven) start(media) else pending = media
    }

    live?.let { media ->
        AlertDialog(
            onDismissRequest = { live = null },
            title = { Text("Live stream") },
            text = { Text("This looks like a live stream. Live streams never end, so the download can't finish. Don't download live streams.") },
            confirmButton = { TextButton(onClick = { live = null; proceed(media) }) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = { live = null }) { Text("Cancel") } }
        )
    }
    return { media ->
        if (media.isLive) live = media else proceed(media)
    }
}

/**
 * Downloads list inside the library screen. [onRemoved] shows "Undo" after a
 * row was removed (file deleted too when the flag is true).
 */
fun LazyListScope.downloadsSection(
    downloads: List<DownloadItem>,
    onCast: (List<SavedMedia>, Int) -> Unit,
    onRemoved: (DownloadItem, Boolean) -> Unit = { _, _ -> }
) {
    item { WifiOnlySwitch() }
    if (downloads.isEmpty()) {
        item { EmptyText("Use \"Download\" on a video in the browser. Files are saved to Download/${Downloads.FOLDER}.") }
        return
    }
    val active = downloads.filter { it.status != DownloadStatus.COMPLETE && it.status != DownloadStatus.FAILED }
    val done = downloads.filter { it.status == DownloadStatus.COMPLETE }
    val failed = downloads.filter { it.status == DownloadStatus.FAILED }
    if (active.isNotEmpty()) item { SectionLabelText("In progress") }
    items(active, key = { it.id }) { DownloadRow(it, onCast, onRemoved) }
    if (done.isNotEmpty()) item { SectionLabelText("Completed") }
    items(done, key = { it.id }) { DownloadRow(it, onCast, onRemoved) }
    if (failed.isNotEmpty()) item { SectionLabelText("Failed") }
    items(failed, key = { it.id }) { DownloadRow(it, onCast, onRemoved) }
}

/** Toolbar "Download only on wifi", right on the downloads screen. */
@Composable
private fun WifiOnlySwitch() {
    val context = LocalContext.current
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clickable { setWifiOnly(context, !settings.downloadWifiOnly) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Download on Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
            if (settings.downloadWifiOnly && !Downloads.isOnWifi(context)) {
                Text("Not on Wi-Fi now: downloads wait", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = settings.downloadWifiOnly, onCheckedChange = { setWifiOnly(context, it) })
    }
}

private fun setWifiOnly(context: android.content.Context, on: Boolean) {
    CastHub.settingsStore.update { it.copy(downloadWifiOnly = on) }
    if (!on) {
        // Downloads that stopped only because of Wi-Fi can go now.
        CastHub.library.data.value.downloads
            .filter { it.status == DownloadStatus.FAILED && it.error?.contains("Wi-Fi") == true }
            .forEach { Downloads.resume(context, it.id) }
    }
}

@Composable
private fun SectionLabelText(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DownloadRow(item: DownloadItem, onCast: (List<SavedMedia>, Int) -> Unit, onRemoved: (DownloadItem, Boolean) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val addToPlaylist = rememberPlaylistPicker()
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = item.status == DownloadStatus.COMPLETE) {
            Downloads.asMedia(item)?.let { onCast(listOf(it), 0) }
        }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusText(item), style = MaterialTheme.typography.labelSmall, color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (item.status) {
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING ->
                        IconButton(onClick = { Downloads.pause(item.id) }) { Icon(Icons.Filled.Pause, "Pause") }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                        IconButton(onClick = { Downloads.resume(context, item.id) }) {
                            Icon(if (item.status == DownloadStatus.FAILED) Icons.Filled.Refresh else Icons.Filled.PlayArrow, "Resume")
                        }
                    DownloadStatus.COMPLETE -> Unit
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (item.status == DownloadStatus.COMPLETE) {
                            DropdownMenuItem(text = { Text("Cast to TV") }, leadingIcon = { Icon(Icons.Filled.Cast, null) }, onClick = {
                                menu = false; Downloads.asMedia(item)?.let { onCast(listOf(it), 0) }
                            })
                            DropdownMenuItem(text = { Text("Play on phone") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = {
                                menu = false; Downloads.asMedia(item)?.let { com.canasnn.tvremote.cast.player.PhonePlayer.play(context, listOf(it)) }
                            })
                            DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }, onClick = {
                                menu = false; Downloads.asMedia(item)?.let { addToPlaylist(listOf(it)) }
                            })
                            DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.Filled.OpenInNew, null) }, onClick = {
                                menu = false; Downloads.openWith(context, item)
                            })
                        }
                        DropdownMenuItem(text = { Text("Remove from app") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                            menu = false
                            Downloads.removeWithUndo(context, item, deleteFile = false)
                            onRemoved(item, false)
                        })
                        if (item.fileUri != null) {
                            DropdownMenuItem(text = { Text("Delete from phone") }, leadingIcon = { Icon(Icons.Filled.DeleteForever, null) }, onClick = {
                                menu = false
                                Downloads.removeWithUndo(context, item, deleteFile = true)
                                onRemoved(item, true)
                            })
                        }
                    }
                }
            }
            val fraction = progressFraction(item)
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.CONVERTING || item.status == DownloadStatus.QUEUED) {
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun progressFraction(item: DownloadItem): Float? = when {
    item.status == DownloadStatus.CONVERTING -> item.convertPercent.takeIf { it >= 0 }?.div(100f)
    item.totalBytes > 0 -> (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)
    else -> null
}

private fun statusText(item: DownloadItem): String = when (item.status) {
    DownloadStatus.QUEUED -> "Waiting…"
    DownloadStatus.DOWNLOADING -> if (item.totalBytes > 0) "${(item.downloadedBytes * 100 / item.totalBytes)}% · ${mb(item.downloadedBytes)} / ${mb(item.totalBytes)}" else mb(item.downloadedBytes)
    DownloadStatus.CONVERTING -> if (item.convertPercent >= 0) "Converting to MP4 · ${item.convertPercent}%" else "Converting to MP4…"
    DownloadStatus.PAUSED -> "Paused · ${mb(item.downloadedBytes)}"
    DownloadStatus.COMPLETE -> "Saved · ${mb(item.totalBytes.coerceAtLeast(item.downloadedBytes))}"
    DownloadStatus.FAILED -> item.error ?: "Failed"
}

private fun mb(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes / (1024 * 1024)} MB"
    else -> "${(bytes + 1023) / 1024} KB"
}
