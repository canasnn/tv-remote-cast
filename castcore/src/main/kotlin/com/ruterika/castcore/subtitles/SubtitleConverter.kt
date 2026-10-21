package com.ruterika.castcore.subtitles

import java.nio.charset.Charset

/**
 * A subtitle ready to be served to the TV: UTF-8 [text] in a format Media3 plays natively.
 *
 * @property text the subtitle text (serve as UTF-8, see [toByteArray]).
 * @property mimeType MIME type to send to the receiver (Media3 `MimeTypes` value).
 * @property extension file extension without dot (`srt`, `vtt`, `ass`, `ssa`, `ttml`).
 * @property format the output format.
 * @property sourceFormat the detected input format.
 * @property sourceCharset the charset the input bytes were decoded with.
 * @property cueCount number of cues in the output, or null for pass-through formats (TTML).
 */
data class NormalizedSubtitle(
    val text: String,
    val mimeType: String,
    val extension: String,
    val format: SubtitleFormat,
    val sourceFormat: SubtitleFormat,
    val sourceCharset: Charset,
    val cueCount: Int?,
) {
    /** The UTF-8 bytes of [text]. */
    fun toByteArray(): ByteArray = text.toByteArray(Charsets.UTF_8)
}

/**
 * Turns raw subtitle bytes from any source into UTF-8 text in a Media3-friendly format,
 * optionally shifting the timing.
 */
object SubtitleConverter {
    private val XML_ENCODING = Regex("""(<\?xml[^>]*?encoding\s*=\s*["'])([^"']+)(["'])""")

    /**
     * Normalizes subtitle [bytes].
     *
     * - The charset is detected with [SubtitleCharset.decode] ([preferredCharset] is a hint).
     * - SRT → SRT and VTT → VTT with shifted times (tolerant parsing, clean re-serialization).
     * - ASS/SSA → kept as ASS/SSA with times shifted in place so styling survives
     *   (when [keepAss] is false it is converted to SRT instead).
     * - MicroDVD (`.sub`) → SRT using [fps] (Media3 cannot play MicroDVD).
     * - TTML → passed through unchanged (timing is not shifted), XML encoding declaration set to UTF-8.
     * - Unknown content is attempted as SRT, then VTT.
     *
     * Negative [offsetMs] values move subtitles earlier: start times clamp at 0 and cues that
     * end at or before 0 are dropped.
     *
     * @param fileName optional original file name, used when the content is ambiguous.
     * @throws SubtitleParseException if no cues could be read.
     */
    fun normalize(
        bytes: ByteArray,
        fileName: String? = null,
        offsetMs: Long = 0,
        preferredCharset: Charset? = null,
        fps: Double = SubtitleParser.DEFAULT_FPS,
        keepAss: Boolean = true,
    ): NormalizedSubtitle {
        val decoded = SubtitleCharset.decode(bytes, preferredCharset)
        val text = decoded.text
        val source = SubtitleFormat.detect(text, fileName)

        fun result(out: String, format: SubtitleFormat, ext: String = format.extension, cues: Int?) =
            NormalizedSubtitle(out, format.mimeType, ext, format, source, decoded.charset, cues)

        fun requireCues(doc: SubtitleDocument): SubtitleDocument {
            if (doc.cues.isEmpty()) throw SubtitleParseException("No subtitle cues found (format: $source)")
            return doc
        }

        return when (source) {
            SubtitleFormat.SRT -> {
                val doc = requireCues(SrtParser.parse(text)).shifted(offsetMs)
                result(SubtitleWriter.toSrt(doc), SubtitleFormat.SRT, cues = doc.cues.size)
            }
            SubtitleFormat.VTT -> {
                val doc = requireCues(VttParser.parse(text)).shifted(offsetMs)
                result(SubtitleWriter.toVtt(doc), SubtitleFormat.VTT, cues = doc.cues.size)
            }
            SubtitleFormat.SSA -> {
                val parsed = requireCues(AssParser.parse(text))
                if (keepAss) {
                    val shifted = AssParser.shift(text, offsetMs)
                    val ext = if (AssParser.isAdvanced(text)) "ass" else "ssa"
                    result(shifted, SubtitleFormat.SSA, ext, parsed.shifted(offsetMs).cues.size)
                } else {
                    val doc = parsed.shifted(offsetMs)
                    result(SubtitleWriter.toSrt(doc), SubtitleFormat.SRT, cues = doc.cues.size)
                }
            }
            SubtitleFormat.MICRODVD -> {
                val doc = requireCues(MicroDvdParser.parse(text, fps)).shifted(offsetMs)
                result(SubtitleWriter.toSrt(doc), SubtitleFormat.SRT, cues = doc.cues.size)
            }
            SubtitleFormat.TTML -> {
                val utf8 = XML_ENCODING.replace(text) { m -> m.groupValues[1] + "UTF-8" + m.groupValues[3] }
                result(utf8, SubtitleFormat.TTML, cues = null)
            }
            SubtitleFormat.UNKNOWN -> {
                val srt = SrtParser.parse(text)
                if (srt.cues.isNotEmpty()) {
                    val doc = srt.shifted(offsetMs)
                    result(SubtitleWriter.toSrt(doc), SubtitleFormat.SRT, cues = doc.cues.size)
                } else {
                    val doc = requireCues(VttParser.parse(text)).shifted(offsetMs)
                    result(SubtitleWriter.toVtt(doc), SubtitleFormat.VTT, cues = doc.cues.size)
                }
            }
        }
    }
}
