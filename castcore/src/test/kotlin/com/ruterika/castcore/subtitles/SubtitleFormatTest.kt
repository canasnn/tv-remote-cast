package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFormatTest {

    @Test
    fun detectsSrt() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect(srt))
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect("\uFEFF\n\n" + srt))
    }

    @Test
    fun detectsSrtWithDotsAndNoIndex() {
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect("0:00:01.000 --> 0:00:02.000\nHello\n"))
    }

    @Test
    fun detectsVtt() {
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect("WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect("WEBVTT - with title\n"))
        // Header-less VTT with short timestamps.
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect("00:01.000 --> 00:02.000\nHi\n"))
    }

    @Test
    fun detectsAss() {
        val ass = "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Text\n"
        assertEquals(SubtitleFormat.SSA, SubtitleFormat.detect(ass))
        assertEquals(SubtitleFormat.SSA, SubtitleFormat.detect("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi"))
    }

    @Test
    fun detectsMicroDvd() {
        assertEquals(SubtitleFormat.MICRODVD, SubtitleFormat.detect("{1}{1}23.976\n{100}{200}Hello|World\n"))
        assertEquals(SubtitleFormat.MICRODVD, SubtitleFormat.detect("{100}{}Hello\n"))
    }

    @Test
    fun detectsTtml() {
        val ttml = """<?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:01.000" end="00:00:02.000">Hi</p></div></body></tt>"""
        assertEquals(SubtitleFormat.TTML, SubtitleFormat.detect(ttml))
        assertEquals(SubtitleFormat.TTML, SubtitleFormat.detect("<tt:tt xmlns:tt=\"http://www.w3.org/ns/ttml\"></tt:tt>"))
    }

    @Test
    fun fileNameIsFallbackOnly() {
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect("garbage", "movie.SRT"))
        assertEquals(SubtitleFormat.SSA, SubtitleFormat.detect("garbage", "movie.ass"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect("garbage", "https://x.com/a/b.vtt?token=1"))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFormat.detect("garbage", "movie.txt"))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFormat.detect("garbage"))
        // Content beats a misleading extension.
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect("1\n00:00:01,000 --> 00:00:02,000\nHi", "movie.sub"))
        assertEquals(SubtitleFormat.MICRODVD, SubtitleFormat.detect("{1}{25}Hi", "movie.srt"))
    }

    @Test
    fun fromFileName() {
        assertEquals(SubtitleFormat.TTML, SubtitleFormat.fromFileName("a.dfxp"))
        assertEquals(SubtitleFormat.MICRODVD, SubtitleFormat.fromFileName("a.sub"))
        assertNull(SubtitleFormat.fromFileName("a"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/x-subrip", SubtitleFormat.SRT.mimeType)
        assertEquals("text/vtt", SubtitleFormat.VTT.mimeType)
        assertEquals("text/x-ssa", SubtitleFormat.SSA.mimeType)
        assertEquals("application/ttml+xml", SubtitleFormat.TTML.mimeType)
    }
}
