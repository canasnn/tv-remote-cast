package com.canasnn.tvremote.cast.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserManager
import com.canasnn.tvremote.ui.app.SettingItemRow
import com.ruterika.castcore.browser.UserAgents
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.CustomSearchEngine
import com.ruterika.castcore.settings.DarkPages
import com.ruterika.castcore.settings.OpenTabTarget
import com.ruterika.castcore.settings.SearchEngine
import com.ruterika.castcore.settings.SearchTemplates
import java.util.UUID

/**
 * Browser options added for parity (user agent, pages, pop-ups, apps,
 * JS dialogs, certificates, tabs, reset). Shown inside the "Browser" section.
 */
internal fun LazyListScope.browserMoreSettings(
    s: CastSettings,
    update: ((CastSettings) -> CastSettings) -> Unit,
    showDialog: (@Composable () -> Unit) -> Unit,
    dismiss: () -> Unit
) {
    item {
        SettingItemRow(
            title = "User agent / Desktop mode",
            desc = UserAgents.label(UserAgents.defaultKey(s), s),
            icon = Icons.Filled.Person,
            onClick = {
                showDialog {
                    UserAgentDialog(
                        selectedKey = UserAgents.defaultKey(s),
                        showMakeDefault = false,
                        onDismiss = dismiss,
                        onSelect = { key, _ -> saveDefaultUserAgent(key); dismiss() }
                    )
                }
            }
        )
    }
    item {
        SettingItemRow(
            title = "Dark pages",
            desc = when (s.effectiveDarkPages) {
                DarkPages.AUTO -> "Automatic (follows the app theme)"
                DarkPages.ON -> "Always dark"
                DarkPages.OFF -> "Off"
            },
            icon = Icons.Filled.DarkMode,
            onClick = {
                showDialog {
                    ChoiceDialog("Dark pages", DarkPages.entries, s.effectiveDarkPages, { it.label }, onDismiss = dismiss) { v ->
                        update { it.copy(darkPages = v, forceDarkPages = v == DarkPages.ON) }
                        dismiss()
                    }
                }
            }
        )
    }
    item { SwitchRow("Search suggestions", "Ask the search engine while you type", Icons.Filled.Search, s.searchSuggestions) { v -> update { it.copy(searchSuggestions = v) } } }
    item { SwitchRow("Zoom buttons", "Show + and − on pages", Icons.Filled.ZoomIn, s.showZoomButtons) { v -> update { it.copy(showZoomButtons = v) } } }
    item { SwitchRow("Tap to play media", "Pages can't start sound or video on their own", Icons.Filled.PlayCircle, s.requireTapToPlay) { v -> update { it.copy(requireTapToPlay = v) } } }

    // Pop-ups and other apps
    item { SwitchRow("Pop-ups without a tap", "Let pages open pop-ups by themselves (still checked by the blocker)", Icons.Filled.TouchApp, s.popupsWithoutTap) { v -> update { it.copy(popupsWithoutTap = v) } } }
    item { SwitchRow("Notify about blocked ad pop-ups", null, Icons.AutoMirrored.Filled.Message, s.notifyBlockedAdPopups) { v -> update { it.copy(notifyBlockedAdPopups = v) } } }
    item {
        SettingItemRow(
            title = "Sites allowed to open pop-ups",
            desc = if (s.allowedPopupHosts.isEmpty()) "None" else s.allowedPopupHosts.sorted().joinToString(", "),
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = {
                showDialog {
                    HostListDialog(
                        title = "Allowed pop-ups",
                        empty = "Use \"Allow pop-ups\" in the browser menu to add a site.",
                        hosts = { it.allowedPopupHosts },
                        onRemove = { host -> update { it.copy(allowedPopupHosts = it.allowedPopupHosts - host) } },
                        onRemoveAll = { update { it.copy(allowedPopupHosts = emptySet()) } },
                        onDismiss = dismiss
                    )
                }
            }
        )
    }
    item {
        SwitchRow("Warn before opening apps", "Show an \"Open\" bar when a page tries to launch an app", Icons.AutoMirrored.Filled.OpenInNew, s.warnBeforeOpeningApps) { v ->
            update { it.copy(warnBeforeOpeningApps = v) }
        }
    }

    // JavaScript dialogs
    item { SwitchRow("Block page alerts", "JavaScript alert() messages", Icons.AutoMirrored.Filled.Message, s.blockJsAlerts) { v -> update { it.copy(blockJsAlerts = v) } } }
    item { SwitchRow("Block page confirmations", "JavaScript confirm() questions are answered \"Cancel\"", Icons.AutoMirrored.Filled.Message, s.blockJsConfirms) { v -> update { it.copy(blockJsConfirms = v) } } }
    item { SwitchRow("Block page prompts", "JavaScript prompt() text questions", Icons.AutoMirrored.Filled.Message, s.blockJsPrompts) { v -> update { it.copy(blockJsPrompts = v) } } }

    // Certificates
    item {
        SettingItemRow(
            title = "Ignored certificate errors",
            desc = if (s.sslIgnoredHosts.isEmpty()) "None" else s.sslIgnoredHosts.sorted().joinToString(", "),
            icon = Icons.Filled.Security,
            onClick = {
                showDialog {
                    HostListDialog(
                        title = "Ignored certificate errors",
                        empty = "Sites you trust despite a certificate error appear here.",
                        hosts = { it.sslIgnoredHosts },
                        onRemove = { host -> update { it.copy(sslIgnoredHosts = it.sslIgnoredHosts - host) } },
                        onRemoveAll = { update { it.copy(sslIgnoredHosts = emptySet()) } },
                        onDismiss = dismiss
                    )
                }
            }
        )
    }
    item {
        SwitchRow("Ignore certificate errors on all sites", "Not safe: anyone on your network could read or change pages", Icons.Filled.GppMaybe, s.sslIgnoreAllHosts) { v ->
            if (!v) update { it.copy(sslIgnoreAllHosts = false) }
            else showDialog {
                AlertDialog(
                    onDismissRequest = dismiss,
                    icon = { Icon(Icons.Filled.GppMaybe, null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text("Ignore all certificate errors?") },
                    text = {
                        Text(
                            "Every site will load even when its certificate is broken, expired or fake. Anyone on the same network " +
                                "could then see passwords and change what you see. Only turn this on for testing, and turn it off again."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { update { it.copy(sslIgnoreAllHosts = true) }; dismiss() }) {
                            Text("Ignore all", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
                )
            }
        }
    }

    // Tabs
    item {
        SettingItemRow(
            title = "Open pages from the app in",
            desc = s.openInAppLinksIn.label + " · bookmarks, history, start page",
            icon = Icons.Filled.Tab,
            onClick = {
                showDialog {
                    ChoiceDialog("Pages from the app", OpenTabTarget.entries, s.openInAppLinksIn, { it.label }, onDismiss = dismiss) { v ->
                        update { it.copy(openInAppLinksIn = v) }; dismiss()
                    }
                }
            }
        )
    }
    item {
        SettingItemRow(
            title = "Open pages from outside in",
            desc = s.openExternalLinksIn.label + " · home screen shortcuts",
            icon = Icons.Filled.Tab,
            onClick = {
                showDialog {
                    ChoiceDialog("Pages from outside", OpenTabTarget.entries, s.openExternalLinksIn, { it.label }, onDismiss = dismiss) { v ->
                        update { it.copy(openExternalLinksIn = v) }; dismiss()
                    }
                }
            }
        )
    }
    item { SwitchRow("Load restored tabs when opened", "Only the current tab loads at start", Icons.Filled.Tab, s.lazyLoadRestoredTabs) { v -> update { it.copy(lazyLoadRestoredTabs = v) } } }
    item {
        SettingItemRow(
            title = "Tabs kept in memory",
            desc = limitLabel(s.maxLiveTabs) + " · other tabs reload when opened",
            icon = Icons.Filled.Memory,
            onClick = {
                showDialog {
                    ChoiceDialog("Tabs kept in memory", LIVE_LIMITS, s.maxLiveTabs, ::limitLabel, onDismiss = dismiss) { v ->
                        update { it.copy(maxLiveTabs = v) }; dismiss()
                    }
                }
            }
        )
    }
    item {
        SettingItemRow(
            title = "Private tabs kept in memory",
            desc = limitLabel(s.maxLivePrivateTabs),
            icon = Icons.Filled.Memory,
            onClick = {
                showDialog {
                    ChoiceDialog("Private tabs kept in memory", LIVE_LIMITS, s.maxLivePrivateTabs, ::limitLabel, onDismiss = dismiss) { v ->
                        update { it.copy(maxLivePrivateTabs = v) }; dismiss()
                    }
                }
            }
        )
    }
    item { ResetBrowserDataRow(showDialog, dismiss) }
}

/** "Search engine": the built-in engines plus the user's own (add, edit, test, delete). */
internal fun LazyListScope.searchEngineSetting(
    s: CastSettings,
    showDialog: (@Composable () -> Unit) -> Unit,
    dismiss: () -> Unit
) {
    item {
        // Back from "Test": the engine that was being edited opens again.
        LaunchedEffect(Unit) {
            val draft = pendingSearchDraft ?: return@LaunchedEffect
            pendingSearchDraft = null
            showDialog { SearchEngineDialog(onDismiss = dismiss, draft = draft) }
        }
        SettingItemRow(
            title = "Search engine",
            desc = if (s.searchEngine == SearchEngine.CUSTOM) s.searchEngineName + " · " + s.searchTemplate else s.searchEngine.label,
            icon = Icons.Filled.Search,
            onClick = { showDialog { SearchEngineDialog(onDismiss = dismiss) } }
        )
    }
}

/** An engine being edited while "Test" shows it in the browser (kept for the way back). */
private class SearchEngineDraft(val id: String?, val name: String, val template: String)

private var pendingSearchDraft: SearchEngineDraft? = null

@Composable
private fun SearchEngineDialog(onDismiss: () -> Unit, draft: SearchEngineDraft? = null) {
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf(draft) }
    var deleting by remember { mutableStateOf<CustomSearchEngine?>(null) }

    editor?.let { current ->
        CustomSearchEngineDialog(
            draft = current,
            engines = settings.customSearchEngines,
            onDismiss = { editor = null },
            onSave = { engine ->
                val isNew = current.id == null
                CastHub.settingsStore.update { s ->
                    val list = if (isNew) s.customSearchEngines + engine
                    else s.customSearchEngines.map { if (it.id == engine.id) engine else it }
                    // A new engine becomes the one in use.
                    if (isNew) s.copy(customSearchEngines = list, searchEngine = SearchEngine.CUSTOM, customSearchEngineId = engine.id)
                    else s.copy(customSearchEngines = list)
                }
                editor = null
            }
        )
        return
    }
    deleting?.let { engine ->
        val inUse = settings.searchEngine == SearchEngine.CUSTOM && settings.selectedCustomSearchEngine?.id == engine.id
        ConfirmDialog(
            "Delete \"${engine.name}\"?",
            if (inUse) "It is the search engine in use; Google will be used instead." else engine.template,
            onDismiss = { deleting = null }
        ) {
            CastHub.settingsStore.update { s ->
                val removed = s.copy(customSearchEngines = s.customSearchEngines.filterNot { it.id == engine.id })
                if (inUse) removed.copy(searchEngine = SearchEngine.GOOGLE, customSearchEngineId = "") else removed
            }
            deleting = null
        }
        return
    }

    @Composable
    fun option(selected: Boolean, label: String, detail: String?, error: String? = null, onClick: () -> Unit, actions: @Composable () -> Unit = {}) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (error != null) Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            actions()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search engine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SearchEngine.entries.filter { it != SearchEngine.CUSTOM }.forEach { engine ->
                    option(settings.searchEngine == engine, engine.label, null, onClick = {
                        CastHub.settingsStore.update { it.copy(searchEngine = engine) }
                    })
                }
                if (settings.customSearchEngines.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Your search engines", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                }
                settings.customSearchEngines.forEach { engine ->
                    val selected = settings.searchEngine == SearchEngine.CUSTOM && settings.selectedCustomSearchEngine?.id == engine.id
                    option(
                        selected = selected,
                        label = engine.name,
                        detail = engine.template,
                        // Saved by an older version without %s: it still works (words added at the end).
                        error = SearchTemplates.templateError(engine.template)?.let { "Needs fixing: $it" },
                        onClick = { CastHub.settingsStore.update { it.copy(searchEngine = SearchEngine.CUSTOM, customSearchEngineId = engine.id) } }
                    ) {
                        IconButton(onClick = { editor = SearchEngineDraft(engine.id, engine.name, engine.template) }) { Icon(Icons.Filled.Edit, "Edit ${engine.name}") }
                        IconButton(onClick = { deleting = engine }) { Icon(Icons.Filled.Delete, "Delete ${engine.name}") }
                    }
                }
                TextButton(onClick = { editor = SearchEngineDraft(null, "", "") }) { Icon(Icons.Filled.Add, null); Text(" Add search engine") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Name + URL template of a custom engine, checked as you type; "Test" opens it in a new tab. */
@Composable
private fun CustomSearchEngineDialog(
    draft: SearchEngineDraft,
    engines: List<CustomSearchEngine>,
    onDismiss: () -> Unit,
    onSave: (CustomSearchEngine) -> Unit
) {
    var name by remember { mutableStateOf(draft.name) }
    var template by remember { mutableStateOf(draft.template) }
    val nameError = SearchTemplates.nameError(name, engines, draft.id)
    val templateError = SearchTemplates.templateError(template)
    val showNameError = nameError != null && name.isNotBlank()
    val showTemplateError = templateError != null && template.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "Add search engine" else "Edit search engine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = showNameError,
                    supportingText = { if (showNameError) Text(nameError.orEmpty()) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Search URL") },
                    placeholder = { Text("https://example.com/search?q=%s") },
                    isError = showTemplateError,
                    supportingText = { Text(if (showTemplateError) templateError.orEmpty() else "%s is replaced by the words you search for") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameError == null && templateError == null,
                onClick = { onSave(CustomSearchEngine(draft.id ?: UUID.randomUUID().toString(), name.trim(), template.trim())) }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = templateError == null,
                    onClick = {
                        // The browser opens over settings; this dialog comes back with the same text.
                        pendingSearchDraft = SearchEngineDraft(draft.id, name, template)
                        BrowserManager.requestOpenInNewTab(SearchTemplates.build(template, SearchTemplates.SAMPLE_QUERY))
                    }
                ) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun ResetBrowserDataRow(showDialog: (@Composable () -> Unit) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    SettingItemRow(
        title = "Reset browser data",
        desc = "Cookies, cache, site storage, saved sign-ins and permissions",
        icon = Icons.Filled.DeleteSweep,
        onClick = {
            showDialog {
                ConfirmDialog(
                    "Reset browser data?",
                    "You will be signed out of websites. Bookmarks, history and tabs are kept.",
                    onDismiss = dismiss
                ) {
                    BrowserManager.resetBrowserData(context)
                    Toast.makeText(context, "Browser data deleted", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
        }
    )
}

/** A removable list of hosts from settings (allowed pop-ups, ignored certificates). */
@Composable
private fun HostListDialog(
    title: String,
    empty: String,
    hosts: (CastSettings) -> Set<String>,
    onRemove: (String) -> Unit,
    onRemoveAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    val list = hosts(settings).sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (list.isEmpty()) Text(empty)
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(list, key = { it }) { host ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, modifier = Modifier.weight(1f).padding(vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { onRemove(host) }) { Icon(Icons.Filled.Close, "Remove $host") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (list.isNotEmpty()) TextButton(onClick = onRemoveAll) { Text("Remove all") } }
    )
}

private val LIVE_LIMITS = listOf(1, 2, 3, 5, 8, 0)

private fun limitLabel(limit: Int): String = when (limit) {
    0 -> "Unlimited"
    1 -> "1 tab"
    else -> "$limit tabs"
}
