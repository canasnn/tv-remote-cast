package com.ruterika.castcore.subtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * One downloadable subtitle file from an OpenSubtitles search. A subtitle with several CDs
 * yields one result per file.
 *
 * @property fileId id to pass to [OpenSubtitlesClient.download].
 * @property language language code as reported by OpenSubtitles (e.g. `tr`, `en`, `pt-BR`).
 * @property release release name the subtitle was made for.
 * @property fps frame rate the subtitle was synced to, if known.
 * @property uploader uploader display name, if any.
 * @property aiTranslated true when the subtitle was translated by AI.
 * @property machineTranslated true when the subtitle was machine translated.
 * @property movieHashMatch true when the result matched the movie hash sent in the search.
 */
data class OpenSubtitlesResult(
    val fileId: Long,
    val fileName: String?,
    val subtitleId: String?,
    val language: String,
    val release: String,
    val downloadCount: Int,
    val hearingImpaired: Boolean,
    val fps: Double?,
    val uploader: String?,
    val aiTranslated: Boolean,
    val machineTranslated: Boolean,
    val fromTrusted: Boolean,
    val hd: Boolean,
    val ratings: Double?,
    val movieHashMatch: Boolean,
    val featureTitle: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val cdNumber: Int?,
    val uploadDate: String?,
    val url: String?,
)

/**
 * Result of `POST /download`: a temporary [link] to the subtitle file and the quota state.
 *
 * @property remaining downloads left in the current quota window.
 * @property requests downloads used in the current quota window.
 */
data class OpenSubtitlesDownloadLink(
    val link: String,
    val fileName: String?,
    val requests: Int?,
    val remaining: Int?,
    val message: String?,
    val resetTime: String?,
    val resetTimeUtc: String?,
)

/** A downloaded subtitle file: raw [bytes] (any charset) plus the link metadata. */
class OpenSubtitlesDownload(val bytes: ByteArray, val link: OpenSubtitlesDownloadLink)

/**
 * Result of [OpenSubtitlesClient.login].
 *
 * @property token JWT used as `Authorization: Bearer` token.
 * @property baseUrl host the API asked us to use from now on (e.g. `vip-api.opensubtitles.com`).
 */
data class OpenSubtitlesSession(
    val token: String,
    val baseUrl: String?,
    val allowedDownloads: Int?,
    val level: String?,
    val vip: Boolean,
)

/** One entry of `GET /infos/languages`, e.g. `pt-BR` / `Portuguese (BR)`. */
@Serializable
data class OpenSubtitlesLanguage(val code: String, val name: String)

/** Errors raised by [OpenSubtitlesClient]. */
sealed class OpenSubtitlesException(message: String, val statusCode: Int?) : IOException(message) {
    /** HTTP 401: invalid API key, wrong credentials or expired token. */
    class Unauthorized(message: String) : OpenSubtitlesException(message, 401)

    /** HTTP 406: download quota exhausted (or invalid download request). */
    class DownloadQuotaExceeded(message: String, val resetTimeUtc: String?) : OpenSubtitlesException(message, 406)

    /** HTTP 429: too many requests; retry after [retryAfterSeconds] if known. */
    class RateLimited(message: String, val retryAfterSeconds: Long?) : OpenSubtitlesException(message, 429)

    /** Any other non-2xx response. */
    class Http(statusCode: Int, val body: String) : OpenSubtitlesException("HTTP $statusCode: ${body.take(200)}", statusCode)

    /** The response body could not be understood. */
    class InvalidResponse(message: String, cause: Throwable? = null) : OpenSubtitlesException(message, null) {
        init {
            cause?.let { initCause(it) }
        }
    }
}

// ---- wire DTOs ------------------------------------------------------------------------------

@Serializable
internal data class SearchResponseDto(
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    val page: Int = 1,
    val data: List<SubtitleItemDto> = emptyList(),
)

@Serializable
internal data class SubtitleItemDto(
    val id: String? = null,
    val type: String? = null,
    val attributes: SubtitleAttributesDto? = null,
)

@Serializable
internal data class SubtitleAttributesDto(
    @SerialName("subtitle_id") val subtitleId: String? = null,
    val language: String? = null,
    @SerialName("download_count") val downloadCount: Int = 0,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    val hd: Boolean = false,
    val fps: Double? = null,
    val ratings: Double? = null,
    @SerialName("from_trusted") val fromTrusted: Boolean? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    @SerialName("ai_translated") val aiTranslated: Boolean = false,
    @SerialName("machine_translated") val machineTranslated: Boolean = false,
    val release: String? = null,
    val uploader: UploaderDto? = null,
    @SerialName("feature_details") val featureDetails: FeatureDetailsDto? = null,
    val url: String? = null,
    val files: List<FileDto> = emptyList(),
    @SerialName("moviehash_match") val moviehashMatch: Boolean = false,
)

@Serializable
internal data class UploaderDto(
    @SerialName("uploader_id") val uploaderId: Long? = null,
    val name: String? = null,
    val rank: String? = null,
)

@Serializable
internal data class FeatureDetailsDto(
    @SerialName("feature_type") val featureType: String? = null,
    val year: Int? = null,
    val title: String? = null,
    @SerialName("movie_name") val movieName: String? = null,
    @SerialName("imdb_id") val imdbId: Long? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("parent_title") val parentTitle: String? = null,
)

@Serializable
internal data class FileDto(
    @SerialName("file_id") val fileId: Long? = null,
    @SerialName("cd_number") val cdNumber: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
)

@Serializable
internal data class DownloadRequestDto(@SerialName("file_id") val fileId: Long)

@Serializable
internal data class DownloadResponseDto(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    val requests: Int? = null,
    val remaining: Int? = null,
    val message: String? = null,
    @SerialName("reset_time") val resetTime: String? = null,
    @SerialName("reset_time_utc") val resetTimeUtc: String? = null,
)

@Serializable
internal data class LanguagesResponseDto(val data: List<LanguageDto> = emptyList())

@Serializable
internal data class LanguageDto(
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("language_name") val languageName: String? = null,
)

@Serializable
internal data class LoginRequestDto(val username: String, val password: String)

@Serializable
internal data class LoginResponseDto(
    val user: LoginUserDto? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val token: String? = null,
    val status: Int? = null,
)

@Serializable
internal data class LoginUserDto(
    @SerialName("allowed_downloads") val allowedDownloads: Int? = null,
    val level: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    val vip: Boolean = false,
)

@Serializable
internal data class ErrorResponseDto(
    val message: String? = null,
    val errors: List<String> = emptyList(),
    @SerialName("reset_time_utc") val resetTimeUtc: String? = null,
)
