package com.ruterika.castcore.subtitles

private fun splitLines(text: String): List<String> =
    text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')

/** Thrown when subtitle text cannot be parsed into any cues. */
class SubtitleParseException(message: String) : IllegalArgumentException(message)

/**
 * Entry point that parses decoded text in a given [SubtitleFormat] into a [SubtitleDocument].
 */
object SubtitleParser {
    /** Default frame rate used for frame based formats when none is known. */
    const val DEFAULT_FPS: Double = 23.976

    /**
     * Parses [text] as [format]. TTML is not parsed (it is passed through by
     * [SubtitleConverter]); [SubtitleFormat.UNKNOWN] is attempted as SRT.
     *
     * @throws SubtitleParseException for TTML input.
     */
    fun parse(text: String, format: SubtitleFormat, fps: Double = DEFAULT_FPS): SubtitleDocument = when (format) {
        SubtitleFormat.SRT, SubtitleFormat.UNKNOWN -> SrtParser.parse(text)
        SubtitleFormat.VTT -> VttParser.parse(text)
        SubtitleFormat.SSA -> AssParser.parse(text)
        SubtitleFormat.MICRODVD -> MicroDvdParser.parse(text, fps)
        SubtitleFormat.TTML -> throw SubtitleParseException("TTML is passed through, not parsed")
    }
}

/**
 * Tolerant SubRip parser: accepts missing or wrong cue indices, `.` or `,` as the
 * millisecond separator, missing hours, sloppy whitespace and missing blank lines.
 * Formatting tags (`<i>`, `<b>`, `<u>`, `<font>`, `{\an8}`) are kept verbatim.
 */
object SrtParser {
    private const val TIME = """(?:\d{1,3}:)?\d{1,2}:\d{1,2}(?:[,.:]\d{1,3})?"""
    private val TIMING = Regex("""^\s*($TIME)\s*-{1,2}>\s*($TIME)(?:\s.*)?$""")
    private val INDEX = Regex("""^\d+$""")

    /** Parses SRT [text]; cues are returned sorted by start time. */
    fun parse(text: String): SubtitleDocument {
        val lines = splitLines(text)
        val cues = ArrayList<SubtitleCue>()
        var start = 0L
        var end = 0L
        var body: MutableList<String>? = null

        fun finish(nextIsTiming: Boolean) {
            val raw = body ?: return
            if (nextIsTiming && raw.isNotEmpty() && INDEX.matches(raw.last().trim())) raw.removeAt(raw.size - 1)
            val textLines = raw.map { it.trim() }.filter { it.isNotEmpty() }
            if (textLines.isNotEmpty()) {
                val fixedEnd = if (end > start) end else start + 2000
                cues += SubtitleCue(start, fixedEnd, textLines)
            }
            body = null
        }

        for (line in lines) {
            val m = TIMING.matchEntire(line)
            val s = m?.let { SubtitleTime.parse(it.groupValues[1]) }
            val e = m?.let { SubtitleTime.parse(it.groupValues[2]) }
            if (s != null && e != null) {
                finish(nextIsTiming = true)
                start = s
                end = e
                body = ArrayList()
            } else {
                body?.add(line)
            }
        }
        finish(nextIsTiming = false)
        return SubtitleDocument(cues.sortedBy { it.startMs }, CueMarkup.SRT_HTML)
    }
}

/**
 * WebVTT parser. Skips the `WEBVTT` header block, `NOTE` and `REGION` blocks, keeps `STYLE`
 * blocks, cue identifiers and cue settings. Accepts `mm:ss.ttt` and `hh:mm:ss.ttt` timestamps.
 */
object VttParser {
    private val TIMING = Regex("""^\s*([\d:.,]+)\s*-->\s*([\d:.,]+)(.*)$""")

    /** Parses WebVTT [text]; cues are returned sorted by start time. */
    fun parse(text: String): SubtitleDocument {
        val blocks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in splitLines(text)) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) blocks += current
                current = ArrayList()
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) blocks += current

        val cues = ArrayList<SubtitleCue>()
        val styles = ArrayList<String>()
        blocks.forEachIndexed { index, block ->
            val first = block[0].trim()
            when {
                index == 0 && first.startsWith("WEBVTT") -> {
                    // A header block may (illegally) contain the first cue without a blank line.
                    val timingIdx = block.indexOfFirst { "-->" in it }
                    if (timingIdx > 0) parseCue(block.subList(maxOf(1, timingIdx - 1), block.size), cues)
                }
                first == "NOTE" || first.startsWith("NOTE ") || first.startsWith("NOTE\t") -> Unit
                first == "STYLE" -> styles += block.drop(1).joinToString("\n")
                first == "REGION" || (first.startsWith("REGION") && "-->" !in first) -> Unit
                else -> parseCue(block, cues)
            }
        }
        return SubtitleDocument(cues.sortedBy { it.startMs }, CueMarkup.VTT, styles)
    }

    private fun parseCue(block: List<String>, out: MutableList<SubtitleCue>) {
        val timingIdx = block.indexOfFirst { "-->" in it }
        if (timingIdx < 0 || timingIdx > 1) return
        val m = TIMING.matchEntire(block[timingIdx]) ?: return
        val start = SubtitleTime.parse(m.groupValues[1]) ?: return
        val end = SubtitleTime.parse(m.groupValues[2]) ?: return
        val lines = block.drop(timingIdx + 1).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        out += SubtitleCue(
            startMs = start,
            endMs = if (end > start) end else start + 2000,
            lines = lines,
            id = if (timingIdx == 1) block[0].trim() else null,
            settings = m.groupValues[3].trim().ifEmpty { null },
        )
    }
}

