package com.canasnn.tvremote.cast.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.browser.BrowserData
import com.canasnn.tvremote.ui.app.SettingItemRow
import com.ruterika.castcore.settings.CastSettings
import com.ruterika.castcore.settings.VideoAdMode
import com.ruterika.castcore.web.AdBlockListStore
import com.ruterika.castcore.web.AdBlocker
import com.ruterika.castcore.web.SiteRulesStore
import kotlinx.coroutines.launch

/** Video detection options of parity batch 5 (ad mode, DASH, sandbox fix, site rules, failed videos). */
internal fun LazyListScope.videoDetectionMoreSettings(
    s: CastSettings,
    update: ((CastSettings) -> CastSettings) -> Unit,
    showDialog: (@Composable () -> Unit) -> Unit,
    dismiss: () -> Unit
) {
    item {
        SettingItemRow(
            title = "Video ad mode",
            desc = s.videoAdMode.label + " · " + s.videoAdMode.description,
            icon = Icons.Filled.SkipNext,
            onClick = {
                showDialog {
                    ChoiceDialog("Video ad mode", VideoAdMode.entries, s.videoAdMode, { it.label + " · " + it.description }, onDismiss = dismiss) { v ->
                        update { it.copy(videoAdMode = v) }; dismiss()
                    }
                }
            }
        )
    }
    item { SwitchRow("Ask before casting ads", "Show \"Video ad detected\" for clips from ad servers or very short clips", Icons.Filled.AdsClick, s.askBeforeCastingAds) { v -> update { it.copy(askBeforeCastingAds = v) } } }
    item {
        SwitchRow("Disable DASH on pages", "Pages fall back to HLS or MP4 links the app can see (not on YouTube) · applies on the next page", Icons.Filled.VideoSettings, s.disableDash) { v ->
            update { it.copy(disableDash = v) }
        }
    }
    item { SwitchRow("Fix sandboxed players", "Let embedded players run on sites listed in the site rules", Icons.Filled.Splitscreen, s.sandboxFix) { v -> update { it.copy(sandboxFix = v) } } }
    item { SiteRulesRow(showDialog, dismiss) }
    item { FailedVideosRow(showDialog, dismiss) }
}

/** Ad-block list options (downloaded lists, allow list, reset), shown under "Ad blocking". */
internal fun LazyListScope.adBlockMoreSettings(
    s: CastSettings,
    update: ((CastSettings) -> CastSettings) -> Unit,
    showDialog: (@Composable () -> Unit) -> Unit,
    dismiss: () -> Unit
) {
    item { AdBlockListsRow(showDialog, dismiss) }
    item {
        SettingItemRow(
            title = "Never blocked hosts",
            desc = if (s.adBlockAllowList.isEmpty()) "None" else s.adBlockAllowList.sorted().joinToString(", "),
            icon = Icons.Filled.VerifiedUser,
            onClick = {
                showDialog {
                    MultiLineDialog(
                        title = "Never blocked hosts",
                        hint = "cdn.example.com\nplayer.example.net",
                        note = "One host per line. These hosts and their subdomains are never blocked.",
                        initial = s.adBlockAllowList.sorted().joinToString("\n"),
                        onDismiss = dismiss
                    ) { text ->
                        val hosts = text.lines().mapNotNull { line ->
                            val h = line.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
                            h.takeIf { it.isNotEmpty() && it.contains('.') }
                        }.toSet()
                        update { it.copy(adBlockAllowList = hosts) }
                        dismiss()
                    }
                }
            }
        )
    }
}

@Composable
private fun SiteRulesRow(showDialog: (@Composable () -> Unit) -> Unit, dismiss: () -> Unit) {
    val store = BrowserData.rulesStore
    val rules by (store?.rules ?: remember { kotlinx.coroutines.flow.MutableStateFlow(BrowserData.siteRules) }).collectAsStateWithLifecycle()
    val downloaded = store?.usesDownloaded == true
    SettingItemRow(
        title = "Site rules",
        desc = (if (downloaded) "Downloaded rules" else "Built-in rules") + " · version ${rules.version}" +
            (store?.updatedAt?.takeIf { it > 0 && downloaded }?.let { " · " + DateUtils.getRelativeTimeSpanString(it) } ?: ""),
        icon = Icons.AutoMirrored.Filled.Rule,
        onClick = { showDialog { SiteRulesDialog(dismiss) } }
    )
}

