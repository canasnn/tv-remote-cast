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
import timber.log.Timber

private val Context.remoteLayoutDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_layout_settings"
)

private val KEY_LAYOUT_CONFIG = stringPreferencesKey("layout_config_json")

/**
 * DataStore persistence layer for saving and loading the customizable remote layout.
 */
object RemoteLayoutDataStore {

    /** Observe the remote layout configuration as a Flow. */
    fun layoutConfigFlow(context: Context): Flow<RemoteLayoutConfig> =
        context.remoteLayoutDataStore.data.map { prefs ->
            RemoteLayoutConfig.fromJson(prefs[KEY_LAYOUT_CONFIG])
        }

    /** One-shot load of the remote layout configuration. */
    suspend fun loadLayoutConfig(context: Context): RemoteLayoutConfig =
        layoutConfigFlow(context).first()

    /** Persist the remote layout configuration. */
    suspend fun saveLayoutConfig(context: Context, config: RemoteLayoutConfig) {
        context.remoteLayoutDataStore.edit { prefs ->
            prefs[KEY_LAYOUT_CONFIG] = RemoteLayoutConfig.toJson(config)
        }
        Timber.d("[RemoteLayoutDataStore] Saved custom layout config")
    }

    /** Reset the remote layout configuration to default. */
    suspend fun resetToDefault(context: Context) {
        context.remoteLayoutDataStore.edit { prefs ->
            prefs.remove(KEY_LAYOUT_CONFIG)
        }
        Timber.d("[RemoteLayoutDataStore] Reset layout config to default")
    }
}
