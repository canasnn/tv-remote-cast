package com.ruterika.castcore.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class PlaylistFilesTest {

    @Test
    fun detectsFormats() {
        assertEquals(PlaylistFormat.M3U, PlaylistFiles.detectFormat("#EXTM3U\n#EXTINF:-1,A\nhttp://a"))
        assertEquals(PlaylistFormat.W3U, PlaylistFiles.detectFormat("{\"name\":\"x\",\"stations\":[]}"))
        assertEquals(PlaylistFormat.W3U, PlaylistFiles.detectFormat("{\"name\":\"x\"}", nameHint = "http://h/list.w3u"))
        assertEquals(PlaylistFormat.M3U, PlaylistFiles.detectFormat("garbage", lenient = true))
        try {
            PlaylistFiles.detectFormat("   ")
            fail()
        } catch (_: EmptyPlaylistException) {
        }
        try {
            PlaylistFiles.detectFormat("{\"error\":\"unauthorized\"}")
            fail()
        } catch (e: NotAnM3uPlaylistException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun extensions() {
        assertEquals("m3u", PlaylistFiles.extensionOf("http://h/a/list.m3u?x=1"))
        assertEquals("w3u", PlaylistFiles.extensionOf("Liste.W3U"))
        assertEquals("php", PlaylistFiles.extensionOf("http://h/get.php"))
        assertNull(PlaylistFiles.extensionOf("content://provider/document/12"))
    }

    @Test
    fun storesHashesAndReadsGzip() {
        val dir = Files.createTempDirectory("plfiles").toFile()
        try {
            val text = "#EXTM3U\n#EXTINF:-1,Kanal\nhttp://s/k.ts\n"
            val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
            val target = File(dir, "x.raw")
            val stored = PlaylistFiles.store(gz.inputStream(), target)
            assertEquals(gz.size.toLong(), stored.bytes)
            assertEquals(40, stored.sha1.length)
            assertEquals(stored.sha1, PlaylistFiles.store(gz.inputStream(), File(dir, "y.raw")).sha1)
            val entries = ArrayList<M3uEntry>()
            PlaylistFiles.openReader(target.inputStream()).use { PlaylistFiles.parse(it, onEntry = entries::add) }
            assertEquals(listOf("Kanal"), entries.map { it.title })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun xtreamUrls() {
        val url = XtreamCodes.buildUrl("host.tv:8080/", "ali veli", "p&ss", XtreamCodes.Output.M3U8)
        assertEquals("http://host.tv:8080/get.php?username=ali+veli&password=p%26ss&type=m3u_plus&output=m3u8", url)
        assertEquals(
            "https://h.tv/get.php?username=u&password=p&type=m3u_plus&output=ts",
            XtreamCodes.buildUrl("https://h.tv/player_api.php", "u", "p")
        )
        val login = XtreamCodes.parse(url)!!
        assertEquals("http://host.tv:8080", login.server)
        assertEquals("ali veli", login.username)
        assertEquals("p&ss", login.password)
        assertEquals(XtreamCodes.Output.M3U8, login.output)
        assertNull(XtreamCodes.parse("http://h/list.m3u"))
    }

    @Test
    fun nestedListDetection() {
        fun e(url: String, attrs: Map<String, String> = emptyMap()) = M3uEntry("x", url, attributes = attrs)
        assertTrue(e("http://h/other.m3u").isNestedList)
        assertTrue(e("http://h/get.php?username=a&password=b&type=m3u_plus").isNestedList)
        assertTrue(e("http://h/a.w3u").isNestedList)
        assertTrue(e("http://h/feed.rss").isNestedList)
        assertTrue(e("http://h/x", mapOf("type" to "playlist")).isNestedList)
        assertFalse(e("http://h/live.m3u8").isNestedList)
        assertFalse(e("http://h/live/u/p/1.ts").isNestedList)
    }

    @Test
    fun groupPathUsesSubgroups() {
        val entry = M3uEntry("x", "http://a", group = "Film", attributes = mapOf("pltv-subgroup" to "Aksiyon"))
        assertEquals(listOf("Film", "Aksiyon"), entry.groupPath())
        assertEquals(listOf("Film"), entry.groupPath(considerSubgroups = false))
        assertEquals(emptyList<String>(), M3uEntry("x", "http://a").groupPath())
    }
}
