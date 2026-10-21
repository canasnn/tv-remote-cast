package com.canasnn.tvremote.ui.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Preview(showBackground = true)
@Composable
fun RemoteControlSettingsScreenPreview() {
    RemoteControlSettingsScreen(
        defaultPadMode = RemotePadMode.DPad,
        hapticsEnabled = true,
        keepScreenAwake = true,
        remoteShelfMode = RemoteShelfMode.Applications,
        remoteApps = resolveRemoteShortcutApps(defaultRemoteShortcutOrder()),
        onBack = {},
        onDefaultPadModeChange = {},
        onHapticsChange = {},
        onKeepScreenAwakeChange = {},
        onRemoteShelfModeChange = {},
        onOpenQuickLaunchOrder = {},
        onOpenManageShortcuts = {},
        onOpenCustomizeLayout = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlSettingsScreen(
    defaultPadMode: RemotePadMode,
    hapticsEnabled: Boolean,
    keepScreenAwake: Boolean,
    remoteShelfMode: RemoteShelfMode,
    remoteApps: List<RemoteShortcutApp>,
    onBack: () -> Unit,
    onDefaultPadModeChange: (RemotePadMode) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onRemoteShelfModeChange: (RemoteShelfMode) -> Unit,
    onOpenQuickLaunchOrder: () -> Unit = {},
    onOpenManageShortcuts: () -> Unit = {},
    onOpenCustomizeLayout: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SettingsTopBar(
                    title = "Remote Controls",
                    onBack = onBack,
                    scrollBehavior = scrollBehavior
                )
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                item {
                    SettingSubtitle(text = "Remote Layout")
                }
                item {
                    DefaultPadModeSelector(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        defaultPadMode = defaultPadMode.primaryMode(),
                        onDefaultPadModeChange = onDefaultPadModeChange
                    )
                }

                item {
                    SettingSubtitle(text = "Remote Controls")
                }
                item {
                    SettingItemRow(
                        title = "Haptic feedback",
                        desc = "Vibrate on button presses",
                        icon = Icons.Filled.TouchApp,
                        onClick = { onHapticsChange(!hapticsEnabled) },
                        action = {
                            Switch(
                                checked = hapticsEnabled,
                                onCheckedChange = onHapticsChange
                            )
                        }
                    )
                }
                item {
                    SettingItemRow(
                        title = "Keep screen awake",
                        desc = "Prevent sleep while using the remote",
                        icon = Icons.Filled.Tv,
                        onClick = { onKeepScreenAwakeChange(!keepScreenAwake) },
                        action = {
                            Switch(
                                checked = keepScreenAwake,
                                onCheckedChange = onKeepScreenAwakeChange
                            )
                        }
                    )
                }
                item {
                    SettingItemRow(
                        title = "Top strip",
                        desc = remoteShelfMode.description,
                        icon = Icons.Filled.Apps,
                    )
                }
                item {
                    ThemeChipRow(
                        options = RemoteShelfMode.entries,
                        selected = remoteShelfMode,
                        labelOf = { it.label },
                        onSelect = onRemoteShelfModeChange
                    )
                }
                item {
                    SettingSubtitle(text = "Manage Applications")
                }
                item {
                    SettingItemRow(
                        title = "Quick-launch order",
                        desc = if (remoteShelfMode == RemoteShelfMode.Applications) {
                            "Drag and arrange the app positions shown on the remote"
                        } else {
                            "Set the app order now for when you use the Applications strip"
                        },
                        icon = Icons.Filled.Apps,
                        onClick = onOpenQuickLaunchOrder
                    )
                }
                item {
                    SettingItemRow(
                        title = "Custom shortcuts",
                        desc = "Add, edit, or delete your own app shortcuts",
                        icon = Icons.Filled.AddCircle,
                        onClick = onOpenManageShortcuts
                    )
                }
                item {
                    SettingItemRow(
                        title = "Customize layout",
                        desc = "Rearrange controls and change rockers",
                        icon = Icons.Filled.Dashboard,
                        onClick = onOpenCustomizeLayout
                    )
                }
            }
        }
    }
}




@Composable
private fun DefaultPadModeSelector(
    modifier: Modifier = Modifier,
    defaultPadMode: RemotePadMode,
    onDefaultPadModeChange: (RemotePadMode) -> Unit
) {
    val layoutModes = remember {
        listOf(RemotePadMode.Touchpad, RemotePadMode.DPad)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        layoutModes.forEach { mode ->
            val isSelected = defaultPadMode == mode
            FilledTonalButton(
                onClick = { onDefaultPadModeChange(mode) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (mode == RemotePadMode.Touchpad) {
                            Icons.Filled.TouchApp
                        } else {
                            Icons.Filled.GridView
                        },
                        contentDescription = null
                    )
                    Text(
                        text = mode.label,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun <T> ThemeChipRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .then(
                        if (isSelected) Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        ) else Modifier
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
