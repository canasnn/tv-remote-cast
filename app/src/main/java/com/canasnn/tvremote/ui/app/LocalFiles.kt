package com.canasnn.tvremote.ui.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import com.canasnn.tvremote.cast.CastHub
import com.canasnn.tvremote.cast.player.PhonePlayer
import com.ruterika.castcore.library.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ─── Persisted choices of the local media tabs ────────────────────────────────

/** A folder the user opened with the system folder picker (persisted read grant). */
data class SafRoot(val title: String, val uri: String)

object LocalMediaPrefs {
    private const val PREFS = "local_media"
    private const val KEY_ROOTS = "saf_roots"
    private const val KEY_LAST_ROOT = "saf_last_root"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Sort choice per list ("Videos", "Photos", "Audio", "folders"). */
    fun sort(context: Context, key: String, default: LocalSort = LocalSort()): LocalSort {
        val p = prefs(context)
        val field = p.getString("sort_field_$key", null)?.let { runCatching { LocalSortField.valueOf(it) }.getOrNull() }
            ?: return default
        return LocalSort(field, p.getBoolean("sort_asc_$key", default.ascending))
    }

    fun setSort(context: Context, key: String, sort: LocalSort) {
        prefs(context).edit()
            .putString("sort_field_$key", sort.field.name)
            .putBoolean("sort_asc_$key", sort.ascending)
            .apply()
    }

    fun safRoots(context: Context): List<SafRoot> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_ROOTS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            SafRoot(o.optString("title"), o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null)
        }.sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())

    private fun saveRoots(context: Context, roots: List<SafRoot>) {
        val array = JSONArray()
        roots.forEach { array.put(JSONObject().put("title", it.title).put("uri", it.uri)) }
        prefs(context).edit().putString(KEY_ROOTS, array.toString()).apply()
    }

    /** Keeps the read grant of a picked folder and remembers it as a shortcut. */
    fun addSafRoot(context: Context, treeUri: Uri): SafRoot? {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { return null }
        val root = SafRoot(safRootTitle(treeUri), treeUri.toString())
        saveRoots(context, safRoots(context).filterNot { it.uri == root.uri } + root)
        setLastRoot(context, root.uri)
        return root
    }

    fun removeSafRoot(context: Context, root: SafRoot) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(root.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        saveRoots(context, safRoots(context).filterNot { it.uri == root.uri })
    }

    fun lastRoot(context: Context): String? = prefs(context).getString(KEY_LAST_ROOT, null)
    fun setLastRoot(context: Context, uri: String?) = prefs(context).edit().putString(KEY_LAST_ROOT, uri).apply()
}

/** "primary:Movies/Series" → "Series"; the volume root → "Internal storage". */
private fun safRootTitle(treeUri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
    val path = docId.substringAfter(':', "")
    val volume = docId.substringBefore(':')
    return path.trimEnd('/').substringAfterLast('/').ifBlank {
        if (volume.equals("primary", ignoreCase = true)) "Internal storage" else volume.ifBlank { "Folder" }
    }
}

// ─── Browsing a picked folder (Storage Access Framework) ─────────────────────

/** One level of the folder path inside a [SafRoot]. */
data class SafDir(val name: String, val documentId: String)

data class SafFolder(val name: String, val documentId: String, val modifiedMs: Long)

sealed interface SafListing {
    object Loading : SafListing
    data class Ok(val folders: List<SafFolder>, val files: List<MediaItemUi>) : SafListing
    data class Error(val message: String) : SafListing
}

fun safRootDir(root: SafRoot): SafDir? = runCatching {
    SafDir(root.title, DocumentsContract.getTreeDocumentId(Uri.parse(root.uri)))
}.getOrNull()

/** Sub-folders and castable files (video, audio, image) of [dir]. */
suspend fun listSafFolder(context: Context, root: SafRoot, dir: SafDir): SafListing = withContext(Dispatchers.IO) {
    val treeUri = Uri.parse(root.uri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.documentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    try {
        val folders = mutableListOf<SafFolder>()
        val files = mutableListOf<MediaItemUi>()
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: return@withContext SafListing.Error("Error reading folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2).orEmpty()
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += SafFolder(name, docId, modified)
                    continue
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                localMediaItem(uri, name, mime, size, modified)?.let { files += it }
            }
        }
        SafListing.Ok(folders, files)
    } catch (e: SecurityException) {
        SafListing.Error("The app no longer has access to this folder. Remove it and add it again.")
    } catch (e: Exception) {
        SafListing.Error("Error reading folder")
    }
}

/** Files picked with the system file picker. Keeps the read grant so playlists keep working. */
suspend fun pickedMediaItems(context: Context, uris: List<Uri>): List<MediaItemUi> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri ->
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        }
        val title = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Media"
        val mime = context.contentResolver.getType(uri).orEmpty()
        localMediaItem(uri, title, mime, size, 0L)
    }
}

private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "3gp", "flv", "wmv", "mpg", "mpeg")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

/** A castable file as a list item, or null when it isn't video, audio or an image. */
private fun localMediaItem(uri: Uri, name: String, mime: String, size: Long, modifiedMs: Long): MediaItemUi? {
    val ext = name.substringAfterLast('.', "").lowercase()
    val kind = when {
        mime.startsWith("video/") || (mime.isGenericMime() && ext in VIDEO_EXTENSIONS) -> MediaKind.Video
        mime.startsWith("audio/") || (mime.isGenericMime() && ext in AUDIO_EXTENSIONS) -> MediaKind.Audio
        mime.startsWith("image/") || (mime.isGenericMime() && ext in IMAGE_EXTENSIONS) -> MediaKind.Photo
        else -> return null
    }
    val effectiveMime = mime.takeUnless { it.isGenericMime() } ?: when (kind) {
        MediaKind.Video -> "video/*"
        MediaKind.Audio -> "audio/*"
        MediaKind.Photo -> "image/*"
    }
    return MediaItemUi(
        id = uri.toString().hashCode().toLong(),
        title = name,
        subtitle = "",
        uri = uri.toString(),
        thumbnailUri = uri.toString().takeIf { kind != MediaKind.Audio },
        mimeType = effectiveMime,
        kind = kind,
        sizeBytes = size,
        dateModifiedMs = modifiedMs
    )
}

private fun String.isGenericMime() = isEmpty() || this == "application/octet-stream" || this == "*/*"

fun formatFileInfo(context: Context, item: MediaItemUi): String = listOfNotNull(
    item.sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
    item.dateModifiedMs.takeIf { it > 0 }?.let {
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
).joinToString(" · ")

// ─── Item actions ─────────────────────────────────────────────────────────────

fun playLocalOnPhone(context: Context, items: List<MediaItemUi>, startIndex: Int = 0) {
    if (items.isEmpty()) return
    PhonePlayer.play(context, items.map { it.toSavedMedia() }, startIndex)
}

fun openLocalWith(context: Context, item: MediaItemUi) {
    val uri = Uri.parse(item.uri)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

fun shareLocal(context: Context, items: List<MediaItemUi>) {
    if (items.isEmpty()) return
    val uris = items.map { Uri.parse(it.uri) }
    val types = items.map { it.mimeType.substringBefore('/') }.distinct()
    val mime = if (types.size == 1) "${types.first()}/*" else "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(items.first().mimeType).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, "media", uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
