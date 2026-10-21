package com.canasnn.tvremote.ui.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.KeyboardBackspace
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PowerSettingsNew

import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.canasnn.tvremote.androidLib.remote.Remotemessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin


private data class RemoteMediaAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)


@Composable
fun RemoteScreen(
    modifier: Modifier = Modifier,
    activePadMode: RemotePadMode,
    defaultPadMode: RemotePadMode,
    sessionState: TvRemoteUiState,
    remoteShelfMode: RemoteShelfMode,
    quickApps: List<RemoteShortcutApp>,
    hapticsEnabled: Boolean,
    onRequireConnection: () -> Unit,
    onCyclePadMode: () -> Unit,
    onQuickApp: (String) -> Unit,
    onRemoteKey: (Remotemessage.RemoteKeyCode) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onKeyboardText: (String) -> Unit,
    onKeyboardEnter: () -> Unit,
    onToggleVoice: () -> Unit,
) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    fun performHaptic() {
        if (hapticsEnabled) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    val wrappedOnRemoteKey: (Remotemessage.RemoteKeyCode) -> Unit = { key ->
        performHaptic()
        onRemoteKey(key)
    }

    val wrappedOnVolumeUp: () -> Unit = {
        performHaptic()
        onVolumeUp()
    }

    val wrappedOnVolumeDown: () -> Unit = {
        performHaptic()
        onVolumeDown()
    }

    val wrappedOnQuickApp: (String) -> Unit = { appId ->
        performHaptic()
        onQuickApp(appId)
    }

    val wrappedOnCyclePadMode: () -> Unit = {
        performHaptic()
        onCyclePadMode()
    }

    val wrappedOnToggleVoice: () -> Unit = {
        performHaptic()
        onToggleVoice()
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) wrappedOnToggleVoice()
    }

    val isConnected = sessionState.connectedDevice != null
    val mediaActions = remember(onRemoteKey) {
        listOf(
            RemoteMediaAction(
                icon = Icons.Filled.FastRewind,
                contentDescription = "Rewind",
                onClick = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_REWIND) }
            ),
            RemoteMediaAction(
                icon = Icons.Filled.PlayArrow,
                contentDescription = "Play or pause",
                onClick = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE) }
            ),
            RemoteMediaAction(
                icon = Icons.Filled.FastForward,
                contentDescription = "Fast forward",
                onClick = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD) }
            ),
            RemoteMediaAction(
                icon = Icons.Filled.Stop,
                contentDescription = "Stop",
                onClick = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_STOP) }
            )
        )
    }
    var showKeyboardDialog by rememberSaveable { mutableStateOf(false) }
    val primaryPadMode = defaultPadMode.primaryMode()
    val isNumberPadVisible = activePadMode == RemotePadMode.NumberPad

    fun handleKeyboardOpen() {
        if (isConnected) {
            showKeyboardDialog = true
        } else {
            onRequireConnection()
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Tablets and unfolded phones: the remote keeps a phone-like width, centred.
        val contentWidth = minOf(maxWidth, 480.dp)
        val hasTopStrip = remoteShelfMode != RemoteShelfMode.None
        val horizontalPadding = when {
            contentWidth < 360.dp -> 10.dp
            contentWidth < 420.dp -> 14.dp
            else -> 20.dp
        }
        val verticalGap = when {
            maxHeight < 620.dp -> 8.dp
            maxHeight < 760.dp -> 12.dp
            else -> 16.dp
        }
        val shelfButtonSize = when {
            contentWidth < 360.dp -> 52.dp
            contentWidth < 420.dp -> 58.dp
            else -> 64.dp
        }
        val actionIconSize = when {
            contentWidth < 360.dp -> 22.dp
            contentWidth < 420.dp -> 24.dp
            else -> 28.dp
        }
        val controlSpacing = when {
            contentWidth < 360.dp -> 8.dp
            contentWidth < 420.dp -> 10.dp
            else -> 12.dp
        }
        val rockerWidth = when {
            contentWidth < 360.dp -> 56.dp
            contentWidth < 420.dp -> 62.dp
            else -> 68.dp
        }
        val rockerHeight = when {
            maxHeight < 620.dp -> 156.dp
            maxHeight < 760.dp -> 170.dp
            else -> 184.dp
        }
        // Load custom remote layout configuration
        val layoutConfig by remember(context) { RemoteLayoutDataStore.layoutConfigFlow(context) }
            .collectAsStateWithLifecycle(initialValue = RemoteLayoutConfig.default)

        // Height the button deck needs: its rows of square buttons between the two rockers.
        val deckRows = ((layoutConfig.gridButtons.size + 2) / 3).coerceAtLeast(1)
        val bubbleSize = ((contentWidth - horizontalPadding * 2 - rockerWidth * 2 - controlSpacing * 4) / 3)
            .coerceAtLeast(40.dp)
        val deckHeight = bubbleSize * deckRows + controlSpacing * (deckRows - 1) + 8.dp
        val stripHeight = when (remoteShelfMode) {
            RemoteShelfMode.Applications -> maxOf(shelfButtonSize * 0.6f, 40.dp) // buttons are at least 40dp
            RemoteShelfMode.MediaButtons -> shelfButtonSize
            RemoteShelfMode.None -> 0.dp
        }
        val powerHeight = if (RemoteLayoutConfig.SECTION_POWER in layoutConfig.sectionOrder) 54.dp else 0.dp
        val fixedHeight = 20.dp + 12.dp + 8.dp + stripHeight + powerHeight + deckHeight +
            verticalGap * (layoutConfig.sectionOrder.size - 1)
        // The D-pad takes what is left (up to its full size); when even a small D-pad doesn't fit
        // (small phones, landscape) the remote scrolls instead of squeezing the buttons away.
        val padStageHeight = minOf(contentWidth - (horizontalPadding * 2), 352.dp, maxHeight - fixedHeight)
            .coerceAtLeast(MIN_PAD_STAGE)
        val fitsScreen = maxHeight - fixedHeight >= MIN_PAD_STAGE

        Column(
            modifier = Modifier
                .width(contentWidth)
                .then(if (fitsScreen) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(verticalGap)
        ) {
            for (section in layoutConfig.sectionOrder) {
                when (section) {
                    RemoteLayoutConfig.SECTION_POWER -> {
                        PowerRow(
                            onPower = { onRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_POWER) },
                            actionIconSize = actionIconSize
                        )
                    }
                    RemoteLayoutConfig.SECTION_TOP_STRIP -> {
                        when (remoteShelfMode) {
                            RemoteShelfMode.Applications -> AppShortcutStrip(
                                quickApps = quickApps,
                                onQuickApp = wrappedOnQuickApp,
                                shortcutSize = shelfButtonSize
                            )

                            RemoteShelfMode.MediaButtons -> MediaButtonStrip(
                                actions = mediaActions,
                                buttonSize = shelfButtonSize
                            )

                            RemoteShelfMode.None -> Unit
                        }
                    }
                    RemoteLayoutConfig.SECTION_DPAD_STAGE -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        RemotePadStage(
                            activePadMode = activePadMode,
                            stageHeight = padStageHeight,
                            isConnected = isConnected,
                            hapticsEnabled = hapticsEnabled,
                            onAction = onRemoteKey
                        )
//                        RemotePageIndicator(
//                            isSecondarySelected = isNumberPadVisible
//                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    RemoteLayoutConfig.SECTION_CONTROL_DECK -> {

                        Box(
                            modifier = if (fitsScreen) Modifier.weight(1f) else Modifier.height(deckHeight),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            // Tall screens (tablets): the rockers don't stretch past a hand-sized height.
                            Box(modifier = Modifier.heightIn(max = maxOf(deckHeight, 320.dp))) {
                            RemoteControlDeck(
                                isVoiceActive = sessionState.isVoiceActive,
                                isMuted = sessionState.isMuted,
                                volumeLevel = sessionState.volumeLevel,
                                volumeFraction = sessionState.volumeFraction,
                                isNumberPadVisible = isNumberPadVisible,
                                primaryPadMode = primaryPadMode,
                                rockerWidth = rockerWidth,
                                controlSpacing = controlSpacing,
                                actionIconSize = actionIconSize,
                                onKeyboard = ::handleKeyboardOpen,
                                onHome = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_HOME) },
                                onSwitchPad = wrappedOnCyclePadMode,
                                onMute = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_MUTE) },
                                onVoice = {
                                    if (hasMicPermission) {
                                        wrappedOnToggleVoice()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onBack = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_BACK) },
                                onVolumeDown = wrappedOnVolumeDown,
                                onVolumeUp = wrappedOnVolumeUp,
                                onChannelUp = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_CHANNEL_UP) },
                                onChannelDown = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_CHANNEL_DOWN) },
                                onRecentApps = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_APP_SWITCH) },
                                onPlayPause = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE) },
                                onPowerMini = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_POWER) },
                                onMenu = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_MENU) },
                                onPower = { wrappedOnRemoteKey(Remotemessage.RemoteKeyCode.KEYCODE_POWER) },
                                layoutConfig = layoutConfig
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showKeyboardDialog) {
        KeyboardDialog(
            tvText = sessionState.tvText,
            tvTextField = sessionState.tvTextField,
            tvTextRevision = sessionState.tvTextRevision,
            onDismiss = { showKeyboardDialog = false },
            onTextChange = onKeyboardText,
            onEnter = onKeyboardEnter
        )
    }
}

