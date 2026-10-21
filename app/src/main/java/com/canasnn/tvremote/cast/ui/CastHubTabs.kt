package com.canasnn.tvremote.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.canasnn.tvremote.ui.app.CastTab

/** Cast hub sections as a 4-column icon grid (fits every tab, no sideways scrolling). */
@Composable
fun CastHubTabs(selected: CastTab, onSelect: (CastTab) -> Unit, onOpenSettings: () -> Unit) {
    val tiles: List<Triple<String, ImageVector, (() -> Unit)?>> =
        CastTab.entries.map { Triple(it.label, it.icon, null) } + Triple("Settings", Icons.Filled.Settings, onOpenSettings)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(COLUMNS).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, (label, icon, action) ->
                    val tab = CastTab.entries.getOrNull(row * COLUMNS + col)
                    val isSelected = action == null && tab == selected
                    HubTile(
                        label = label,
                        icon = icon,
                        selected = isSelected,
                        modifier = Modifier.weight(1f),
                        onClick = { if (action != null) action() else if (tab != null) onSelect(tab) }
                    )
                }
                repeat(COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HubTile(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val COLUMNS = 4