@Composable
private fun SiteRulesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(settings.siteRulesUrl) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Site rules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Rules fix detection on some sites: ignored player stubs, sign-in and captcha pages that are left alone, " +
                        "extra headers, and sites that keep their video list. The built-in rules always work; " +
                        "a rules address (JSON) keeps them up to date. Leave it empty to use only the built-in rules.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Update address") },
                    placeholder = { Text("https://example.com/site_rules.json") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy && url.isNotBlank(), onClick = {
                        CastHub.settingsStore.update { it.copy(siteRulesUrl = url.trim()) }
                        busy = true
                        scope.launch {
                            val message = when (val r = BrowserData.updateSiteRules()) {
                                is SiteRulesStore.UpdateResult.Updated -> "Site rules updated (version ${r.version})"
                                SiteRulesStore.UpdateResult.Unchanged -> "Site rules are up to date"
                                is SiteRulesStore.UpdateResult.Failed -> "Update failed: ${r.reason}"
                            }
                            busy = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(if (busy) "Updating…" else "Update now") }
                    TextButton(onClick = {
                        BrowserData.resetSiteRules()
                        Toast.makeText(context, "Using the built-in rules", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) { Text("Use built-in") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CastHub.settingsStore.update { it.copy(siteRulesUrl = url.trim()) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FailedVideosRow(showDialog: (@Composable () -> Unit) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var count by remember { mutableStateOf(BrowserData.failedCount) }
    SettingItemRow(
        title = "Forget failed videos",
        desc = if (count == 0) "Videos that fail on the TV are listed last for 30 days" else "$count videos that failed on the TV are listed last",
        icon = Icons.Filled.ErrorOutline,
        onClick = {
            showDialog {
                ConfirmDialog("Forget failed videos?", "They will be listed normally again.", onDismiss = dismiss) {
                    BrowserData.clearFailed()
                    count = 0
                    Toast.makeText(context, "Failed videos forgotten", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
        }
    )
}

@Composable
private fun AdBlockListsRow(showDialog: (@Composable () -> Unit) -> Unit, dismiss: () -> Unit) {
    val info by (BrowserData.adBlockLists?.info ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AdBlockListStore.Info(hostCount = AdBlocker.default().blockedCount)) })
        .collectAsStateWithLifecycle()
    val listUrl = CastHub.settings.collectAsStateWithLifecycle().value.adBlockUpdateUrl
    val listName = com.ruterika.castcore.web.AdBlockPresets.of(listUrl)?.name?.substringBefore(" (") ?: if (listUrl.isBlank()) "Built-in list" else "Custom list"
    SettingItemRow(
        title = "Ad block lists",
        desc = "$listName · " + "%,d hosts".format(info.hostCount) + if (info.updatedAt > 0) " · checked " + DateUtils.getRelativeTimeSpanString(info.updatedAt) else "",
        icon = Icons.Filled.FilterAlt,
        onClick = { showDialog { AdBlockListsDialog(dismiss) } }
    )
}

@Composable
private fun AdBlockListsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    var mainUrl by remember { mutableStateOf(settings.adBlockUpdateUrl) }
    var custom by remember { mutableStateOf(settings.adBlockCustomListUrls.joinToString("\n")) }
    var busy by remember { mutableStateOf(false) }
    fun save() {
        CastHub.settingsStore.update { s ->
            s.copy(
                adBlockUpdateUrl = mainUrl.trim(),
                adBlockCustomListUrls = custom.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ad block lists") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The built-in list is always used. A maintained worldwide list is downloaded on top of it and " +
                        "checked every day; when it did not change nothing is downloaded.",
                    style = MaterialTheme.typography.bodySmall
                )
                val presets = com.ruterika.castcore.web.AdBlockPresets.all
                var customSelected by remember { mutableStateOf(com.ruterika.castcore.web.AdBlockPresets.of(mainUrl) == null && mainUrl.isNotBlank()) }
                presets.forEach { preset ->
                    Row(
                        Modifier.fillMaxWidth().clickable { mainUrl = preset.url; customSelected = false },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = !customSelected && mainUrl == preset.url, onClick = { mainUrl = preset.url; customSelected = false })
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                            Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { customSelected = true }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = customSelected, onClick = { customSelected = true })
                    Text("Custom address", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().clickable { mainUrl = ""; customSelected = false }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = !customSelected && mainUrl.isBlank(), onClick = { mainUrl = ""; customSelected = false })
                    Text("Built-in list only", style = MaterialTheme.typography.bodyMedium)
                }
                if (customSelected) {
                    OutlinedTextField(
                        value = mainUrl,
                        onValueChange = { mainUrl = it },
                        singleLine = true,
                        label = { Text("List address (hosts, domains or adblock)") },
                        placeholder = { Text("https://example.com/hosts.txt") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom lists (one per line)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep up to date (daily check)", modifier = Modifier.weight(1f))
                    Switch(checked = settings.adBlockAutoUpdate, onCheckedChange = { v -> CastHub.settingsStore.update { it.copy(adBlockAutoUpdate = v) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        save()
                        busy = true
                        scope.launch {
                            val message = when (val r = BrowserData.updateAdBlockLists(force = true)) {
                                is AdBlockListStore.UpdateResult.Updated -> "%,d hosts blocked".format(r.hostCount)
                                AdBlockListStore.UpdateResult.Unchanged -> "Lists are up to date"
                                is AdBlockListStore.UpdateResult.Failed -> "Update failed: ${r.reason}"
                            }
                            busy = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(if (busy) "Updating…" else "Update now") }
                    TextButton(onClick = {
                        BrowserData.resetAdBlockLists()
                        Toast.makeText(context, "Back to the built-in list", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) { Text("Reset") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MultiLineDialog(
    title: String,
    hint: String,
    note: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
