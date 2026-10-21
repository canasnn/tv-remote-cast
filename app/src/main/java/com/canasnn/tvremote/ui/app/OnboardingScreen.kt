package com.canasnn.tvremote.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.canasnn.tvremote.R
import kotlinx.coroutines.launch

/** One intro page; [icon] null = the app logo. */
private data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val icon: ImageVector?
)

private val pages = listOf(
    OnboardingPage(
        "TV Remote & Cast",
        "Your Android TV remote and a cast hub in one free app.",
        null
    ),
    OnboardingPage(
        "A real remote",
        "D-pad, touchpad, keyboard and voice search. Your TV's own apps are one tap away.",
        Icons.Filled.TouchApp
    ),
    OnboardingPage(
        "Cast anything",
        "Videos from websites, IPTV lists, your photos, music and files go straight to the TV.",
        Icons.Filled.Cast
    ),
    OnboardingPage(
        "One small app on the TV",
        "Install the TV receiver once. It stays out of sight and starts playback by itself.",
        Icons.Filled.Tv
    ),
    OnboardingPage(
        "Free and private",
        "No ads, no accounts, nothing tracked. The app is open source.",
        Icons.Filled.Lock
    ),
    OnboardingPage(
        "Connect your TV",
        "Keep the phone and the TV on the same Wi-Fi, then enter the code your TV shows.",
        Icons.Filled.Wifi
    )
)

@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    /** Last page: go straight to finding the TV. */
    onFindTv: () -> Unit = onGetStarted
) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    AppBackdrop {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Very short windows (landscape split screen): the whole page scrolls instead.
        val short = maxHeight < 480.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .wrapContentWidth()
                .widthIn(max = 560.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onGetStarted) { Text("Skip") }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().then(if (short) Modifier.height(340.dp) else Modifier.weight(1f)),
                verticalAlignment = Alignment.CenterVertically
            ) { index ->
                PageCard(pages[index])
            }

            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    pages.indices.forEach { index ->
                        val active = index == pager.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(width = if (active) 28.dp else 10.dp, height = 10.dp)
                                .background(
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                                    shape = CircleShape
                                )
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("Back") }
                    Button(
                        onClick = { if (last) onFindTv() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (last) "Find my TV" else "Next") }
                }
            }
        }
        }
    }
}

@Composable
private fun PageCard(page: OnboardingPage) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (page.icon == null) {
                Image(painter = painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(112.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = page.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
