package com.canasnn.tvremote.cast.download

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.canasnn.tvremote.MainActivity
import com.canasnn.tvremote.R
import com.canasnn.tvremote.cast.CastHub
import com.ruterika.castcore.download.HlsSegments
import com.ruterika.castcore.library.DownloadFormat
import com.ruterika.castcore.library.DownloadItem
import com.ruterika.castcore.library.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Works through the download queue one item at a time (foreground, dataSync).
 * Plain files resume with Range requests; HLS/DASH are converted to MP4 with
 * Media3 Transformer.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop?.isActive != true) loop = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private var current: Job? = null
    private var currentId: String? = null

    private suspend fun runQueue() {
        // Items interrupted by a process death continue.
        CastHub.library.data.value.downloads
            .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONVERTING }
            .forEach { stale -> CastHub.library.updateDownload(stale.id) { it.copy(status = DownloadStatus.QUEUED) } }
        while (true) {
            val item = CastHub.library.nextQueuedDownload() ?: break
            currentId = item.id
            val job = scope.launch { process(item) }
            current = job
            job.join()
            currentId = null
        }
        stopSelf()
    }

    private suspend fun process(item: DownloadItem) {
        if (CastHub.settings.value.downloadWifiOnly && !Downloads.isOnWifi(this)) {
            fail(item.id, "Your settings allow downloads on Wi-Fi only.")
            return
        }
        try {
            when (item.format) {
                DownloadFormat.ORIGINAL -> copyFile(item)
                DownloadFormat.MP4_FROM_HLS -> {
                    withContext(Dispatchers.IO) { ensureNotLive(item) }
                    convert(item)
                }
                DownloadFormat.MP4_FROM_DASH -> convert(item)
                DownloadFormat.TS_FROM_HLS -> if (!joinTs(item)) {
                    // Encrypted or fMP4 segments can't simply be joined: convert to MP4 instead.
                    val mp4 = item.copy(format = DownloadFormat.MP4_FROM_HLS, mimeType = "video/mp4", fileName = item.fileName.substringBeforeLast('.') + ".mp4")
                    CastHub.library.updateDownload(item.id) { it.copy(format = mp4.format, mimeType = mp4.mimeType, fileName = mp4.fileName) }
                    convert(mp4)
                }
            }
            saveSubtitle(item.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "[Download] ${item.fileName} failed")
            val retries = (CastHub.library.download(item.id)?.attempts ?: 0) + 1
            if (e is RetryableHttp && retries < MAX_ATTEMPTS) {
                CastHub.library.updateDownload(item.id) { it.copy(status = DownloadStatus.QUEUED, attempts = retries, error = e.message) }
                delay(RETRY_DELAY_MS)
            } else {
                fail(item.id, e.message ?: "There was an error downloading")
            }
        } finally {
            updateNotification()
        }
    }

    private class RetryableHttp(code: Int) : Exception("Server responded with error code: $code")

    private class LiveStream : Exception("This is a live stream. Live streams can't be downloaded.")

    // ── HLS helpers ─────────────────────────────────────────────────────────

    private fun fetch(url: String, item: DownloadItem): okhttp3.Response {
        val request = Request.Builder().url(url).apply { Downloads.requestHeaders(item).forEach { (k, v) -> header(k, v) } }.build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw RetryableHttp(response.code)
        }
        return response
    }

    /** The media playlist of [item] (a master playlist is followed to its best variant): text and URL. */
    private fun mediaPlaylist(item: DownloadItem): Pair<String, String> {
        var url = item.url
        var text = fetch(url, item).use { it.body.string() }
        HlsSegments.bestVariantUrl(text, url)?.let { variant ->
            url = variant
            text = fetch(variant, item).use { it.body.string() }
        }
        return text to url
    }

    /** A playlist without an end never finishes. */
    private fun ensureNotLive(item: DownloadItem) {
        val (text, url) = mediaPlaylist(item)
        if (HlsSegments.parseMedia(text, url).live) throw LiveStream()
    }

    /**
     * HLS → one .ts file by joining the segments. Returns
     * false when the stream can't be joined (encrypted / fMP4).
     */
    private suspend fun joinTs(start: DownloadItem): Boolean = withContext(Dispatchers.IO) {
        val (text, playlistUrl) = mediaPlaylist(start)
        val playlist = HlsSegments.parseMedia(text, playlistUrl)
        if (playlist.live) throw LiveStream()
        if (!playlist.joinableAsTs) return@withContext false
        var item = start
        val out = item.fileUri?.let(Uri::parse) ?: Downloads.createOutput(this@DownloadService, item).also { uri ->
            CastHub.library.updateDownload(item.id) { it.copy(fileUri = uri.toString()) }
            item = item.copy(fileUri = uri.toString())
        }
        CastHub.library.updateDownload(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, downloadedBytes = 0L, totalBytes = 0L, error = null) }
        var done = 0L
        var lastReport = 0L
        val count = playlist.segments.size
        contentResolver.openOutputStream(out, "wt")!!.use { sink ->
            for ((index, segment) in playlist.segments.withIndex()) {
                if (!isActive) break
                val bytes = segmentBytes(segment, item)
                sink.write(bytes)
                done += bytes.size
                val now = System.currentTimeMillis()
                if (now - lastReport > PROGRESS_INTERVAL_MS) {
                    lastReport = now
                    val bytesSoFar = done
                    // Total is estimated from the average segment so far.
                    val estimate = bytesSoFar * count / (index + 1)
                    CastHub.library.updateDownload(item.id) { it.copy(downloadedBytes = bytesSoFar, totalBytes = estimate) }
                    withContext(Dispatchers.Main) { updateNotification() }
                }
            }
        }
        if (!isActive) return@withContext true
        Downloads.publish(this@DownloadService, out)
        val size = done
        CastHub.library.updateDownload(item.id) { it.copy(status = DownloadStatus.COMPLETE, downloadedBytes = size, totalBytes = size) }
        true
    }

    /** One segment, retried a few times before the whole download counts as failed. */
    private suspend fun segmentBytes(url: String, item: DownloadItem): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return fetch(url, item).use { it.body.bytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= SEGMENT_ATTEMPTS) throw e
                delay(1000L * attempt)
            }
        }
    }

    /** "Download subtitles too": the first subtitle of the video goes next to it (best effort). */
    private suspend fun saveSubtitle(id: String) = withContext(Dispatchers.IO) {
        val item = CastHub.library.download(id) ?: return@withContext
        val url = item.subtitleUrl ?: return@withContext
        if (item.status != DownloadStatus.COMPLETE) return@withContext
        runCatching {
            val bytes = if (url.startsWith("http", ignoreCase = true)) fetch(url, item).use { it.body.bytes() }
            else contentResolver.openInputStream(Uri.parse(url))!!.use { it.readBytes() }
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in SUBTITLE_EXTENSIONS } ?: "srt"
            val mime = when (ext) {
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                else -> "application/octet-stream"
            }
            val out = Downloads.createOutput(this@DownloadService, item.fileName.substringBeforeLast('.') + "." + ext, mime)
            contentResolver.openOutputStream(out, "wt")!!.use { it.write(bytes) }
            Downloads.publish(this@DownloadService, out)
        }.onFailure { Timber.w(it, "[Download] Subtitle of ${item.fileName} not saved") }
    }

    /** Plain HTTP copy; appends with a Range request when a partial file exists. */
    private suspend fun copyFile(start: DownloadItem) = withContext(Dispatchers.IO) {
        var item = start
        val out = item.fileUri?.let(Uri::parse) ?: Downloads.createOutput(this@DownloadService, item).also { uri ->
            CastHub.library.updateDownload(item.id) { it.copy(fileUri = uri.toString()) }
            item = item.copy(fileUri = uri.toString())
        }
        val existing = if (item.fileUri != null && item.downloadedBytes > 2) item.downloadedBytes else 0L
        val request = Request.Builder().url(item.url).apply {
            Downloads.requestHeaders(item).forEach { (k, v) -> header(k, v) }
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RetryableHttp(response.code)
            val resumed = response.code == 206 && existing > 0
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.body.contentLength().takeIf { it > 0 }?.plus(if (resumed) existing else 0L) ?: 0L
            var done = if (resumed) existing else 0L
            CastHub.library.updateDownload(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, totalBytes = total, downloadedBytes = done, error = null) }
            contentResolver.openOutputStream(out, if (resumed) "wa" else "wt")!!.use { sink ->
                response.body.byteStream().use { source ->
                    val buffer = ByteArray(128 * 1024)
                    var lastReport = 0L
                    while (isActive) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            val bytes = done
                            CastHub.library.updateDownload(item.id) { it.copy(downloadedBytes = bytes) }
                            withContext(Dispatchers.Main) { updateNotification() }
                        }
                    }
                }
            }
            if (!isActive) return@use
            Downloads.publish(this@DownloadService, out)
            val finalBytes = done
            CastHub.library.updateDownload(item.id) { it.copy(status = DownloadStatus.COMPLETE, downloadedBytes = finalBytes, totalBytes = maxOf(total, finalBytes)) }
        }
    }

    /** HLS / DASH → MP4 with Media3 Transformer (remux when possible), then copy into Downloads. */
    @OptIn(UnstableApi::class)
    private suspend fun convert(item: DownloadItem) {
        val temp = File(cacheDir, "download-${item.id}.mp4")
        temp.delete()
        CastHub.library.updateDownload(item.id) { it.copy(status = DownloadStatus.CONVERTING, convertPercent = -1, error = null) }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(Downloads.requestHeaders(item))
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val transformer = Transformer.Builder(this)
            .setAssetLoaderFactory(
                DefaultAssetLoaderFactory(
                    this, DefaultDecoderFactory.Builder(this).build(), androidx.media3.common.util.Clock.DEFAULT,
                    sources, androidx.media3.datasource.DataSourceBitmapLoader(this)
                )
            )
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(item.url)
            .setMimeType(if (item.format == DownloadFormat.MP4_FROM_HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
            .build()
        val progressJob = scope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = holder.progress
                    CastHub.library.updateDownload(item.id) { it.copy(convertPercent = percent) }
                    updateNotification()
                }
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                transformer.start(EditedMediaItem.Builder(mediaItem).build(), temp.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            progressJob.cancel()
        }
        withContext(Dispatchers.IO) {
            val out = Downloads.createOutput(this@DownloadService, item)
            contentResolver.openOutputStream(out, "wt")!!.use { sink -> temp.inputStream().use { it.copyTo(sink, 128 * 1024) } }
            Downloads.publish(this@DownloadService, out)
            val size = temp.length()
            temp.delete()
            CastHub.library.updateDownload(item.id) {
                it.copy(status = DownloadStatus.COMPLETE, fileUri = out.toString(), downloadedBytes = size, totalBytes = size, convertPercent = 100)
            }
        }
    }

    private fun fail(id: String, message: String) {
        CastHub.library.updateDownload(id) { it.copy(status = DownloadStatus.FAILED, error = message) }
    }

    // ── Notification / locks ────────────────────────────────────────────────

    private fun startForegroundCompat() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type) }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(currentTitle())
        .setContentText(progressText())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, progressPercent(), progressPercent() < 0)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()

    private fun currentItem(): DownloadItem? = currentId?.let { CastHub.library.download(it) }

    private fun currentTitle(): String {
        val active = CastHub.library.data.value.downloads.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONVERTING }
        return if (active > 1) "Downloading $active files" else currentItem()?.fileName ?: getString(R.string.app_name)
    }

    private fun progressPercent(): Int {
        val item = currentItem() ?: return -1
        return when {
            item.status == DownloadStatus.CONVERTING -> item.convertPercent
            item.totalBytes > 0 -> (item.downloadedBytes * 100 / item.totalBytes).toInt()
            else -> -1
        }
    }

    private fun progressText(): String {
        val item = currentItem() ?: return "Download pending"
        return when {
            item.status == DownloadStatus.CONVERTING -> if (item.convertPercent >= 0) "Converting ${item.convertPercent}%" else "Converting…"
            item.totalBytes > 0 -> "${progressPercent()}%"
            item.downloadedBytes > 0 -> "${item.downloadedBytes / (1024 * 1024)} MB"
            else -> "Download pending"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvRemote:download")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvRemote:download").apply { acquire() }
        }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 23426
        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val MAX_ATTEMPTS = 10
        private const val RETRY_DELAY_MS = 10_000L
        private const val SEGMENT_ATTEMPTS = 3
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "dfxp")

        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        @Volatile private var instance: DownloadService? = null

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        /** Stops the item that is downloading right now (pause / remove). */
        fun cancelIfRunning(id: String) {
            val service = instance ?: return
            if (service.currentId == id) service.current?.cancel()
        }
    }
}
