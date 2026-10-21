package com.ruterika.castcore.subtitles

import java.util.Locale

/**
 * A single timed subtitle cue.
 *
 * @property startMs start time in milliseconds.
 * @property endMs end time in milliseconds.
 * @property lines text lines; markup is kept as found in the source (see [SubtitleDocument.markup]).
 * @property id optional cue identifier (WebVTT cue id).
 * @property settings optional WebVTT cue settings (e.g. `align:start position:10%`).
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val lines: List<String>,
    val id: String? = null,
    val settings: String? = null,
) {
    /** The cue text with lines joined by `\n`. */
    val text: String get() = lines.joinToString("\n")
}

/** The markup dialect used inside cue lines of a [SubtitleDocument]. */
enum class CueMarkup {
    /** SRT-style HTML subset: `<i>`, `<b>`, `<u>`, `<font ...>`. */
    SRT_HTML,

    /** WebVTT cue text: `<i>`, `<b>`, `<c.class>`, `<v Speaker>`, entities like `&amp;`. */
    VTT,

    /** Plain text, no markup. */
    PLAIN,
}

/**
 * An in-memory subtitle track.
 *
 * @property cues cues in source order (parsers sort them by start time).
 * @property markup the markup dialect of the cue lines; writers convert as needed.
 * @property vttStyles raw WebVTT `STYLE` blocks (without the `STYLE` line) kept for VTT output.
 */
data class SubtitleDocument(
    val cues: List<SubtitleCue>,
    val markup: CueMarkup = CueMarkup.SRT_HTML,
    val vttStyles: List<String> = emptyList(),
) {
    /**
     * Returns a copy with all cues moved by [offsetMs] (positive = later).
     * Start times are clamped at 0 and cues that end at or before 0 are dropped.
     */
    fun shifted(offsetMs: Long): SubtitleDocument {
        if (offsetMs == 0L) return this
        val moved = cues.mapNotNull { cue ->
            val end = cue.endMs + offsetMs
            if (end <= 0) null else cue.copy(startMs = (cue.startMs + offsetMs).coerceAtLeast(0), endMs = end)
        }
        return copy(cues = moved)
    }
}

/** Helpers for parsing and formatting subtitle timestamps. */
object SubtitleTime {
    private val TIMESTAMP = Regex("""^(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?$""")

    /**
     * Parses `hh:mm:ss,mmm`, `hh:mm:ss.mmm`, `mm:ss.mmm`, `h:mm:ss.cc` (and similar
     * sloppy variants) into milliseconds. The fractional part is interpreted as a
     * decimal fraction of a second (`.5` = 500 ms, `.50` = 500 ms). Returns null if invalid.
     */
    fun parse(value: String): Long? {
        val m = TIMESTAMP.matchEntire(value.trim()) ?: return null
        val (h, min, s, frac) = m.destructured
        val hours = h.ifEmpty { "0" }.toLong()
        val millis = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').toLong()
        return ((hours * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    /** Formats milliseconds as `hh:mm:ss<sep>mmm` (negative values are clamped to 0). */
    fun format(ms: Long, separator: Char): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val milli = t % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, separator, milli)
    }

    /** Formats milliseconds as an ASS timestamp `h:mm:ss.cc` (centiseconds, rounded). */
    fun formatAss(ms: Long): String {
        val cs = (ms.coerceAtLeast(0) + 5) / 10
        val h = cs / 360_000
        val m = cs / 6_000 % 60
        val s = cs / 100 % 60
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs % 100)
    }
}
