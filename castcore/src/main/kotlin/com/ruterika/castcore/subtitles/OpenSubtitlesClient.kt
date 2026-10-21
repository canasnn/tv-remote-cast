package com.ruterika.castcore.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Client for the OpenSubtitles REST API v1 (https://opensubtitles.stoplight.io/).
 *
 * Every request carries the `Api-Key` and `User-Agent` headers; after [login] (or when [token]
 * is set from a persisted session) requests also carry `Authorization: Bearer <token>`.
 * All calls are main-safe suspend functions.
 *
 * @param http shared OkHttp client.
 * @param apiKey consumer API key.
 * @param userAgent application user agent, e.g. `RuterikaCast v1.0`.
 * @param baseUrl API root; overridable for tests.
 */
class OpenSubtitlesClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val userAgent: String,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    /** Bearer token from [login]; may be set directly to restore a saved session. */
    @Volatile
    var token: String? = null

    @Volatile
    private var base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** The API root currently in use (may change after [login] for VIP users). */
    val currentBaseUrl: String get() = base.toString().trimEnd('/')

    /**
     * Searches subtitles. At least one of [query], [imdbId] or [movieHash] should be given.
     * Parameters are sent sorted and lower-cased as the API recommends (avoids redirects).
     *
     * @param query free text, typically from [SubtitleNaming.cleanTitle].
     * @param imdbId IMDb id with or without the `tt` prefix.
     * @param season season number (series).
     * @param episode episode number (series).
     * @param languages language codes, e.g. `["tr", "en"]` (empty = all languages).
     * @param movieHash hash from [OpenSubtitlesHash].
     * @param page 1-based result page.
     * @param noGuessit true sends `no_guessit=1`: the query is taken literally instead of being
     *   split into title / season / episode by the server ("Exact" search).
     * @param parentImdbId IMDb id of the show when searching episodes (`parent_imdb_id`).
     * @return one [OpenSubtitlesResult] per downloadable file.
     */
    suspend fun search(
        query: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        languages: List<String> = emptyList(),
        movieHash: String? = null,
        page: Int = 1,
        noGuessit: Boolean = false,
        parentImdbId: String? = null,
    ): List<OpenSubtitlesResult> {
        val params = sortedMapOf<String, String>()
        episode?.let { params["episode_number"] = it.toString() }
        imdbId?.let(::normalizeImdbId)?.let { params["imdb_id"] = it }
        if (noGuessit) params["no_guessit"] = "1"
        parentImdbId?.let(::normalizeImdbId)?.let { params["parent_imdb_id"] = it }
        languages.map { lang -> lang.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' } }
            .filter { it.isNotEmpty() }.distinct().sorted()
            .takeIf { it.isNotEmpty() }?.let { params["languages"] = it.joinToString(",") }
        movieHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { params["moviehash"] = it }
        if (page > 1) params["page"] = page.toString()
        query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { params["query"] = it }
        season?.let { params["season_number"] = it.toString() }

        val url = endpoint("subtitles").newBuilder().apply {
            // Commas in the language list are sent literally, as in the API documentation.
            params.forEach { (k, v) -> if (k == "languages") addEncodedQueryParameter(k, v) else addQueryParameter(k, v) }
        }.build()
        val dto = decode<SearchResponseDto>(execute(apiRequest(url).get().build()))
        return dto.data.flatMap { it.toResults() }
    }

    /** Languages the API knows (`GET /infos/languages`), in API order. */
    suspend fun languages(): List<OpenSubtitlesLanguage> {
        val dto = decode<LanguagesResponseDto>(execute(apiRequest(endpoint("infos/languages")).get().build()))
        return dto.data.mapNotNull { l ->
            val code = l.languageCode?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            OpenSubtitlesLanguage(code, l.languageName?.trim()?.takeIf { it.isNotEmpty() } ?: code)
        }
    }

    /**
     * Requests a temporary download link for [fileId] (`POST /download`). Consumes quota.
     *
     * @throws OpenSubtitlesException.DownloadQuotaExceeded when the daily quota is used up.
     */
    suspend fun download(fileId: Long): OpenSubtitlesDownloadLink {
        val body = json.encodeToString(DownloadRequestDto.serializer(), DownloadRequestDto(fileId))
        val dto = decode<DownloadResponseDto>(execute(apiRequest(endpoint("download")).post(body.toRequestBody(JSON)).build()))
        val link = dto.link ?: throw OpenSubtitlesException.InvalidResponse("Download response has no link: ${dto.message}")
        return OpenSubtitlesDownloadLink(link, dto.fileName, dto.requests, dto.remaining, dto.message, dto.resetTime, dto.resetTimeUtc)
    }

    /** Fetches the subtitle bytes behind a [link] returned by [download] (max [maxBytes]). */
    suspend fun fetch(link: OpenSubtitlesDownloadLink, maxBytes: Int = MAX_SUBTITLE_BYTES): ByteArray {
        val request = Request.Builder().url(link.link).header("User-Agent", userAgent).get().build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw mapError(response)
                val body = response.body
                if (body.contentLength() > maxBytes) throw IOException("Subtitle too large: ${body.contentLength()} bytes")
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IOException("Subtitle larger than $maxBytes bytes")
                    }
                }
                out.toByteArray()
            }
        }
    }

    /** Convenience: [download] then [fetch]. */
    suspend fun downloadFile(fileId: Long): OpenSubtitlesDownload {
        val link = download(fileId)
        return OpenSubtitlesDownload(fetch(link), link)
    }

    /**
     * Logs in (`POST /login`) and stores the returned [token]. If the API returns a different
     * `base_url` (VIP users) and the client talks to opensubtitles.com, later calls use it.
     *
     * @throws OpenSubtitlesException.Unauthorized for wrong credentials.
     */
    suspend fun login(username: String, password: String): OpenSubtitlesSession {
        val body = json.encodeToString(LoginRequestDto.serializer(), LoginRequestDto(username, password))
        val dto = decode<LoginResponseDto>(execute(apiRequest(endpoint("login")).post(body.toRequestBody(JSON)).build()))
        val newToken = dto.token ?: throw OpenSubtitlesException.InvalidResponse("Login response has no token")
        token = newToken
        dto.baseUrl?.takeIf { it.isNotBlank() }?.let { host ->
            if (base.host.endsWith("opensubtitles.com")) {
                val clean = host.removePrefix("https://").removePrefix("http://").trimEnd('/').substringBefore('/')
                runCatching { "https://$clean/api/v1".toHttpUrl() }.getOrNull()?.let { base = it }
            }
        }
        return OpenSubtitlesSession(newToken, dto.baseUrl, dto.user?.allowedDownloads, dto.user?.level, dto.user?.vip ?: false)
    }

    /** Invalidates the current token (`DELETE /logout`) and forgets it locally. */
    suspend fun logout() {
        if (token == null) return
        try {
            execute(apiRequest(endpoint("logout")).delete().build())
        } finally {
            token = null
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun endpoint(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun apiRequest(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("Api-Key", apiKey)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw mapError(response)
            response.body.string()
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw OpenSubtitlesException.InvalidResponse("Unexpected response: ${body.take(200)}", e)
    } catch (e: IllegalArgumentException) {
        throw OpenSubtitlesException.InvalidResponse("Unexpected response: ${body.take(200)}", e)
    }

    private fun mapError(response: Response): OpenSubtitlesException {
        val body = runCatching { response.body.string() }.getOrDefault("")
        val error = runCatching { json.decodeFromString<ErrorResponseDto>(body) }.getOrNull()
        val message = error?.message ?: error?.errors?.firstOrNull() ?: body.take(200).ifEmpty { "HTTP ${response.code}" }
        return when (response.code) {
            401 -> OpenSubtitlesException.Unauthorized(message)
            406 -> OpenSubtitlesException.DownloadQuotaExceeded(message, error?.resetTimeUtc)
            429 -> OpenSubtitlesException.RateLimited(message, response.header("Retry-After")?.trim()?.toLongOrNull())
            else -> OpenSubtitlesException.Http(response.code, body)
        }
    }

    companion object {
        /** Production API root. */
        const val DEFAULT_BASE_URL: String = "https://api.opensubtitles.com/api/v1"

        /** Upper bound for a downloaded subtitle file. */
        const val MAX_SUBTITLE_BYTES: Int = 10 * 1024 * 1024

        private val JSON = "application/json".toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }

        /** Removes a `tt` prefix and leading zeros from an IMDb id; null if it has no digits. */
        internal fun normalizeImdbId(id: String): String? =
            id.trim().lowercase().removePrefix("tt").trimStart('0').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

        /** Parses a raw `/subtitles` JSON response; exposed for tests and offline caching. */
        internal fun parseSearchResponse(body: String): List<OpenSubtitlesResult> =
            json.decodeFromString<SearchResponseDto>(body).data.flatMap { it.toResults() }

        private fun SubtitleItemDto.toResults(): List<OpenSubtitlesResult> {
            val a = attributes ?: return emptyList()
            val feature = a.featureDetails
            return a.files.mapNotNull { file ->
                val fileId = file.fileId ?: return@mapNotNull null
                OpenSubtitlesResult(
                    fileId = fileId,
                    fileName = file.fileName,
                    subtitleId = a.subtitleId ?: id,
                    language = a.language.orEmpty(),
                    release = a.release.orEmpty(),
                    downloadCount = a.downloadCount,
                    hearingImpaired = a.hearingImpaired,
                    fps = a.fps?.takeIf { it > 0 },
                    uploader = a.uploader?.name?.takeIf { it.isNotBlank() },
                    aiTranslated = a.aiTranslated,
                    machineTranslated = a.machineTranslated,
                    fromTrusted = a.fromTrusted ?: false,
                    hd = a.hd,
                    ratings = a.ratings,
                    movieHashMatch = a.moviehashMatch,
                    featureTitle = feature?.parentTitle?.takeIf { it.isNotBlank() } ?: feature?.title ?: feature?.movieName,
                    year = feature?.year?.takeIf { it > 0 },
                    season = feature?.seasonNumber?.takeIf { it > 0 },
                    episode = feature?.episodeNumber?.takeIf { it > 0 },
                    cdNumber = file.cdNumber,
                    uploadDate = a.uploadDate,
                    url = a.url,
                )
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
