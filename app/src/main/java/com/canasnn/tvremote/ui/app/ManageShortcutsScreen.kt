package com.canasnn.tvremote.ui.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
fun ManageShortcutsScreenPreview() {
    ManageShortcutsScreen(
        onBack = {},
        onTestShortcut = {}
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageShortcutsScreen(
    remoteApps: List<RemoteShortcutApp> = defaultRemoteShortcutAppsPublic(),
    onBack: () -> Unit,
    onRemoteAppOrderChange: (List<String>) -> Unit = {},
    onTestShortcut: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    // ── State ──────────────────────────────────────────────────────────────
    val shortcuts by ShortcutsDataStore.shortcutsFlow(context)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val defaultApps = remember(remoteApps) { remoteApps.filter { !it.isCustom } }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomShortcut?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomShortcut?>(null) }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SettingsTopBar(
                    title = "App Shortcuts",
                    onBack = onBack,
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add Shortcut") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 88.dp)  // FAB clearance
            ) {
                // ── Help banner ──────────────────────────────────────────────
                item {
                    IntentUriHelpBanner(context = context)
                }

                // ── Default shortcuts ───────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Default app shortcuts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = {
                                val customIds = shortcuts.map { it.id }
                                onRemoteAppOrderChange(defaultRemoteShortcutOrder() + customIds)
                            }
                        ) {
                            Text("Reset defaults")
                        }
                    }
                }

                if (defaultApps.isEmpty()) {
                    item {
                        Text(
                            text = "No default apps selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    items(defaultApps, key = { it.id }) { app ->
                        DefaultShortcutRow(
                            app = app,
                            onDelete = {
                                val updatedOrder = remoteApps.filter { it.id != app.id }.map { it.id }
                                onRemoteAppOrderChange(updatedOrder)
                            }
                        )
                    }
                }

                // ── Custom shortcuts ─────────────────────────────────────────
                item { SettingSubtitle(text = "Custom shortcuts") }

                if (shortcuts.isEmpty()) {
                    item {
                        EmptyShortcutsPlaceholder(onAdd = { showAddDialog = true })
                    }
                } else {
                    items(shortcuts, key = { it.id }) { shortcut ->
                        CustomShortcutRow(
                            shortcut = shortcut,
                            onEdit = { editTarget = shortcut },
                            onDelete = { deleteTarget = shortcut },
                            onTest = { onTestShortcut(shortcut.intentUri) }
                        )
                    }
                }
            }
        }
    }

    // ── Add / Edit dialog ──────────────────────────────────────────────────
    if (showAddDialog) {
        ShortcutEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { shortcut ->
                scope.launch {
                    ShortcutsDataStore.addShortcut(context, shortcut)
                    val currentIds = remoteApps.map { it.id }
                    if (shortcut.id !in currentIds) {
                        onRemoteAppOrderChange(currentIds + shortcut.id)
                    }
                }
                showAddDialog = false
            }
        )
    }

    editTarget?.let { target ->
        ShortcutEditDialog(
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                scope.launch {
                    ShortcutsDataStore.updateShortcut(context, updated)
                }
                editTarget = null
            }
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove shortcut?") },
            text = { Text("\"${target.label}\" will be removed from your shortcuts strip.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            ShortcutsDataStore.removeShortcut(context, target.id)
                            onRemoteAppOrderChange(remoteApps.filter { it.id != target.id }.map { it.id })
                        }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Help banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IntentUriHelpBanner(context: android.content.Context) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "How to find Intent URIs",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = "Use https:// URLs or intent:// URIs to launch apps on your TV. The Home Assistant ATV integration docs list many common app URIs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val url = "https://www.home-assistant.io/integrations/androidtv_remote"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Open HA ATV Remote Docs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Default shortcut row (deletable, text-only)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DefaultShortcutRow(
    app: RemoteShortcutApp,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Default app",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete app shortcut",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom shortcut row (text-only)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CustomShortcutRow(
    shortcut: CustomShortcut,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = shortcut.intentUri,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Test button
        IconButton(
            onClick = onTest,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Test shortcut",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        // Edit button
        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        // Delete button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyShortcutsPlaceholder(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.AddCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = "No custom shortcuts yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Tap \"Add Shortcut\" to create your first custom app shortcut.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add Shortcut")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add / Edit dialog
// ─────────────────────────────────────────────────────────────────────────────

private val accentPresets = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5),
    Color(0xFF00ACC1), Color(0xFF00897B), Color(0xFF43A047),
    Color(0xFF7CB342), Color(0xFFF4511E), Color(0xFFFF8F00),
)

@Composable
private fun ShortcutEditDialog(
    initial: CustomShortcut?,
    onDismiss: () -> Unit,
    onSave: (CustomShortcut) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var intentUri by rememberSaveable { mutableStateOf(initial?.intentUri ?: "") }
    var uriError by remember { mutableStateOf<String?>(null) }
    var labelError by remember { mutableStateOf<String?>(null) }

    // Keep existing accent/mark if editing; otherwise use defaults derived from name.
    val existingAccentArgb = initial?.accentArgb ?: accentPresets.first().toArgb()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                shadowElevation = 24.dp
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // ── Header ────────────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                )
                            )
                            .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.align(Alignment.CenterStart)) {
                            Text(
                                text = if (initial == null) "Add Shortcut" else "Edit Shortcut",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Create a custom app link for your TV",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }

                    // ── Form ──────────────────────────────────────────────────
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Application name
                        OutlinedTextField(
                            value = label,
                            onValueChange = {
                                label = it.take(30)
                                labelError = null
                            },
                            label = { Text("Application name") },
                            placeholder = { Text("e.g. Netflix") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Filled.Label, null, modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(14.dp),
                            isError = labelError != null,
                            supportingText = labelError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )

                        // Intent URI
                        OutlinedTextField(
                            value = intentUri,
                            onValueChange = { input ->
                                intentUri = input
                                uriError = null
                            },
                            label = { Text("Intent URL") },
                            placeholder = { Text("https://www.netflix.com/title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Outlined.Link, null, modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(14.dp),
                            isError = uriError != null,
                            supportingText = {
                                if (uriError != null) {
                                    Text(uriError!!, color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text("https://, http://, or intent:// format")
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // ── Footer ────────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = {
                                val trimmedUri = intentUri.trim()
                                var hasError = false
                                val validationError = CustomShortcut.validateIntentUri(trimmedUri)
                                if (validationError != null) {
                                    uriError = validationError
                                    hasError = true
                                }
                                if (label.isBlank()) {
                                    labelError = "Application name cannot be empty."
                                    hasError = true
                                }
                                if (hasError) return@Button
                                onSave(
                                    CustomShortcut(
                                        id = initial?.id ?: UUID.randomUUID().toString(),
                                        label = label.trim(),
                                        mark = label.trim().take(2).uppercase(),
                                        accentArgb = existingAccentArgb,
                                        intentUri = trimmedUri
                                    )
                                )
                            }
                        ) { Text("Save") }
                    }
                }
            }
        }
    }
}

private fun defaultRemoteShortcutAppsPublic(): List<RemoteShortcutApp> =
    resolveRemoteShortcutApps(defaultRemoteShortcutOrder())
