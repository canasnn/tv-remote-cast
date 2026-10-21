package com.canasnn.tvremote.cast.subtitles

import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.subtitles.OpenSubtitlesClient
import com.ruterika.castcore.subtitles.OpenSubtitlesException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One OpenSubtitles client for the whole app. The login token is kept in the
 * cast settings, so the user logs in once (from the account dialog) instead of
 * on every search and download. Without a login the API key works anonymously.
 */
object OpenSubtitlesAccount {

    private const val USER_AGENT = "TVRemoteCaster v1"
    const val REGISTER_URL = "https://www.opensubtitles.com/en/users/sign_up"
    const val API_KEY_URL = "https://www.opensubtitles.com/en/consumers"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private var cached: Pair<String, OpenSubtitlesClient>? = null

    /** Null until the user entered an API key. */
    @Synchronized
    fun client(): OpenSubtitlesClient? {
        val s = CastHub.settings.value
        val key = s.openSubtitlesApiKey.trim()
        if (key.isEmpty()) return null
        val cacheKey = key + "|" + s.openSubtitlesBaseUrl
        cached?.takeIf { it.first == cacheKey }?.let { (_, client) ->
            client.token = s.openSubtitlesToken.ifBlank { null }
            return client
        }
        val client = OpenSubtitlesClient(
            http, key, USER_AGENT,
            s.openSubtitlesBaseUrl.ifBlank { OpenSubtitlesClient.DEFAULT_BASE_URL }
        )
        client.token = s.openSubtitlesToken.ifBlank { null }
        cached = cacheKey to client
        return client
    }

    val isLoggedIn: Boolean get() = CastHub.settings.value.openSubtitlesToken.isNotBlank()

    /** Logs in and keeps the token; the password itself is not stored. */
    suspend fun login(user: String, password: String) {
        val client = client() ?: throw OpenSubtitlesException.Unauthorized("Add your OpenSubtitles API key first.")
        client.token = null
        val session = client.login(user.trim(), password)
        val base = client.currentBaseUrl
        CastHub.settingsStore.update {
            it.copy(
                openSubtitlesUser = user.trim(),
                openSubtitlesPassword = "",
                openSubtitlesToken = session.token,
                openSubtitlesBaseUrl = if (base == OpenSubtitlesClient.DEFAULT_BASE_URL) "" else base
            )
        }
    }

    /** Ends the session on the server and clears the stored credentials. */
    suspend fun logout() {
        runCatching { client()?.logout() }
        CastHub.settingsStore.update {
            it.copy(openSubtitlesUser = "", openSubtitlesPassword = "", openSubtitlesToken = "", openSubtitlesBaseUrl = "")
        }
    }

    /**
     * Runs [block] with the shared client. An expired token is dropped and the
     * call is repeated anonymously, so searches keep working.
     */
    suspend fun <T> call(block: suspend (OpenSubtitlesClient) -> T): T {
        val client = client() ?: throw OpenSubtitlesException.Unauthorized("Add your OpenSubtitles API key in settings.")
        return try {
            block(client)
        } catch (e: OpenSubtitlesException.Unauthorized) {
            if (client.token == null) throw e
            CastHub.settingsStore.update { it.copy(openSubtitlesToken = "", openSubtitlesBaseUrl = "") }
            block(client() ?: throw e)
        }
    }
}