/**
 * MicroDVD parser (`{startFrame}{endFrame}text`, `|` separates lines).
 *
 * A first cue of the form `{1}{1}25.000` (a common convention) overrides [parse]'s `fps`.
 * Style codes `{y:i}`, `{y:b}`, `{y:u}` (lower case = this line, upper case `{Y:...}` = whole
 * cue) are mapped to `<i>`/`<b>`/`<u>`; all other `{...}` control codes are removed.
 */
object MicroDvdParser {
    private val LINE = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""")
    private val CONTROL = Regex("""\{([a-zA-Z]):([^}]*)\}""")
    private val ANY_BRACES = Regex("""\{[^}]*\}""")

    /** Parses MicroDVD [text] using [fps] (frames per second) to convert frames to time. */
    fun parse(text: String, fps: Double = SubtitleParser.DEFAULT_FPS): SubtitleDocument {
        data class Raw(val start: Long, val end: Long?, val text: String)

        val raws = splitLines(text).mapNotNull { line ->
            LINE.matchEntire(line.trim())?.let { m ->
                Raw(m.groupValues[1].toLong(), m.groupValues[2].toLongOrNull(), m.groupValues[3])
            }
        }.toMutableList()

        var effectiveFps = if (fps > 0) fps else SubtitleParser.DEFAULT_FPS
        raws.firstOrNull()?.let { first ->
            val declared = first.text.trim().toDoubleOrNull()
            if (first.start <= 1 && (first.end ?: 1) <= 1 && declared != null && declared in 1.0..200.0) {
                effectiveFps = declared
                raws.removeAt(0)
            }
        }

        fun toMs(frame: Long): Long = Math.round(frame * 1000.0 / effectiveFps)

        val sorted = raws.sortedBy { it.start }
        val cues = sorted.mapIndexedNotNull { i, raw ->
            val lines = convertText(raw.text)
            if (lines.isEmpty()) return@mapIndexedNotNull null
            val startMs = toMs(raw.start)
            val endMs = raw.end?.let { toMs(it) }?.takeIf { it > startMs }
                ?: minOf(startMs + 3000, sorted.getOrNull(i + 1)?.let { toMs(it.start) }?.takeIf { it > startMs } ?: Long.MAX_VALUE)
            SubtitleCue(startMs, endMs, lines)
        }
        return SubtitleDocument(cues, CueMarkup.SRT_HTML)
    }

    private fun convertText(text: String): List<String> {
        val globalStyles = HashSet<Char>()
        return text.split('|').mapNotNull { part ->
            val local = HashSet<Char>()
            CONTROL.findAll(part).forEach { m ->
                if (m.groupValues[1].equals("y", ignoreCase = true)) {
                    val target = if (m.groupValues[1] == "Y") globalStyles else local
                    m.groupValues[2].lowercase().forEach { if (it in "ibu") target += it }
                }
            }
            val plain = ANY_BRACES.replace(part, "").trim()
            if (plain.isEmpty()) return@mapNotNull null
            val styles = (globalStyles + local).sorted()
            styles.joinToString("") { "<$it>" } + plain + styles.reversed().joinToString("") { "</$it>" }
        }
    }
}

/**
 * Minimal SubStation Alpha / Advanced SubStation Alpha parser: reads `Dialogue:` lines of the
 * `[Events]` section using its `Format:` line. Override blocks (`{\an8}`, `{\pos(...)}`...) are
 * removed, except `\i` / `\b` / `\u` which become `<i>` / `<b>` / `<u>`. `\N` becomes a line
 * break, `\n` and `\h` become spaces. Drawing cues (`{\p1}`) are skipped.
 */
object AssParser {
    private val DEFAULT_FORMAT = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    private val OVERRIDE = Regex("""\{([^}]*)\}""")
    private val DRAWING = Regex("""\\p[1-9]""")
    private val STYLE_TOGGLE = Regex("""\\([ibu])(\d+)""")

    /** Parses ASS/SSA [text]; cues are returned sorted by start time. */
    fun parse(text: String): SubtitleDocument {
        val cues = ArrayList<SubtitleCue>()
        forEachEventLine(text) { kind, fields, values ->
            if (kind != "dialogue") return@forEachEventLine
            val start = values.getOrNull(fields.indexOf("start"))?.let { SubtitleTime.parse(it) } ?: return@forEachEventLine
            val end = values.getOrNull(fields.indexOf("end"))?.let { SubtitleTime.parse(it) } ?: return@forEachEventLine
            val body = values.getOrNull(fields.indexOf("text")) ?: return@forEachEventLine
            val lines = convertText(body) ?: return@forEachEventLine
            if (lines.isNotEmpty() && end > start) cues += SubtitleCue(start, end, lines)
        }
        return SubtitleDocument(cues.sortedBy { it.startMs }, CueMarkup.SRT_HTML)
    }

    /**
     * Shifts all `Dialogue:`/`Comment:` event times in ASS/SSA [text] by [offsetMs], keeping
     * everything else (styles, positioning, karaoke) untouched. Events that end at or before 0
     * are removed; start times are clamped at 0. Line endings are normalized to `\n`.
     */
    fun shift(text: String, offsetMs: Long): String {
        val lines = splitLines(text)
        if (offsetMs == 0L) return lines.joinToString("\n")
        val out = ArrayList<String>(lines.size)
        var section = ""
        var fields = DEFAULT_FORMAT
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) section = trimmed.lowercase()
            if (section == "[events]") {
                val colon = trimmed.indexOf(':')
                val key = if (colon > 0) trimmed.substring(0, colon).trim().lowercase() else ""
                if (key == "format") fields = parseFormat(trimmed.substring(colon + 1))
                if (key == "dialogue" || key == "comment") {
                    val values = trimmed.substring(colon + 1).trimStart().split(",", limit = fields.size).toMutableList()
                    val si = fields.indexOf("start")
                    val ei = fields.indexOf("end")
                    val s = values.getOrNull(si)?.let { SubtitleTime.parse(it) }
                    val e = values.getOrNull(ei)?.let { SubtitleTime.parse(it) }
                    if (s != null && e != null) {
                        val newEnd = e + offsetMs
                        if (newEnd <= 0) continue
                        values[si] = SubtitleTime.formatAss((s + offsetMs).coerceAtLeast(0))
                        values[ei] = SubtitleTime.formatAss(newEnd)
                        out += trimmed.substring(0, colon) + ": " + values.joinToString(",")
                        continue
                    }
                }
            }
            out += line
        }
        return out.joinToString("\n")
    }

    /** True when the script declares ASS (`[V4+ Styles]`) rather than SSA v4. */
    fun isAdvanced(text: String): Boolean = text.contains("[V4+ Styles]", ignoreCase = true) ||
        text.contains("ScriptType: v4.00+", ignoreCase = true)

    private fun parseFormat(value: String): List<String> =
        value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_FORMAT }

    private inline fun forEachEventLine(text: String, block: (kind: String, fields: List<String>, values: List<String>) -> Unit) {
        var section = ""
        var fields = DEFAULT_FORMAT
        for (line in splitLines(text)) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.lowercase()
                continue
            }
            if (section != "[events]" && section != "") continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim().lowercase()
            val rest = trimmed.substring(colon + 1)
            when (key) {
                "format" -> if (section == "[events]") fields = parseFormat(rest)
                "dialogue", "comment" -> block(key, fields, rest.trimStart().split(",", limit = fields.size))
            }
        }
    }

    /** Converts ASS event text to cue lines, or null for drawing events. */
    internal fun convertText(text: String): List<String>? {
        val sb = StringBuilder()
        val open = LinkedHashSet<Char>()
        var last = 0
        for (m in OVERRIDE.findAll(text)) {
            sb.append(text, last, m.range.first)
            last = m.range.last + 1
            val tags = m.groupValues[1]
            if (DRAWING.containsMatchIn(tags)) return null
            for (t in STYLE_TOGGLE.findAll(tags)) {
                val tag = t.groupValues[1][0]
                val on = t.groupValues[2] != "0"
                if (on && tag !in open) {
                    open += tag
                    sb.append('<').append(tag).append('>')
                } else if (!on && tag in open) {
                    open -= tag
                    sb.append("</").append(tag).append('>')
                }
            }
        }
        sb.append(text, last, text.length)
        open.reversed().forEach { sb.append("</").append(it).append('>') }
        val converted = sb.toString().replace("\\N", "\n").replace("\\n", " ").replace("\\h", " ")
        return converted.split('\n').map { it.trim().replace(Regex(" {2,}"), " ") }
            .filter { it.isNotEmpty() && it.replace(Regex("</?[ibu]>"), "").isNotBlank() }
    }
}
