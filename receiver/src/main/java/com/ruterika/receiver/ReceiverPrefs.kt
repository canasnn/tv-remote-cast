package com.ruterika.receiver

import android.content.Context
import android.content.SharedPreferences
import com.ruterika.protocol.ProtocolJson
import com.ruterika.protocol.SubtitleStyle

/**
 * Persistent receiver state.
 *
 * Tokens: a phone may control this TV only after it handed us its token
 * through a start deep link. Those links arrive via the TV's system remote
 * service, i.e. only from a phone that is paired as an Android TV remote.
 */
class ReceiverPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    fun isTokenAllowed(token: String): Boolean =
        token.isNotBlank() && token in tokens()

    fun addToken(token: String) {
        if (token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) return
        // Most recent last; keep a few phones.
        val updated = (tokens().filter { it != token } + token).takeLast(MAX_TOKENS)
        prefs.edit().putString(KEY_TOKENS, updated.joinToString(SEPARATOR)).apply()
    }

    private fun tokens(): List<String> =
        prefs.getString(KEY_TOKENS, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    var subtitleStyle: SubtitleStyle
        get() = prefs.getString(KEY_SUBTITLE_STYLE, null)
            ?.let { runCatching { ProtocolJson.json.decodeFromString(SubtitleStyle.serializer(), it) }.getOrNull() }
            ?: SubtitleStyle()
        set(value) {
            prefs.edit()
                .putString(KEY_SUBTITLE_STYLE, ProtocolJson.json.encodeToString(SubtitleStyle.serializer(), value))
                .apply()
        }

    /** Last position per media URL, for "continue where you left off". */
    fun savePosition(url: String, positionMs: Long, durationMs: Long) {
        val key = KEY_POSITION_PREFIX + url.hashCode()
        val nearEnd = durationMs > 0 && durationMs - positionMs < 15_000
        if (positionMs < 10_000 || nearEnd) prefs.edit().remove(key).apply()
        else prefs.edit().putLong(key, positionMs).apply()
    }

    fun savedPosition(url: String): Long = prefs.getLong(KEY_POSITION_PREFIX + url.hashCode(), 0L)

    private companion object {
        const val KEY_TOKENS = "tokens"
        const val KEY_SUBTITLE_STYLE = "subtitle_style"
        const val KEY_POSITION_PREFIX = "pos_"
        const val SEPARATOR = ","
        const val MAX_TOKENS = 8
        const val MIN_TOKEN_LENGTH = 16
        const val MAX_TOKEN_LENGTH = 128
    }
}
