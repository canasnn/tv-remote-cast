package com.canasnn.tvremote.ui.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.canasnn.tvremote.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canasnn.tvremote.androidLib.remote.Remotemessage
import kotlinx.coroutines.launch

// Outline icons for unselected state — ReadYou uses outlined/filled toggle
private val HomeTab.outlinedIcon: ImageVector
    get() = when (this) {
        HomeTab.Remote -> Icons.Outlined.Tv
        HomeTab.Cast -> Icons.Outlined.Cast
    }

private val HomeTab.filledIcon: ImageVector
    get() = when (this) {
        HomeTab.Remote -> Icons.Filled.Tv
        HomeTab.Cast -> Icons.Filled.Cast
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    currentTab: HomeTab,
    activePadMode: RemotePadMode,
    defaultPadMode: RemotePadMode,
    sessionState: TvRemoteUiState,
    hapticsEnabled: Boolean,
    remoteShelfMode: RemoteShelfMode,
    remoteApps: List<RemoteShortcutApp>,
    onTabSelected: (HomeTab) -> Unit,
    onCyclePadMode: () -> Unit,
    onOpenDiscovery: () -> Unit,
    onOpenSettings: () -> Unit,
    onPower: () -> Unit,
    onQuickApp: (String) -> Unit,
    onRemoteKey: (Remotemessage.RemoteKeyCode) -> Unit,
    onVolumeChanged: (Float) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onKeyboardText: (String) -> Unit,
    onKeyboardEnter: () -> Unit,
    onToggleVoice: () -> Unit,
    onOpenCastPlayer: (MediaItemUi) -> Unit,
    onClearStatus: () -> Unit,
    onOpenBrowser: (String?) -> Unit = {},
    onOpenIptvSource: (String) -> Unit = {},
    onOpenLibrary: (com.canasnn.tvremote.cast.ui.LibrarySection) -> Unit = {},
    onCastSaved: (List<com.ruterika.castcore.library.SavedMedia>, Int) -> Unit = { _, _ -> },
    onOpenCastSettings: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current

    // Connection status is shown in the toolbar subtitle; no snackbar needed.

    fun performHaptic() {
        if (hapticsEnabled) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun handleAction(action: () -> Unit) {
        performHaptic()
        action()
    }

    fun handleRemoteAction(action: () -> Unit) {
        performHaptic()
        if (sessionState.connectedDevice != null) {
            action()
        } else {
            onOpenDiscovery()
        }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val titleText = when {
                                sessionState.isConnecting -> "Connecting..."
                                sessionState.connectedDevice != null -> sessionState.connectedDevice.name
                                else -> "TV Remote & Cast"
                            }
                            val dotColor = when {
                                sessionState.connectedDevice != null -> Color(0xFF4CAF50)
                                sessionState.isConnecting -> Color(0xFFFFB300)
                                else -> Color(0xFFE53935)
                            }

                            Text(
                                text = titleText,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(dotColor, CircleShape)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { handleRemoteAction(onPower) },
                            enabled = true
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PowerSettingsNew,
                                contentDescription = "Power",
                                tint = if (sessionState.connectedDevice != null)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(onClick = onOpenDiscovery) {
                            Icon(
                                imageVector = if (sessionState.connectedDevice != null) {
                                    Icons.Filled.CastConnected
                                } else {
                                    Icons.Filled.Cast
                                },
                                contentDescription = if (sessionState.connectedDevice != null) {
                                    "Connected device"
                                } else {
                                    "Discover"
                                }
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = { HomeBottomBar(currentTab = currentTab, onTabSelected = onTabSelected) }
        ) { innerPadding ->
            Crossfade(targetState = currentTab, label = "tabContent") { tab ->
                when (tab) {
                      HomeTab.Remote -> RemoteScreen(
                         modifier = Modifier.padding(innerPadding),
                         activePadMode = activePadMode,
                         defaultPadMode = defaultPadMode,
                         sessionState = sessionState,
                         remoteShelfMode = remoteShelfMode,
                         quickApps = remoteApps,
                         hapticsEnabled = hapticsEnabled,
                         onRequireConnection = onOpenDiscovery,
                         onCyclePadMode = onCyclePadMode,
                         onQuickApp = onQuickApp,
                         onRemoteKey = onRemoteKey,
                         onVolumeUp = onVolumeUp,
                         onVolumeDown = onVolumeDown,
                         onKeyboardText = onKeyboardText,
                         onKeyboardEnter = onKeyboardEnter,
                         onToggleVoice = onToggleVoice
                     )

                    HomeTab.Cast -> CastScreen(
                        modifier = Modifier.padding(innerPadding),
                        isConnected = sessionState.connectedDevice != null,
                        deviceName = sessionState.connectedDevice?.name,
                        castState = sessionState.cast,
                        onOpenBrowser = onOpenBrowser,
                        onOpenIptvSource = onOpenIptvSource,
                        onOpenLibrary = onOpenLibrary,
                        onCastSaved = onCastSaved,
                        onOpenCastSettings = onOpenCastSettings,
                        onOpenCastPlayer = { mediaItem ->
                            handleAction { onOpenCastPlayer(mediaItem) }
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Starting cast for ${mediaItem.title}",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    )
                }
            }
        }
    }

}

/** Remote / Cast bottom navigation, shared by the home screen and every cast screen. */
@Composable
fun HomeBottomBar(
    currentTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = Color.Transparent,   // transparent — backdrop shows through
                    tonalElevation = 0.dp
                ) {
                    HomeTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        val iconColor = animateColorAsState(
                            targetValue = if (selected)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(220),
                            label = "iconColor"
                        )

                        NavigationBarItem(
                            selected = selected,
                            onClick = { onTabSelected(tab) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.filledIcon else tab.outlinedIcon,
                                    contentDescription = tab.label,
                                    tint = iconColor.value,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                // pill indicator — matches ReadYou's secondaryContainer pill
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                }
            }
    }
}
