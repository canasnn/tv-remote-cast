package com.ruterika.castcore.iptv

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

class PlaylistLoaderTest {

    private class Route(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private lateinit var server: HttpServer
    private val routes = ConcurrentHashMap<String, Route>()
    private val seenUserAgents = ConcurrentHashMap<String, String>()
    private val seenReferers = ConcurrentHashMap<String, String>()
    private val loader = PlaylistLoader(OkHttpClient())

    private val sample = """
        #EXTM3U x-tvg-url="http://epg/guide.xml"
        #EXTINF:-1 tvg-id="trt1.tr" group-title="Ulusal",TRT 1
        http://stream/trt1.m3u8
        #EXTINF:-1 group-title="Haber",Habertürk
        http://stream/haberturk.ts
    """.trimIndent()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            exchange.requestHeaders.getFirst("User-Agent")?.let { seenUserAgents[path] = it }
            exchange.requestHeaders.getFirst("Referer")?.let { seenReferers[path] = it }
            val route = routes[path] ?: Route(404, "not found".toByteArray())
            route.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(route.status, if (route.body.isEmpty()) -1 else route.body.size.toLong())
            exchange.responseBody.use { it.write(route.body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun loadsPlaylistAndSendsUserAgent() = runBlocking {
        routes["/list.m3u"] = Route(200, sample.toByteArray(), mapOf("Content-Type" to "audio/x-mpegurl"))
        val playlist = loader.load(url("/list.m3u"), userAgent = "RuterikaTest/1.0")
        assertEquals(listOf("TRT 1", "Habertürk"), playlist.entries.map { it.title })
        assertEquals(listOf("http://epg/guide.xml"), playlist.epgUrls)
        assertEquals("RuterikaTest/1.0", seenUserAgents["/list.m3u"])
    }

    @Test
    fun pipeHeadersOnPlaylistUrlAreSent() = runBlocking {
        routes["/p.m3u"] = Route(200, sample.toByteArray())
        loader.load(url("/p.m3u") + "|User-Agent=PipeAgent&Referer=https://ref.example.com/")
        assertEquals("PipeAgent", seenUserAgents["/p.m3u"])
        assertEquals("https://ref.example.com/", seenReferers["/p.m3u"])
    }

    @Test
    fun handlesContentEncodingGzip() = runBlocking {
        routes["/enc.m3u"] = Route(200, gzip(sample), mapOf("Content-Encoding" to "gzip"))
        assertEquals(2, loader.load(url("/enc.m3u")).entries.size)
    }

    @Test
    fun handlesRawGzipFile() = runBlocking {
        routes["/tv.m3u.gz"] = Route(200, gzip(sample), mapOf("Content-Type" to "application/gzip"))
        assertEquals("Habertürk", loader.load(url("/tv.m3u.gz")).entries[1].title)
    }

    @Test
    fun decodesUtf8WithBomAndLatin1Charset() = runBlocking {
        routes["/bom.m3u"] = Route(200, "﻿$sample".toByteArray(Charsets.UTF_8))
        assertEquals("trt1.tr", loader.load(url("/bom.m3u")).entries[0].tvgId)

        val latin = "#EXTM3U\n#EXTINF:-1,Café\nhttp://x/a.ts\n"
        routes["/latin.m3u"] = Route(
            200,
            latin.toByteArray(Charsets.ISO_8859_1),
            mapOf("Content-Type" to "text/plain; charset=ISO-8859-1"),
        )
        assertEquals("Café", loader.load(url("/latin.m3u")).entries.single().title)
    }

    @Test
    fun streamsLargePlaylistsBeyondSniffWindow() = runBlocking {
        val big = buildString {
            append("#EXTM3U\n")
            repeat(20_000) { append("#EXTINF:-1 group-title=\"G${it % 10}\",Kanal $it\nhttp://s/$it.ts\n") }
        }
        routes["/big.m3u"] = Route(200, big.toByteArray())
        val playlist = loader.load(url("/big.m3u"))
        assertEquals(20_000, playlist.entries.size)
        assertEquals("Kanal 0", playlist.entries.first().title)
        assertEquals("http://s/19999.ts", playlist.entries.last().url)
    }

    @Test
    fun rejectsHtmlErrorPage() = runBlocking {
        routes["/expired.m3u"] = Route(200, "<!DOCTYPE html><html><body>Account expired</body></html>".toByteArray())
        try {
            loader.load(url("/expired.m3u"))
            fail("expected NotAnM3uPlaylistException")
        } catch (e: NotAnM3uPlaylistException) {
            assertFalse(e.isHlsStream)
            assertTrue(e.message!!.contains("HTML"))
        }
    }

    @Test
    fun rejectsHlsManifestWithFlag() = runBlocking {
        val hls = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg1.ts\n"
        routes["/live.m3u8"] = Route(200, hls.toByteArray())
        try {
            loader.load(url("/live.m3u8"))
            fail("expected NotAnM3uPlaylistException")
        } catch (e: NotAnM3uPlaylistException) {
            assertTrue(e.isHlsStream)
        }
    }

    @Test
    fun reportsHttpErrors() = runBlocking {
        try {
            loader.load(url("/missing.m3u"))
            fail("expected PlaylistHttpException")
        } catch (e: PlaylistHttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun httpErrorsCarryDetails() = runBlocking {
        routes["/denied.m3u"] = Route(403, "Account expired".toByteArray())
        try {
            loader.download(url("/denied.m3u"), null, java.io.File.createTempFile("plist", ".tmp").apply { deleteOnExit() })
            fail("expected PlaylistHttpException")
        } catch (e: PlaylistHttpException) {
            assertEquals(403, e.code)
            assertEquals("Account expired", e.details)
        }
    }

    @Test
    fun downloadsAndPeeks() = runBlocking {
        routes["/list.m3u"] = Route(200, gzip(sample), mapOf("Content-Type" to "audio/x-mpegurl"))
        val target = java.io.File.createTempFile("plist", ".tmp").apply { deleteOnExit() }
        val stored = loader.download(url("/list.m3u"), "UA/1", target)
        assertEquals(target.length(), stored.bytes)
        assertEquals("UA/1", seenUserAgents["/list.m3u"])
        val peek = loader.peek(url("/list.m3u"))
        assertTrue(peek.head.startsWith("#EXTM3U"))
        assertEquals(PlaylistFormat.M3U, PlaylistFiles.detectFormat(peek.head, "list.m3u", peek.contentType))
    }

    @Test
    fun loadsW3u() = runBlocking {
        routes["/l.w3u"] = Route(200, """{"name":"L","stations":[{"name":"A","url":"http://a/1.ts"}]}""".toByteArray())
        assertEquals(listOf("A"), loader.load(url("/l.w3u")).entries.map { it.title })
    }

    @Test
    fun wrapsNetworkAndUrlErrors() = runBlocking {
        val port = server.address.port
        server.stop(0)
        try {
            loader.load("http://127.0.0.1:$port/list.m3u")
            fail("expected PlaylistLoadException")
        } catch (e: PlaylistLoadException) {
            assertTrue(e.cause != null)
        }
        try {
            loader.load("not a url")
            fail("expected PlaylistLoadException")
        } catch (e: PlaylistLoadException) {
            assertTrue(e.message!!.contains("Invalid"))
        }
    }

    @Test
    fun fromTextValidatesContent() {
        assertEquals(2, loader.fromText(sample).entries.size)
        assertEquals(1, loader.fromText("http://x/a.ts\n").entries.size)
        assertTrue(loader.fromText("#EXTM3U\n").entries.isEmpty())
        for (bad in listOf("", "{\"error\":\"unauthorized\"}", "Invalid username or password")) {
            try {
                loader.fromText(bad)
                fail("expected NotAnM3uPlaylistException for '$bad'")
            } catch (_: NotAnM3uPlaylistException) {
            }
        }
    }
}
