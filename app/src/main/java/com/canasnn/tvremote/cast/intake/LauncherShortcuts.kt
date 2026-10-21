package com.canasnn.tvremote.cast.intake

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.canasnn.tvremote.MainActivity
import com.canasnn.tvremote.R

/**
 * Long-press menu of the launcher icon: Bookmarks, Local
 * media, Most visited, IPTV. Dynamic shortcuts, so they follow the build's
 * application id.
 */
object LauncherShortcuts {

    private data class Entry(val section: String, val short: String, val long: String, val icon: Int)

    private val entries = listOf(
        Entry(Intake.SECTION_BOOKMARKS, "Bookmarks", "Open bookmarks", R.drawable.ic_shortcut_bookmarks),
        Entry(Intake.SECTION_LOCAL_MEDIA, "Local media", "Cast videos from this phone", R.drawable.ic_shortcut_local_media),
        Entry(Intake.SECTION_MOST_VISITED, "Most visited", "Most visited sites", R.drawable.ic_shortcut_most_visited),
        Entry(Intake.SECTION_IPTV, "IPTV", "Open IPTV lists", R.drawable.ic_shortcut_iptv)
    )

    fun publish(context: Context) {
        runCatching {
            val existing = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.toSet()
            if (existing == entries.map { "section-" + it.section }.toSet()) return
            val shortcuts = entries.map { e ->
                ShortcutInfoCompat.Builder(context, "section-" + e.section)
                    .setShortLabel(e.short)
                    .setLongLabel(e.long)
                    .setIcon(IconCompat.createWithResource(context, e.icon))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(Intake.ACTION_OPEN_SECTION)
                            .putExtra(Intake.EXTRA_SECTION, e.section)
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }
}
