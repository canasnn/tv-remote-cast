package com.canasnn.tvremote.navigation

import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.canasnn.tvremote.ui.app.CastPlayerScreen
import com.canasnn.tvremote.ui.app.DeviceDiscoveryScreen
import com.canasnn.tvremote.ui.app.AppearanceScreen
import com.canasnn.tvremote.ui.app.HomeScreen
import com.canasnn.tvremote.ui.app.HomeTab
import com.canasnn.tvremote.ui.app.ManageShortcutsScreen
import com.canasnn.tvremote.ui.app.CustomizeLayoutScreen
import com.canasnn.tvremote.ui.app.MediaItemUi
import com.canasnn.tvremote.ui.app.MediaKind
import com.canasnn.tvremote.ui.app.OnboardingScreen
import com.canasnn.tvremote.ui.app.RemotePadMode
import com.canasnn.tvremote.ui.app.RemoteControlSettingsScreen
import com.canasnn.tvremote.ui.app.RemoteShelfMode
import com.canasnn.tvremote.ui.app.SettingsScreen
import com.canasnn.tvremote.ui.app.SplashRoute
import com.canasnn.tvremote.ui.app.TipsSupportScreen
import com.canasnn.tvremote.ui.app.TvRemoteViewModel
import com.canasnn.tvremote.ui.app.decodeRemoteShortcutOrder
import com.canasnn.tvremote.ui.app.encodeRemoteShortcutOrder
import com.canasnn.tvremote.ui.app.queryPhotosInAlbum
import com.canasnn.tvremote.ui.app.resolveRemoteShortcutApps
import com.canasnn.tvremote.ui.app.QuickLaunchOrderScreen
import com.canasnn.tvremote.ui.app.ShortcutsDataStore
import com.canasnn.tvremote.ui.app.getAllKnownShortcutApps
import com.canasnn.tvremote.ui.app.getDefaultRemoteShortcutApps
import com.canasnn.tvremote.utils.Constant
import com.canasnn.tvremote.androidLib.remote.Remotemessage
import com.canasnn.tvremote.ui.app.toggleNumberPad



private const val TRANSITION_DURATION = 300

// Material 3 recommended easings
// Forward enter:  EmphasizedDecelerate  (spring into place)
// Forward exit:   EmphasizedAccelerate  (quick out)
// Pop enter:      EmphasizedDecelerate  (come back smoothly)
// Pop exit:       EmphasizedAccelerate  (quick leave)

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
private const val FADE_THROUGH_DURATION = 300

// Small offset — Material 3 uses ~6–8% of screen width, not full width
private const val SLIDE_OFFSET_FRACTION = 0.06f





