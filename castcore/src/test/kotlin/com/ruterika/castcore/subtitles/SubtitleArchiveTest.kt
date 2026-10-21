package com.ruterika.castcore.subtitles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleArchiveTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun plainBytesPassThrough() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()
        val (out, name) = SubtitleArchive.unwrap(bytes, "a.srt")
        assertArrayEquals(bytes, out)
        assertEquals("a.srt", name)
    }

    @Test
    fun picksFirstSubtitleEntry() {
        val archive = zip(
            "__MACOSX/._movie.srt" to "junk",
            "readme.nfo" to "info",
            "notes.txt" to "text",
            "Subs/movie.tr.srt" to "1\n00:00:01,000 --> 00:00:02,000\nMerhaba\n",
            "movie.en.srt" to "second"
        )
        assertTrue(SubtitleArchive.isZip(archive))
        val (out, name) = SubtitleArchive.unwrap(archive, "subs.zip")
        assertEquals("movie.tr.srt", name)
        assertTrue(String(out).contains("Merhaba"))
        // The extracted file parses.
        assertEquals(1, SubtitleConverter.normalize(out, name).cueCount)
    }

    @Test
    fun txtIsLastResort() {
        val (_, name) = SubtitleArchive.unwrap(zip("a.nfo" to "x", "sub.txt" to "1\n00:00:01,000 --> 00:00:02,000\nA\n"), "x.zip")
        assertEquals("sub.txt", name)
    }

    @Test
    fun archiveWithoutSubtitlesFails() {
        try {
            SubtitleArchive.unwrap(zip("movie.nfo" to "x", "cover.jpg" to "y"), "x.zip")
            fail()
        } catch (e: SubtitleParseException) {
            // expected
        }
    }

    @Test
    fun suspiciousNames() {
        assertTrue(SubtitleArchive.isSuspiciousName("movie.mp4"))
        assertTrue(SubtitleArchive.isSuspiciousName("photo.JPG"))
        assertFalse(SubtitleArchive.isSuspiciousName("movie.SRT"))
        assertFalse(SubtitleArchive.isSuspiciousName("subs.zip"))
        assertFalse(SubtitleArchive.isSuspiciousName("subtitle"))
        assertFalse(SubtitleArchive.isSuspiciousName(null))
        assertFalse(SubtitleArchive.isSuspiciousName("https://x.com/a/b.vtt?token=1"))
    }
}
