package com.ruterika.castcore.subtitles

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenSubtitlesClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenSubtitlesClient
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenSubtitlesClient(http, "test-key", "RuterikaCast v1.0", server.url("/api/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val searchJson = """
        {
          "total_pages": 1,
          "total_count": 2,
          "per_page": 60,
          "page": 1,
          "data": [
            {
              "id": "6346418",
              "type": "subtitle",
              "attributes": {
                "subtitle_id": "6346418",
                "language": "tr",
                "download_count": 15234,
                "new_download_count": 120,
                "hearing_impaired": false,
                "hd": true,
                "fps": 23.976,
                "votes": 3,
                "ratings": 8.5,
                "from_trusted": true,
                "foreign_parts_only": false,
                "upload_date": "2021-03-01T10:00:00Z",
                "ai_translated": false,
                "machine_translated": false,
                "release": "Inception.2010.1080p.BluRay.x264-SPARKS",
                "comments": "",
                "legacy_subtitle_id": 1234,
                "uploader": { "uploader_id": 42, "name": "altyazici", "rank": "trusted" },
                "feature_details": {
                  "feature_id": 1,
                  "feature_type": "Movie",
                  "year": 2010,
                  "title": "Inception",
                  "movie_name": "2010 - Inception",
                  "imdb_id": 1375666,
                  "tmdb_id": 27205
                },
                "url": "https://www.opensubtitles.com/tr/subtitles/legacy/1234",
                "related_links": [],
                "files": [
                  { "file_id": 111, "cd_number": 1, "file_name": "Inception.2010.1080p.tr.srt" }
                ],
                "moviehash_match": true
              }
            },
            {
              "id": "999",
              "type": "subtitle",
              "attributes": {
                "subtitle_id": 999,
                "language": "en",
                "download_count": 7,
                "hearing_impaired": true,
                "hd": false,
                "fps": 0,
                "ratings": 0,
                "from_trusted": null,
                "ai_translated": true,
                "machine_translated": true,
                "release": null,
                "uploader": { "uploader_id": null, "name": "", "rank": "anonymous" },
                "feature_details": {
                  "feature_type": "Episode",
                  "year": 2008,
                  "title": "Pilot",
                  "parent_title": "Breaking Bad",
                  "season_number": 1,
                  "episode_number": 1
                },
                "files": [
                  { "file_id": 221, "cd_number": 1, "file_name": "cd1.srt" },
                  { "file_id": 222, "cd_number": 2, "file_name": "cd2.srt" }
                ],
                "unknown_new_field": { "nested": [1, 2, 3] }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun searchBuildsSortedLowercaseQueryAndParsesResults() = runBlocking {
        server.enqueue(json(searchJson))
        val results = client.search(
            query = "Inception",
            imdbId = "tt0001375666",
            season = null,
            episode = null,
            languages = listOf("TR", "en", "tr"),
            movieHash = "8E245D9679D31E12",
        )

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/v1/subtitles?imdb_id=1375666&languages=en,tr&moviehash=8e245d9679d31e12&query=inception",
            request.path,
        )
        assertEquals("test-key", request.getHeader("Api-Key"))
        assertEquals("RuterikaCast v1.0", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))

        assertEquals(3, results.size)
        val first = results[0]
        assertEquals(111L, first.fileId)
        assertEquals("Inception.2010.1080p.tr.srt", first.fileName)
        assertEquals("6346418", first.subtitleId)
        assertEquals("tr", first.language)
        assertEquals("Inception.2010.1080p.BluRay.x264-SPARKS", first.release)
        assertEquals(15234, first.downloadCount)
        assertFalse(first.hearingImpaired)
        assertEquals(23.976, first.fps!!, 0.0001)
        assertEquals("altyazici", first.uploader)
        assertFalse(first.aiTranslated)
        assertFalse(first.machineTranslated)
        assertTrue(first.fromTrusted)
        assertTrue(first.movieHashMatch)
        assertEquals("Inception", first.featureTitle)
        assertEquals(2010, first.year)

        val episode = results[1]
        assertEquals(221L, episode.fileId)
        assertEquals("999", episode.subtitleId)
        assertEquals("", episode.release)
        assertTrue(episode.hearingImpaired)
        assertNull(episode.fps)
        assertNull(episode.uploader)
        assertTrue(episode.aiTranslated)
        assertTrue(episode.machineTranslated)
        assertFalse(episode.fromTrusted)
        assertEquals("Breaking Bad", episode.featureTitle)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
        assertEquals(222L, results[2].fileId)
        assertEquals(2, results[2].cdNumber)
    }

    @Test
    fun searchEpisodeParameters() = runBlocking {
        server.enqueue(json("""{"data": []}"""))
        val results = client.search(query = "Breaking Bad", imdbId = null, season = 5, episode = 14, languages = emptyList(), movieHash = null, page = 2)
        assertTrue(results.isEmpty())
        assertEquals(
            "/api/v1/subtitles?episode_number=14&page=2&query=breaking%20bad&season_number=5",
            server.takeRequest().path,
        )
    }

    @Test
    fun downloadReturnsLinkAndQuotaThenFetchesBytes() = runBlocking {
        val fileUrl = server.url("/files/abc.srt").toString()
        server.enqueue(
            json(
                """{"link":"$fileUrl","file_name":"abc.srt","requests":3,"remaining":17,
                "message":"Your quota will be renewed in 07 hours","reset_time":"07 hours","reset_time_utc":"2026-10-21T00:00:00.000Z"}""",
            ),
        )
        val srtBytes = "1\n00:00:01,000 --> 00:00:02,000\nMerhaba\n".toByteArray(Charsets.UTF_8)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(srtBytes)))

        val download = client.downloadFile(111)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/v1/download", post.path)
        assertEquals("""{"file_id":111}""", post.body.readUtf8())
        assertTrue(post.getHeader("Content-Type")!!.startsWith("application/json"))

        assertEquals(17, download.link.remaining)
        assertEquals(3, download.link.requests)
        assertEquals("abc.srt", download.link.fileName)
        assertEquals("2026-10-21T00:00:00.000Z", download.link.resetTimeUtc)
        assertArrayEquals(srtBytes, download.bytes)

        val fetch = server.takeRequest()
        assertEquals("/files/abc.srt", fetch.path)
        assertNull(fetch.getHeader("Api-Key"))
    }

    @Test
    fun loginStoresTokenUsedAsBearer() = runBlocking {
        server.enqueue(
            json(
                """{"user":{"allowed_downloads":100,"level":"Sub leecher","user_id":66,"ext_installed":false,"vip":false},
                "base_url":"api.opensubtitles.com","token":"jwt-token","status":200}""",
            ),
        )
        server.enqueue(json("""{"data": []}"""))
        val session = client.login("user", "pass")
        assertEquals("jwt-token", session.token)
        assertEquals(100, session.allowedDownloads)
        assertFalse(session.vip)
        assertEquals("jwt-token", client.token)
        // The mock server is not opensubtitles.com, so the base URL is kept.
        assertEquals(server.url("/api/v1").toString().trimEnd('/'), client.currentBaseUrl)

        val login = server.takeRequest()
        assertEquals("/api/v1/login", login.path)
        assertEquals("""{"username":"user","password":"pass"}""", login.body.readUtf8())

        client.search(query = "x", imdbId = null, season = null, episode = null, languages = emptyList(), movieHash = null)
        assertEquals("Bearer jwt-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun loginSwitchesToVipHostOnProduction() = runBlocking {
        val fake = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"base_url":"vip-api.opensubtitles.com","token":"t","user":{"vip":true}}""".toResponseBody(null))
                    .build()
            }
            .build()
        val prod = OpenSubtitlesClient(fake, "k", "ua")
        assertEquals("https://api.opensubtitles.com/api/v1", prod.currentBaseUrl)
        val session = prod.login("u", "p")
        assertTrue(session.vip)
        assertEquals("https://vip-api.opensubtitles.com/api/v1", prod.currentBaseUrl)
    }

    @Test
    fun errorsAreTyped() = runBlocking {
        server.enqueue(json("""{"message":"You cannot consume this service","status":401}""", 401))
        server.enqueue(
            json(
                """{"requests":21,"remaining":-1,"message":"You have downloaded your allowed 20 subtitles for 24h.","reset_time_utc":"2026-10-21T00:00:00.000Z"}""",
                406,
            ),
        )
        server.enqueue(json("""{"message":"Throttle limit reached. Retry later."}""", 429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(json("not json"))

        try {
            client.search(query = "x", imdbId = null, season = null, episode = null, languages = emptyList(), movieHash = null)
            fail()
        } catch (e: OpenSubtitlesException.Unauthorized) {
            assertEquals(401, e.statusCode)
            assertEquals("You cannot consume this service", e.message)
        }
        try {
            client.download(1)
            fail()
        } catch (e: OpenSubtitlesException.DownloadQuotaExceeded) {
            assertEquals("2026-10-21T00:00:00.000Z", e.resetTimeUtc)
            assertTrue(e.message!!.contains("allowed 20"))
        }
        try {
            client.download(1)
            fail()
        } catch (e: OpenSubtitlesException.RateLimited) {
            assertEquals(2L, e.retryAfterSeconds)
        }
        try {
            client.download(1)
            fail()
        } catch (e: OpenSubtitlesException.Http) {
            assertEquals(503, e.statusCode)
            assertEquals("down", e.body)
        }
        try {
            client.download(1)
            fail()
        } catch (e: OpenSubtitlesException.InvalidResponse) {
            // expected
        }
    }

    @Test
    fun parseCannedResponseOffline() {
        val results = OpenSubtitlesClient.parseSearchResponse(searchJson)
        assertEquals(listOf(111L, 221L, 222L), results.map { it.fileId })
    }

    @Test
    fun imdbIdNormalization() {
        assertEquals("1375666", OpenSubtitlesClient.normalizeImdbId("tt1375666"))
        assertEquals("133093", OpenSubtitlesClient.normalizeImdbId("TT0133093"))
        assertEquals("133093", OpenSubtitlesClient.normalizeImdbId("0133093"))
        assertNull(OpenSubtitlesClient.normalizeImdbId("tt"))
        assertNull(OpenSubtitlesClient.normalizeImdbId("abc"))
    }

    @Test
    fun exactEpisodeSearchSendsNoGuessitAndParentImdbId() = runBlocking {
        server.enqueue(json("""{"data": []}"""))
        client.search(
            query = "The Office",
            season = 2,
            episode = 3,
            languages = listOf("en"),
            noGuessit = true,
            parentImdbId = "tt0386676",
        )
        assertEquals(
            "/api/v1/subtitles?episode_number=3&languages=en&no_guessit=1&parent_imdb_id=386676&query=the%20office&season_number=2",
            server.takeRequest().path,
        )
    }

    @Test
    fun guessitIsDefaultAndBlankParentIdIsIgnored() = runBlocking {
        server.enqueue(json("""{"data": []}"""))
        client.search(query = "Up", parentImdbId = "  ")
        assertEquals("/api/v1/subtitles?query=up", server.takeRequest().path)
    }

    @Test
    fun languagesAreParsed() = runBlocking {
        server.enqueue(
            json(
                """{"data":[{"language_code":"en","language_name":"English"},{"language_code":"pt-BR","language_name":"Portuguese (BR)"},{"language_code":"","language_name":"Broken"},{"language_code":"tr"}]}"""
            )
        )
        val languages = client.languages()
        assertEquals("/api/v1/infos/languages", server.takeRequest().path)
        assertEquals(
            listOf(
                OpenSubtitlesLanguage("en", "English"),
                OpenSubtitlesLanguage("pt-BR", "Portuguese (BR)"),
                OpenSubtitlesLanguage("tr", "tr"),
            ),
            languages,
        )
    }
}
