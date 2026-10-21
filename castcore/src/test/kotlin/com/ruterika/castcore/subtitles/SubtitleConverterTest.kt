package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleConverterTest {

    private val cp1254 = Charset.forName("windows-1254")

    // ---- writers -------------------------------------------------------------------------------

    @Test
    fun srtWriter() {
        val doc = SubtitleDocument(
            listOf(
                SubtitleCue(1000, 2500, listOf("<i>Merhaba</i>", "dünya")),
                SubtitleCue(3_723_456, 3_725_000, listOf("İkinci")),
            ),
        )
        val expected = "1\n00:00:01,000 --> 00:00:02,500\n<i>Merhaba</i>\ndünya\n\n" +
            "2\n01:02:03,456 --> 01:02:05,000\nİkinci\n\n"
        assertEquals(expected, SubtitleWriter.toSrt(doc))
    }

    @Test
    fun vttWriterFromSrtMarkup() {
        val doc = SubtitleDocument(
            listOf(
                SubtitleCue(1000, 2000, listOf("<font color=\"red\"><I>Tom & Jerry</I></font>", "a < b --> c")),
                SubtitleCue(3000, 4000, listOf("{\\an8}Top")),
            ),
        )
        val expected = "WEBVTT\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<i>Tom &amp; Jerry</i>\na &lt; b --&gt; c\n\n" +
            "00:00:03.000 --> 00:00:04.000 line:0\nTop\n\n"
        assertEquals(expected, SubtitleWriter.toVtt(doc))
    }

    @Test
    fun vttRoundTripKeepsIdsSettingsAndStyles() {
        val doc = SubtitleDocument(
            listOf(SubtitleCue(1000, 2000, listOf("<v Bob>Hi &amp; bye</v>"), id = "c1", settings = "align:start")),
            CueMarkup.VTT,
            listOf("::cue { color: lime }"),
        )
        val expected = "WEBVTT\n\nSTYLE\n::cue { color: lime }\n\n" +
            "c1\n00:00:01.000 --> 00:00:02.000 align:start\n<v Bob>Hi &amp; bye</v>\n\n"
        assertEquals(expected, SubtitleWriter.toVtt(doc))
    }

    @Test
    fun vttMarkupToSrt() {
        val doc = SubtitleDocument(
            listOf(SubtitleCue(0, 1000, listOf("<v Bob><i>Hi</i></v> <c.yellow>there</c><00:00:00.500>&nbsp;now"))),
            CueMarkup.VTT,
        )
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\n<i>Hi</i> there\u00A0now\n\n", SubtitleWriter.toSrt(doc))
    }

    // ---- normalize -----------------------------------------------------------------------------

    private val turkishSrt = "1\r\n00:00:01,000 --> 00:00:03,000\r\nŞimdi gitmeliyiz, ışıklar söndü.\r\n\r\n" +
        "2\r\n00:00:04,000 --> 00:00:05,000\r\n<i>Öğretmen geliyor!</i>\r\n"

    @Test
    fun srtIn1254BecomesUtf8Srt() {
        val result = SubtitleConverter.normalize(turkishSrt.toByteArray(cp1254), "film.tr.srt")
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals("application/x-subrip", result.mimeType)
        assertEquals("srt", result.extension)
        assertEquals(cp1254, result.sourceCharset)
        assertEquals(2, result.cueCount)
        val expected = "1\n00:00:01,000 --> 00:00:03,000\nŞimdi gitmeliyiz, ışıklar söndü.\n\n" +
            "2\n00:00:04,000 --> 00:00:05,000\n<i>Öğretmen geliyor!</i>\n\n"
        assertEquals(expected, result.text)
        assertEquals(expected, String(result.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun srtOffsetPositiveAndNegative() {
        val later = SubtitleConverter.normalize(turkishSrt.toByteArray(Charsets.UTF_8), offsetMs = 1500)
        assertTrue(later.text.startsWith("1\n00:00:02,500 --> 00:00:04,500\n"))

        val earlier = SubtitleConverter.normalize(turkishSrt.toByteArray(Charsets.UTF_8), offsetMs = -3500)
        assertEquals(1, earlier.cueCount)
        assertEquals("1\n00:00:00,500 --> 00:00:01,500\n<i>Öğretmen geliyor!</i>\n\n", earlier.text)

        val clamped = SubtitleConverter.normalize(turkishSrt.toByteArray(Charsets.UTF_8), offsetMs = -2000)
        assertTrue(clamped.text.startsWith("1\n00:00:00,000 --> 00:00:01,000\n"))
        assertEquals(2, clamped.cueCount)
    }

    @Test
    fun vttStaysVttAndShifts() {
        val vtt = "WEBVTT\n\nNOTE hi\n\n1\n00:01.000 --> 00:02.000 line:0\nMerhaba\n"
        val result = SubtitleConverter.normalize(vtt.toByteArray(), offsetMs = 250)
        assertEquals(SubtitleFormat.VTT, result.format)
        assertEquals("text/vtt", result.mimeType)
        assertEquals("WEBVTT\n\n1\n00:00:01.250 --> 00:00:02.250 line:0\nMerhaba\n\n", result.text)
    }

    private val assText = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nStyle: Default,Arial,20\n\n[Events]\n" +
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
        "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}Yukarıda\\Nikinci\n"

    @Test
    fun assIsKeptAndShiftedInPlace() {
        val result = SubtitleConverter.normalize(assText.toByteArray(cp1254), "x.ass", offsetMs = 1000)
        assertEquals(SubtitleFormat.SSA, result.format)
        assertEquals("text/x-ssa", result.mimeType)
        assertEquals("ass", result.extension)
        assertTrue(result.text.contains("Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,{\\an8}Yukarıda\\Nikinci"))
        assertTrue(result.text.contains("[V4+ Styles]"))
        assertEquals(1, result.cueCount)

        val unshifted = SubtitleConverter.normalize(assText.toByteArray(Charsets.UTF_8))
        assertEquals(assText.trimEnd('\n'), unshifted.text.trimEnd('\n'))
    }

    @Test
    fun assCanBeConvertedToSrt() {
        val result = SubtitleConverter.normalize(assText.toByteArray(Charsets.UTF_8), offsetMs = 500, keepAss = false)
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals(SubtitleFormat.SSA, result.sourceFormat)
        assertEquals("1\n00:00:01,500 --> 00:00:02,500\nYukarıda\nikinci\n\n", result.text)
    }

    @Test
    fun ssaV4Extension() {
        val ssa = "[Script Info]\nScriptType: v4.00\n\n[V4 Styles]\n\n[Events]\n" +
            "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
        assertEquals("ssa", SubtitleConverter.normalize(ssa.toByteArray()).extension)
    }

    @Test
    fun microDvdBecomesSrt() {
        val sub = "{1}{1}25\n{25}{50}Merhaba|dünya\n"
        val result = SubtitleConverter.normalize(sub.toByteArray(cp1254), "movie.sub")
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals(SubtitleFormat.MICRODVD, result.sourceFormat)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nMerhaba\ndünya\n\n", result.text)
    }

    @Test
    fun microDvdUsesFpsParameter() {
        val result = SubtitleConverter.normalize("{50}{100}Hi".toByteArray(), fps = 50.0)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n", result.text)
    }

    @Test
    fun ttmlPassThroughWithUtf8Declaration() {
        val ttml = "<?xml version=\"1.0\" encoding=\"ISO-8859-9\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p>Işık</p></body></tt>"
        val result = SubtitleConverter.normalize(ttml.toByteArray(Charset.forName("ISO-8859-9")), preferredCharset = Charset.forName("ISO-8859-9"))
        assertEquals(SubtitleFormat.TTML, result.format)
        assertEquals("application/ttml+xml", result.mimeType)
        assertNull(result.cueCount)
        assertTrue(result.text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(result.text.contains("<p>Işık</p>"))
    }

    @Test
    fun unknownContentFallsBackToSrtParsing() {
        val text = "garbage line\n00:00:01,000 --> 00:00:02,000\nHi\n"
        val result = SubtitleConverter.normalize(text.toByteArray())
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n", result.text)
    }

    @Test(expected = SubtitleParseException::class)
    fun noCuesThrows() {
        SubtitleConverter.normalize("this is not a subtitle".toByteArray(), "notes.txt")
    }

    @Test
    fun negativeOffsetCanDropEverything() {
        val result = SubtitleConverter.normalize(turkishSrt.toByteArray(), offsetMs = -60_000)
        assertEquals(0, result.cueCount)
        assertFalse(result.text.contains("-->"))
    }
}
