package com.ruterika.castcore.subtitles

/**
 * Serializes a [SubtitleDocument] to SubRip or WebVTT text (callers encode the result as
 * UTF-8). Cue markup is converted between dialects as needed (see [CueMarkup]).
 */
object SubtitleWriter {
    private val ASS_TAG = Regex("""\{\\[^}]*\}""")
    private val ASS_TOP = Regex("""\{\\an[789]\}""")
    private val FONT_TAG = Regex("""</?font\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SIMPLE_TAG = Regex("""<(/?)([ibuIBU])>""")
    private val VTT_TAG = Regex("""<(/?)([a-zA-Z]+)?[^>]*>""")
    private val VTT_TIMESTAMP_TAG = Regex("""<\d[^>]*>""")
    private val BARE_AMP = Regex("""&(?![a-zA-Z]+;|#\d+;|#x[0-9a-fA-F]+;)""")
    private val LT_NOT_TAG = Regex("""<(?!/?[ibu]>)""")

    /** Writes [doc] as SubRip text with sequential indices and `hh:mm:ss,mmm` timestamps. */
    fun toSrt(doc: SubtitleDocument): String {
        val sb = StringBuilder()
        var index = 1
        for (cue in doc.cues) {
            val lines = cue.lines.map { toSrtLine(it, doc.markup) }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            sb.append(index++).append('\n')
            sb.append(SubtitleTime.format(cue.startMs, ',')).append(" --> ").append(SubtitleTime.format(cue.endMs, ',')).append('\n')
            lines.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Writes [doc] as WebVTT text, keeping cue ids, cue settings and STYLE blocks. */
    fun toVtt(doc: SubtitleDocument): String {
        val sb = StringBuilder("WEBVTT\n\n")
        for (style in doc.vttStyles) sb.append("STYLE\n").append(style.trimEnd()).append("\n\n")
        for (cue in doc.cues) {
            val lines = cue.lines.map { toVttLine(it, doc.markup) }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val settings = cue.settings
                ?: if (doc.markup != CueMarkup.VTT && cue.lines.any { ASS_TOP.containsMatchIn(it) }) "line:0" else null
            cue.id?.takeIf { "-->" !in it }?.let { sb.append(it).append('\n') }
            sb.append(SubtitleTime.format(cue.startMs, '.')).append(" --> ").append(SubtitleTime.format(cue.endMs, '.'))
            settings?.let { sb.append(' ').append(it) }
            sb.append('\n')
            lines.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun toSrtLine(line: String, markup: CueMarkup): String = when (markup) {
        CueMarkup.SRT_HTML, CueMarkup.PLAIN -> line.trim()
        CueMarkup.VTT -> {
            var s = VTT_TIMESTAMP_TAG.replace(line, "")
            s = VTT_TAG.replace(s) { m ->
                val name = m.groupValues[2].lowercase()
                if (name == "i" || name == "b" || name == "u") "<${m.groupValues[1]}$name>" else ""
            }
            s.replace("&nbsp;", "\u00A0").replace("&lrm;", "\u200E").replace("&rlm;", "\u200F").trim()
        }
    }

    private fun toVttLine(line: String, markup: CueMarkup): String = when (markup) {
        CueMarkup.VTT -> line.trim().replace("-->", "--&gt;")
        CueMarkup.SRT_HTML, CueMarkup.PLAIN -> {
            var s = ASS_TAG.replace(line, "")
            s = FONT_TAG.replace(s, "")
            s = SIMPLE_TAG.replace(s) { m -> "<${m.groupValues[1]}${m.groupValues[2].lowercase()}>" }
            s = BARE_AMP.replace(s, "&amp;")
            s = LT_NOT_TAG.replace(s, "&lt;")
            s.replace("-->", "--&gt;").trim()
        }
    }
}
