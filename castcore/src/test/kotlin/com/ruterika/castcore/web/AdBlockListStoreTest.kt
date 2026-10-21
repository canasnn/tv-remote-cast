package com.ruterika.castcore.web

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AdBlockListStoreTest {

    private val embedded = "0.0.0.0 ads.builtin.example\n"

    @Test
    fun updateHashResetAndAllowList() = runBlocking {
        val dir = Files.createTempDirectory("adblock").toFile()
        val server = MockWebServer()
        val list = "# hosts\n0.0.0.0 tracker.remote.example\n0.0.0.0 pop.remote.example\n"
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setBody("||custom.example^\n"))
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setBody("||custom.example^\n"))
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val store = AdBlockListStore(dir, OkHttpClient(), embeddedText = { embedded })
            store.load()
            assertTrue(store.blocker.value.isBlocked("https://ads.builtin.example/x.js"))
            assertFalse(store.blocker.value.isBlocked("https://tracker.remote.example/"))

            val urls = listOf(server.url("/hosts").toString(), server.url("/custom").toString())
            val first = store.update(urls)
            assertTrue(first is AdBlockListStore.UpdateResult.Updated)
            assertTrue(store.blocker.value.isBlocked("https://tracker.remote.example/"))
            assertTrue(store.blocker.value.isBlocked("https://a.custom.example/"))
            assertTrue(store.info.value.hash.isNotEmpty())
            assertEquals(4, store.info.value.hostCount)

            // Same content: nothing to rebuild.
            assertEquals(AdBlockListStore.UpdateResult.Unchanged, store.update(urls))

            // One list failing keeps the previous lists (all or nothing).
            assertTrue(store.update(urls) is AdBlockListStore.UpdateResult.Failed)
            assertTrue(store.blocker.value.isBlocked("https://a.custom.example/"))

            // The cache survives a restart.
            val again = AdBlockListStore(dir, OkHttpClient(), embeddedText = { embedded })
            again.load(listOf("pop.remote.example"))
            assertTrue(again.blocker.value.isBlocked("https://tracker.remote.example/"))
            assertFalse(again.blocker.value.isBlocked("https://pop.remote.example/"))
            again.setAllowList(emptyList())
            assertTrue(again.blocker.value.isBlocked("https://pop.remote.example/"))

            again.reset()
            assertFalse(again.blocker.value.isBlocked("https://tracker.remote.example/"))
            assertTrue(again.blocker.value.isBlocked("https://ads.builtin.example/"))
            assertEquals("", again.info.value.hash)
            assertTrue(again.update(listOf("file:///etc/hosts")) is AdBlockListStore.UpdateResult.Failed)
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun unchangedListIsNotDownloadedAgain() = runBlocking {
        val dir = Files.createTempDirectory("adblock").toFile()
        val server = MockWebServer()
        val list = "ads.remote.example\ntracker.remote.example\n"
        server.enqueue(MockResponse().setBody(list).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))
        server.enqueue(MockResponse().setBody("new.remote.example\n").setHeader("ETag", "\"v2\""))
        server.start()
        try {
            val store = AdBlockListStore(dir, OkHttpClient(), embeddedText = { embedded })
            val urls = listOf(server.url("/list").toString())
            assertTrue(store.update(urls) is AdBlockListStore.UpdateResult.Updated)
            server.takeRequest()

            // The daily check asks "changed since v1?" and gets 304: nothing downloaded.
            assertEquals(AdBlockListStore.UpdateResult.Unchanged, store.update(urls))
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))

            // A changed list (200 on the check) is downloaded again and replaces the old one.
            assertTrue(store.update(urls) is AdBlockListStore.UpdateResult.Updated)
            assertTrue(store.blocker.value.isBlocked("https://new.remote.example/"))
            assertFalse(store.blocker.value.isBlocked("https://ads.remote.example/"))
            assertEquals("E:\"v2\"", store.info.value.validators.values.single())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }
}