/** Smallest D-pad area before the remote scrolls instead. */
private val MIN_PAD_STAGE = 180.dp

@Composable
private fun AppShortcutStrip(
    quickApps: List<RemoteShortcutApp>,
    onQuickApp: (String) -> Unit,
    shortcutSize: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quickApps.forEach { app ->
            FilledTonalButton(
                onClick = { onQuickApp(app.intentUri ?: app.launchName) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = shortcutSize * 0.6f)
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MediaButtonStrip(
    actions: List<RemoteMediaAction>,
    buttonSize: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            FilledTonalButton(
                onClick = action.onClick,
                modifier = Modifier
                    .weight(1f)
                    .height(buttonSize),
                shape = RoundedCornerShape(20.dp),

                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary
                ),


                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = action.icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = action.contentDescription
                )
            }
        }
    }
}

@Composable
private fun RemotePadStage(
    activePadMode: RemotePadMode,
    stageHeight: Dp,
    isConnected: Boolean,
    hapticsEnabled: Boolean,
    onAction: (Remotemessage.RemoteKeyCode) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(stageHeight),
        contentAlignment = Alignment.Center
    ) {
        val basePadSize = minOf(maxWidth, stageHeight)
        val widePadWidth = minOf(maxWidth, (basePadSize * 1.08f).coerceAtLeast(basePadSize))

        AnimatedContent(targetState = activePadMode, label = "remotePad") { mode ->
            when (mode) {
                RemotePadMode.DPad -> GoogleTvDPad(
                    size = basePadSize,
                    hapticsEnabled = hapticsEnabled,
                    onAction = onAction
                )

                RemotePadMode.Touchpad -> TouchpadPanel(
                    width = widePadWidth,
                    height = basePadSize,
                    isConnected = isConnected,
                    hapticsEnabled = hapticsEnabled,
                    onAction = onAction
                )

                RemotePadMode.NumberPad -> NumberPadPanel(
                    width = widePadWidth,
                    maxHeight = basePadSize,
                    onAction = { key ->
                        if (hapticsEnabled) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onAction(key)
                    }
                )
            }
        }
    }
}

