package com.ruterika.castcore.subtitles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Subtitle files come as plain text or inside a `.zip` (subtitle sites, OpenSubtitles
 * mirrors). [unwrap] returns the first subtitle file of an archive, anything else unchanged.
 */
object SubtitleArchive {

    /** Extensions of files we treat as subtitles (and accept without asking). */
    val SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "vtt", "webvtt", "ass", "ssa", "sub", "ttml", "dfxp", "xml", "txt", "smi")

    /** True for a ZIP local file header (`PK\u0003\u0004`). */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /**
     * True when [fileName] has an extension that is not a subtitle or zip extension (e.g. `movie.mp4`),
     * so the user should confirm before we try to read it. Names without an extension (many
     * subtitle URLs) are not suspicious.
     */
    fun isSuspiciousName(fileName: String?): Boolean {
        val ext = extension(fileName) ?: return false
        return ext !in SUBTITLE_EXTENSIONS && ext != "zip"
    }

    /**
     * When [bytes] is a zip archive, returns the first entry whose extension is a known subtitle
     * format (`.txt` only as a last resort) together with its name; otherwise returns the input.
     *
     * @throws SubtitleParseException when the archive holds no subtitle file.
     */
    fun unwrap(bytes: ByteArray, fileName: String?, maxBytes: Int = OpenSubtitlesClient.MAX_SUBTITLE_BYTES): Pair<ByteArray, String?> {
        if (!isZip(bytes)) return bytes to fileName
        var fallback: Pair<ByteArray, String>? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.isEmpty() || name.startsWith(".") || entry.name.startsWith("__MACOSX/")) continue
                val ext = extension(name) ?: continue
                if (ext !in SUBTITLE_EXTENSIONS) continue
                val content = readLimited(zip, maxBytes)
                if (ext == "txt" || ext == "xml") {
                    if (fallback == null) fallback = content to name
                    continue
                }
                return content to name
            }
        }
        return fallback ?: throw SubtitleParseException("The archive does not contain a subtitle file")
    }

    private fun readLimited(zip: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) throw SubtitleParseException("Subtitle inside the archive is too large")
        }
        return out.toByteArray()
    }

    private fun extension(fileName: String?): String? =
        fileName?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
            ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 6 }
}
