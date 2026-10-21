package com.canasnn.tvremote.cast.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.library.Playlist
import com.ruterika.castcore.library.SavedMedia

/**
 * Returns a function that opens the picker for some
 * media. A single item gets an editable "Title for media item"; "New playlist"
 * creates one and adds to it. Keep the returned function in a composable that
 * stays on screen (the picker closes with it).
 */
@Composable
fun rememberPlaylistPicker(): (List<SavedMedia>) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<List<SavedMedia>?>(null) }
    pending?.let { items ->
        PlaylistChooserDialog(
            title = if (items.size == 1) "Add to playlist" else "Add ${items.size} items to playlist",
            itemTitle = items.singleOrNull()?.title,
            onDismiss = { pending = null }
        ) { playlistId, editedTitle ->
            val media = items.singleOrNull()?.let { single ->
                listOf(single.copy(title = editedTitle?.trim()?.ifBlank { null } ?: single.title))
            } ?: items
            pending = null
            if (CastHub.library.addToPlaylist(media, playlistId)) {
                Toast.makeText(context, "Added to playlist", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return { items -> if (items.isNotEmpty()) pending = items }
}

/**
 * Picks a playlist (or creates one). [itemTitle] non-null shows the editable
 * title field; [excludeId] hides the playlist the items come from.
 */
@Composable
internal fun PlaylistChooserDialog(
    title: String,
    itemTitle: String? = null,
    excludeId: String? = null,
    onDismiss: () -> Unit,
    onChosen: (playlistId: String, itemTitle: String?) -> Unit
) {
    val data by CastHub.library.data.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(itemTitle.orEmpty()) }
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog("New playlist", "", "Name", password = false, onDismiss = { creating = false }) {
            creating = false
            val created = CastHub.library.createPlaylist(it.trim())
            onChosen(created.id, itemTitle?.let { name })
        }
        return
    }
    val playlists = data.playlists.filter { it.id != excludeId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (itemTitle != null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Title for media item") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    item {
                        ChooserRow(
                            leading = {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                            },
                            title = "New playlist",
                            subtitle = null,
                            onClick = { creating = true }
                        )
                        if (playlists.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        ChooserRow(
                            leading = { PlaylistCover(playlist, 40.dp) },
                            title = playlist.name,
                            subtitle = itemCountText(playlist.entries.size),
                            onClick = { onChosen(playlist.id, itemTitle?.let { name }) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChooserRow(leading: @Composable () -> Unit, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Playlist cover: the first poster (or phone-file thumbnail), else an icon. */
@Composable
internal fun PlaylistCover(playlist: Playlist, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val thumb = playlist.coverUrl
            ?: localThumbnail(playlist.entries.firstOrNull { it.media.url.startsWith("content://") }?.media?.url.orEmpty())
        if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

internal fun itemCountText(count: Int): String = if (count == 1) "1 item" else "$count items"