@Composable
fun AppNavGraph(
    showOnboarding: Boolean,
    onFinishOnboarding: () -> Unit,
) {


    // Lock the initial value — prevents DataStore recompositions from
    // flipping the destination after nav has already started.
    val startWithOnboarding = rememberSaveable { showOnboarding }
    val navController = rememberNavController()
    val context = LocalContext.current

    val prefs = remember(context) {
        context.getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val application = context.applicationContext as android.app.Application
    val tvRemoteViewModel: TvRemoteViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
    val sessionState by tvRemoteViewModel.uiState.collectAsStateWithLifecycle()

    // Start screen setting (Remote / Cast hub / Browser / last used), read once per launch.
    val startTarget = rememberSaveable { startTargetFor(context) }
    val workingTab = rememberSaveable { mutableStateOf(if (startTarget == START_REMOTE) HomeTab.Remote else HomeTab.Cast) }
    var currentTab by workingTab

    // ── Centralized Connection Guard Navigation trigger ──
    val navigateToDiscovery by tvRemoteViewModel.navigateToDiscoveryTrigger.collectAsStateWithLifecycle()
    LaunchedEffect(navigateToDiscovery) {
        if (navigateToDiscovery) {
            if (navController.currentDestination?.route != Screen.Discovery.route) {
                navController.navigate(Screen.Discovery.route) {
                    launchSingleTop = true
                }
            }
            tvRemoteViewModel.resetDiscoveryTrigger()
        }
    }
    var defaultPadMode by rememberSaveable {
        mutableStateOf(
            prefs.getString(Constant.PREF_DEFAULT_PAD_MODE, RemotePadMode.DPad.name)
                ?.let { value -> RemotePadMode.entries.firstOrNull { it.name == value } }
                ?.takeIf { it != RemotePadMode.NumberPad }
                ?: RemotePadMode.DPad
        )
    }
    var activePadMode by rememberSaveable { mutableStateOf(defaultPadMode) }
    var autoReconnectEnabled by rememberSaveable {
        mutableStateOf(prefs.getBoolean(Constant.PREF_AUTO_RECONNECT, true))
    }
    var hapticsEnabled by rememberSaveable {
        mutableStateOf(prefs.getBoolean(Constant.PREF_HAPTICS, true))
    }
    var keepScreenAwake by rememberSaveable {
        mutableStateOf(prefs.getBoolean(Constant.PREF_KEEP_SCREEN_AWAKE, false))
    }
    var remoteShelfMode by rememberSaveable {
        mutableStateOf(
            RemoteShelfMode.fromStorage(
                prefs.getString(Constant.PREF_REMOTE_SHELF_MODE, null)
            ) ?: if (prefs.getBoolean(Constant.PREF_SMART_SUGGESTIONS, true)) {
                RemoteShelfMode.Applications
            } else {
                RemoteShelfMode.None
            }
        )
    }
    val customShortcuts by ShortcutsDataStore.shortcutsFlow(context)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var remoteAppOrder by rememberSaveable {
        mutableStateOf(
            decodeRemoteShortcutOrder(
                prefs.getString(Constant.PREF_REMOTE_APP_ORDER, null),
                customShortcuts
            )
        )
    }

    val tvApps by com.canasnn.tvremote.utils.TvAppsRepository.apps
        .collectAsStateWithLifecycle()

    LaunchedEffect(customShortcuts, tvApps) {
        val defaultIds = getDefaultRemoteShortcutApps().map { it.id }.toSet()
        val customIds = customShortcuts.map { it.id }.toSet()

        val validExisting = remoteAppOrder.filter { it in defaultIds || it in customIds }
        val missingCustom = customShortcuts.map { it.id }.filter { it !in validExisting }
        // TV apps: add only ones never offered before. Apps the user removed
        // stay out of the strip and show up under "Available to Add".
        val tvIds = tvApps.map { it.id }
        val seen = com.canasnn.tvremote.utils.TvAppsRepository.seenIds()
        val newTv = tvIds.filter { it !in seen && it !in validExisting }
        if (tvIds.isNotEmpty()) com.canasnn.tvremote.utils.TvAppsRepository.markSeen(tvIds)
        val newOrder = validExisting + newTv + missingCustom
        if (newOrder != remoteAppOrder) {
            remoteAppOrder = newOrder
        }
    }

    val resolvedRemoteApps = remember(remoteAppOrder, customShortcuts, tvApps) {
        resolveRemoteShortcutApps(remoteAppOrder, customShortcuts)
    }

    LaunchedEffect(defaultPadMode) {
        prefs.edit { putString(Constant.PREF_DEFAULT_PAD_MODE, defaultPadMode.name) }
    }
    LaunchedEffect(autoReconnectEnabled) {
        prefs.edit { putBoolean(Constant.PREF_AUTO_RECONNECT, autoReconnectEnabled) }
    }
    LaunchedEffect(hapticsEnabled) {
        prefs.edit { putBoolean(Constant.PREF_HAPTICS, hapticsEnabled) }
    }
    LaunchedEffect(keepScreenAwake) {
        prefs.edit { putBoolean(Constant.PREF_KEEP_SCREEN_AWAKE, keepScreenAwake) }
    }
    LaunchedEffect(remoteShelfMode) {
        prefs.edit {
            putString(Constant.PREF_REMOTE_SHELF_MODE, remoteShelfMode.storageValue)
            putBoolean(
                Constant.PREF_SMART_SUGGESTIONS,
                remoteShelfMode == RemoteShelfMode.Applications
            )
        }
    }
    LaunchedEffect(remoteAppOrder) {
        prefs.edit {
            putString(Constant.PREF_REMOTE_APP_ORDER, encodeRemoteShortcutOrder(remoteAppOrder))
        }
    }

    val castLauncher = com.canasnn.tvremote.cast.ui.rememberCastLauncher(
        play = { items, index, position ->
            tvRemoteViewModel.castSaved(items, index, position)
            if (com.canasnn.tvremote.cast.CastHub.settings.value.openControllerOnPlay) {
                navController.navigate(Screen.CastPlayer.route) { launchSingleTop = true }
            }
        },
        enqueue = { items -> tvRemoteViewModel.enqueueSaved(items) }
    )

    // ── Remote / Cast bar on every cast screen ──────────────────────────────
    // The cast section remembers its last screen (browser, player, IPTV list…)
    // so switching to the remote and back returns to exactly that place.
    val castScreenRoutes = remember {
        setOf(
            Screen.Browser.route, Screen.CastPlayer.route, Screen.IptvSource.route,
            Screen.Library.route, Screen.Playlist.route, Screen.SubtitleSearch.route
        )
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentPattern = currentEntry?.destination?.route
    var lastCastRoute by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentEntry) {
        val entry = currentEntry ?: return@LaunchedEffect
        val args = entry.arguments
        lastCastRoute = when (entry.destination.route) {
            Screen.Browser.route -> Screen.Browser.create(null)
            Screen.CastPlayer.route -> Screen.CastPlayer.route
            Screen.SubtitleSearch.route -> Screen.SubtitleSearch.route
            Screen.IptvSource.route -> Screen.IptvSource.create(args?.getString("id").orEmpty())
            Screen.Library.route -> Screen.Library.create(args?.getString("section").orEmpty())
            Screen.Playlist.route -> Screen.Playlist.create(args?.getString("id").orEmpty())
            else -> lastCastRoute
        }
    }
    // Back on the cast hub itself: the cast section starts from the hub again.
    LaunchedEffect(currentPattern, currentTab) {
        if (currentPattern == Screen.Main.route && currentTab == HomeTab.Cast) lastCastRoute = null
    }
    val selectHomeTab: (HomeTab) -> Unit = { tab ->
        val onCastScreen = currentPattern in castScreenRoutes
        when {
            tab == HomeTab.Remote -> {
                currentTab = HomeTab.Remote
                if (onCastScreen) navController.popBackStack(Screen.Main.route, inclusive = false)
            }
            onCastScreen -> {
                // Cast tapped while already in the cast section: back to the hub.
                lastCastRoute = null
                currentTab = HomeTab.Cast
                navController.popBackStack(Screen.Main.route, inclusive = false)
            }
            else -> {
                currentTab = HomeTab.Cast
                lastCastRoute?.let { route -> navController.navigate(route) { launchSingleTop = true } }
            }
        }
    }
    val showCastBar = currentPattern in castScreenRoutes

    LaunchedEffect(Unit) {
        com.canasnn.tvremote.cast.CastHub.errors.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Cast notification tapped: show "Now playing" once the app is past the splash.
    val openNowPlaying by com.canasnn.tvremote.cast.CastHub.openNowPlayingRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openNowPlaying, currentPattern) {
        if (!openNowPlaying) return@LaunchedEffect
        if (currentPattern == null || currentPattern == Screen.Splash.route || currentPattern == Screen.Welcome.route) return@LaunchedEffect
        com.canasnn.tvremote.cast.CastHub.consumeOpenNowPlaying()
        if (com.canasnn.tvremote.cast.CastHub.session.value != null) {
            currentTab = HomeTab.Cast
            navController.navigate(Screen.CastPlayer.route) { launchSingleTop = true }
        }
    }

    // Home screen shortcut of a web page: open it in the in-app browser.
    val externalUrl by com.canasnn.tvremote.cast.browser.BrowserManager.externalOpenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(externalUrl, currentPattern) {
        val url = externalUrl ?: return@LaunchedEffect
        if (currentPattern == null || currentPattern == Screen.Splash.route || currentPattern == Screen.Welcome.route) return@LaunchedEffect
        com.canasnn.tvremote.cast.browser.BrowserManager.consumeExternalOpen()
        com.canasnn.tvremote.cast.browser.BrowserManager.openFromOutside(context, url)
        currentTab = HomeTab.Cast
        if (currentPattern != Screen.Browser.route) navController.navigate(Screen.Browser.create()) { launchSingleTop = true }
    }

    val castSettings by com.canasnn.tvremote.cast.CastHub.settings.collectAsStateWithLifecycle()
    val animate = castSettings.transitionAnimations

    // ── Links, media and files from other apps; launcher shortcuts ─────────
    val addIncomingToPlaylist = com.canasnn.tvremote.cast.ui.rememberPlaylistPicker()
    val downloadIncoming = com.canasnn.tvremote.cast.ui.rememberDownloadStarter()
    var incomingMedia by remember { mutableStateOf<com.canasnn.tvremote.cast.intake.IncomingRequest.Media?>(null) }
    val incoming by com.canasnn.tvremote.cast.intake.Intake.request.collectAsStateWithLifecycle()
    LaunchedEffect(incoming, currentPattern) {
        val request = incoming ?: return@LaunchedEffect
        if (currentPattern == null || currentPattern == Screen.Splash.route || currentPattern == Screen.Welcome.route) return@LaunchedEffect
        com.canasnn.tvremote.cast.intake.Intake.consume()
        when (request) {
            is com.canasnn.tvremote.cast.intake.IncomingRequest.Page -> {
                if (com.canasnn.tvremote.cast.CastHub.settings.value.openLinksInApp) {
                    com.canasnn.tvremote.cast.browser.BrowserManager.openFromOutside(context, request.url)
                    currentTab = HomeTab.Cast
                    if (currentPattern != Screen.Browser.route) navController.navigate(Screen.Browser.create()) { launchSingleTop = true }
                } else com.canasnn.tvremote.cast.ui.openInOtherBrowser(context, request.url)
            }
            is com.canasnn.tvremote.cast.intake.IncomingRequest.Media -> {
                // Connected to the TV receiver: plain media goes straight to the TV.
                if (request.iptv == null && com.canasnn.tvremote.receiver.ReceiverClient.isConnected) {
                    currentTab = HomeTab.Cast
                    castLauncher.cast(request.items, 0)
                } else incomingMedia = request
            }
            is com.canasnn.tvremote.cast.intake.IncomingRequest.Section -> {
                val section = when (request.section) {
                    com.canasnn.tvremote.cast.intake.Intake.SECTION_BOOKMARKS -> com.canasnn.tvremote.cast.ui.LibrarySection.Bookmarks
                    com.canasnn.tvremote.cast.intake.Intake.SECTION_MOST_VISITED -> com.canasnn.tvremote.cast.ui.LibrarySection.MostVisited
                    else -> null
                }
                currentTab = HomeTab.Cast
                if (currentPattern != Screen.Main.route) navController.popBackStack(Screen.Main.route, inclusive = false)
                if (section != null) navController.navigate(Screen.Library.create(section.route)) { launchSingleTop = true }
                else com.canasnn.tvremote.cast.intake.Intake.requestCastTab(
                    if (request.section == com.canasnn.tvremote.cast.intake.Intake.SECTION_IPTV) com.canasnn.tvremote.ui.app.CastTab.Iptv
                    else com.canasnn.tvremote.ui.app.CastTab.Videos
                )
            }
        }
    }
    incomingMedia?.let { request ->
        com.canasnn.tvremote.cast.ui.IncomingMediaDialog(
            request = request,
            onDismiss = { incomingMedia = null },
            onCast = { items -> currentTab = HomeTab.Cast; castLauncher.cast(items, 0) },
            onAddToPlaylist = addIncomingToPlaylist,
            onDownload = downloadIncoming,
            onOpenInBrowser = { url ->
                com.canasnn.tvremote.cast.browser.BrowserManager.openFromOutside(context, url)
                currentTab = HomeTab.Cast
                if (currentPattern != Screen.Browser.route) navController.navigate(Screen.Browser.create()) { launchSingleTop = true }
            },
            onOpenIptvList = { id -> currentTab = HomeTab.Cast; navController.navigate(Screen.IptvSource.create(id)) }
        )
    }

    // Start screen "Browser" (or last used = browser): open it once the home screen is up,
    // unless something from outside is being opened.
    var startHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentPattern) {
        if (startHandled || currentPattern != Screen.Main.route) return@LaunchedEffect
        startHandled = true
        val busy = com.canasnn.tvremote.cast.intake.Intake.request.value != null ||
            com.canasnn.tvremote.cast.browser.BrowserManager.externalOpenRequest.value != null ||
            com.canasnn.tvremote.cast.CastHub.openNowPlayingRequest.value
        if (!busy) when (startTarget) {
            START_BROWSER -> navController.navigate(Screen.Browser.create()) { launchSingleTop = true }
            START_FILES -> com.canasnn.tvremote.cast.intake.Intake.requestCastTab(com.canasnn.tvremote.ui.app.CastTab.Videos)
            START_IPTV -> com.canasnn.tvremote.cast.intake.Intake.requestCastTab(com.canasnn.tvremote.ui.app.CastTab.Iptv)
            START_RECENT -> navController.navigate(Screen.Library.create(com.canasnn.tvremote.cast.ui.LibrarySection.Recent.route)) { launchSingleTop = true }
        }
    }
    // Remember where the user was for "Last used".
    LaunchedEffect(currentPattern, currentTab) {
        val value = when {
            currentPattern == Screen.Browser.route -> START_BROWSER
            currentPattern == Screen.Main.route && currentTab == HomeTab.Cast -> START_CAST
            currentPattern == Screen.Main.route -> START_REMOTE
            else -> return@LaunchedEffect
        }
        prefs.edit { putString(PREF_LAST_START_SCREEN, value) }
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
    Box(
        Modifier
            .weight(1f)
            .then(if (showCastBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
    ) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.canasnn.tvremote.cast.ui.LocalOpenNowPlaying provides {
            navController.navigate(Screen.CastPlayer.route) { launchSingleTop = true }
        },
        // Text without an explicit color follows the theme (never stays black in dark mode).
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface
    ) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,

        // Forward → new screen grows in (scale 92% → 100%) + fades in
        enterTransition = {
            if (!animate) androidx.compose.animation.EnterTransition.None else scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedDecelerate)
            ) + fadeIn(
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedDecelerate)
            )
        },

        // Forward → old screen shrinks out (100% → 108%) + fades out
        exitTransition = {
            if (!animate) androidx.compose.animation.ExitTransition.None else scaleOut(
                targetScale = 1.08f,
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedAccelerate)
            ) + fadeOut(
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedAccelerate)
            )
        },

        // Back → previous screen grows back in (92% → 100%) + fades in
        popEnterTransition = {
            if (!animate) androidx.compose.animation.EnterTransition.None else scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedDecelerate)
            ) + fadeIn(
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedDecelerate)
            )
        },

        // Back → current screen shrinks away (100% → 108%) + fades out
        popExitTransition = {
            if (!animate) androidx.compose.animation.ExitTransition.None else scaleOut(
                targetScale = 1.08f,
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedAccelerate)
            ) + fadeOut(
                animationSpec = tween(TRANSITION_DURATION, easing = EmphasizedAccelerate)
            )
        },
    )  {
        composable(Screen.Splash.route) {
            SplashRoute(
                showOnboarding = startWithOnboarding,
                onFinished = { shouldShowOnboarding ->
                    navController.navigate(
                        if (shouldShowOnboarding) Screen.Welcome.route else Screen.Main.route
                    ) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Welcome.route) {
            OnboardingScreen(
                onGetStarted = {
                    onFinishOnboarding()
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onFindTv = {
                    onFinishOnboarding()
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                    navController.navigate(Screen.Discovery.route) { launchSingleTop = true }
                }
            )
        }
        composable(Screen.Main.route) {
            HomeScreen(
                currentTab = currentTab,
                activePadMode = activePadMode,
                sessionState = sessionState,
                hapticsEnabled = hapticsEnabled,
                defaultPadMode = defaultPadMode,
                remoteShelfMode = remoteShelfMode,
                remoteApps = resolvedRemoteApps,
                onTabSelected = selectHomeTab,
                onCyclePadMode = {
                    activePadMode = activePadMode.toggleNumberPad(defaultPadMode)
                },
                onOpenDiscovery = {
                    if (navController.currentDestination?.route != Screen.Discovery.route) {
                        navController.navigate(Screen.Discovery.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onPower = {
                    tvRemoteViewModel.sendKey(Remotemessage.RemoteKeyCode.KEYCODE_POWER)
                },
                onQuickApp = tvRemoteViewModel::launchQuickApp,
                onRemoteKey = tvRemoteViewModel::sendKey,
                onClearStatus = tvRemoteViewModel::clearStatus,
                onVolumeChanged = tvRemoteViewModel::setVolume,
                onVolumeUp = tvRemoteViewModel::volumeUp,
                onVolumeDown = tvRemoteViewModel::volumeDown,
                onKeyboardText = tvRemoteViewModel::setKeyboardText,
                onKeyboardEnter = tvRemoteViewModel::sendKeyboardEnter,
                onToggleVoice = tvRemoteViewModel::toggleVoice,
                onOpenCastPlayer = { mediaItem: MediaItemUi ->
                    tvRemoteViewModel.castMedia(mediaItem)
                    navController.navigate(Screen.CastPlayer.route)
                },
                onOpenBrowser = { url -> navController.navigate(Screen.Browser.create(url)) },
                onOpenIptvSource = { id -> navController.navigate(Screen.IptvSource.create(id)) },
                onOpenLibrary = { section -> navController.navigate(Screen.Library.create(section.route)) },
                onCastSaved = { items, index ->
                    castLauncher.cast(items, index)
                },
                onOpenCastSettings = { navController.navigate(Screen.CastSettings.route) }
            )
        }
        composable(Screen.Discovery.route) {
            val initiallyConnectedDeviceId = remember { sessionState.connectedDevice?.id }
            var hasDisconnectedWhileHere by remember { mutableStateOf(false) }
            var connectRequested by remember { mutableStateOf(false) }

            LaunchedEffect(sessionState.connectedDevice?.id) {
                val connectedId = sessionState.connectedDevice?.id
                if (connectedId == null) {
                    hasDisconnectedWhileHere = true
                } else if (hasDisconnectedWhileHere || connectRequested || connectedId != initiallyConnectedDeviceId) {
                    if (navController.currentDestination?.route == Screen.Discovery.route) {
                        navController.popBackStack()
                    }
                }
            }
            DeviceDiscoveryScreen(
                sessionState = sessionState,
                onBack = { navController.popBackStack() },
                onRefresh = tvRemoteViewModel::refreshDiscoveredDevices,
                onConnect = { deviceId ->
                    connectRequested = true
                    tvRemoteViewModel.connectToDevice(deviceId)
                },
                onDisconnect = {
                    hasDisconnectedWhileHere = true
                    connectRequested = false
                    tvRemoteViewModel.disconnectCurrentDevice()
                },
                onSubmitPairingCode = { code ->
                    connectRequested = true
                    tvRemoteViewModel.submitPairingCode(code)
                },
                onCancelPairing = {
                    connectRequested = false
                    tvRemoteViewModel.cancelPairing()
                },
                onClearStatus = tvRemoteViewModel::clearStatus
            )
        }

        // ── Settings ────────────────────────────────────────────────────────
        composable(Screen.Settings.route) {
            SettingsScreen(
                autoReconnectEnabled = autoReconnectEnabled,
                onBack = { navController.popBackStack() },
                onOpenRemoteControls = { navController.navigate(Screen.RemoteControls.route) },
                onOpenAppearance = { navController.navigate(Screen.Appearance.route) },
                onOpenTipsSupport = { navController.navigate(Screen.TipsSupport.route) },
                onAutoReconnectChange = { autoReconnectEnabled = it },
                onOpenCastSettings = { navController.navigate(Screen.CastSettings.route) },
            )
        }
        composable(
            Screen.Browser.route,
            arguments = listOf(androidx.navigation.navArgument("url") {
                type = androidx.navigation.NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { entry ->
            com.canasnn.tvremote.cast.ui.BrowserScreen(
                initialUrl = entry.arguments?.getString("url"),
                onBack = { navController.popBackStack() },
                onCast = { items, index ->
                    castLauncher.cast(items, index)
                },
                onOpenLibrary = { section -> navController.navigate(Screen.Library.create(section.route)) },
                onOpenSettings = { navController.navigate(Screen.CastSettings.route) }
            )
        }
        composable(Screen.IptvSource.route) { entry ->
            com.canasnn.tvremote.cast.ui.IptvSourceScreen(
                sourceId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
                onCast = { items, index ->
                    castLauncher.cast(items, index)
                }
            )
        }
        composable(Screen.Library.route) { entry ->
            com.canasnn.tvremote.cast.ui.LibraryScreen(
                section = com.canasnn.tvremote.cast.ui.LibrarySection.of(entry.arguments?.getString("section")),
                onBack = { navController.popBackStack() },
                onOpenUrl = { url ->
                    // Back into the open browser instead of stacking a new one.
                    if (navController.previousBackStackEntry?.destination?.route == Screen.Browser.route) {
                        navController.popBackStack()
                        com.canasnn.tvremote.cast.browser.BrowserManager.open(url)
                    } else navController.navigate(Screen.Browser.create(url))
                },
                onOpenUrlInNewTab = { url ->
                    com.canasnn.tvremote.cast.browser.BrowserManager.init(context)
                    com.canasnn.tvremote.cast.browser.BrowserManager.newTab(url)
                    if (navController.previousBackStackEntry?.destination?.route == Screen.Browser.route) navController.popBackStack()
                    else navController.navigate(Screen.Browser.create())
                },
                onCast = { items, index ->
                    castLauncher.cast(items, index)
                },
                onOpenPlaylist = { id -> navController.navigate(Screen.Playlist.create(id)) }
            )
        }
        composable(Screen.Playlist.route) { entry ->
            com.canasnn.tvremote.cast.ui.PlaylistScreen(
                playlistId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
                onCast = { items, index ->
                    castLauncher.cast(items, index)
                },
                onOpenUrl = { url -> navController.navigate(Screen.Browser.create(url)) }
            )
        }
        composable(Screen.SubtitleSearch.route) {
            com.canasnn.tvremote.cast.ui.SubtitleScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Screen.CastSettings.route) }
            )
        }
        composable(Screen.CastSettings.route) {
            com.canasnn.tvremote.cast.ui.CastSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenHelp = { navController.navigate(Screen.CastHelp.route) }
            )
        }
        composable(Screen.CastHelp.route) {
            com.canasnn.tvremote.cast.ui.CastHelpScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.RemoteControls.route) {
            RemoteControlSettingsScreen(
                defaultPadMode = defaultPadMode,
                hapticsEnabled = hapticsEnabled,
                keepScreenAwake = keepScreenAwake,
                remoteShelfMode = remoteShelfMode,
                remoteApps = resolvedRemoteApps,
                onBack = { navController.popBackStack() },
                onDefaultPadModeChange = {
                    defaultPadMode = it
                    activePadMode = it
                },
                onHapticsChange = { hapticsEnabled = it },
                onKeepScreenAwakeChange = { keepScreenAwake = it },
                onRemoteShelfModeChange = { remoteShelfMode = it },
                onOpenQuickLaunchOrder = {
                    navController.navigate(Screen.QuickLaunchOrder.route)
                },
                onOpenManageShortcuts = {
                    navController.navigate(Screen.ManageShortcuts.route)
                },
                onOpenCustomizeLayout = {
                    navController.navigate(Screen.CustomizeLayout.route)
                }
            )
        }
        composable(Screen.Appearance.route) {
            AppearanceScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.TipsSupport.route) {
            TipsSupportScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── Quick launch app order ───────────────────────────────────────────
        composable(Screen.QuickLaunchOrder.route) {
            QuickLaunchOrderScreen(
                remoteApps = resolvedRemoteApps,
                allAvailableApps = remember(customShortcuts, tvApps) { getAllKnownShortcutApps(customShortcuts) },
                onBack = { navController.popBackStack() },
                onRemoteAppOrderChange = { newOrder ->
                    remoteAppOrder = newOrder
                }
            )
        }

        // ── Manage custom shortcuts ──────────────────────────────────────────
        composable(Screen.ManageShortcuts.route) {
            ManageShortcutsScreen(
                remoteApps = resolvedRemoteApps,
                onBack = { navController.popBackStack() },
                onRemoteAppOrderChange = { newOrder ->
                    remoteAppOrder = newOrder
                },
                onTestShortcut = tvRemoteViewModel::launchCustomShortcut
            )
        }

        // ── Customize remote layout ──────────────────────────────────────────
        composable(Screen.CustomizeLayout.route) {
            CustomizeLayoutScreen(
                onBack = { navController.popBackStack() }
            )
        }


        // ── Cast player ──────────────────────────────────────────────────────────
        composable(Screen.CastPlayer.route) {
            val receiverSession by com.canasnn.tvremote.cast.CastHub.session.collectAsStateWithLifecycle()
            if (receiverSession != null || tvRemoteViewModel.isReceiverCasting) {
                com.canasnn.tvremote.cast.ui.NowPlayingScreen(
                    deviceName = sessionState.connectedDevice?.name,
                    onBack = { navController.popBackStack() },
                    onAddSubtitles = { navController.navigate(Screen.SubtitleSearch.route) },
                    // Same level the remote's volume rocker shows.
                    tvVolume = sessionState.volumeFraction.takeIf { it > 0f } ?: (((sessionState.volumeLevel ?: 50) / 100f).coerceIn(0f, 1f)),
                    tvMuted = sessionState.isMuted,
                    tvVolumeKnown = sessionState.volumeKnown,
                    onVolumeDown = tvRemoteViewModel::volumeDown,
                    onVolumeUp = tvRemoteViewModel::volumeUp,
                    onMute = { tvRemoteViewModel.sendKey(com.canasnn.tvremote.androidLib.remote.Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_MUTE) }
                )
                return@composable
            }
            val activeMedia = sessionState.cast.activeMedia
            val albumItems by produceState(
                initialValue = emptyList<MediaItemUi>(),
                key1 = activeMedia?.collectionId,
                key2 = activeMedia?.kind
            ) {
                value = if (activeMedia?.kind == MediaKind.Photo && !activeMedia.collectionId.isNullOrBlank()) {
                    queryPhotosInAlbum(context, activeMedia.collectionId.orEmpty()).take(30)
                } else emptyList()
            }
            CastPlayerScreen(
                mediaItem = activeMedia,
                deviceName = sessionState.connectedDevice?.name,
                castState = sessionState.cast,
                volumeFraction = sessionState.volumeFraction,
                isMuted = sessionState.isMuted,
                onBack = {
                    navController.popBackStack()
                },
                onTogglePlayback = tvRemoteViewModel::togglePlayback,
                onSeekTo = tvRemoteViewModel::seekTo,
                onStopCasting = tvRemoteViewModel::stopCasting,
                onVolumeChanged = tvRemoteViewModel::setVolume,
                onToggleMute = {
                    tvRemoteViewModel.sendKey(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_MUTE)
                },
                albumItems = albumItems,
                onCastOther = { photo ->
                    tvRemoteViewModel.castMedia(photo)
                },
            )
        }
    }
    }
    }
    if (showCastBar) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            com.canasnn.tvremote.ui.app.HomeBottomBar(currentTab = HomeTab.Cast, onTabSelected = selectHomeTab)
        }
    }
    }
}

