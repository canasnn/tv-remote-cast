package com.canasnn.tvremote.cast.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.backup.Backup
import com.canasnn.tvremote.cast.backup.BackupModule
import com.canasnn.tvremote.cast.backup.PrivacyItem
import com.canasnn.tvremote.ui.app.SettingItemRow
import com.ruterika.castcore.library.BackupFile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Backup & privacy" rows for the cast settings screen. */
@Composable
fun BackupAndPrivacyRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by CastHub.settings.collectAsStateWithLifecycle()
    var exportModules by remember { mutableStateOf<Set<BackupModule>?>(null) }
    var restoreFile by remember { mutableStateOf<BackupFile?>(null) }
    var erasing by remember { mutableStateOf(false) }
    var chosenForExport by remember { mutableStateOf(BackupModule.entries.toSet()) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val modules = exportModules ?: return@rememberLauncherForActivityResult
        exportModules = null
        if (uri != null) scope.launch {
            val ok = runCatching { Backup.export(context, uri, modules) }.isSuccess
            Toast.makeText(context, if (ok) "Backup saved" else "Could not save the backup", Toast.LENGTH_SHORT).show()
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val file = Backup.read(context, uri)
            if (file == null) Toast.makeText(context, "This file is not a backup of this app", Toast.LENGTH_SHORT).show()
            else restoreFile = file
        }
    }
    var pickExport by remember { mutableStateOf(false) }

    if (pickExport) {
        ModulePicker("Back up", BackupModule.entries, chosenForExport, { it.label }, onDismiss = { pickExport = false }) { chosen ->
            pickExport = false
            chosenForExport = chosen
            exportModules = chosen
            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            createFile.launch("tvremote-backup-$stamp.json")
        }
    }
    restoreFile?.let { file ->
        RestoreDialog(file, onDismiss = { restoreFile = null }) { chosen, replace ->
            restoreFile = null
            scope.launch {
                val ok = runCatching { Backup.import(context, file, chosen, replace) }.isSuccess
                Toast.makeText(context, if (ok) "Backup restored" else "Could not restore the backup", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (erasing) {
        EraseDialog(onDismiss = { erasing = false }) { chosen, always, exit ->
            erasing = false
            CastHub.settingsStore.update {
                if (always) it.copy(eraseOnExit = true, eraseOnExitItems = chosen.map(PrivacyItem::name).toSet())
                else it.copy(eraseOnExit = false)
            }
            scope.launch {
                Backup.erase(context, chosen)
                Toast.makeText(context, "Data erased", Toast.LENGTH_SHORT).show()
                if (exit) context.findActivity()?.finishAndRemoveTask()
            }
        }
    }

    Column {
        SettingItemRow(title = "Back up", desc = "Bookmarks, history, open tabs, playlists, IPTV lists, settings", icon = Icons.Filled.Backup, onClick = { pickExport = true })
        SettingItemRow(title = "Restore from backup", desc = "Merge a backup file into this phone, or replace what is here", icon = Icons.Filled.Restore, onClick = { openFile.launch(arrayOf("application/json", "text/*", "*/*")) })
        SettingItemRow(title = "Erase data", desc = "History, cookies, bookmarks, playlists, IPTV lists, subtitles…", icon = Icons.Filled.DeleteSweep, onClick = { erasing = true })
        val exitItems = PrivacyItem.fromNames(settings.eraseOnExitItems)
        SwitchRow(
            "Erase when leaving the app",
            if (exitItems.isEmpty()) "Pick what to erase in \"Erase data\"" else exitItems.joinToString(", ") { it.label.lowercase() }.replaceFirstChar { it.uppercase() },
            Icons.AutoMirrored.Filled.ExitToApp,
            settings.eraseOnExit && exitItems.isNotEmpty()
        ) { on ->
            if (on && exitItems.isEmpty()) erasing = true
            else CastHub.settingsStore.update { it.copy(eraseOnExit = on) }
        }
    }
}

/** Restore: which modules, and whether to merge with or replace what is here. */
@Composable
private fun RestoreDialog(file: BackupFile, onDismiss: () -> Unit, onConfirm: (Set<BackupModule>, Boolean) -> Unit) {
    val available = remember(file) {
        BackupModule.entries.filter { m ->
            when (m) {
                BackupModule.BOOKMARKS -> file.library.bookmarks.isNotEmpty()
                BackupModule.HISTORY -> file.library.history.isNotEmpty() || file.library.sites.isNotEmpty()
                BackupModule.TABS -> file.tabs.isNotEmpty()
                BackupModule.RECENT -> file.library.recentMedia.isNotEmpty()
                BackupModule.PLAYLISTS -> file.library.playlists.isNotEmpty()
                BackupModule.IPTV -> file.library.iptvSources.isNotEmpty() || file.library.favoriteChannels.isNotEmpty()
                BackupModule.DOWNLOADS -> file.library.downloads.isNotEmpty()
                BackupModule.SETTINGS -> file.settings != null
            }
        }
    }
    var replace by remember { mutableStateOf(false) }
    ModulePicker(
        title = "Restore",
        options = available,
        initial = available.toSet(),
        label = { it.label },
        confirm = if (replace) "Replace" else "Merge",
        onDismiss = onDismiss,
        footer = {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            listOf(false to "Merge: keep what is here and add what is missing", true to "Replace: the chosen lists become the backup's").forEach { (value, text) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = replace == value, onClick = { replace = value }).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = replace == value, onClick = null)
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    ) { chosen -> onConfirm(chosen, replace) }
}

/** What to erase, "always" and the exit button. */
@Composable
private fun EraseDialog(onDismiss: () -> Unit, onConfirm: (Set<PrivacyItem>, Boolean, Boolean) -> Unit) {
    val s = CastHub.settings.value
    val remembered = PrivacyItem.fromNames(s.eraseOnExitItems)
    var chosen by remember { mutableStateOf(remembered.ifEmpty { PrivacyItem.DEFAULT }) }
    var always by remember { mutableStateOf(s.eraseOnExit) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erase data") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                PrivacyItem.entries.forEach { option ->
                    CheckRow(option.label, option in chosen) { on -> chosen = if (on) chosen + option else chosen - option }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                CheckRow("Always erase these when leaving the app", always) { always = it }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen, always, true) }) { Text("Erase & exit") }
                TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen, always, false) }) { Text("Erase") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun <T> ModulePicker(
    title: String,
    options: List<T>,
    initial: Set<T>,
    label: (T) -> String,
    confirm: String = "OK",
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    onConfirm: (Set<T>) -> Unit
) {
    var chosen by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text("The backup is empty.")
                options.forEach { option ->
                    CheckRow(label(option), option in chosen) { on -> chosen = if (on) chosen + option else chosen - option }
                }
                footer?.invoke()
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
