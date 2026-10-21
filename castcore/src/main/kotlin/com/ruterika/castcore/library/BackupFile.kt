package com.ruterika.castcore.library

import com.ruterika.castcore.settings.CastSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One backup file: library lists plus (optionally) the cast settings and the open browser tabs. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val createdAt: Long,
    val library: LibraryData,
    val settings: CastSettings? = null,
    /** URLs of the open (non-private) browser tabs. */
    val tabs: List<String> = emptyList()
) {
    companion object {
        const val FORMAT = "tvremote-cast-backup"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

        fun encode(file: BackupFile): String = json.encodeToString(serializer(), file)

        /** Null when [text] is not one of our backups. */
        fun decode(text: String): BackupFile? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.format == FORMAT }
    }
}
