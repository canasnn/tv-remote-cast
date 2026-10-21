package com.canasnn.tvremote.cast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.canasnn.tvremote.MainActivity
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.intake.IncomingRequest
import com.canasnn.tvremote.cast.player.PhonePlayer
import com.ruterika.castcore.library.SavedMedia

/**
 * What to do with media another app sent us: cast it, play it on the phone,
 * keep it in a playlist, open it in the browser or add an m3u file as an IPTV list.
 */
@Composable
fun IncomingMediaDialog(
    request: IncomingRequest.Media,
    onDismiss: () -> Unit,
    onCast: (List<SavedMedia>) -> Unit,
    onAddToPlaylist: (List<SavedMedia>) -> Unit,
    onDownload: (SavedMedia) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenIptvList: (String) -> Unit
) {
    val context = LocalContext.current
    val items = request.items
    val first = items.first()
    val web = first.url.startsWith("http://") || first.url.startsWith("https://")
    val photos = items.all { it.mimeType?.startsWith("image") == true }
    fun done(action: () -> Unit) = { onDismiss(); action() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.likelyIptv) "IPTV list" else if (items.size > 1) "${items.size} files" else "Open media", maxLines = 1) },
        text = {
            Column {
                Text(
                    if (items.size > 1) items.joinToString(", ") { it.title } else first.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val iptv = request.iptv
                if (iptv != null && request.likelyIptv) {
                    ActionRow(Icons.Filled.LiveTv, "Add as IPTV list", done { addIptvList(context, iptv.name, iptv.location, onOpenIptvList) })
                }
                ActionRow(Icons.Filled.Cast, if (photos && items.size > 1) "Show on TV" else "Cast to TV", done { onCast(items) })
                if (!photos) ActionRow(Icons.Filled.PhoneAndroid, "Play on phone", done { PhonePlayer.play(context, items) })
                ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", done { onAddToPlaylist(items) })
                if (web && items.size == 1 && !first.isLive) ActionRow(Icons.Filled.Download, "Download", done { onDownload(first) })
                if (web) ActionRow(Icons.Filled.Language, "Open in browser", done { onOpenInBrowser(first.url) })
                if (iptv != null && !request.likelyIptv) {
                    ActionRow(Icons.Filled.LiveTv, "Add as IPTV list", done { addIptvList(context, iptv.name, iptv.location, onOpenIptvList) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

private fun addIptvList(context: Context, name: String, location: String, open: (String) -> Unit) {
    val existing = CastHub.library.data.value.iptvSources.firstOrNull { it.location == location }
    val source = existing ?: CastHub.library.addIptvSource(name.ifBlank { "IPTV list" }, location)
    if (existing != null) Toast.makeText(context, "This list is already added", Toast.LENGTH_SHORT).show()
    open(source.id)
}

/** "Open links in the in-app browser" is off: hand the page to another browser. */
fun openInOtherBrowser(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val chooser = Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, "No other browser found", Toast.LENGTH_SHORT).show() }
}
