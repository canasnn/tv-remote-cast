package com.canasnn.tvremote.cast.subtitles

import android.content.Context
import android.net.Uri
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.library.SavedSubtitle
import com.ruterika.castcore.subtitles.DownloadedSubtitle
import com.ruterika.castcore.subtitles.SubtitleConverter
import com.ruterika.castcore.subtitles.SubtitleNaming
import com.ruterika.castcore.subtitles.SubtitleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/**
 * Subtitles the user downloaded or picked are kept on the phone
 * ("Previously downloaded subtitles"), checked before they are sent, and
 * attached as `file://` URLs that survive app restarts.
 */
object SubtitleLibrary {

    @Volatile
    private var instance: SubtitleStore? = null

    fun store(context: Context): SubtitleStore = instance ?: synchronized(this) {
        instance ?: SubtitleStore(File(context.applicationContext.filesDir, "cast/subtitles")).also { instance = it }
    }

    /**
     * Downloads (with [headers]) or reads [url], unzips it, checks that it has
     * cues and keeps it. Throws [com.ruterika.castcore.subtitles.SubtitleParseException]
     * when the file does not contain subtitles.
     */
    suspend fun obtain(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        language: String? = null,
        source: String? = null,
        mediaTitle: String? = null
    ): SavedSubtitle = withContext(Dispatchers.IO) {
        val (bytes, name) = SubtitlePreparer.load(context, url, headers)
        keep(context, bytes, name, language, source ?: url, mediaTitle)
    }

    /** Checks and stores already downloaded (unzipped) [bytes]. */
    fun keep(
        context: Context,
        bytes: ByteArray,
        fileName: String?,
        language: String?,
        source: String?,
        mediaTitle: String?,
        label: String? = null
    ): SavedSubtitle {
        validate(bytes, fileName)
        val lang = language ?: fileName?.let(SubtitleNaming::guessLanguage)
        val entry = store(context).save(bytes, fileName, lang, source, mediaTitle)
        return toSaved(context, entry, label)
    }

    /** Throws when [bytes] has no readable cues ("does not appear to contain subtitles"). */
    fun validate(bytes: ByteArray, fileName: String?) {
        val preferred = CastHub.settings.value.subtitleCharset.charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
        SubtitleConverter.normalize(bytes, fileName, 0L, preferred)
    }

    fun toSaved(context: Context, entry: DownloadedSubtitle, label: String? = null): SavedSubtitle = SavedSubtitle(
        url = Uri.fromFile(store(context).file(entry)).toString(),
        language = entry.language,
        label = label ?: labelFor(entry)
    )

    fun labelFor(entry: DownloadedSubtitle): String =
        listOfNotNull(entry.language?.let(SubtitleNaming::displayName), entry.fileName.substringBeforeLast('.'))
            .joinToString(" · ").take(60)
}
