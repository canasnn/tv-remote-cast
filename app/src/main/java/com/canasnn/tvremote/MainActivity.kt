package com.canasnn.tvremote

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.canasnn.tvremote.navigation.AppNavGraph

import com.canasnn.tvremote.preference.LocalDarkTheme
import com.canasnn.tvremote.preference.LocalThemeIndex

import com.canasnn.tvremote.preference.DarkThemePreference
import com.canasnn.tvremote.preference.darkThemeFlow
import com.canasnn.tvremote.preference.themeIndexFlow
import com.canasnn.tvremote.preference.oledDarkThemeFlow
import com.canasnn.tvremote.preference.LocalAmoledDarkTheme
import com.canasnn.tvremote.preference.AmoledDarkTheme
import com.canasnn.tvremote.ui.theme.AndroidTVRemoteTheme
import com.canasnn.tvremote.utils.Constant

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // A recreated activity (theme change…) or a launch from recents must not open the same link again.
        val fromHistory = intent?.flags?.and(android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
        com.canasnn.tvremote.cast.intake.LauncherShortcuts.publish(this)
        val prefs = getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(Constant.PREF_FIRST_LAUNCH, true)

        setContent {
            val context = LocalContext.current

            // Collect appearance DataStore flows as Compose state
            val darkThemeInt by context.darkThemeFlow()
                .collectAsStateWithLifecycle(initialValue = DarkThemePreference.UseDeviceTheme.value)
            val themeIndex by context.themeIndexFlow()
                .collectAsStateWithLifecycle(initialValue = 0)
            val oledDarkThemeEnabled by context.oledDarkThemeFlow()
                .collectAsStateWithLifecycle(initialValue = false)

            val darkTheme = DarkThemePreference.fromInt(darkThemeInt)
            CompositionLocalProvider(
                LocalDarkTheme provides darkTheme,
                LocalThemeIndex provides themeIndex,
                LocalAmoledDarkTheme provides AmoledDarkTheme(oledDarkThemeEnabled)
            ) {
                AndroidTVRemoteTheme {
                    AppNavGraph(
                        showOnboarding = isFirstLaunch,
                        onFinishOnboarding = {
                            prefs.edit { putBoolean(Constant.PREF_FIRST_LAUNCH, false) }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == com.canasnn.tvremote.cast.CastHub.ACTION_OPEN_NOW_PLAYING) {
            com.canasnn.tvremote.cast.CastHub.requestOpenNowPlaying()
        }
        if (intent?.action == com.canasnn.tvremote.cast.browser.BrowserManager.ACTION_OPEN_URL) {
            intent.getStringExtra(com.canasnn.tvremote.cast.browser.BrowserManager.EXTRA_URL)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let(com.canasnn.tvremote.cast.browser.BrowserManager::requestExternalOpen)
        }
        // Links, media and files from other apps (VIEW / SEND), launcher shortcuts.
        if (intent != null && com.canasnn.tvremote.cast.intake.Intake.handles(intent)) {
            val received = android.content.Intent(intent)
            lifecycleScope.launch {
                val request = com.canasnn.tvremote.cast.intake.Intake.parse(applicationContext, received)
                if (request != null) com.canasnn.tvremote.cast.intake.Intake.post(request)
                else android.widget.Toast.makeText(this@MainActivity, "Nothing to open in what was shared", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        // "Erase when leaving the app": the user closed the app (not a rotation / theme change).
        if (isFinishing && !isChangingConfigurations) com.canasnn.tvremote.cast.backup.Backup.eraseOnExitIfEnabled(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onStop() {
        super.onStop()
    }
}