private const val START_REMOTE = "remote"
private const val START_CAST = "cast"
private const val START_BROWSER = "browser"
private const val START_FILES = "files"
private const val START_IPTV = "iptv"
private const val START_RECENT = "recent"
private const val PREF_LAST_START_SCREEN = "last_start_screen"

/** Where the app opens: [START_REMOTE], [START_CAST] or [START_BROWSER] (from the "Start screen" setting). */
private fun startTargetFor(context: Context): String = when (com.canasnn.tvremote.cast.CastHub.settings.value.startScreen) {
    com.ruterika.castcore.settings.StartScreen.REMOTE -> START_REMOTE
    com.ruterika.castcore.settings.StartScreen.CAST -> START_CAST
    com.ruterika.castcore.settings.StartScreen.BROWSER -> START_BROWSER
    com.ruterika.castcore.settings.StartScreen.PHONE_FILES -> START_FILES
    com.ruterika.castcore.settings.StartScreen.IPTV -> START_IPTV
    com.ruterika.castcore.settings.StartScreen.RECENT -> START_RECENT
    com.ruterika.castcore.settings.StartScreen.LAST_USED ->
        context.getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_LAST_START_SCREEN, null)
            ?.takeIf { it == START_CAST || it == START_BROWSER } ?: START_REMOTE
}