@Composable
private fun RemotePageIndicator(
    isSecondarySelected: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(2) { index ->
            val isSelected = if (index == 0) !isSecondarySelected else isSecondarySelected
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(if (isSelected) 12.dp else 10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                        }
                    )
            )
        }
    }
}

@Composable
private fun RemoteControlDeck(
    isVoiceActive: Boolean,
    isMuted: Boolean = false,
    volumeLevel: Int? = null,
    volumeFraction: Float = 0.5f,
    isNumberPadVisible: Boolean,
    primaryPadMode: RemotePadMode,
    rockerWidth: Dp,
    controlSpacing: Dp,
    actionIconSize: Dp,
    onKeyboard: () -> Unit,
    onHome: () -> Unit,
    onSwitchPad: () -> Unit,
    onMute: () -> Unit,
    onVoice: () -> Unit,
    onBack: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onRecentApps: () -> Unit,
    onPlayPause: () -> Unit,
    onPowerMini: () -> Unit,
    onMenu: () -> Unit,
    onPower: () -> Unit,
    layoutConfig: RemoteLayoutConfig
) {
    val currentFraction = volumeFraction.takeIf { it > 0f }
        ?: (((volumeLevel ?: 50) / 100f).coerceIn(0f, 1f))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(controlSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Render Left Rocker ────────────────────────────────────────────────
        when (layoutConfig.leftRocker) {
            RemoteLayoutConfig.ROCKER_VOLUME -> {
                RemoteVerticalRocker(
                    modifier = Modifier.width(rockerWidth).fillMaxHeight(),
                    label = "VOL",
                    fluidLevel = currentFraction,
                    isMuted = isMuted,
                    topIcon = Icons.Filled.Add,
                    bottomIcon = Icons.Filled.Remove,
                    iconSize = actionIconSize,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onTopClick = onVolumeUp,
                    onBottomClick = onVolumeDown
                )
            }
            RemoteLayoutConfig.ROCKER_CHANNEL -> {
                RemoteVerticalRocker(
                    modifier = Modifier.width(rockerWidth).fillMaxHeight(),
                    label = "CH",
                    topIcon = Icons.Filled.KeyboardArrowUp,
                    bottomIcon = Icons.Filled.KeyboardArrowDown,
                    iconSize = actionIconSize,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onTopClick = onChannelUp,
                    onBottomClick = onChannelDown
                )
            }
        }

        // ── Render Middle button grid ─────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(controlSpacing)
        ) {
            val buttonRows = layoutConfig.gridButtons.chunked(3)
            buttonRows.forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(controlSpacing)
                ) {
                    rowButtons.forEach { btn ->
                        when (btn) {
                            RemoteLayoutConfig.BUTTON_KEYBOARD -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.Keyboard,
                                    contentDescription = "Keyboard",
                                    onClick = onKeyboard,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_HOME -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.Home,
                                    contentDescription = "Home",
                                    onClick = onHome,
                                    iconSize = actionIconSize,
                                    bubbleShape = RoundedCornerShape(18.dp),
                                )
                            }
                            RemoteLayoutConfig.BUTTON_SWITCH_PAD -> {
                                val currentPadMode = if (isNumberPadVisible) RemotePadMode.NumberPad else primaryPadMode
                                RemoteSwitcherBubble(
                                    modifier = Modifier.weight(1f),
                                    activePadMode = currentPadMode,
                                    onClick = onSwitchPad,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_MUTE -> {
                                val muteIcon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = muteIcon,
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    onClick = onMute,
                                    emphasized = isMuted,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_VOICE -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.Mic,
                                    contentDescription = if (isVoiceActive) "Stop voice search" else "Voice search",
                                    onClick = onVoice,
                                    emphasized = isVoiceActive,
                                    iconSize = actionIconSize,
                                )
                            }
                            RemoteLayoutConfig.BUTTON_BACK -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.AutoMirrored.Filled.KeyboardBackspace,
                                    contentDescription = "Back",
                                    onClick = onBack,
                                    iconSize = actionIconSize,
                                    bubbleShape = CircleShape,
                                )
                            }
                            RemoteLayoutConfig.BUTTON_RECENT_APPS -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.Dashboard,
                                    contentDescription = "Recent Apps",
                                    onClick = onRecentApps,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_PLAY_PAUSE -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    onClick = onPlayPause,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_POWER -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.PowerSettingsNew,
                                    contentDescription = "Power",
                                    onClick = onPower,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_MENU -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.Menu,
                                    contentDescription = "Menu",
                                    onClick = onMenu,
                                    iconSize = actionIconSize
                                )
                            }
                            RemoteLayoutConfig.BUTTON_POWER_MINI -> {
                                RemoteActionBubble(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Filled.PowerSettingsNew,
                                    contentDescription = "Power",
                                    onClick = onPowerMini,
                                    iconSize = actionIconSize
                                )
                            }
                        }
                    }
                    if (rowButtons.size < 3) {
                        repeat(3 - rowButtons.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ── Render Right Rocker ───────────────────────────────────────────────
        when (layoutConfig.rightRocker) {
            RemoteLayoutConfig.ROCKER_VOLUME -> {
                RemoteVerticalRocker(
                    modifier = Modifier.width(rockerWidth).fillMaxHeight(),
                    label = "VOL",
                    fluidLevel = currentFraction,
                    isMuted = isMuted,
                    topIcon = Icons.Filled.Add,
                    bottomIcon = Icons.Filled.Remove,
                    iconSize = actionIconSize,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onTopClick = onVolumeUp,
                    onBottomClick = onVolumeDown
                )
            }
            RemoteLayoutConfig.ROCKER_CHANNEL -> {
                RemoteVerticalRocker(
                    modifier = Modifier.width(rockerWidth).fillMaxHeight(),
                    label = "CH",
                    topIcon = Icons.Filled.KeyboardArrowUp,
                    bottomIcon = Icons.Filled.KeyboardArrowDown,
                    iconSize = actionIconSize,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onTopClick = onChannelUp,
                    onBottomClick = onChannelDown
                )
            }
        }
    }
}

