package com.canasnn.tvremote.cast.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.library.DownloadFormat
import com.ruterika.castcore.library.DownloadItem
import com.ruterika.castcore.library.DownloadStatus
import com.ruterika.castcore.library.SavedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Download queue. Items live in the library; [DownloadService]
 * works through the queue one at a time in the foreground.
 */
object Downloads {

    /**
     * Adds [media] to the queue; HLS and DASH are converted to MP4 (HLS can be
     * kept as .ts, see the "Save HLS streams as .ts" setting).
     */
    fun enqueue(context: Context, media: SavedMedia): DownloadItem {
        val settings = CastHub.settings.value
        val lower = media.url.substringBefore('?').lowercase()
        val mime = media.mimeType?.lowercase().orEmpty()
        val format = when {
            "mpegurl" in mime || lower.endsWith(".m3u8") -> if (settings.downloadHlsAsTs) DownloadFormat.TS_FROM_HLS else DownloadFormat.MP4_FROM_HLS
            "dash" in mime || lower.endsWith(".mpd") -> DownloadFormat.MP4_FROM_DASH
            else -> DownloadFormat.ORIGINAL
        }
        val outMime = when {
            format == DownloadFormat.TS_FROM_HLS -> TS_MIME
            format != DownloadFormat.ORIGINAL -> "video/mp4"
            mime.isNotBlank() && !mime.endsWith("/*") -> mime
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substringAfterLast('.', "")) ?: "video/mp4"
        }
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = media.url,
            title = media.title,
            fileName = fileNameFor(media.title, media.url, outMime),
            mimeType = outMime,
            format = format,
            headers = media.headers,
            pageUrl = media.pageUrl,
            posterUrl = media.posterUrl,
            subtitleUrl = if (settings.downloadSubtitles) media.subtitles.firstOrNull()?.url else null
        )
        CastHub.library.addDownload(item)
        DownloadService.start(context)
        return item
    }

    fun pause(id: String) {
        CastHub.library.updateDownload(id) { it.copy(status = DownloadStatus.PAUSED) }
        DownloadService.cancelIfRunning(id)
    }

    fun resume(context: Context, id: String) {
        CastHub.library.updateDownload(id) { it.copy(status = DownloadStatus.QUEUED, error = null, attempts = 0) }
        DownloadService.start(context)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingFileDeletes = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Removes the row at once; with [deleteFile] the saved file goes after
     * [UNDO_WINDOW_MS] unless [undoRemove] brings the download back first.
     */
    fun removeWithUndo(context: Context, item: DownloadItem, deleteFile: Boolean) {
        DownloadService.cancelIfRunning(item.id)
        CastHub.library.removeDownload(item.id)
        val uri = item.fileUri ?: return
        if (!deleteFile) return
        val app = context.applicationContext
        pendingFileDeletes[item.id] = scope.launch {
            delay(UNDO_WINDOW_MS)
            pendingFileDeletes.remove(item.id)
            runCatching { app.contentResolver.delete(Uri.parse(uri), null, null) }
        }
    }

    /** "Undo" after [removeWithUndo]: the row (and its file, if not deleted yet) come back. */
    fun undoRemove(item: DownloadItem) {
        pendingFileDeletes.remove(item.id)?.cancel()
        val running = item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.CONVERTING
        CastHub.library.addDownload(if (running) item.copy(status = DownloadStatus.PAUSED) else item)
    }

    /** Removes the row; with [deleteFile] also the saved file. */
    fun remove(context: Context, item: DownloadItem, deleteFile: Boolean) {
        DownloadService.cancelIfRunning(item.id)
        CastHub.library.removeDownload(item.id)
        if (deleteFile) item.fileUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
    }

    /** As something castable / playable (the file is served by the local server when cast). */
    fun asMedia(item: DownloadItem): SavedMedia? = item.fileUri?.let {
        SavedMedia(url = it, title = item.title, mimeType = item.mimeType, posterUrl = item.posterUrl)
    }

    fun openWith(context: Context, item: DownloadItem) {
        val uri = item.fileUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ── Helpers for the service ─────────────────────────────────────────────

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Request headers for the download: the page's headers + current cookies. */
    fun requestHeaders(item: DownloadItem): Map<String, String> {
        val cookie = runCatching { CookieManager.getInstance().getCookie(item.url) }.getOrNull()
        return item.headers.filterKeys { !it.equals("Range", true) && !it.equals("Cookie", true) } +
            (if (!cookie.isNullOrBlank()) mapOf("Cookie" to cookie) else emptyMap())
    }

    /** Creates the output file in Download/TV Remote and returns its content:// uri. */
    fun createOutput(context: Context, item: DownloadItem): Uri = createOutput(context, item.fileName, item.mimeType)

    fun createOutput(context: Context, fileName: String, mimeType: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the file")
        }
        val dir = File(ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_DOWNLOADS).first(), FOLDER).apply { mkdirs() }
        val file = File(dir, fileName)
        file.createNewFile()
        return FileProvider.getUriForFile(context, context.packageName + ".downloads", file)
    }

    /** Makes a finished MediaStore download visible to other apps. */
    fun publish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    private fun fileNameFor(title: String, url: String, mime: String): String {
        val ext = if (mime == TS_MIME) "ts"
        else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: url.substringBefore('?').substringAfterLast('.', "mp4").take(5)
        val base = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "video" }
        return if (base.lowercase().endsWith(".$ext")) base else "$base.$ext"
    }

    const val FOLDER = "TV Remote"
    const val TS_MIME = "video/mp2t"
    private const val UNDO_WINDOW_MS = 6_000L
}
