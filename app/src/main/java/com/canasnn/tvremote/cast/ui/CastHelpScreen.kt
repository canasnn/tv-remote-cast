package com.canasnn.tvremote.cast.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import com.canasnn.tvremote.App
import com.canasnn.tvremote.receiver.ReceiverManager
import com.canasnn.tvremote.ui.app.AppBackdrop
import com.canasnn.tvremote.ui.app.SettingSubtitle
import com.canasnn.tvremote.ui.app.SettingsTopBar

private data class Faq(val question: String, val answer: String)

private val intro = listOf(
    Faq(
        "How does casting work?",
        "This app pairs with your Android TV as a remote. To play videos, it also talks to a small receiver app on the TV. " +
            "The receiver has no icon and needs no setup: it starts by itself when you cast and plays what you send in its own player."
    ),
    Faq(
        "How do I install the TV receiver?",
        "Connect to your TV in the Remote tab first, then tap \"Open the receiver on the TV's Play Store\" below. " +
            "The store page opens on the TV: install it with the TV remote (or this app's remote), then cast again. " +
            "Without the receiver, casting says \"TV receiver not found\"."
    ),
    Faq(
        "What can I cast?",
        "Videos found on web pages in the built-in browser, IPTV channels, videos, photos and music from this phone, links you paste in the Link tab, " +
            "and links or files other apps share with this app (\"Share\" or \"Open with\")."
    )
)

private val troubleshooting = listOf(
    Faq(
        "Nothing happens when I cast",
        "Make sure the phone and the TV are on the same Wi-Fi network and the remote is connected. Turn off VPNs on the phone: the TV must be able to " +
            "reach it directly. If the TV was asleep, wake it with the remote and try again."
    ),
    Faq(
        "The video starts but stops or keeps buffering",
        "Some sites only allow their own player, or tie the link to your phone. Play the video in the browser first, then cast the entry from the video list. " +
            "Links from the phone's own storage are served by this phone, so keep the app open nearby while watching."
    ),
    Faq(
        "Phone videos don't play on the TV",
        "Phone files are streamed from this phone over Wi-Fi. Keep the phone awake and allow the app to run in the background (battery settings). " +
            "Very high bitrate files may need a faster Wi-Fi connection."
    ),
    Faq(
        "Subtitles don't show",
        "Use \"Subtitles\" on the now playing screen to load a file, a link, or search OpenSubtitles. SRT, VTT, ASS and SSA are supported. " +
            "If letters look wrong, change the character encoding in the subtitle settings."
    ),
    Faq(
        "The TV says the receiver is not installed",
        "Connect the remote, open the receiver's store page with the button at the top of this page, install it and cast again. " +
            "If it was installed before, open it once from the TV's app list after a TV update."
    ),
    Faq(
        "Live streams and IPTV",
        "Live channels can't be paused for long and can't be downloaded. If a channel does not start, try \"Play as live stream\" from its menu, " +
            "or set a user agent for the list."
    )
)

/** Short help page for casting: what the TV receiver is, installing it, common problems. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastHelpScreen(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { SettingsTopBar(title = "Casting help", onBack = onBack, scrollBehavior = scrollBehavior) },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { SettingSubtitle(text = "Getting started") }
                items(intro) { FaqCard(it, initiallyOpen = true) }
                item {
                    val context = LocalContext.current
                    Button(
                        onClick = {
                            if (App.app?.androidRemoteTv == null) {
                                Toast.makeText(context, "Connect to your TV in the Remote tab first", Toast.LENGTH_SHORT).show()
                            } else {
                                ReceiverManager.openStorePage()
                                Toast.makeText(context, "Look at your TV", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) { Text("Open the receiver on the TV's Play Store") }
                }
                item { SettingSubtitle(text = "Troubleshooting") }
                items(troubleshooting) { FaqCard(it, initiallyOpen = false) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun FaqCard(faq: Faq, initiallyOpen: Boolean) {
    var open by rememberSaveable(faq.question) { mutableStateOf(initiallyOpen) }
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize().clickable { open = !open }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(faq.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) Text(faq.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
