package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParsersTest {

    // ---- time ----------------------------------------------------------------------------------

    @Test
    fun timeParsing() {
        assertEquals(3_723_456L, SubtitleTime.parse("01:02:03,456"))
        assertEquals(3_723_456L, SubtitleTime.parse("01:02:03.456"))
        assertEquals(63_500L, SubtitleTime.parse("01:03.5"))
        assertEquals(63_500L, SubtitleTime.parse("0:01:03.50"))
        assertEquals(63_000L, SubtitleTime.parse(" 00:01:03 "))
        assertEquals(1_500L, SubtitleTime.parse("00:00:01:500"))
        assertNull(SubtitleTime.parse("abc"))
        assertNull(SubtitleTime.parse("12"))
    }

    @Test
    fun timeFormatting() {
        assertEquals("01:02:03,456", SubtitleTime.format(3_723_456, ','))
        assertEquals("00:00:00.000", SubtitleTime.format(-5, '.'))
        assertEquals("100:00:00.000", SubtitleTime.format(360_000_000, '.'))
        assertEquals("1:02:03.46", SubtitleTime.formatAss(3_723_456))
        assertEquals("0:00:00.00", SubtitleTime.formatAss(0))
    }

    // ---- SRT -----------------------------------------------------------------------------------

    @Test
    fun srtBasic() {
        val doc = SrtParser.parse(
            "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nWorld\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\n<i>Second</i>\r\n",
        )
        assertEquals(2, doc.cues.size)
        assertEquals(SubtitleCue(1000, 2500, listOf("Hello", "World")), doc.cues[0])
        assertEquals(listOf("<i>Second</i>"), doc.cues[1].lines)
        assertEquals(CueMarkup.SRT_HTML, doc.markup)
    }

    @Test
    fun srtTolerantOfMissingIndicesDotsAndWhitespace() {
        val text = """

            00:00:01.000-->00:00:02.000
              First line
            3
              00:00:03,5  -->  00:00:04,25   X1:100 X2:200 Y1:10 Y2:20
            <font color="#ffff00">Second</font>

            7
            0:00:05,000 --> 0:00:06,000
            Third


        """.trimIndent()
        val doc = SrtParser.parse(text)
        assertEquals(3, doc.cues.size)
        assertEquals(SubtitleCue(1000, 2000, listOf("First line")), doc.cues[0])
        assertEquals(SubtitleCue(3500, 4250, listOf("<font color=\"#ffff00\">Second</font>")), doc.cues[1])
        assertEquals(SubtitleCue(5000, 6000, listOf("Third")), doc.cues[2])
    }

    @Test
    fun srtNumericTextIsKeptWhenNotAnIndex() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n42\n\n2\n00:00:03,000 --> 00:00:04,000\n3\n2\n\n3\n00:00:05,000 --> 00:00:06,000\nGo\n"
        val doc = SrtParser.parse(text)
        assertEquals(listOf("42"), doc.cues[0].lines)
        assertEquals(listOf("3", "2"), doc.cues[1].lines)
        assertEquals(listOf("Go"), doc.cues[2].lines)
    }

    @Test
    fun srtInternalBlankLinesAndEmptyCues() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nA\n\nB\n\n2\n00:00:03,000 --> 00:00:04,000\n\n3\n00:00:05,000 --> 00:00:04,000\nBad end\n"
        val doc = SrtParser.parse(text)
        assertEquals(2, doc.cues.size)
        assertEquals(listOf("A", "B"), doc.cues[0].lines)
        assertEquals(5000L, doc.cues[1].startMs)
        assertEquals(7000L, doc.cues[1].endMs)
    }

    @Test
    fun srtSortsCuesAndKeepsAssAlignmentTags() {
        val text = "2\n00:00:05,000 --> 00:00:06,000\n{\\an8}Top\n\n1\n00:00:01,000 --> 00:00:02,000\nFirst\n"
        val doc = SrtParser.parse(text)
        assertEquals(listOf("First"), doc.cues[0].lines)
        assertEquals(listOf("{\\an8}Top"), doc.cues[1].lines)
    }

    @Test
    fun srtGarbageYieldsNoCues() {
        assertTrue(SrtParser.parse("just some text\nwithout timing").cues.isEmpty())
    }

    // ---- VTT -----------------------------------------------------------------------------------

    @Test
    fun vttWithHeadersNotesStylesAndSettings() {
        val text = """
            WEBVTT - Some title
            Kind: captions
            Language: tr

            NOTE This is a comment
            spanning lines

            STYLE
            ::cue { color: yellow }

            REGION
            id:fred width:40%

            intro
            00:01.000 --> 00:04.000 align:start position:10%
            <v Roger>Merhaba</v>
            <c.yellow>dünya</c>

            00:00:05.250 --> 00:00:06.000
            Second &amp; last
        """.trimIndent()
        val doc = VttParser.parse(text)
        assertEquals(CueMarkup.VTT, doc.markup)
        assertEquals(listOf("::cue { color: yellow }"), doc.vttStyles)
        assertEquals(2, doc.cues.size)
        val first = doc.cues[0]
        assertEquals(1000L, first.startMs)
        assertEquals(4000L, first.endMs)
        assertEquals("intro", first.id)
        assertEquals("align:start position:10%", first.settings)
        assertEquals(listOf("<v Roger>Merhaba</v>", "<c.yellow>dünya</c>"), first.lines)
        assertEquals(SubtitleCue(5250, 6000, listOf("Second &amp; last")), doc.cues[1])
    }

    @Test
    fun vttCueRightAfterHeaderWithoutBlankLine() {
        val doc = VttParser.parse("WEBVTT\n00:01.000 --> 00:02.000\nHi\n")
        assertEquals(1, doc.cues.size)
        assertEquals(listOf("Hi"), doc.cues[0].lines)
    }

    // ---- MicroDVD ------------------------------------------------------------------------------

    @Test
    fun microDvdUsesFpsParameter() {
        val doc = MicroDvdParser.parse("{25}{50}Hello|World\n{75}{100}{y:i}Italic\n", fps = 25.0)
        assertEquals(2, doc.cues.size)
        assertEquals(SubtitleCue(1000, 2000, listOf("Hello", "World")), doc.cues[0])
        assertEquals(SubtitleCue(3000, 4000, listOf("<i>Italic</i>")), doc.cues[1])
    }

    @Test
    fun microDvdDefaultFps() {
        val doc = MicroDvdParser.parse("{23976}{47952}Hi")
        assertEquals(1_000_000L, doc.cues[0].startMs)
        assertEquals(2_000_000L, doc.cues[0].endMs)
    }

    @Test
    fun microDvdDeclaredFpsAndMissingEnd() {
        val doc = MicroDvdParser.parse("{1}{1}25.000\n{25}{}{Y:b}One|{c:\$0000ff}Two\n{50}{75}Next\n{200}{}Last")
        assertEquals(3, doc.cues.size)
        assertEquals(SubtitleCue(1000, 2000, listOf("<b>One</b>", "<b>Two</b>")), doc.cues[0])
        assertEquals(SubtitleCue(8000, 11000, listOf("Last")), doc.cues[2])
    }

    // ---- ASS -----------------------------------------------------------------------------------

    private val ass = """
        [Script Info]
        Title: Test
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour
        Style: Default,Arial,20,&H00FFFFFF

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:05.00,0:00:07.50,Default,,0,0,0,,{\an8}Üstte, virgüllü\Nikinci satır
        Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Not shown
        Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\i1}İtalik{\i0} normal\h metin
        Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
        Dialogue: 0,0:00:08.00,0:00:09.00,Default,,0,0,0,,{\pos(10,10)\b1}Bold
    """.trimIndent()

    @Test
    fun assParsesDialogueAndStripsOverrides() {
        val doc = AssParser.parse(ass)
        assertEquals(3, doc.cues.size)
        assertEquals(SubtitleCue(1000, 2000, listOf("<i>İtalik</i> normal metin")), doc.cues[0])
        assertEquals(SubtitleCue(5000, 7500, listOf("Üstte, virgüllü", "ikinci satır")), doc.cues[1])
        assertEquals(SubtitleCue(8000, 9000, listOf("<b>Bold</b>")), doc.cues[2])
    }

    @Test
    fun assCustomFormatOrder() {
        val text = "[Events]\nFormat: Start, End, Text, Style\nDialogue: 0:00:01.00,0:00:02.00,Hi there,Default\n"
        val doc = AssParser.parse(text)
        assertEquals(listOf("Hi there"), doc.cues.single().lines)
    }

    @Test
    fun assShiftInPlaceKeepsEverythingElse() {
        val shifted = AssParser.shift(ass, 1500)
        assertTrue(shifted.contains("Dialogue: 0,0:00:06.50,0:00:09.00,Default,,0,0,0,,{\\an8}Üstte, virgüllü\\Nikinci satır"))
        assertTrue(shifted.contains("Comment: 0,0:00:02.50,0:00:03.50,Default,,0,0,0,,Not shown"))
        assertTrue(shifted.contains("Style: Default,Arial,20,&H00FFFFFF"))
        assertTrue(shifted.contains("[V4+ Styles]"))
    }

    @Test
    fun assNegativeShiftDropsAndClamps() {
        val shifted = AssParser.shift(ass, -2500)
        assertTrue(!shifted.contains("İtalik")) // ended at 2.0s
        assertTrue(shifted.contains("Dialogue: 0,0:00:00.50,0:00:01.50,Default,,0,0,0,,{\\p1}"))
        assertTrue(shifted.contains("Dialogue: 0,0:00:02.50,0:00:05.00,Default"))
        val doc = AssParser.parse(AssParser.shift(ass, -7600))
        assertEquals(listOf("<b>Bold</b>"), doc.cues.single().lines)
        assertEquals(400L, doc.cues.single().startMs)
        val clamped = AssParser.parse(AssParser.shift(ass, -5500))
        assertEquals(0L, clamped.cues[0].startMs)
        assertEquals(2000L, clamped.cues[0].endMs)
    }

    @Test
    fun assTextConversion() {
        assertNull(AssParser.convertText("{\\p1}m 0 0 l 1 1"))
        assertEquals(listOf("<i>a</i>", "b"), AssParser.convertText("{\\i1}a{\\i0}\\Nb"))
        assertEquals(listOf("<i>open</i>"), AssParser.convertText("{\\i1}open"))
        assertEquals(listOf("soft break"), AssParser.convertText("soft\\nbreak"))
    }

    @Test
    fun ssaV4IsNotAdvanced() {
        assertTrue(AssParser.isAdvanced(ass))
        assertTrue(!AssParser.isAdvanced("[Script Info]\nScriptType: v4.00\n[V4 Styles]\n"))
    }

    // ---- dispatcher / shifting ------------------------------------------------------------------

    @Test
    fun documentShift() {
        val doc = SubtitleDocument(
            listOf(SubtitleCue(0, 1000, listOf("a")), SubtitleCue(1500, 3000, listOf("b")), SubtitleCue(5000, 6000, listOf("c"))),
        )
        assertEquals(listOf(500L, 2000L, 5500L), doc.shifted(500).cues.map { it.startMs })
        val back = doc.shifted(-2000)
        assertEquals(2, back.cues.size)
        assertEquals(SubtitleCue(0, 1000, listOf("b")), back.cues[0])
        assertEquals(SubtitleCue(3000, 4000, listOf("c")), back.cues[1])
        assertTrue(doc.shifted(-1000).cues.none { it.lines == listOf("a") })
        assertTrue(doc.shifted(0) === doc)
    }

    @Test(expected = SubtitleParseException::class)
    fun ttmlIsNotParsed() {
        SubtitleParser.parse("<tt/>", SubtitleFormat.TTML)
    }
}
