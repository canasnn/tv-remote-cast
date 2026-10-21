package com.ruterika.castcore.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Performs the [ProbeRequest]s emitted by [PageMediaCollector.pendingProbes] with OkHttp.
 *
 * HEAD probes fall back to a `Range: bytes=0-0` GET when the server rejects HEAD. Playlist probes
 * download at most [maxPlaylistBytes].
 */
class MediaProber(
    private val client: OkHttpClient,
    private val maxPlaylistBytes: Long = 2L * 1024 * 1024,
) {

    /** Result of a HEAD probe. [finalUrl] is the URL after redirects. */
    data class HeadResult(val mime: String?, val contentLength: Long?, val finalUrl: String? = null)

    /** Sends a HEAD (or tiny ranged GET) and returns content type and length; `null` on failure. */
    suspend fun head(request: ProbeRequest): HeadResult? = withContext(Dispatchers.IO) {
        try {
            val head = execute(request, "HEAD")
            if (head != null && head.code in 200..299) return@withContext head.toResult(ranged = false)
            val ranged = execute(request, "GET", range = true) ?: return@withContext null
            if (ranged.code in 200..299) ranged.toResult(ranged = ranged.code == 206) else null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Downloads a playlist body; `null` on failure or if it is not an HLS playlist. */
    suspend fun fetchPlaylist(request: ProbeRequest): String? = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(request, "GET", range = false)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val source = resp.body.source()
                source.request(maxPlaylistBytes)
                val bytes = source.buffer.let { buf -> buf.readByteArray(minOf(buf.size, maxPlaylistBytes)) }
                val text = bytes.toString(Charsets.UTF_8)
                if (HlsPlaylistParser.isHlsPlaylist(text)) text else null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Downloads a DASH manifest body; `null` on failure or if it is not an MPD. */
    suspend fun fetchDashManifest(request: ProbeRequest): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildRequest(request, "GET", range = false)).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val source = resp.body.source()
                source.request(maxPlaylistBytes)
                val text = source.buffer.let { buf -> buf.readByteArray(minOf(buf.size, maxPlaylistBytes)) }.toString(Charsets.UTF_8)
                if (DashManifestParser.isDashManifest(text)) text else null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Collects [collector]'s probes in [scope], runs at most [parallelism] at a time and feeds the
     * results back. Also performs probes that were requested before this call.
     */
    fun attach(scope: CoroutineScope, collector: PageMediaCollector, parallelism: Int = 4): Job {
        val permits = Semaphore(parallelism)
        return scope.launch {
            val handle: suspend (ProbeRequest) -> Unit = { req ->
                permits.withPermit {
                    when (req.kind) {
                        ProbeKind.HEAD -> {
                            val r = head(req)
                            if (r == null) collector.onProbeFailed(req.url) else collector.onHeadProbeResult(req.url, r.mime, r.contentLength)
                        }
                        ProbeKind.PLAYLIST -> {
                            val body = fetchPlaylist(req)
                            if (body == null) collector.onProbeFailed(req.url) else collector.onPlaylistLoaded(req.url, body)
                        }
                        ProbeKind.DASH -> {
                            val body = fetchDashManifest(req)
                            if (body == null) collector.onProbeFailed(req.url) else collector.onDashManifestLoaded(req.url, body)
                        }
                    }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { collector.pendingProbes.collect { req -> launch { handle(req) } } }
            val backlog = collector.pendingProbeRequests()
            backlog.forEach { req -> launch { handle(req) } }
        }
    }

    private class Probe(val code: Int, val mime: String?, val length: Long?, val contentRange: String?, val finalUrl: String?) {
        fun toResult(ranged: Boolean): HeadResult {
            val total = if (ranged) contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() else length
            return HeadResult(mime, total?.takeIf { it >= 0 }, finalUrl)
        }
    }

    private fun execute(request: ProbeRequest, method: String, range: Boolean = false): Probe? {
        val req = buildRequest(request, method, range)
        return client.newCall(req).execute().use { resp ->
            Probe(
                code = resp.code,
                mime = resp.header("Content-Type"),
                length = resp.header("Content-Length")?.toLongOrNull(),
                contentRange = resp.header("Content-Range"),
                finalUrl = resp.request.url.toString(),
            )
        }
    }

    private fun buildRequest(request: ProbeRequest, method: String, range: Boolean): Request {
        val b = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> b.header(k, v) }
        if (range) b.header("Range", "bytes=0-0")
        if (method == "HEAD") b.head() else b.get()
        return b.build()
    }
}
