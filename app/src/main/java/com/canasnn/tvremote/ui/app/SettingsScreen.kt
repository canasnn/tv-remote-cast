package com.canasnn.tvremote.ui.app

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.ui.ChoiceDialog
import com.ruterika.castcore.settings.StartScreen

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    SettingsScreen(
        autoReconnectEnabled = true,
        onBack = {},
        onOpenRemoteControls = {},
        onOpenAppearance = {},
        onOpenTipsSupport = {},
        onAutoReconnectChange = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    autoReconnectEnabled: Boolean,
    onBack: () -> Unit,
    onOpenRemoteControls: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenTipsSupport: () -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onOpenCastSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SettingsTopBar(
                    title = "Settings",
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
                    SettingSubtitle(text = "Remote")
                }
                item {
                    SettingGroupItem(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        title = "Remote Controls",
                        desc = "Personalize controls, layout, and quick actions",
                        icon = Icons.Filled.SettingsRemote,
                        onClick = onOpenRemoteControls
                    )
                }

                item {
                    SettingSubtitle(text = "Casting")
                }
                item {
                    SettingGroupItem(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        title = "Casting & Browser",
                        desc = "Browser, video detection, TV player, subtitles, IPTV",
                        icon = Icons.Filled.Cast,
                        onClick = onOpenCastSettings
                    )
                }
                item {
                    SettingSubtitle(text = "Appearance")
                }
                item {
                    SettingGroupItem(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        title = "Color & style",
                        desc = "Wallpaper colors, basic colors, and dark theme",
                        icon = Icons.Outlined.Palette,
                        onClick = onOpenAppearance
                    )
                }

                item {
                    SettingSubtitle(text = "About")
                }
                item {
                    SettingGroupItem(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        title = "About",
                        desc = "Version, feedback, source code and licenses",
                        icon = Icons.Outlined.TipsAndUpdates,
                        onClick = onOpenTipsSupport
                    )
                }


                item {
                    SettingSubtitle(text = "General")
                }
                item { GeneralSettingsRows() }

                item {
                    SettingSubtitle(text = "Connection")
                }
                item {
                    SettingItemRow(
                        title = "Auto reconnect",
                        desc = "Automatically reconnect to the last paired device",
                        icon = Icons.Outlined.Autorenew,
                        onClick = { onAutoReconnectChange(!autoReconnectEnabled) },
                        action = {
                            Switch(
                                checked = autoReconnectEnabled,
                                onCheckedChange = onAutoReconnectChange
                            )
                        }
                    )
                }
            }
        }
    }
}

/** App-wide options: start screen, language, animations, reports. */
@Composable
private fun GeneralSettingsRows() {
    val context = LocalContext.current
    val s by CastHub.settings.collectAsStateWithLifecycle()
    var pickStart by remember { mutableStateOf(false) }
    if (pickStart) {
        ChoiceDialog("Start screen", StartScreen.entries, s.startScreen, { it.label }, onDismiss = { pickStart = false }) { v ->
            CastHub.settingsStore.update { it.copy(startScreen = v) }
            pickStart = false
        }
    }
    Column {
        SettingItemRow(
            title = "Start screen",
            desc = s.startScreen.label,
            icon = Icons.Outlined.Home,
            onClick = { pickStart = true }
        )
        // The app has no translations of its own yet: offer Android's per-app language page.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingItemRow(
                title = "App language",
                desc = "Opens Android's language setting for this app",
                icon = Icons.Outlined.Language,
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    runCatching { context.startActivity(intent) }
                        .onFailure { Toast.makeText(context, "Language settings are not available", Toast.LENGTH_SHORT).show() }
                }
            )
        }
        SettingItemRow(
            title = "Screen transition animations",
            desc = "Animate moving between screens",
            icon = Icons.Outlined.Animation,
            onClick = { CastHub.settingsStore.update { it.copy(transitionAnimations = !it.transitionAnimations) } },
            action = { Switch(checked = s.transitionAnimations, onCheckedChange = { v -> CastHub.settingsStore.update { it.copy(transitionAnimations = v) } }) }
        )
    }
}
