package com.canasnn.tvremote.ui.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

// ── DataStore singleton ───────────────────────────────────────────────────────
private val Context.shortcutsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "custom_shortcuts"
)

private val KEY_SHORTCUTS = stringPreferencesKey("shortcuts_json")

/**
 * DataStore-backed persistence layer for user-defined app shortcuts.
 *
 * JSON schema (array of objects):
 * [
 *   {
 *     "id":         "uuid-string",
 *     "label":      "My App",
 *     "mark":       "MA",
 *     "accentArgb": -6543,          // ARGB int
 *     "intentUri":  "https://..."
 *   }, ...
 * ]
 */
object ShortcutsDataStore {

    /** Observe the shortcut list as a Flow — updates whenever the list changes. */
    fun shortcutsFlow(context: Context): Flow<List<CustomShortcut>> =
        context.shortcutsDataStore.data.map { prefs ->
            decode(prefs[KEY_SHORTCUTS])
        }

    /** One-shot read — suspends until the value is available. */
    suspend fun loadShortcuts(context: Context): List<CustomShortcut> =
        shortcutsFlow(context).first()

    /** Persist a new list, completely replacing the previous one. */
    suspend fun saveShortcuts(context: Context, shortcuts: List<CustomShortcut>) {
        context.shortcutsDataStore.edit { prefs ->
            prefs[KEY_SHORTCUTS] = encode(shortcuts)
        }
        Timber.d("[ShortcutsDataStore] Saved ${shortcuts.size} custom shortcuts")
    }

    /** Append a single shortcut and persist. Returns the updated list. */
    suspend fun addShortcut(context: Context, shortcut: CustomShortcut): List<CustomShortcut> {
        val updated = loadShortcuts(context) + shortcut
        saveShortcuts(context, updated)
        return updated
    }

    /** Remove a shortcut by [id] and persist. Returns the updated list. */
    suspend fun removeShortcut(context: Context, id: String): List<CustomShortcut> {
        val updated = loadShortcuts(context).filter { it.id != id }
        saveShortcuts(context, updated)
        return updated
    }

    /** Replace a shortcut with the same [CustomShortcut.id] and persist. */
    suspend fun updateShortcut(context: Context, updated: CustomShortcut): List<CustomShortcut> {
        val list = loadShortcuts(context).map { if (it.id == updated.id) updated else it }
        saveShortcuts(context, list)
        return list
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun encode(shortcuts: List<CustomShortcut>): String {
        val array = JSONArray()
        shortcuts.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("mark", s.mark)
                put("accentArgb", s.accentArgb)
                put("intentUri", s.intentUri)
            })
        }
        return array.toString()
    }

    private fun decode(json: String?): List<CustomShortcut> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                CustomShortcut(
                    id         = obj.optString("id", UUID.randomUUID().toString()),
                    label      = obj.optString("label", "Unknown"),
                    mark       = obj.optString("mark", "?").take(3),
                    accentArgb = obj.optInt("accentArgb", android.graphics.Color.GRAY),
                    intentUri  = obj.optString("intentUri", "")
                )
            }.filter { it.intentUri.isNotBlank() }
        } catch (e: Exception) {
            Timber.e(e, "[ShortcutsDataStore] Failed to decode shortcuts JSON")
            emptyList()
        }
    }
}
