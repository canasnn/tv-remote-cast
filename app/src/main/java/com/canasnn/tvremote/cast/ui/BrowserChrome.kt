package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserManager
import com.canasnn.tvremote.cast.browser.BrowserTab
import com.ruterika.castcore.browser.AddressSuggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Address bar suggestions: matching bookmarks and history, then the search
 * engine's own suggestions ("Search suggestions" setting; never in private tabs).
 */
@Composable
internal fun AddressSuggestionsList(
    query: String,
    incognito: Boolean,
    onOpen: (String) -> Unit,
    onFill: (String) -> Unit
) {
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val library by CastHub.library.data.collectAsStateWithLifecycle()
    val local = remember(query, library) { AddressSuggestions.local(query, library) }
    var remote by remember { mutableStateOf<List<AddressSuggestions.Suggestion>>(emptyList()) }
    LaunchedEffect(query, settings.searchSuggestions, settings.searchEngine, incognito) {
        remote = emptyList()
        val q = query.trim()
        if (!settings.searchSuggestions || incognito || q.isEmpty() || "://" in q) return@LaunchedEffect
        delay(SUGGEST_DEBOUNCE_MS)
        remote = AddressSuggestions.fetchRemote(BrowserManager.http, settings, q)
    }
    val all = local + remote
    if (all.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(all, key = { (it.url ?: "q:" + it.text) + it.kind }) { s ->
                ListItem(
                    headlineContent = { Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = s.url?.let { { Text(AddressSuggestions.bareUrl(it), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = {
                        Icon(
                            when (s.kind) {
                                AddressSuggestions.Kind.BOOKMARK -> Icons.Filled.Bookmark
                                AddressSuggestions.Kind.HISTORY -> Icons.Filled.History
                                AddressSuggestions.Kind.SEARCH -> Icons.Filled.Search
                            },
                            null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onFill(s.url ?: s.text) }) { Icon(Icons.Filled.NorthWest, "Edit", modifier = Modifier.size(18.dp)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpen(s.url ?: settings.searchUrl(s.text)) }
                )
            }
        }
    }
}

/** "Find in page": search field, match count, previous / next, close. */
@Composable
internal fun FindInPageBar(tab: BrowserTab, onClose: () -> Unit) {
    var query by remember(tab) { mutableStateOf("") }
    val result by tab.findResult.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(tab) { runCatching { focus.requestFocus() } }
    DisposableEffect(tab) { onDispose { tab.clearFind() } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close find") }
        TextField(
            value = query,
            onValueChange = { query = it; tab.find(it) },
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            placeholder = { Text("Find in page") },
            trailingIcon = {
                if (query.isNotEmpty()) Text(
                    "${result.active}/${result.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            },
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { tab.findNext(true) })
        )
        IconButton(onClick = { tab.findNext(false) }, enabled = result.total > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
        IconButton(onClick = { tab.findNext(true) }, enabled = result.total > 0) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
    }
}

/** "Pop-up blocked · Tap to open" with "Allow on this site". */
@Composable
internal fun PopupBlockedBar(
    count: Int,
    host: String?,
    canOpen: Boolean,
    onOpen: () -> Unit,
    onAllow: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count == 1) "Pop-up blocked" else "$count pop-ups blocked",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            if (canOpen) TextButton(onClick = onOpen) { Text("Tap to open") }
            if (host != null) TextButton(onClick = { onAllow(host) }) { Text("Allow on site") }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Shows the tab's notices ("App launch blocked — Open", "Blocked an ad pop-up", …) as snackbars. */
@Composable
internal fun TabNotices(tab: BrowserTab, snackbar: SnackbarHostState) {
    LaunchedEffect(tab) {
        tab.notices.collect { notice ->
            launch {
                val result = snackbar.showSnackbar(
                    message = notice.text,
                    actionLabel = notice.actionLabel,
                    withDismissAction = notice.actionLabel != null,
                    duration = if (notice.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) notice.action?.invoke()
            }
        }
    }
}

private const val SUGGEST_DEBOUNCE_MS = 250L
