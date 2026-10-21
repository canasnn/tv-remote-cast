package com.ruterika.castcore.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSegmentsTest {

    @Test
    fun picksTheBestVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2000000,BANDWIDTH=2500000,RESOLUTION=1280x720
            https://cdn.test/720/index.m3u8
        """.trimIndent()
        assertEquals("https://cdn.test/720/index.m3u8", HlsSegments.bestVariantUrl(master, "https://a.test/master.m3u8"))
        assertNull(HlsSegments.bestVariantUrl("#EXTM3U\n#EXTINF:4,\na.ts\n", "https://a.test/x.m3u8"))
    }

    @Test
    fun listsSegmentsOfAVod() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            /abs/seg1.ts?t=1
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsSegments.parseMedia(media, "https://cdn.test/v/index.m3u8")
        assertEquals(listOf("https://cdn.test/v/seg0.ts", "https://cdn.test/abs/seg1.ts?t=1"), p.segments)
        assertFalse(p.live)
        assertTrue(p.joinableAsTs)
    }

    @Test
    fun refusesLiveEncryptedAndFmp4() {
        assertTrue(HlsSegments.parseMedia("#EXTM3U\n#EXTINF:4,\na.ts\n", "https://x/y.m3u8").live)
        val enc = HlsSegments.parseMedia("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST", "https://x/y.m3u8")
        assertTrue(enc.encrypted)
        assertFalse(enc.joinableAsTs)
        val fmp4 = HlsSegments.parseMedia("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST", "https://x/y.m3u8")
        assertTrue(fmp4.fragmentedMp4)
        assertFalse(fmp4.joinableAsTs)
    }
}
