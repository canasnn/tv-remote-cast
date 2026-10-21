package com.canasnn.tvremote.ui.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.canasnn.tvremote.BuildConfig
import com.canasnn.tvremote.R

/** Project home: source, issues (feedback) and open source notices. */
private const val PROJECT_URL = "https://github.com/canasnn/tv-remote-cast"
private const val ISSUES_URL = "$PROJECT_URL/issues"

/** About the app: version, feedback, source code and open source licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsSupportScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { SettingsTopBar(title = "About", onBack = onBack, scrollBehavior = scrollBehavior) },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                item { AboutHero() }
                item { SettingSubtitle(text = "Feedback") }
                item {
                    SettingItemRow(
                        title = "Send feedback",
                        desc = "Report a bug or suggest a feature on GitHub",
                        icon = Icons.Filled.BugReport,
                        onClick = { open(ISSUES_URL) }
                    )
                }
                item { SettingSubtitle(text = "About") }
                item {
                    SettingItemRow(
                        title = "Version",
                        desc = BuildConfig.VERSION_NAME,
                        icon = Icons.Filled.Info
                    )
                }
                item {
                    SettingItemRow(
                        title = "Source code",
                        desc = "Free and open source on GitHub",
                        icon = Icons.Filled.Code,
                        onClick = { open(PROJECT_URL) }
                    )
                }
                item {
                    SettingItemRow(
                        title = "Open source licenses",
                        desc = "Projects and libraries this app is built on",
                        icon = Icons.Filled.Description,
                        onClick = { showLicenses = true }
                    )
                }
            }
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open source licenses") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(OPEN_SOURCE_NOTICE, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painter = painterResource(id = R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(120.dp))
        Surface(
            modifier = Modifier.padding(top = 18.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text(
            text = "TV Remote & Cast",
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Control your Android TV and cast videos, IPTV and your own files to it. Free, no ads.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Keep in sync with the NOTICE file at the repository root. */
private val OPEN_SOURCE_NOTICE = """
TV Remote & Cast
Copyright 2026 canasnn
Licensed under the Apache License, Version 2.0.

Parts of the remote control code are derived from open-source code licensed under the Apache License 2.0 and were modified for this app.

Colour palette code: MusicYou by Kyant0 (Apache License 2.0).
Connect SDK (Apache License 2.0).
AndroidX, Jetpack Compose and Media3 by Google (Apache License 2.0).
Kotlin, kotlinx.coroutines and kotlinx.serialization by JetBrains (Apache License 2.0).
OkHttp and Retrofit by Square (Apache License 2.0).
Coil (Apache License 2.0). Timber by Jake Wharton (Apache License 2.0).
Gson by Google (Apache License 2.0). RxJava and RxAndroid (Apache License 2.0).
Ktor by JetBrains (Apache License 2.0). Protocol Buffers by Google (BSD 3-Clause).
Bouncy Castle (MIT). Java-WebSocket by Nathan Rajlich (MIT).
NanoHTTPD (BSD 3-Clause).

Ad blocking downloads public lists from their publishers (HaGeZi, OISD, StevenBlack); they are not part of the app.

The TV receiver app uses Jellyfin's Media3 FFmpeg decoder (GPL-3.0) and FFmpeg, so the receiver is distributed under the GNU General Public License v3.0.
""".trimIndent()
