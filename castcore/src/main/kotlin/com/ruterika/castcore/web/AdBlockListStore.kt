package com.ruterika.castcore.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The ad-block host list in use: the embedded default list, plus downloaded lists (an update
 * URL and custom list URLs, hosts or adblock-domain format), minus the user's allow list.
 *
 * Downloads are all-or-nothing: if any list fails the previous lists stay. A SHA-1 of the
 * combined download tells whether anything changed (unchanged lists are not rebuilt).
 */
class AdBlockListStore(
    private val dir: File?,
    private val http: OkHttpClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val embeddedText: () -> String? = { AdBlocker.loadDefaultListText() },
) {
    /** State of the downloaded lists. */
    @Serializable
    data class Info(
        /** SHA-1 of the combined downloaded lists ("" = none). */
        val hash: String = "",
        val updatedAt: Long = 0L,
        /** Sources of the cached download. */
        val sources: List<String> = emptyList(),
        /** Blocked domains in use (embedded + downloaded). */
        val hostCount: Int = 0,
        /** Per list URL: its ETag / Last-Modified, so a check downloads nothing when unchanged. */
        val validators: Map<String, String> = emptyMap(),
    )

    /** Result of [update]. */
    sealed class UpdateResult {
        data class Updated(val hostCount: Int) : UpdateResult()
        object Unchanged : UpdateResult()
        data class Failed(val reason: String) : UpdateResult()
    }

    private val lock = Any()
    private var allow: Set<String> = emptySet()
    private val _blocker = MutableStateFlow(AdBlocker.default())
    private val _info = MutableStateFlow(readInfo())

    /** The matcher to use. Starts with the embedded list; call [load] to add the cached download. */
    val blocker: StateFlow<AdBlocker> = _blocker.asStateFlow()

    val info: StateFlow<Info> = _info.asStateFlow()

    private val cacheFile get() = dir?.let { File(it, "adblock_lists.txt") }
    private val infoFile get() = dir?.let { File(it, "adblock_lists.json") }

    /** Builds the matcher from the embedded list, the cached download and [allowList]. Blocking IO. */
    fun load(allowList: Collection<String> = allow) {
        synchronized(lock) {
            allow = allowList.mapNotNull { AdBlocker.normalizeDomain(it.removePrefix("*.")) }.toSet()
            val cached = cacheFile?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            rebuild(cached)
        }
    }

    /** Changes the user allow list (rebuilds the matcher when it changed). Blocking. */
    fun setAllowList(allowList: Collection<String>) {
        val normalized = allowList.mapNotNull { AdBlocker.normalizeDomain(it.removePrefix("*.")) }.toSet()
        if (normalized == allow) return
        load(normalized)
    }

    /**
     * Downloads [urls] and uses them on top of the embedded list. With [force] the lists are
     * rebuilt even when their hash did not change.
     */
    suspend fun update(urls: List<String>, force: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        val sources = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (sources.isEmpty()) {
            val hadDownload = _info.value.hash.isNotEmpty()
            if (hadDownload) reset()
            return@withContext if (hadDownload) UpdateResult.Updated(_blocker.value.blockedCount) else UpdateResult.Unchanged
        }
        val client = http ?: return@withContext UpdateResult.Failed("No network client")
        val previous = _info.value
        // Cheap check first: when every list answers "not modified", nothing is downloaded.
        if (!force && previous.sources == sources && previous.hash.isNotEmpty() && sources.all { it in previous.validators }) {
            val unchanged = sources.all { url -> notModified(client, url, previous.validators.getValue(url)) }
            if (unchanged) {
                synchronized(lock) { saveInfo(previous.copy(updatedAt = clock())) }
                return@withContext UpdateResult.Unchanged
            }
        }
        val texts = ArrayList<String>()
        val validators = HashMap<String, String>()
        for (url in sources) {
            if (!WebUrls.isHttp(url)) return@withContext UpdateResult.Failed("Not a web address: $url")
            val text = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext UpdateResult.Failed("HTTP ${resp.code} for $url")
                    validatorOf(resp)?.let { validators[url] = it }
                    resp.body.string()
                }
            } catch (e: Exception) {
                return@withContext UpdateResult.Failed(e.message ?: "Download failed: $url")
            }
            if (AdBlocker.parseList(text).blocked.isEmpty()) return@withContext UpdateResult.Failed("No hosts in $url")
            texts += text
        }
        val combined = texts.joinToString("\n")
        val hash = sha1(combined)
        synchronized(lock) {
            val old = _info.value
            if (!force && old.hash == hash && old.sources == sources) {
                saveInfo(old.copy(updatedAt = clock(), validators = validators))
                return@withContext UpdateResult.Unchanged
            }
            cacheFile?.let { writeAtomically(it, combined) }
            rebuild(combined)
            saveInfo(Info(hash = hash, updatedAt = clock(), sources = sources, hostCount = _blocker.value.blockedCount, validators = validators))
        }
        UpdateResult.Updated(_blocker.value.blockedCount)
    }

    /** Forgets downloaded lists; only the embedded list (and the allow list) stays. */
    fun reset() {
        synchronized(lock) {
            cacheFile?.delete()
            infoFile?.delete()
            rebuild(null)
            _info.value = Info(hostCount = _blocker.value.blockedCount)
        }
    }

    private fun rebuild(downloaded: String?) {
        val b = AdBlocker.Builder()
        embeddedText()?.let { b.addList(it) }
        if (downloaded != null) b.addList(downloaded)
        allow.forEach { b.allow(it) }
        _blocker.value = b.build()
        _info.value = _info.value.copy(hostCount = _blocker.value.blockedCount)
    }

    private fun readInfo(): Info =
        infoFile?.takeIf { it.exists() }?.let { f -> runCatching { JSON.decodeFromString(Info.serializer(), f.readText()) }.getOrNull() }
            ?: Info(hostCount = AdBlocker.default().blockedCount)

    private fun saveInfo(info: Info) {
        _info.value = info
        infoFile?.let { writeAtomically(it, JSON.encodeToString(Info.serializer(), info)) }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        /** "E:<etag>" or "M:<last-modified>". */
        fun validatorOf(resp: okhttp3.Response): String? =
            resp.header("ETag")?.let { "E:$it" } ?: resp.header("Last-Modified")?.let { "M:$it" }

        /** True when the server says the list did not change since [validator]. */
        fun notModified(client: OkHttpClient, url: String, validator: String): Boolean = runCatching {
            val request = Request.Builder().url(url).apply {
                if (validator.startsWith("E:")) header("If-None-Match", validator.removePrefix("E:"))
                else header("If-Modified-Since", validator.removePrefix("M:"))
            }.build()
            client.newCall(request).execute().use { it.code == 304 }
        }.getOrDefault(false)

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
