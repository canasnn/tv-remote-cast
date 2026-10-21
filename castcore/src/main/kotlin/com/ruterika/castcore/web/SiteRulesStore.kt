package com.ruterika.castcore.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Holds the active [SiteRules]: the embedded default, replaced by a downloaded document
 * (cached in [cacheFile]) when that one is valid and not older than the embedded version.
 */
class SiteRulesStore(
    private val cacheFile: File?,
    private val http: OkHttpClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val embedded: SiteRules = SiteRules.default(),
) {
    /** Result of [update]. */
    sealed class UpdateResult {
        data class Updated(val version: Int) : UpdateResult()
        object Unchanged : UpdateResult()
        data class Failed(val reason: String) : UpdateResult()
    }

    private val _rules = MutableStateFlow(loadCached() ?: embedded)

    /** The rules in use. */
    val rules: StateFlow<SiteRules> = _rules.asStateFlow()

    /** True when downloaded rules are in use. */
    val usesDownloaded: Boolean get() = _rules.value !== embedded

    /** When the cache was last written (0 = never). */
    val updatedAt: Long get() = cacheFile?.takeIf { it.exists() }?.lastModified() ?: 0L

    /**
     * Downloads rules from [url] (http/https). The current rules are kept when the download
     * fails, is not valid JSON, or is older than the embedded rules.
     */
    suspend fun update(url: String): UpdateResult = withContext(Dispatchers.IO) {
        val client = http ?: return@withContext UpdateResult.Failed("No network client")
        if (!WebUrls.isHttp(url)) return@withContext UpdateResult.Failed("Not a web address")
        val body = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext UpdateResult.Failed("HTTP ${resp.code}")
                resp.body.string()
            }
        } catch (e: Exception) {
            return@withContext UpdateResult.Failed(e.message ?: "Download failed")
        }
        val parsed = SiteRules.parseOrNull(body) ?: return@withContext UpdateResult.Failed("Not a valid rules file")
        if (parsed.version < embedded.version) return@withContext UpdateResult.Failed("Older than the built-in rules")
        val old = cacheFile?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        if (old == body) {
            cacheFile?.setLastModified(clock())
            _rules.value = parsed
            return@withContext UpdateResult.Unchanged
        }
        cacheFile?.let { writeAtomically(it, body) }
        _rules.value = parsed
        UpdateResult.Updated(parsed.version)
    }

    /** Drops the downloaded rules and goes back to the embedded ones. */
    fun reset() {
        cacheFile?.delete()
        _rules.value = embedded
    }

    private fun loadCached(): SiteRules? {
        val f = cacheFile?.takeIf { it.exists() } ?: return null
        val parsed = runCatching { SiteRules.parseOrNull(f.readText()) }.getOrNull() ?: return null
        return parsed.takeIf { it.version >= embedded.version }
    }
}

/** Writes [text] to a temp file next to [file] and renames it over [file]. */
internal fun writeAtomically(file: File, text: String) {
    runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
