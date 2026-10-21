package com.ruterika.castcore.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resumeWithException

/** Base class for failures while loading a playlist. */
open class PlaylistLoadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The server answered with a non-2xx status [code]. [reason] is the HTTP reason phrase and
 * [details] the start of the response body (often the provider's own error text).
 */
class PlaylistHttpException(val code: Int, url: String, val reason: String = "", val details: String? = null) :
    PlaylistLoadException("Playlist request failed with HTTP $code: $url")

/** The server did not answer in time. */
class PlaylistTimeoutException(url: String, cause: Throwable? = null) :
    PlaylistLoadException("The server did not answer in time: $url", cause)

/**
 * The content is not an M3U playlist. [isHlsStream] is true when it is an HLS
 * stream manifest (playable directly) rather than a channel list.
 */
open class NotAnM3uPlaylistException(message: String, val isHlsStream: Boolean = false) :
    PlaylistLoadException(message)

/** The list has no content or no channels. */
class EmptyPlaylistException(message: String = "Playlist is empty") : NotAnM3uPlaylistException(message)

/** The first characters of a remote list, for a quick "is this an IPTV list?" check. */
data class PlaylistPeek(val head: String, val contentType: String?)

/**
 * Downloads and parses IPTV playlists.
 *
 * Gzip is handled both as `Content-Encoding` (by OkHttp) and as raw gzip payloads such as
 * `.m3u.gz` files. Content is sniffed before parsing so HTML error pages, JSON and HLS
 * manifests fail with a [NotAnM3uPlaylistException] instead of yielding an empty playlist.
 * W3U (JSON) lists and RSS/Atom feeds are detected and parsed too.
 */
class PlaylistLoader(private val client: OkHttpClient) {

    /**
     * Downloads [url] and parses it. A Kodi style `|User-Agent=...` suffix on [url] is sent as
     * request headers; an explicit [userAgent] takes precedence.
     *
     * @throws PlaylistLoadException on invalid URLs, network/HTTP failures or non-M3U content.
     */
    suspend fun load(url: String, userAgent: String? = null): M3uPlaylist {
        val response = execute(url, userAgent)
        return response.use {
            withContext(Dispatchers.IO) { parseBody(it, url) }
        }
    }

    /**
     * Downloads [url] into [target] without parsing it, hashing the bytes on the way.
     *
     * @throws PlaylistLoadException on invalid URLs and network/HTTP failures.
     */
    suspend fun download(url: String, userAgent: String?, target: File): StoredPlaylist {
        val response = execute(url, userAgent)
        return response.use {
            withContext(Dispatchers.IO) {
                val contentType = it.body.contentType()
                try {
                    PlaylistFiles.store(it.body.byteStream(), target, contentType?.toString(), contentType?.charset()?.name())
                } catch (e: SocketTimeoutException) {
                    throw PlaylistTimeoutException(M3uParser.splitPipeHeaders(url).first, e)
                } catch (e: IOException) {
                    throw PlaylistLoadException("Failed to read playlist: ${e.message}", e)
                }
            }
        }
    }

    /** Reads the first characters of [url] (for a format check before adding a list). */
    suspend fun peek(url: String, userAgent: String? = null): PlaylistPeek {
        val response = execute(url, userAgent)
        return response.use {
            withContext(Dispatchers.IO) {
                val contentType = it.body.contentType()
                val head = try {
                    PlaylistFiles.openReader(it.body.byteStream(), contentType?.charset()).use(PlaylistFiles::peekHead)
                } catch (e: IOException) {
                    throw PlaylistLoadException("Failed to read playlist: ${e.message}", e)
                }
                PlaylistPeek(head, contentType?.toString())
            }
        }
    }

    /**
     * Parses an in-memory playlist after validating that it looks like M3U (or W3U).
     *
     * @throws NotAnM3uPlaylistException when [text] is not an M3U playlist.
     */
    fun fromText(text: String): M3uPlaylist =
        when (PlaylistFiles.detectFormat(text.take(PlaylistFiles.SNIFF_CHARS))) {
            PlaylistFormat.M3U -> M3uParser.parse(text)
            PlaylistFormat.W3U -> W3uParser.parse(text)
            PlaylistFormat.RSS -> RssParser.parse(text)
        }

    private suspend fun execute(url: String, userAgent: String?): Response {
        val (bareUrl, pipeHeaders) = M3uParser.splitPipeHeaders(url)
        val request = try {
            Request.Builder().url(bareUrl).apply {
                pipeHeaders.forEach { (name, value) -> header(name, value) }
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
            }.build()
        } catch (e: IllegalArgumentException) {
            throw PlaylistLoadException("Invalid playlist URL: $url", e)
        }
        val response = try {
            client.newCall(request).await()
        } catch (e: PlaylistLoadException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw PlaylistTimeoutException(bareUrl, e)
        } catch (e: InterruptedIOException) {
            if (e.message?.contains("timeout", ignoreCase = true) == true) throw PlaylistTimeoutException(bareUrl, e)
            throw PlaylistLoadException("Failed to download playlist from $bareUrl: ${e.message}", e)
        } catch (e: IOException) {
            throw PlaylistLoadException("Failed to download playlist from $bareUrl: ${e.message}", e)
        }
        if (!response.isSuccessful) {
            val details = response.use {
                runCatching { it.peekBody(ERROR_BODY_BYTES).string().trim().take(ERROR_DETAIL_CHARS) }.getOrNull()?.ifEmpty { null }
            }
            throw PlaylistHttpException(response.code, bareUrl, response.message, details)
        }
        return response
    }

    private suspend fun parseBody(response: Response, url: String): M3uPlaylist {
        val body = response.body
        val contentType = body.contentType()
        val reader = PlaylistFiles.openReader(body.source().inputStream(), contentType?.charset(Charsets.UTF_8))
        val context = currentCoroutineContext()
        val entries = ArrayList<M3uEntry>()
        val header = try {
            PlaylistFiles.parse(reader, nameHint = url, contentType = contentType?.toString()) { entry ->
                entries.add(entry)
                if (entries.size % CANCEL_CHECK_INTERVAL == 0) context.ensureActive()
            }
        } catch (e: PlaylistLoadException) {
            throw e
        } catch (e: IOException) {
            throw PlaylistLoadException("Failed to read playlist: ${e.message}", e)
        }
        return M3uPlaylist(header, entries)
    }

    private companion object {
        const val CANCEL_CHECK_INTERVAL = 1024
        const val ERROR_BODY_BYTES = 4096L
        const val ERROR_DETAIL_CHARS = 256
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
