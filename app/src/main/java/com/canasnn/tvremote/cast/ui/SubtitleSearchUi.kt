package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.subtitles.OpenSubtitlesAccount
import com.canasnn.tvremote.cast.subtitles.SubtitleLibrary
import com.ruterika.castcore.subtitles.OpenSubtitlesException
import com.ruterika.castcore.subtitles.OpenSubtitlesLanguage
import com.ruterika.castcore.subtitles.OpenSubtitlesResult
import com.ruterika.castcore.subtitles.SubtitleNaming
import com.ruterika.castcore.subtitles.SubtitleSearch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** OpenSubtitles allows at most this many languages per search. */
internal const val MAX_SEARCH_LANGUAGES = 5

private const val PROVIDER = "OpenSubtitles.com"

/** User-facing text for OpenSubtitles failures (quota, rate limit, provider errors). */
internal fun openSubtitlesMessage(e: Throwable, searching: Boolean): String = when (e) {
    is OpenSubtitlesException.RateLimited -> "$PROVIDER requires that you wait a moment before trying again."
    is OpenSubtitlesException.DownloadQuotaExceeded -> buildString {
        append("You have exceeded the download quota granted to you by $PROVIDER.")
        if (!OpenSubtitlesAccount.isLoggedIn) append(" Your quota might be higher if you log in.")
        resetTime(e.resetTimeUtc)?.let { append(" It resets at $it.") }
    }
    is OpenSubtitlesException.Unauthorized ->
        if (OpenSubtitlesAccount.isLoggedIn) "Your $PROVIDER login has expired. Log in again from the account button."
        else "$PROVIDER did not accept the API key. Check it in settings. (${e.message})"
    is OpenSubtitlesException -> {
        val what = if (searching) "There was an error searching for subtitles." else "Error downloading subtitle."
        "$what ($PROVIDER: ${e.message} - ${e.statusCode ?: 0})"
    }
    else -> (if (searching) "There was an error searching for subtitles: " else "Error downloading subtitle: ") + (e.message ?: e.javaClass.simpleName)
}

private fun resetTime(utc: String?): String? = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val date = parser.parse(utc!!.take(19))!!
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
}.getOrNull()

/** Shown when the language list cannot be loaded from the API. */
private val FALLBACK_LANGUAGES = listOf(
    "en", "tr", "es", "pt-BR", "pt-PT", "fr", "de", "it", "nl", "pl", "ru", "uk", "ar", "fa", "he", "el", "ro",
    "hu", "cs", "sk", "bg", "sr", "hr", "sl", "sv", "no", "da", "fi", "id", "ms", "vi", "th", "ko", "ja", "zh-CN", "zh-TW", "hi"
).map { OpenSubtitlesLanguage(it, SubtitleNaming.displayName(it)) }

