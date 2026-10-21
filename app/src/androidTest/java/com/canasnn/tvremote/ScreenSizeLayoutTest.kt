package com.canasnn.tvremote

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.canasnn.tvremote.ui.app.OnboardingScreen
import com.canasnn.tvremote.ui.app.RemotePadMode
import com.canasnn.tvremote.ui.app.RemoteScreen
import com.canasnn.tvremote.ui.app.RemoteShelfMode
import com.canasnn.tvremote.ui.app.RemoteShortcutApp
import com.canasnn.tvremote.ui.app.TvRemoteUiState
import com.canasnn.tvremote.ui.theme.AndroidTVRemoteTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the main screens at small-phone, landscape, tablet and large-font sizes and saves
 * screenshots to the app's files/layout folder for review.
 *
 * Heights are what the Remote tab leaves for its content: screen minus status bar, top bar,
 * bottom tabs and the 3-button navigation bar (24 + 64 + 80 + 48 dp).
 */
class ScreenSizeLayoutTest {
    @get:Rule val rule = createComposeRule()

    private data class Case(val name: String, val width: Int, val screenHeight: Int, val fontScale: Float = 1f)

    private val cases = listOf(
        Case("phone_320x568", 320, 568),
        Case("phone_360x640", 360, 640),
        Case("phone_360x640_font15", 360, 640, 1.5f),
        Case("phone_411x918", 411, 918),
        Case("phone_land_800x360", 800, 360),
        Case("tablet_800x1280", 800, 1280),
        Case("tablet_land_1280x800", 1280, 800),
    )

    private val outDir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "layout").apply { mkdirs() }
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Sized(case: Case, contentHeight: Int, content: @Composable () -> Unit) {
        DeviceConfigurationOverride(
            DeviceConfigurationOverride.ForcedSize(DpSize(case.width.dp, contentHeight.dp)) then
                DeviceConfigurationOverride.FontScale(case.fontScale)
        ) {
            AndroidTVRemoteTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }

    @Test
    fun remoteScreen() {
        var case by mutableStateOf(cases.first())
        val apps = listOf("YouTube", "Netflix", "Prime Video", "Spotify").map {
            RemoteShortcutApp(id = it, launchName = it, label = it, mark = it.take(1), accent = Color(0xFF0FA3A3))
        }
        rule.setContent {
            Sized(case, case.screenHeight - 216) {
                RemoteScreen(
                    activePadMode = RemotePadMode.DPad,
                    defaultPadMode = RemotePadMode.DPad,
                    sessionState = TvRemoteUiState(),
                    remoteShelfMode = RemoteShelfMode.Applications,
                    quickApps = apps,
                    hapticsEnabled = false,
                    onRequireConnection = {}, onCyclePadMode = {}, onQuickApp = {}, onRemoteKey = {},
                    onVolumeUp = {}, onVolumeDown = {}, onKeyboardText = {}, onKeyboardEnter = {}, onToggleVoice = {}
                )
            }
        }
        for (c in cases) {
            case = c
            rule.waitForIdle()
            save(rule.onRoot().captureToImage().asAndroidBitmap(), "remote_${c.name}")
            // The lower deck is there (it used to get no height at all on small phones).
            rule.onNodeWithContentDescription("Keyboard", useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun onboardingScreen() {
        var case by mutableStateOf(cases.first())
        rule.setContent {
            Sized(case, case.screenHeight) { OnboardingScreen(onGetStarted = {}) }
        }
        for (c in cases) {
            case = c
            rule.waitForIdle()
            save(rule.onRoot().captureToImage().asAndroidBitmap(), "onboarding_${c.name}")
        }
    }
}