@Composable
private fun FluidWaterCanvas(
    modifier: Modifier = Modifier,
    fillFraction: Float,
    primaryColor: Color = MaterialTheme.colorScheme.primaryContainer,
    secondaryColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    isMuted: Boolean = false
) {
    val animatedFill by animateFloatAsState(
        targetValue = if (isMuted) 0f else fillFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 350f),
        label = "waterFillLevel"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "waterWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        if (width <= 0f || height <= 0f) return@Canvas

        val baseWaterY = height * (1f - animatedFill)
        val waveAmplitude = (6.dp.toPx() * (if (animatedFill > 0.05f && animatedFill < 0.95f) 1f else 0.3f))

        // Secondary Wave (Back Layer)
        val wave2Path = Path().apply {
            moveTo(0f, height)
            lineTo(0f, baseWaterY)
            var x = 0f
            while (x <= width) {
                val relativeX = x / width
                val y = baseWaterY + (sin((relativeX * 2f * Math.PI.toFloat()) - phase + 1.2f) * (waveAmplitude * 0.7f))
                lineTo(x, y)
                x += 4f
            }
            lineTo(width, height)
            close()
        }

        // Primary Wave (Front Layer)
        val wave1Path = Path().apply {
            moveTo(0f, height)
            lineTo(0f, baseWaterY)
            var x = 0f
            while (x <= width) {
                val relativeX = x / width
                val y = baseWaterY + (sin((relativeX * 2f * Math.PI.toFloat()) + phase) * waveAmplitude)
                lineTo(x, y)
                x += 4f
            }
            lineTo(width, height)
            close()
        }

        drawPath(
            path = wave2Path,
            brush = Brush.verticalGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = 0.40f),
                    primaryColor.copy(alpha = 0.55f)
                ),
                startY = (baseWaterY - waveAmplitude).coerceAtLeast(0f),
                endY = height
            )
        )

        drawPath(
            path = wave1Path,
            brush = Brush.verticalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.65f),
                    primaryColor.copy(alpha = 0.90f)
                ),
                startY = (baseWaterY - waveAmplitude).coerceAtLeast(0f),
                endY = height
            )
        )
    }
}

