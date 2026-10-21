package com.ruterika.castcore.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the phone was casting, kept on disk so a restarted app can take over
 * the playback that is still running on the TV (matched by [sessionId]).
 */
@Serializable
data class SavedCastSession(
    val sessionId: String,
    val items: List<SavedMedia>,
    val startIndex: Int = 0,
    val subtitleOffsetMs: Long = 0L,
    val playlistId: String? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun read(file: File): SavedCastSession? =
            runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()

        fun write(file: File, session: SavedCastSession?) {
            runCatching {
                if (session == null) {
                    file.delete()
                } else {
                    file.parentFile?.mkdirs()
                    file.writeText(json.encodeToString(serializer(), session))
                }
            }
        }
    }
}
