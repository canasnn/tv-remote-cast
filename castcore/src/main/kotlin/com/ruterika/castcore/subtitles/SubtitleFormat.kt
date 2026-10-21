package com.ruterika.castcore.subtitles

/**
 * Subtitle formats the phone understands.
 *
 * @property mimeType the MIME type Media3 expects for this format (as in `androidx.media3.common.MimeTypes`).
 * @property extension the canonical file extension (without dot).
 */
enum class SubtitleFormat(val mimeType: String, val extension: String) {
    /** SubRip (`.srt`). */
    SRT("application/x-subrip", "srt"),

    /** WebVTT (`.vtt`). */
    VTT("text/vtt", "vtt"),

    /** SubStation Alpha / Advanced SubStation Alpha (`.ssa` / `.ass`). */
    SSA("text/x-ssa", "ass"),

    /** MicroDVD frame based subtitles (`{123}{456}text`, usually `.sub`). Not playable by Media3. */
    MICRODVD("text/x-microdvd", "sub"),

    /** TTML / DFXP XML subtitles. */
    TTML("application/ttml+xml", "ttml"),

    /** Not recognized. */
    UNKNOWN("application/octet-stream", "txt");

    companion object {
        private val SRT_TIMING = Regex(
            """^\s*\d{1,3}:\d{1,2}:\d{1,2}(?:[,.:]\d{1,3})?\s*-{1,2}>\s*\d{1,3}:\d{1,2}:\d{1,2}(?:[,.:]\d{1,3})?""",
            RegexOption.MULTILINE,
        )
        private val VTT_SHORT_TIMING = Regex(
            """^\s*(?:\d{1,3}:)?\d{1,2}:\d{1,2}\.\d{1,3}\s*-->\s*(?:\d{1,3}:)?\d{1,2}:\d{1,2}\.\d{1,3}""",
            RegexOption.MULTILINE,
        )
        private val MICRODVD_LINE = Regex("""^\{\d+\}\{\d*\}""")
        private val SSA_MARKERS = Regex(
            """^\s*(?:\[Script Info\]|\[V4\+? Styles\]|\[Events\]|Dialogue:\s*\S)""",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
        )
        private val TTML_ROOT = Regex("""<tt[\s>:]""")

        /**
         * Detects the format of decoded subtitle [text]. Content signatures win; the
         * [fileName] extension is only used when the content is inconclusive.
         */
        fun detect(text: String, fileName: String? = null): SubtitleFormat {
            val head = text.removePrefix("\uFEFF").trimStart().let { if (it.length > 64_000) it.substring(0, 64_000) else it }
            fromContent(head)?.let { return it }
            return fileName?.let { fromFileName(it) } ?: UNKNOWN
        }

        /** Maps a file name (or URL path) extension to a format, or null if unknown. */
        fun fromFileName(fileName: String): SubtitleFormat? {
            val clean = fileName.substringBefore('?').substringBefore('#')
            return when (clean.substringAfterLast('.', "").lowercase()) {
                "srt" -> SRT
                "vtt", "webvtt" -> VTT
                "ass", "ssa" -> SSA
                "sub" -> MICRODVD
                "ttml", "dfxp", "xml" -> TTML
                else -> null
            }
        }

        private fun fromContent(head: String): SubtitleFormat? {
            if (head.startsWith("WEBVTT")) return VTT
            if (MICRODVD_LINE.containsMatchIn(head.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "")) {
                return MICRODVD
            }
            if (head.startsWith("<")) {
                if (TTML_ROOT.containsMatchIn(head)) return TTML
            }
            if (SSA_MARKERS.containsMatchIn(head)) return SSA
            if (SRT_TIMING.containsMatchIn(head)) return SRT
            if (VTT_SHORT_TIMING.containsMatchIn(head)) return VTT
            return null
        }
    }
}