/**
 * "Subtitle search": name with autocomplete from earlier searches,
 * Exact switch (`no_guessit`), Movie / Episodes of a show with Season, Episode
 * and IMDb ID, up to five languages from `/infos/languages`.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun OpenSubtitlesSearchCard(
    mediaTitle: String?,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onResults: (List<OpenSubtitlesResult>?) -> Unit,
    onOpenSettings: (() -> Unit)?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val store = remember { SubtitleLibrary.store(context) }
    val storeData by store.data.collectAsStateWithLifecycle()

    val cleaned = remember(mediaTitle) { SubtitleNaming.cleanTitle(mediaTitle.orEmpty()) }
    var query by remember(cleaned) { mutableStateOf(if (settings.fillSubtitleNameWithTitle) cleaned.query else "") }
    var episodes by remember(cleaned) { mutableStateOf(cleaned.isEpisode) }
    var season by remember(cleaned) { mutableStateOf(cleaned.season?.toString().orEmpty()) }
    var episode by remember(cleaned) { mutableStateOf(cleaned.episode?.toString().orEmpty()) }
    var imdbId by remember(cleaned) { mutableStateOf("") }
    var exact by remember { mutableStateOf(true) }
    var languages by remember { mutableStateOf(settings.subtitleLanguages.take(MAX_SEARCH_LANGUAGES)) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pickLanguages by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    val exactOn = exact || settings.subtitleExactAlways

    fun search() {
        val q = query.trim()
        val imdb = imdbId.trim()
        when {
            settings.openSubtitlesApiKey.isBlank() -> { error = "Add your OpenSubtitles API key in settings."; return }
            q.isEmpty() && imdb.isEmpty() -> { error = "Enter a name or an IMDb ID."; return }
            languages.isEmpty() || languages.size > MAX_SEARCH_LANGUAGES -> {
                error = "Please select between 1 and $MAX_SEARCH_LANGUAGES languages"; return
            }
        }
        val seasonNumber = season.trim().toIntOrNull()
        val episodeNumber = episode.trim().toIntOrNull()
        if (settings.rememberSubtitleSearches) {
            store.addSearch(SubtitleSearch(q, languages, seasonNumber.takeIf { episodes }, episodeNumber.takeIf { episodes }, imdb, episodes))
        }
        scope.launch {
            onBusy(true)
            error = null
            try {
                onResults(
                    OpenSubtitlesAccount.call { c ->
                        c.search(
                            query = q.ifEmpty { null },
                            imdbId = imdb.takeIf { !episodes && it.isNotEmpty() },
                            parentImdbId = imdb.takeIf { episodes && it.isNotEmpty() },
                            season = seasonNumber.takeIf { episodes },
                            episode = episodeNumber.takeIf { episodes },
                            languages = languages,
                            noGuessit = exactOn
                        )
                    }.sortedWith(compareByDescending<OpenSubtitlesResult> { it.movieHashMatch }.thenByDescending { it.downloadCount })
                )
            } catch (e: Exception) {
                error = openSubtitlesMessage(e, searching = true)
            } finally {
                onBusy(false)
            }
        }
    }

    // Load (and cache for a week) the API's language list.
    LaunchedEffect(settings.openSubtitlesApiKey) {
        if (settings.openSubtitlesApiKey.isNotBlank() && store.languagesStale()) {
            runCatching { OpenSubtitlesAccount.call { it.languages() } }.getOrNull()?.let(store::setLanguages)
        }
    }
    LaunchedEffect(Unit) {
        if (settings.autoSearchOpenSubtitles && settings.openSubtitlesApiKey.isNotBlank() && query.isNotBlank()) search()
    }

    if (pickLanguages) {
        LanguagePickerDialog(
            available = storeData.languages.ifEmpty { FALLBACK_LANGUAGES },
            selected = languages,
            onDismiss = { pickLanguages = false },
            onConfirm = { languages = it; pickLanguages = false; error = null }
        )
    }

    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Online subtitles ($PROVIDER)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Search options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        CheckItem("Fill name with media title", settings.fillSubtitleNameWithTitle) { v ->
                            CastHub.settingsStore.update { it.copy(fillSubtitleNameWithTitle = v) }
                            if (v) query = cleaned.query
                        }
                        CheckItem("Remember searches", settings.rememberSubtitleSearches) { v ->
                            CastHub.settingsStore.update { it.copy(rememberSubtitleSearches = v) }
                        }
                        CheckItem("Always do exact searches", settings.subtitleExactAlways) { v ->
                            CastHub.settingsStore.update { it.copy(subtitleExactAlways = v) }
                        }
                        DropdownMenuItem(
                            text = { Text("Clear search history") },
                            enabled = storeData.searches.isNotEmpty(),
                            onClick = { store.clearSearches(); menu = false }
                        )
                    }
                }
            }
            if (settings.openSubtitlesApiKey.isBlank()) {
                Text(
                    "Get a free API key at opensubtitles.com (Profile → API consumers) and add it in settings. The free tier allows a few downloads a day; logging in raises it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                onOpenSettings?.let { FilledTonalButton(onClick = it) { Text("Open settings") } }
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().onFocusChanged { nameFocused = it.isFocused },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear name") } },
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
            )
            // Autocomplete from earlier searches.
            val suggestions = remember(query, storeData.searches, nameFocused) {
                if (!nameFocused || !settings.rememberSubtitleSearches) emptyList()
                else store.suggestions(query).filterNot { it.query.equals(query.trim(), ignoreCase = true) }
            }
            if (suggestions.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        suggestions.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    query = s.query
                                    episodes = s.episodes
                                    season = s.season?.toString().orEmpty()
                                    episode = s.episode?.toString().orEmpty()
                                    imdbId = s.imdbId
                                    if (s.languages.isNotEmpty()) languages = s.languages.take(MAX_SEARCH_LANGUAGES)
                                    nameFocused = false
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(s.query.ifEmpty { "IMDb ${s.imdbId}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildList {
                                            if (s.languages.isNotEmpty()) add(s.languages.joinToString(", "))
                                            if (s.season != null) add("Season ${s.season}")
                                            if (s.episode != null) add("Episode ${s.episode}")
                                        }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exact", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (settings.subtitleExactAlways) "Always on (search options)"
                        else "Takes the name you enter literally. Use this if you get irrelevant results or none at all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = exactOn, onCheckedChange = { exact = it }, enabled = !settings.subtitleExactAlways)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(false to "Movie", true to "Episodes of a show").forEach { (value, label) ->
                    Row(
                        Modifier.selectable(selected = episodes == value, onClick = { episodes = value }).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = episodes == value, onClick = { episodes = value })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (episodes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = season, onValueChange = { v -> season = v.filter(Char::isDigit).take(3) },
                        label = { Text("Season") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = episode, onValueChange = { v -> episode = v.filter(Char::isDigit).take(4) },
                        label = { Text("Episode") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
            OutlinedTextField(
                value = imdbId, onValueChange = { v -> imdbId = v.trim().take(12) },
                label = { Text(if (episodes) "IMDb ID of the show (optional)" else "IMDb ID (optional)") },
                placeholder = { Text("tt0133093") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Text("Languages (up to $MAX_SEARCH_LANGUAGES)", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { code ->
                    InputChip(
                        selected = true,
                        onClick = { languages = languages - code },
                        label = { Text(SubtitleNaming.displayName(code)) },
                        trailingIcon = { Icon(Icons.Filled.Close, "Remove") }
                    )
                }
                AssistChip(onClick = { pickLanguages = true }, label = { Text(if (languages.isEmpty()) "Choose languages" else "Change") })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    query = ""; season = ""; episode = ""; imdbId = ""; error = null; onResults(null)
                }) { Text("Clear") }
                FilledTonalButton(onClick = ::search, enabled = !busy) { Text("Search") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CheckItem(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) }
    )
}

/** Multi-select of OpenSubtitles languages, at most [MAX_SEARCH_LANGUAGES]. */
@Composable
private fun LanguagePickerDialog(
    available: List<OpenSubtitlesLanguage>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var chosen by remember { mutableStateOf(selected) }
    var filter by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    // Selected first, then the API order; codes we know but the API does not list stay visible.
    val all = remember(available, selected) {
        val known = available.map { it.code.lowercase() }.toSet()
        selected.filter { it.lowercase() !in known }.map { OpenSubtitlesLanguage(it, SubtitleNaming.displayName(it)) } + available
    }
    val shown = all.filter { filter.isBlank() || it.name.contains(filter.trim(), true) || it.code.contains(filter.trim(), true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Languages (up to $MAX_SEARCH_LANGUAGES)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter") }, modifier = Modifier.fillMaxWidth())
                if (warning || chosen.isEmpty()) {
                    Text("Please select between 1 and $MAX_SEARCH_LANGUAGES languages", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { lang ->
                        val isChosen = chosen.any { it.equals(lang.code, ignoreCase = true) }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                when {
                                    isChosen -> { chosen = chosen.filterNot { it.equals(lang.code, true) }; warning = false }
                                    chosen.size >= MAX_SEARCH_LANGUAGES -> warning = true
                                    else -> chosen = chosen + lang.code
                                }
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isChosen, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Text(lang.name, modifier = Modifier.weight(1f))
                            Text(lang.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.size in 1..MAX_SEARCH_LANGUAGES, onClick = { onConfirm(chosen) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
