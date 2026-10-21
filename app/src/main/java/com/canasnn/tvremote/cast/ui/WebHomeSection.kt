package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.library.SavedMedia

/** Web tab of the Cast hub: search/address bar, most visited, bookmarks, recent media. */
fun LazyListScope.webHomeSection(
    onOpenBrowser: (String?) -> Unit,
    onOpenLibrary: (LibrarySection) -> Unit,
    onCast: (List<SavedMedia>, Int) -> Unit
) {
    item {
        var input by rememberSaveable { mutableStateOf("") }
        val settings by CastHub.settings.collectAsStateWithLifecycle()
        ElevatedCard(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Find videos on the web", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Open any site; videos it plays are detected and sent to your TV with one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search ${settings.searchEngineName} or type a URL") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        onOpenBrowser(settings.resolveInput(input).ifBlank { null })
                    })
                )
            }
        }
    }
    item {
        val data by CastHub.library.data.collectAsStateWithLifecycle()
        val sites = remember(data.sites) { CastHub.library.mostVisited(8) }
        if (sites.isNotEmpty()) {
            SectionHeader("Most visited") { onOpenLibrary(LibrarySection.MostVisited) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sites.forEach { site -> SiteTile(siteName(site.host), site.host) { onOpenBrowser(site.url) } }
            }
        }
        if (data.bookmarks.isNotEmpty()) {
            SectionHeader("Bookmarks") { onOpenLibrary(LibrarySection.Bookmarks) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                data.bookmarks.take(5).forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenBrowser(b.url) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(b.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (data.recentMedia.isNotEmpty()) {
            SectionHeader("Continue watching") { onOpenLibrary(LibrarySection.Recent) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                data.recentMedia.take(3).forEach { media -> MediaRow(media, onClick = { onCast(listOf(media), 0) }) }
            }
        }
        if (sites.isEmpty() && data.bookmarks.isEmpty()) {
            TextButton(onClick = { onOpenBrowser(null) }, modifier = Modifier.padding(top = 4.dp)) { Text("Open browser") }
        }
    }
}

/** "www.example-site.com" -> "Example-site" */
private fun siteName(host: String): String {
    val parts = host.removePrefix("www.").removePrefix("m.").split('.')
    val main = if (parts.size >= 3 && parts[parts.size - 2].length <= 3) parts[parts.size - 3] else parts.getOrElse(parts.size - 2) { host }
    return main.replaceFirstChar { it.uppercase() }
}

@androidx.compose.runtime.Composable
private fun SectionHeader(title: String, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text(
            "See all",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onMore).padding(4.dp)
        )
    }
}

@androidx.compose.runtime.Composable
private fun SiteTile(title: String, host: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(76.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                val letter = host.firstOrNull()?.uppercaseChar()
                if (letter != null) Text(letter.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                else Icon(Icons.Filled.Public, null)
                // The site's own favicon over the letter (stays a letter if it has none).
                coil.compose.AsyncImage(
                    model = "https://$host/favicon.ico",
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
