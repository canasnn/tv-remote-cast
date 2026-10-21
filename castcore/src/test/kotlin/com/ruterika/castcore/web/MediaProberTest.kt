package com.ruterika.castcore.web

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class MediaProberTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val seenHeaders = ConcurrentHashMap<String, String>()
    private val prober = MediaProber(OkHttpClient())

    private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\nv/720.m3u8\n"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestHeaders.getFirst("Referer")?.let { seenHeaders[ex.requestURI.path] = it }
            val path = ex.requestURI.path
            when {
                path == "/video" -> {
                    ex.responseHeaders.add("Content-Type", "video/mp4")
                    if (ex.requestMethod == "HEAD") {
                        ex.responseHeaders.add("Content-Length", "5000")
                        ex.sendResponseHeaders(200, -1)
                    } else {
                        ex.sendResponseHeaders(200, 0)
                    }
                }
                path == "/nohead" -> {
                    if (ex.requestMethod == "HEAD") {
                        ex.sendResponseHeaders(405, -1)
                    } else {
                        ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                        ex.responseHeaders.add("Content-Range", "bytes 0-0/777")
                        ex.sendResponseHeaders(206, 1)
                        ex.responseBody.write('#'.code)
                    }
                }
                path.endsWith(".m3u8") -> {
                    val body = master.toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/x-mpegurl")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.write(body)
                }
                path == "/html" -> {
                    val body = "<html></html>".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.write(body)
                }
                else -> ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun headAndRangedFallback() = runBlocking {
        val r = prober.head(ProbeRequest("$base/video", mapOf("Referer" to "https://site.example/p")))
        assertEquals("video/mp4", r?.mime)
        assertEquals(5000L, r?.contentLength)
        assertEquals("https://site.example/p", seenHeaders["/video"])

        val r2 = prober.head(ProbeRequest("$base/nohead"))
        assertEquals("application/vnd.apple.mpegurl", r2?.mime)
        assertEquals(777L, r2?.contentLength)

        assertNull(prober.head(ProbeRequest("$base/missing")))
    }

    @Test
    fun fetchPlaylist() = runBlocking {
        assertEquals(master, prober.fetchPlaylist(ProbeRequest("$base/hls/master.m3u8", kind = ProbeKind.PLAYLIST)))
        assertNull(prober.fetchPlaylist(ProbeRequest("$base/html", kind = ProbeKind.PLAYLIST)))
    }

    @Test
    fun attachDrivesCollector() = runBlocking {
        val collector = PageMediaCollector(AdBlocker.EMPTY)
        collector.onPageStarted("https://site.example/p")
        // requested before attaching: picked up from the backlog
        collector.onRequest("$base/hls/master.m3u8", headers = mapOf("User-Agent" to "UA"))
        val job = prober.attach(this + Dispatchers.Default, collector)
        collector.onRequest("$base/video/abc", headers = mapOf("User-Agent" to "UA"))
        withTimeout(10_000) {
            while (collector.items.value.firstOrNull()?.variants?.isNotEmpty() != true) delay(20)
        }
        val item = collector.items.value.first()
        assertEquals("$base/hls/master.m3u8", item.url)
        assertEquals(720, item.height)
        assertTrue(item.isMasterPlaylist == true)
        job.cancelAndJoin()
    }
}