@Composable
private fun RemoteVerticalRocker(
    modifier: Modifier = Modifier,
    label: String,
    centerText: String? = null,
    fluidLevel: Float? = null,
    isMuted: Boolean = false,
    topIcon: ImageVector,
    bottomIcon: ImageVector,
    iconSize: Dp,
    contentColor: Color,
    onTopClick: () -> Unit,
    onBottomClick: () -> Unit,
) {
    var topPressed by remember { mutableStateOf(false) }
    var bottomPressed by remember { mutableStateOf(false) }
    val topScale by animateFloatAsState(
        targetValue = if (topPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
        label = "rockerTopScale"
    )
    val bottomScale by animateFloatAsState(
        targetValue = if (bottomPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
        label = "rockerBottomScale"
    )
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(30.dp)
            )
    ) {
        if (fluidLevel != null) {
            FluidWaterCanvas(
                fillFraction = fluidLevel,
                isMuted = isMuted
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .scale(topScale)
                    .clickable {
                        topPressed = true
                        onTopClick()
                        scope.launch {
                            delay(160)
                            topPressed = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = topIcon,
                    contentDescription = "$label up",
                    modifier = Modifier.size(iconSize),
                    tint = if (fluidLevel != null) MaterialTheme.colorScheme.onSurface else contentColor
                )
            }
            if (centerText != null) {
                Text(
                    text = centerText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = contentColor
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .scale(bottomScale)
                    .clickable {
                        bottomPressed = true
                        onBottomClick()
                        scope.launch {
                            delay(160)
                            bottomPressed = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = bottomIcon,
                    contentDescription = "$label down",
                    modifier = Modifier.size(iconSize),
                    tint = if (fluidLevel != null) MaterialTheme.colorScheme.onSurface else contentColor
                )
            }
        }
    }
}


@Composable
private fun RemoteActionBubble(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    iconSize: Dp,
    // Optional overrides — callers use these for shape-contrast and color-hierarchy
    bubbleShape: androidx.compose.ui.graphics.Shape = CircleShape,
    bubbleColor: Color? = null,
    iconTint: Color? = null,
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
        label = "bubblePressScale"
    )

    val resolvedContainerColor = bubbleColor ?: if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val resolvedIconTint = iconTint ?: if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    FilledTonalIconButton(
        modifier = modifier
            .aspectRatio(1f)
            .scale(pressScale),
        onClick = {
            pressed = true
            onClick()
            scope.launch {
                delay(160)
                pressed = false
            }
        },
        shape = bubbleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = resolvedContainerColor
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = resolvedIconTint
        )
    }
}


@Composable
private fun RemoteSwitcherBubble(
    modifier: Modifier = Modifier,
    activePadMode: RemotePadMode,
    onClick: () -> Unit,
    iconSize: Dp,
) {
    // Show the target mode icon (the mode the user will switch TO):
    // - When DPad is showing -> show Touchpad icon
    // - When Touchpad is showing -> show DPad icon
    val targetMode = when (activePadMode) {
        RemotePadMode.DPad -> RemotePadMode.Touchpad
        RemotePadMode.Touchpad -> RemotePadMode.DPad
        else -> RemotePadMode.Touchpad
    }
    val isAlternative = activePadMode == RemotePadMode.Touchpad

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isAlternative) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (isAlternative) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = targetMode.icon,
                contentDescription = "Switch to ${targetMode.label}",
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun KeyboardDialog(
    tvText: String,
    tvTextField: Int,
    tvTextRevision: Int,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onEnter: () -> Unit,
) {
    // Starts with what the TV's text box holds; another box on the TV starts over with its text.
    var value by remember(tvTextRevision) {
        mutableStateOf(TextFieldValue(tvText, selection = TextRange(tvText.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TV Keyboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        val changed = next.text != value.text
                        value = next
                        if (changed) onTextChange(next.text)
                    },
                    placeholder = { Text("Type for your TV") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    trailingIcon = {
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { value = TextFieldValue(""); onTextChange("") }) {
                                Icon(Icons.Filled.Clear, "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onEnter() })
                )
                if (tvTextField < 0) {
                    Text(
                        "Select a text box on the TV, then type here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onEnter) { Text("Enter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun GoogleTvDPad(
    size: Dp,
    hapticsEnabled: Boolean,
    onAction: (Remotemessage.RemoteKeyCode) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    var activeZone by remember { mutableStateOf<DPadZone?>(null) }
    val padScale by animateFloatAsState(
        targetValue = if (activeZone != null) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "dpadScale"
    )
    val separatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val edgeOffset = if (size < 260.dp) 20.dp else 28.dp
    val sideOffset = if (size < 260.dp) 18.dp else 26.dp
    val edgeBubbleSize = if (size < 260.dp) 30.dp else 34.dp

    fun activate(zone: DPadZone, key: Remotemessage.RemoteKeyCode) {
        activeZone = zone
        if (hapticsEnabled) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
        onAction(key)
        scope.launch {
            delay(160)
            if (activeZone == zone) {
                activeZone = null
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .scale(padScale),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.matchParentSize(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.colorScheme.surfaceContainer
                                )
                            )
                        )
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val outerRadius = size.value / 2f
                        val innerRadius = outerRadius * 0.40f
                        listOf(45f, 135f, 225f, 315f).forEach { angle ->
                            val radians = Math.toRadians(angle.toDouble()).toFloat()
                            val start = Offset(
                                x = center.x + cos(radians) * innerRadius,
                                y = center.y + sin(radians) * innerRadius
                            )
                            val end = Offset(
                                x = center.x + cos(radians) * outerRadius,
                                y = center.y + sin(radians) * outerRadius
                            )
                            drawLine(
                                color = separatorColor,
                                start = start,
                                end = end,
                                strokeWidth = 3f
                            )
                        }
                    }

                    DirectionalZone(
                        modifier = Modifier.matchParentSize(),
                        zone = DPadZone.Up,
                        activeZone = activeZone,
                        icon = {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up")
                        },
                        iconAlignment = Alignment.TopCenter,
                        iconOffsetY = edgeOffset,
                        bubbleSize = edgeBubbleSize,
                        onClick = {
                            activate(
                                DPadZone.Up,
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_UP
                            )
                        }
                    )
                    DirectionalZone(
                        modifier = Modifier.matchParentSize(),
                        zone = DPadZone.Right,
                        activeZone = activeZone,
                        icon = {
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = "Right"
                            )
                        },
                        iconAlignment = Alignment.CenterEnd,
                        iconOffsetX = sideOffset * -1f,
                        bubbleSize = edgeBubbleSize,
                        onClick = {
                            activate(
                                DPadZone.Right,
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT
                            )
                        }
                    )
                    DirectionalZone(
                        modifier = Modifier.matchParentSize(),
                        zone = DPadZone.Down,
                        activeZone = activeZone,
                        icon = {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down")
                        },
                        iconAlignment = Alignment.BottomCenter,
                        iconOffsetY = edgeOffset * -1f,
                        bubbleSize = edgeBubbleSize,
                        onClick = {
                            activate(
                                DPadZone.Down,
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN
                            )
                        }
                    )
                    DirectionalZone(
                        modifier = Modifier.matchParentSize(),
                        zone = DPadZone.Left,
                        activeZone = activeZone,
                        icon = {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Left")
                        },
                        iconAlignment = Alignment.CenterStart,
                        iconOffsetX = sideOffset,
                        bubbleSize = edgeBubbleSize,
                        onClick = {
                            activate(
                                DPadZone.Left,
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT
                            )
                        }
                    )
                }
            }

            val centerPressed = activeZone == DPadZone.Center
            val centerScale by animateFloatAsState(
                targetValue = if (centerPressed) 0.96f else 1f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f),
                label = "centerScale"
            )
            Surface(
                modifier = Modifier
                    .size(size * 0.41f)
                    .scale(centerScale)
                    .clip(CircleShape)
                    .clickable {
                        activate(DPadZone.Center, Remotemessage.RemoteKeyCode.KEYCODE_DPAD_CENTER)
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 18.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(

                            color = MaterialTheme.colorScheme.outlineVariant
//                                    Brush.radialGradient(
//                                listOf(
//                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
//                                    MaterialTheme.colorScheme.surface
//                                )
//                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionalZone(
    modifier: Modifier = Modifier,
    zone: DPadZone,
    activeZone: DPadZone?,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    iconAlignment: Alignment,
    iconOffsetX: Dp = 0.dp,
    iconOffsetY: Dp = 0.dp,
    bubbleSize: Dp = 34.dp,
) {
    val isActive = activeZone == zone
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "${zone.name}Overlay"
    )
    val zoneScale by animateFloatAsState(
        targetValue = if (isActive) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "${zone.name}Scale"
    )

    Box(
        modifier = modifier
            .scale(zoneScale)
            .clip(zone.shape)
            .background(
                Brush.radialGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * overlayAlpha),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f * overlayAlpha),
                        Color.Transparent
                    )
                )
            )
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.04f * overlayAlpha),
                    zone.shape
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = iconOffsetX, y = iconOffsetY),
            contentAlignment = iconAlignment
        ) {
            Box(
                modifier = Modifier
                    .size(bubbleSize)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f + (0.14f * overlayAlpha)),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
    }
}

private enum class DPadZone(val startAngle: Float) {
    Up(225f),
    Right(315f),
    Down(45f),
    Left(135f),
    Center(0f);

    val shape
        get() = directionalSectorShape(startAngle)
}

private fun directionalSectorShape(
    startAngle: Float,
    sweepAngle: Float = 90f,
    innerRadiusFraction: Float = 0.40f,
) = GenericShape { size, _ ->
    val path = Path()
    val outerRadius = size.minDimension / 2f
    val innerRadius = outerRadius * innerRadiusFraction
    val center = Offset(size.width / 2f, size.height / 2f)
    val outerRect = Rect(Offset.Zero, size)
    val innerRect = Rect(
        left = center.x - innerRadius,
        top = center.y - innerRadius,
        right = center.x + innerRadius,
        bottom = center.y + innerRadius
    )

    fun pointOnCircle(radius: Float, angleDegrees: Float): Offset {
        val radians = Math.toRadians(angleDegrees.toDouble()).toFloat()
        return Offset(
            x = center.x + cos(radians) * radius,
            y = center.y + sin(radians) * radius
        )
    }

    val startPoint = pointOnCircle(outerRadius, startAngle)
    path.moveTo(startPoint.x, startPoint.y)
    path.arcTo(outerRect, startAngle, sweepAngle, false)
    path.arcTo(innerRect, startAngle + sweepAngle, -sweepAngle, false)
    path.close()
    addPath(path)
}

// ── Continuous hold-swipe tuning constants (exposed for easy adjustments) ────
private object TouchpadConfig {
    const val LOCK_THRESHOLD_PX = 25f        // px before direction is committed
    const val NAVIGATION_STEP_PX = 80f       // px of drag = 1 key press
    const val INITIAL_REPEAT_DELAY_MS = 350L  // delay before repeat starts
    const val REPEAT_INTERVAL_MS = 160L      // smooth repeat interval
}

@Composable
private fun TouchpadPanel(
    width: Dp,
    height: Dp,
    isConnected: Boolean,
    hapticsEnabled: Boolean,
    onAction: (Remotemessage.RemoteKeyCode) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var isTouchActive by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val dotScale by animateFloatAsState(
        targetValue = if (isTouchActive) 1.15f else 1f,
        label = "touchpadDot"
    )

    // Remember continuous swipe repeat job at composable level to cancel it on connection loss
    var repeatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Cancel running hold-swipe coroutine when connection is lost
    LaunchedEffect(isConnected) {
        if (!isConnected) {
            repeatJob?.cancel()
            repeatJob = null
            isTouchActive = false
            dragOffset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(34.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.18f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(34.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isTouchActive = true
                        tryAwaitRelease()
                        dragOffset = Offset.Zero
                        isTouchActive = false
                    },
                    onTap = {
                        if (hapticsEnabled) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onAction(Remotemessage.RemoteKeyCode.KEYCODE_DPAD_CENTER)
                    }
                )
            }
            .pointerInput(isConnected) {
                var lockedDirection: Remotemessage.RemoteKeyCode? = null
                var tempDrag = Offset.Zero
                var totalDrag = Offset.Zero

                fun fireKey(direction: Remotemessage.RemoteKeyCode) {
                    if (isConnected) {
                        if (hapticsEnabled) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onAction(direction)
                    }
                }

                fun startRepeatCoroutine(direction: Remotemessage.RemoteKeyCode) {
                    repeatJob?.cancel()
                    repeatJob = coroutineScope.launch {
                        delay(TouchpadConfig.INITIAL_REPEAT_DELAY_MS)
                        while (true) {
                            fireKey(direction)
                            delay(TouchpadConfig.REPEAT_INTERVAL_MS)
                        }
                    }
                }

                detectDragGestures(
                    onDragStart = {
                        if (!isConnected) return@detectDragGestures
                        lockedDirection = null
                        tempDrag = Offset.Zero
                        totalDrag = Offset.Zero
                        isTouchActive = true
                        dragOffset = Offset.Zero
                        repeatJob?.cancel()
                        repeatJob = null
                    },
                    onDragCancel = {
                        repeatJob?.cancel()
                        repeatJob = null
                        lockedDirection = null
                        tempDrag = Offset.Zero
                        dragOffset = Offset.Zero
                        isTouchActive = false
                    },
                    onDragEnd = {
                        repeatJob?.cancel()
                        repeatJob = null
                        // If no direction was locked (micro-tap), treat as center click
                        if (lockedDirection == null && kotlin.math.abs(totalDrag.x) < TouchpadConfig.LOCK_THRESHOLD_PX && kotlin.math.abs(totalDrag.y) < TouchpadConfig.LOCK_THRESHOLD_PX) {
                            fireKey(Remotemessage.RemoteKeyCode.KEYCODE_DPAD_CENTER)
                        }
                        lockedDirection = null
                        tempDrag = Offset.Zero
                        dragOffset = Offset.Zero
                        isTouchActive = false
                    }
                ) { change, dragAmount ->
                    if (!isConnected) {
                        change.consume()
                        return@detectDragGestures
                    }
                    change.consume()
                    dragOffset += dragAmount
                    totalDrag += dragAmount
                    tempDrag += dragAmount

                    val ax = kotlin.math.abs(tempDrag.x)
                    val ay = kotlin.math.abs(tempDrag.y)

                    if (lockedDirection == null) {
                        // ── Lock initial direction ────────────────────────────────
                        if (ax >= TouchpadConfig.LOCK_THRESHOLD_PX || ay >= TouchpadConfig.LOCK_THRESHOLD_PX) {
                            val dir = when {
                                ax >= ay && tempDrag.x > 0 -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT
                                ax >= ay && tempDrag.x < 0 -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT
                                tempDrag.y > 0            -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN
                                else                        -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_UP
                            }
                            lockedDirection = dir
                            tempDrag = Offset.Zero
                            fireKey(dir)
                            startRepeatCoroutine(dir)
                        }
                    } else {
                        // ── Check for direction switch while holding ──────────────
                        val currentDir = lockedDirection!!
                        val isOppositeOrDifferent = when (currentDir) {
                            Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT -> tempDrag.x < 0 && ax >= ay
                            Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT  -> tempDrag.x > 0 && ax >= ay
                            Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN  -> tempDrag.y < 0 && ay > ax
                            else                                            -> tempDrag.y > 0 && ay > ax
                        } || (ax >= TouchpadConfig.LOCK_THRESHOLD_PX && ax >= ay && (currentDir == Remotemessage.RemoteKeyCode.KEYCODE_DPAD_UP || currentDir == Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN))
                           || (ay >= TouchpadConfig.LOCK_THRESHOLD_PX && ay > ax && (currentDir == Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT || currentDir == Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT))

                        if (isOppositeOrDifferent) {
                            if (ax >= TouchpadConfig.LOCK_THRESHOLD_PX || ay >= TouchpadConfig.LOCK_THRESHOLD_PX) {
                                val newDir = when {
                                    ax >= ay && tempDrag.x > 0 -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT
                                    ax >= ay && tempDrag.x < 0 -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT
                                    tempDrag.y > 0            -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN
                                    else                        -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_UP
                                }
                                if (newDir != currentDir) {
                                    lockedDirection = newDir
                                    tempDrag = Offset.Zero
                                    fireKey(newDir)
                                    startRepeatCoroutine(newDir)
                                }
                            }
                        } else {
                            // If they are moving further in the same direction, keep sensitivity high by zeroing perpendicular drift
                            val isSameDirection = when (currentDir) {
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT -> tempDrag.x > 0
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT  -> tempDrag.x < 0
                                Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN  -> tempDrag.y > 0
                                else                                            -> tempDrag.y < 0
                            }
                            if (isSameDirection) {
                                tempDrag = when (currentDir) {
                                    Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT,
                                    Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT  -> Offset(tempDrag.x, 0f)
                                    else -> Offset(0f, tempDrag.y)
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(10.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(28.dp)
                )
        )
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (dragOffset.x / 5f).roundToInt(),
                        y = (dragOffset.y / 5f).roundToInt()
                    )
                }
                .scale(dotScale)
                .size(if (isTouchActive) 25.dp else 20.dp)
                .zIndex(0f)
                .background(
                    color = if (isTouchActive) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                    },
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun NumberPadPanel(
    width: Dp,
    maxHeight: Dp,
    onAction: (Remotemessage.RemoteKeyCode) -> Unit,
) {
    val keys = remember {
        listOf(
            "1" to Remotemessage.RemoteKeyCode.KEYCODE_1,
            "2" to Remotemessage.RemoteKeyCode.KEYCODE_2,
            "3" to Remotemessage.RemoteKeyCode.KEYCODE_3,
            "4" to Remotemessage.RemoteKeyCode.KEYCODE_4,
            "5" to Remotemessage.RemoteKeyCode.KEYCODE_5,
            "6" to Remotemessage.RemoteKeyCode.KEYCODE_6,
            "7" to Remotemessage.RemoteKeyCode.KEYCODE_7,
            "8" to Remotemessage.RemoteKeyCode.KEYCODE_8,
            "9" to Remotemessage.RemoteKeyCode.KEYCODE_9,
            "*" to Remotemessage.RemoteKeyCode.KEYCODE_STAR,
            "0" to Remotemessage.RemoteKeyCode.KEYCODE_0,
            "#" to Remotemessage.RemoteKeyCode.KEYCODE_POUND
        )
    }

    val gridSpacing = if (width < 260.dp || maxHeight < 300.dp) 8.dp else 12.dp
    // Four rows must fit the D-pad area, which is smaller on small screens.
    val rowRoom = (maxHeight - 24.dp - gridSpacing * 3) / 4
    val buttonPadding = minOf(if (width < 260.dp) 12.dp else 16.dp, ((rowRoom - 36.dp) / 2).coerceAtLeast(2.dp))

    Column(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(28.dp))
            .background(
                
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.16f)
                    )
                )
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(gridSpacing)
    ) {
        keys.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridSpacing)
            ) {
                row.forEach { (label, keyCode) ->
                    FilledTonalButton(
                        onClick = { onAction(keyCode) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = PaddingValues(vertical = buttonPadding)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerRow(
    onPower: () -> Unit,
    actionIconSize: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        FilledTonalIconButton(
            onClick = onPower,
            modifier = Modifier.size(54.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "Power",
                modifier = Modifier.size(actionIconSize * 1.1f)
            )
        }
    }
}
