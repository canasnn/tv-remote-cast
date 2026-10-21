package com.ruterika.castcore.web

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * URLs that failed to play on the TV. They are listed last in the
 * video list for [ttlMs] (30 days by default). Persisted as JSON in [file] when given.
 */
class FailedUrlStore(
    private val file: File?,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val failed: LinkedHashMap<String, Long> = load()

    /** Number of remembered URLs (expired ones excluded). */
    val size: Int get() = synchronized(lock) { pruneLocked(); failed.size }

    /** Remembers that [url] failed to play. */
    fun markFailed(url: String) {
        synchronized(lock) {
            val key = WebUrls.normalize(url)
            failed.remove(key)
            failed[key] = clock()
            while (failed.size > maxEntries) failed.remove(failed.keys.first())
            saveLocked()
        }
    }

    /** [url] played fine: forget an earlier failure. */
    fun markWorking(url: String) {
        synchronized(lock) {
            if (failed.remove(WebUrls.normalize(url)) != null) saveLocked()
        }
    }

    /** True when [url] failed within the last [ttlMs]. */
    fun isFailed(url: String): Boolean {
        synchronized(lock) {
            if (failed.isEmpty()) return false
            val at = failed[WebUrls.normalize(url)] ?: return false
            return clock() - at < ttlMs
        }
    }

    /** Forgets everything. */
    fun clear() {
        synchronized(lock) {
            failed.clear()
            saveLocked()
        }
    }

    private fun pruneLocked() {
        val now = clock()
        if (failed.values.removeAll { now - it >= ttlMs }) saveLocked()
    }

    private fun load(): LinkedHashMap<String, Long> {
        val f = file?.takeIf { it.exists() } ?: return LinkedHashMap()
        val map = runCatching { JSON.decodeFromString(SERIALIZER, f.readText()) }.getOrNull() ?: return LinkedHashMap()
        val now = clock()
        return map.filterValues { now - it < ttlMs }.entries.sortedBy { it.value }.associateTo(LinkedHashMap()) { it.key to it.value }
    }

    private fun saveLocked() {
        val f = file ?: return
        writeAtomically(f, JSON.encodeToString(SERIALIZER, failed))
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
