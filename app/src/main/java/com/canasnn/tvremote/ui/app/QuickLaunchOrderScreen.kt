package com.canasnn.tvremote.ui.app

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

// ─────────────────────────────────────────────────────────────────────────────
// Drag state holder — fast in-memory swaps without triggering disk I/O per frame
// ─────────────────────────────────────────────────────────────────────────────
private class DragDropState(
    val items: SnapshotStateList<RemoteShortcutApp>
) {
    var draggingId by mutableStateOf<String?>(null)
    var dragOffsetPx by mutableFloatStateOf(0f)
    var itemHeightPx by mutableFloatStateOf(0f)

    val isDragging get() = draggingId != null

    fun onDragStart(id: String) {
        draggingId = id
        dragOffsetPx = 0f
    }

    fun onDrag(deltaY: Float, onSwap: () -> Unit) {
        if (draggingId == null) return
        dragOffsetPx += deltaY

        val step = if (itemHeightPx > 0f) itemHeightPx else 160f
        var idx = items.indexOfFirst { it.id == draggingId }.takeIf { it >= 0 } ?: return

        // Swap DOWN
        while (dragOffsetPx > step * 0.5f && idx < items.lastIndex) {
            val moved = items.removeAt(idx)
            items.add(idx + 1, moved)
            dragOffsetPx -= step
            idx++
            onSwap()
        }
        // Swap UP
        while (dragOffsetPx < -step * 0.5f && idx > 0) {
            val moved = items.removeAt(idx)
            items.add(idx - 1, moved)
            dragOffsetPx += step
            idx--
            onSwap()
        }
    }

    fun onDragEnd() {
        draggingId = null
        dragOffsetPx = 0f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dedicated Screen: QuickLaunchOrderScreen
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLaunchOrderScreen(
    remoteApps: List<RemoteShortcutApp>,
    allAvailableApps: List<RemoteShortcutApp> = emptyList(),
    onBack: () -> Unit,
    onRemoteAppOrderChange: (List<String>) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    // Maintain local list independently so parent recompositions never break active dragging
    val workingApps = remember { remoteApps.toMutableStateList() }
    val dragState = remember(workingApps) { DragDropState(workingApps) }
    val listState = rememberLazyListState()

    // Sync only when NOT actively dragging
    LaunchedEffect(remoteApps) {
        if (!dragState.isDragging && workingApps.map { it.id } != remoteApps.map { it.id }) {
            workingApps.clear()
            workingApps.addAll(remoteApps)
        }
    }

    // Apps that are currently not in workingApps (user removed them from the strip)
    val hiddenApps = remember(workingApps.toList(), allAvailableApps) {
        val workingIds = workingApps.map { it.id }.toSet()
        allAvailableApps.filter { it.id !in workingIds }
    }

    AppBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SettingsTopBar(
                    title = "Quick-Launch Order",
                    onBack = onBack,
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                val defaultIds = defaultRemoteShortcutOrder()
                                val customApps = allAvailableApps.filter { it.isCustom }
                                val newOrder = defaultIds + customApps.map { it.id }
                                workingApps.clear()
                                val resetList = resolveRemoteShortcutApps(newOrder, emptyList())
                                workingApps.addAll(resetList)
                                onRemoteAppOrderChange(workingApps.map { it.id })
                            }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Defaults")
                        }

                        Button(
                            onClick = onBack,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Done")
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = !dragState.isDragging
            ) {
                // ── Info banner ──────────────────────────────────────────────
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Arrange Application Strip",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Hold & drag the ≡ handle to arrange apps. Items at the top appear first on the remote screen.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                // ── Empty State ──────────────────────────────────────────────
                if (workingApps.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = "No apps in Quick-Launch",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Add apps below or reset defaults to restore the strip.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    // ── Active Draggable List ────────────────────────────────
                    itemsIndexed(
                        items = workingApps,
                        key = { _, app -> app.id }
                    ) { index, app ->
                        QuickLaunchOrderItem(
                            app = app,
                            index = index,
                            dragState = dragState,
                            onDelete = {
                                workingApps.remove(app)
                                onRemoteAppOrderChange(workingApps.map { it.id })
                            },
                            onDragComplete = {
                                onRemoteAppOrderChange(workingApps.map { it.id })
                            }
                        )
                    }
                }

                // ── Available to add section ─────────────────────────────────
                if (hiddenApps.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Available to Add",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }

                    itemsIndexed(
                        items = hiddenApps,
                        key = { _, app -> "hidden_${app.id}" }
                    ) { _, app ->
                        HiddenAppItem(
                            app = app,
                            onAdd = {
                                workingApps.add(app)
                                onRemoteAppOrderChange(workingApps.map { it.id })
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(88.dp)) // Bottom bar clearance
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single Draggable Row Item — Clean, polished with ONLY Delete & Drag Handle
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun QuickLaunchOrderItem(
    app: RemoteShortcutApp,
    index: Int,
    dragState: DragDropState,
    onDelete: () -> Unit,
    onDragComplete: () -> Unit,
) {
    val view = LocalView.current
    val isDragging = dragState.draggingId == app.id

    // Direct GPU translation for instant, buttery-smooth 1:1 finger tracking
    val translationY = if (isDragging) dragState.dragOffsetPx else 0f

    val elevation by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 0.dp,
        animationSpec = tween(120),
        label = "elevation_${app.id}"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = tween(120),
        label = "scale_${app.id}"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (size.height > 0 && dragState.itemHeightPx == 0f) {
                    dragState.itemHeightPx = size.height.toFloat()
                }
            }
            .zIndex(if (isDragging) 5f else 0f)
            .graphicsLayer {
                this.translationY = translationY
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(16.dp),
                clip = false
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .border(
                BorderStroke(
                    width = 1.dp,
                    color = if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 1. Position badge ────────────────────────────────────────────────
        Surface(
            shape = CircleShape,
            color = if (isDragging) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isDragging) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 2. App brand mark ────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = app.accent.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, app.accent.copy(alpha = 0.35f)),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = app.mark,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = app.accent,
                    fontSize = 12.sp
                )
            }
        }

        // ── 3. App label and subtitle tags ───────────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (index == 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "First in stripe",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 10.sp
                        )
                    }
                }

                if (app.isCustom) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Custom",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 10.sp
                        )
                    }
                } else if (index != 0) {
                    Text(
                        text = "Default app",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── 4. Delete Button (Circular with subtle error container) ─────────
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove ${app.label}",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── 5. Drag Handle Button (Prominent touch target, instant response) ─
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isDragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                )
                .pointerInput(app.id) {
                    detectDragGestures(
                        onDragStart = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            dragState.onDragStart(app.id)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragState.onDrag(dragAmount.y) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        },
                        onDragEnd = {
                            dragState.onDragEnd()
                            onDragComplete()
                        },
                        onDragCancel = {
                            dragState.onDragEnd()
                            onDragComplete()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder ${app.label}",
                modifier = Modifier.size(22.dp),
                tint = if (isDragging) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hidden App Item (shown in "Available to add")
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HiddenAppItem(
    app: RemoteShortcutApp,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = app.accent.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, app.accent.copy(alpha = 0.35f)),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = app.mark,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = app.accent,
                    fontSize = 12.sp
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when { app.isCustom -> "Custom shortcut"; app.id.startsWith(com.canasnn.tvremote.utils.TvApp.ID_PREFIX) -> "TV app"; else -> "Default app" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FilledTonalButton(
            onClick = onAdd,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add", style = MaterialTheme.typography.labelMedium)
        }
    }
}
