package com.canasnn.tvremote.ui.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.canasnn.tvremote.navigation.Screen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress

// ─────────────────────────────────────────────────────────────────────────────
// Drag state holder for sections
// ─────────────────────────────────────────────────────────────────────────────
private class SectionDragState(
    val items: SnapshotStateList<String>
) {
    var draggingId by mutableStateOf<String?>(null)
    var dragOffsetPx by mutableFloatStateOf(0f)
    var itemHeightPx by mutableFloatStateOf(0f)

    val isDragging get() = draggingId != null

    fun onDragStart(id: String) {
        draggingId = id
        dragOffsetPx = 0f
    }

    fun onDrag(deltaY: Float) {
        if (draggingId == null) return
        dragOffsetPx += deltaY

        val step = itemHeightPx.takeIf { it > 0f } ?: return
        var idx = items.indexOf(draggingId).takeIf { it >= 0 } ?: return

        // Swap DOWN
        while (dragOffsetPx > step / 2f && idx < items.lastIndex) {
            val moved = items.removeAt(idx)
            items.add(idx + 1, moved)
            dragOffsetPx -= step
            idx++
        }
        // Swap UP
        while (dragOffsetPx < -step / 2f && idx > 0) {
            val moved = items.removeAt(idx)
            items.add(idx - 1, moved)
            dragOffsetPx += step
            idx--
        }
    }

    fun onDragEnd() {
        draggingId = null
        dragOffsetPx = 0f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Drag state holder for buttons
// ─────────────────────────────────────────────────────────────────────────────
private class ButtonDragState(
    val items: SnapshotStateList<String>
) {
    var draggingId by mutableStateOf<String?>(null)
    var dragOffsetPx by mutableFloatStateOf(0f)
    var itemHeightPx by mutableFloatStateOf(0f)

    val isDragging get() = draggingId != null

    fun onDragStart(id: String) {
        draggingId = id
        dragOffsetPx = 0f
    }

    fun onDrag(deltaY: Float) {
        if (draggingId == null) return
        dragOffsetPx += deltaY

        val step = itemHeightPx.takeIf { it > 0f } ?: return
        var idx = items.indexOf(draggingId).takeIf { it >= 0 } ?: return

        // Swap DOWN
        while (dragOffsetPx > step / 2f && idx < items.lastIndex) {
            val moved = items.removeAt(idx)
            items.add(idx + 1, moved)
            dragOffsetPx -= step
            idx++
        }
        // Swap UP
        while (dragOffsetPx < -step / 2f && idx > 0) {
            val moved = items.removeAt(idx)
            items.add(idx - 1, moved)
            dragOffsetPx += step
            idx--
        }
    }

    fun onDragEnd() {
        draggingId = null
        dragOffsetPx = 0f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Customization Screen
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeLayoutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    // Load persisted layout configuration
    val savedConfig by RemoteLayoutDataStore.layoutConfigFlow(context)
        .collectAsStateWithLifecycle(initialValue = RemoteLayoutConfig.default)

    // Layout configuration editor state
    val workingSections = remember { mutableStateListOf<String>() }
    val workingButtons = remember { mutableStateListOf<String>() }
    var leftRocker by remember { mutableStateOf(RemoteLayoutConfig.ROCKER_VOLUME) }
    var rightRocker by remember { mutableStateOf(RemoteLayoutConfig.ROCKER_CHANNEL) }

    // Synchronize working state with loaded configuration
    LaunchedEffect(savedConfig) {
        workingSections.clear()
        workingSections.addAll(savedConfig.sectionOrder)
        workingButtons.clear()
        workingButtons.addAll(savedConfig.gridButtons)
        leftRocker = savedConfig.leftRocker
        rightRocker = savedConfig.rightRocker
    }

    val sectionDragState = remember(workingSections) { SectionDragState(workingSections) }
    val buttonDragState = remember(workingButtons) { ButtonDragState(workingButtons) }

    val listState = rememberLazyListState()

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SettingsTopBar(
                    title = "Customize Layout",
                    onBack = onBack,
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                // Reset to default layout
                                scope.launch {
                                    RemoteLayoutDataStore.resetToDefault(context)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset")
                        }
                        Button(
                            onClick = {
                                // Save customized layout
                                scope.launch {
                                    val newConfig = RemoteLayoutConfig(
                                        sectionOrder = workingSections.toList(),
                                        leftRocker = leftRocker,
                                        rightRocker = rightRocker,
                                        gridButtons = workingButtons.toList()
                                    )
                                    RemoteLayoutDataStore.saveLayoutConfig(context, newConfig)
                                    onBack()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 100.dp) // space for bottom save bar
            ) {
                // ── Section 1: Live Layout Preview ───────────────────────────
                item {
                    SettingSubtitle(text = "Live Layout Preview")
                }
                item {
                    RemotePreviewCard(
                        sectionOrder = workingSections,
                        leftRocker = leftRocker,
                        rightRocker = rightRocker,
                        gridButtons = workingButtons
                    )
                }

                // ── Section 2: Component Arrangement ─────────────────────────
                item {
                    SettingSubtitle(text = "Section Order (Long-press ≡ to drag)")
                }
                itemsIndexed(
                    items = workingSections,
                    key = { _, sec -> "sec_$sec" }
                ) { index, sec ->
                    DraggableSectionRow(
                        section = sec,
                        index = index,
                        dragState = sectionDragState
                    )
                }

                // ── Section 3: Control Deck Side-Rockers ────────────────────
                item {
                    SettingSubtitle(text = "Control Deck Side-Rockers")
                }
                item {
                    RockerConfigurationCard(
                        leftRocker = leftRocker,
                        rightRocker = rightRocker,
                        onLeftChange = { leftRocker = it },
                        onRightChange = { rightRocker = it }
                    )
                }

                // ── Section 4: Grid Button Layout ────────────────────────────
                item {
                    SettingSubtitle(text = "Grid Button Order (Long-press ≡ to drag)")
                }
                itemsIndexed(
                    items = workingButtons,
                    key = { _, btn -> "btn_$btn" }
                ) { index, btn ->
                    DraggableButtonRow(
                        buttonId = btn,
                        index = index,
                        dragState = buttonDragState,
                        allButtons = workingButtons
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Real-time remote layout preview
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RemotePreviewCard(
    sectionOrder: List<String>,
    leftRocker: String,
    rightRocker: String,
    gridButtons: List<String>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Mini TV remote boundary mockup
            Column(
                modifier = Modifier
                    .width(180.dp)
                    .border(
                        1.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(20.dp)
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                sectionOrder.forEach { section ->
                    when (section) {
                        RemoteLayoutConfig.SECTION_POWER -> {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.PowerSettingsNew, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        RemoteLayoutConfig.SECTION_TOP_STRIP -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(14.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                RoundedCornerShape(4.dp)
                                            )
                                    )
                                }
                            }
                        }
                        RemoteLayoutConfig.SECTION_DPAD_STAGE -> {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                        RemoteLayoutConfig.SECTION_CONTROL_DECK -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left Rocker
                                if (leftRocker != RemoteLayoutConfig.ROCKER_NONE) {
                                    Box(
                                        modifier = Modifier
                                            .width(18.dp)
                                            .height(48.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (leftRocker == RemoteLayoutConfig.ROCKER_VOLUME) "V" else "C",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                // Middle button grid
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val buttonRows = gridButtons.chunked(3)
                                    buttonRows.forEach { rowButtons ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            rowButtons.forEach { btn ->
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(18.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceContainerHighest,
                                                            CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = getButtonIcon(btn),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(10.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Right Rocker
                                if (rightRocker != RemoteLayoutConfig.ROCKER_NONE) {
                                    Box(
                                        modifier = Modifier
                                            .width(18.dp)
                                            .height(48.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (rightRocker == RemoteLayoutConfig.ROCKER_VOLUME) "V" else "C",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Draggable item for Sections
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DraggableSectionRow(
    section: String,
    index: Int,
    dragState: SectionDragState
) {
    val isDragging = dragState.draggingId == section
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val offsetDp by animateDpAsState(
        targetValue = if (isDragging) with(density) { dragState.dragOffsetPx.toDp() } else 0.dp,
        animationSpec = spring(stiffness = if (isDragging) 600f else 1800f),
        label = "sectionOffset"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        label = "sectionScale"
    )

    val textColor = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val detailColor = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val dragHandleTint = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .onSizeChanged { size ->
                if (size.height > 0 && dragState.itemHeightPx == 0f) {
                    dragState.itemHeightPx = size.height.toFloat()
                }
            }
            .offset(y = offsetDp)
            .zIndex(if (isDragging) 2f else 0f)
            .shadow(
                elevation = if (isDragging) 8.dp else 1.dp,
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (section) {
                    RemoteLayoutConfig.SECTION_POWER -> "Power Bar"
                    RemoteLayoutConfig.SECTION_TOP_STRIP -> "App/Media Shortcut Strip"
                    RemoteLayoutConfig.SECTION_DPAD_STAGE -> "D-pad / Touchpad Area"
                    RemoteLayoutConfig.SECTION_CONTROL_DECK -> "Control Buttons Deck"
                    else -> section
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = when (section) {
                    RemoteLayoutConfig.SECTION_POWER -> "Standard power command and indicators"
                    RemoteLayoutConfig.SECTION_TOP_STRIP -> "Quick apps list or play control strip"
                    RemoteLayoutConfig.SECTION_DPAD_STAGE -> "Swipe pad, classic D-pad, or Number pad"
                    RemoteLayoutConfig.SECTION_CONTROL_DECK -> "Volume, Channel, and custom grid buttons"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = detailColor
            )
        }

        // Drag Handle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDragging)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
                .pointerInput(section) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragState.onDragStart(section)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragState.onDrag(dragAmount.y)
                        },
                        onDragEnd = { dragState.onDragEnd() },
                        onDragCancel = { dragState.onDragEnd() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                tint = dragHandleTint
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rocker Configuration Selector Card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RockerConfigurationCard(
    leftRocker: String,
    rightRocker: String,
    onLeftChange: (String) -> Unit,
    onRightChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Left Rocker Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Left Side Option",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        RemoteLayoutConfig.ROCKER_VOLUME to "Volume",
                        RemoteLayoutConfig.ROCKER_CHANNEL to "Channel",
                        RemoteLayoutConfig.ROCKER_NONE to "None"
                    ).forEach { (type, label) ->
                        val selected = leftRocker == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { onLeftChange(type) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Right Rocker Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Right Side Option",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        RemoteLayoutConfig.ROCKER_VOLUME to "Volume",
                        RemoteLayoutConfig.ROCKER_CHANNEL to "Channel",
                        RemoteLayoutConfig.ROCKER_NONE to "None"
                    ).forEach { (type, label) ->
                        val selected = rightRocker == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { onRightChange(type) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Draggable item for Grid Buttons
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DraggableButtonRow(
    buttonId: String,
    index: Int,
    dragState: ButtonDragState,
    allButtons: List<String>
) {
    val isDragging = dragState.draggingId == buttonId
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val offsetDp by animateDpAsState(
        targetValue = if (isDragging) with(density) { dragState.dragOffsetPx.toDp() } else 0.dp,
        animationSpec = spring(stiffness = if (isDragging) 600f else 1800f),
        label = "btnOffset"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        label = "btnScale"
    )

    val textColor = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val detailColor = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconTint = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    val dragHandleTint = if (isDragging) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .onSizeChanged { size ->
                if (size.height > 0 && dragState.itemHeightPx == 0f) {
                    dragState.itemHeightPx = size.height.toFloat()
                }
            }
            .offset(y = offsetDp)
            .zIndex(if (isDragging) 2f else 0f)
            .shadow(
                elevation = if (isDragging) 8.dp else 1.dp,
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = getButtonIcon(buttonId),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = getButtonLabel(buttonId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
            Text(
                text = "Position: Row ${(index / 3) + 1}, Col ${(index % 3) + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = detailColor
            )
        }

        // Drag handle
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDragging)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
                .pointerInput(buttonId) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragState.onDragStart(buttonId)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragState.onDrag(dragAmount.y)
                        },
                        onDragEnd = { dragState.onDragEnd() },
                        onDragCancel = { dragState.onDragEnd() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = Modifier.size(18.dp),
                tint = dragHandleTint
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Flow Row Selector for Inactive Buttons
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowButtonSelector(
    buttons: List<String>,
    onAdd: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        buttons.forEach { btn ->
            InputChip(
                selected = false,
                onClick = { onAdd(btn) },
                label = { Text(getButtonLabel(btn)) },
                leadingIcon = {
                    Icon(imageVector = getButtonIcon(btn), contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Helper Functions for Icons and Labels
// ─────────────────────────────────────────────────────────────────────────────
private fun getButtonIcon(id: String): ImageVector = when (id) {
    RemoteLayoutConfig.BUTTON_KEYBOARD -> Icons.Filled.Keyboard
    RemoteLayoutConfig.BUTTON_HOME -> Icons.Filled.Home
    RemoteLayoutConfig.BUTTON_SWITCH_PAD -> Icons.Filled.Apps
    RemoteLayoutConfig.BUTTON_MUTE -> Icons.Filled.VolumeMute
    RemoteLayoutConfig.BUTTON_VOICE -> Icons.Filled.Mic
    RemoteLayoutConfig.BUTTON_BACK -> Icons.Filled.ArrowBack
    RemoteLayoutConfig.BUTTON_RECENT_APPS -> Icons.Filled.Dashboard
    RemoteLayoutConfig.BUTTON_PLAY_PAUSE -> Icons.Filled.PlayArrow
    RemoteLayoutConfig.BUTTON_POWER -> Icons.Filled.PowerSettingsNew
    RemoteLayoutConfig.BUTTON_MENU -> Icons.Filled.Menu
    RemoteLayoutConfig.BUTTON_POWER_MINI -> Icons.Filled.PowerSettingsNew
    else -> Icons.Filled.QuestionMark
}

private fun getButtonLabel(id: String): String = when (id) {
    RemoteLayoutConfig.BUTTON_KEYBOARD -> "Keyboard"
    RemoteLayoutConfig.BUTTON_HOME -> "Home"
    RemoteLayoutConfig.BUTTON_SWITCH_PAD -> "Switch Pad"
    RemoteLayoutConfig.BUTTON_MUTE -> "Mute"
    RemoteLayoutConfig.BUTTON_VOICE -> "Voice Input"
    RemoteLayoutConfig.BUTTON_BACK -> "Back"
    RemoteLayoutConfig.BUTTON_RECENT_APPS -> "Recent Apps"
    RemoteLayoutConfig.BUTTON_PLAY_PAUSE -> "Play/Pause"
    RemoteLayoutConfig.BUTTON_POWER -> "Power"
    RemoteLayoutConfig.BUTTON_MENU -> "Menu"
    RemoteLayoutConfig.BUTTON_POWER_MINI -> "Mini Power"
    else -> id
}
