package com.ruterika.castcore.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestParserTest {

    private val vod = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S" minBufferTime="PT2S">
          <Period id="0">
            <AdaptationSet mimeType="video/mp4" segmentAlignment="true" frameRate="30000/1001">
              <Representation id="v1" bandwidth="800000" width="640" height="360" codecs="avc1.4d401e"/>
              <Representation id="v2" bandwidth="2500000" width="1280" height="720" codecs="avc1.4d401f"/>
              <Representation id="v3" bandwidth="5000000" width="1920" height="1080" codecs="avc1.640028">
                <BaseURL>1080/</BaseURL>
              </Representation>
            </AdaptationSet>
            <AdaptationSet contentType="audio" lang="en">
              <Representation id="a1" bandwidth="128000" codecs="mp4a.40.2" mimeType="audio/mp4"/>
            </AdaptationSet>
            <AdaptationSet mimeType="text/vtt" lang="tr">
              <Representation id="t1" bandwidth="100"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun parsesVod() {
        assertTrue(DashManifestParser.isDashManifest(vod))
        val m = DashManifestParser.parse(vod)
        assertFalse(m.isLive)
        assertEquals(3723500L, m.durationMs)
        assertFalse(m.isDrmProtected)
        assertEquals(5, m.representations.size)
        val video = m.videoRepresentations
        assertEquals(listOf(1080, 720, 360), video.map { it.height })
        assertEquals(29.97, video.first().frameRate!!, 0.01)
        assertEquals(listOf("en"), m.audioLanguages)
        val q = m.qualities("https://cdn.example/v/manifest.mpd")
        assertEquals(3, q.size)
        assertEquals("1080p", q.first().label)
        assertTrue(q.all { it.url == "https://cdn.example/v/manifest.mpd" })
    }

    @Test
    fun parsesLiveAndDrm() {
        val live = """
            <MPD type="dynamic" availabilityStartTime="2024-01-01T00:00:00Z" xmlns:cenc="urn:mpeg:cenc:2013">
              <Period>
                <AdaptationSet contentType="video">
                  <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                  <ContentProtection schemeIdUri="urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"><cenc:pssh>AAA=</cenc:pssh></ContentProtection>
                  <Representation id="1" bandwidth="3000000" width="1280" height="720"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val m = DashManifestParser.parse(live)
        assertTrue(m.isLive)
        assertNull(m.durationMs)
        assertTrue(m.isDrmProtected)
        assertEquals(listOf("CENC", "Widevine"), m.drmSystems)
        assertEquals(720, m.videoRepresentations.single().height)
    }

    @Test
    fun rejectsOtherText() {
        assertFalse(DashManifestParser.isDashManifest("#EXTM3U\n#EXT-X-VERSION:3"))
        assertNull(DashManifestParser.parseOrNull("<html><body>MPD</body></html>"))
        assertEquals(90_000L, DashManifestParser.isoDurationMs("PT1M30S"))
        assertEquals(86_400_000L, DashManifestParser.isoDurationMs("P1D"))
        assertNull(DashManifestParser.isoDurationMs("soon"))
    }
}
