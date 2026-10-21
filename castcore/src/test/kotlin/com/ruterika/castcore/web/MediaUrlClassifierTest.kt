package com.ruterika.castcore.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlClassifierTest {

    private fun c(url: String, mime: String? = null) = MediaUrlClassifier.classify(url, mime, null)

    @Test
    fun manifestsByExtension() {
        assertEquals(MediaClass.HLS, c("https://cdn.example.com/hls/master.m3u8"))
        assertEquals(MediaClass.HLS, c("https://cdn.example.com/hls/master.M3U8?token=abc#t=10"))
        assertEquals(MediaClass.HLS, c("https://cdn.example.com/radio.m3u"))
        assertEquals(MediaClass.DASH, c("https://cdn.example.com/dash/manifest.mpd?x=1"))
    }

    @Test
    fun queryAndFragmentAreIgnoredForExtension() {
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, c("https://v.example.com/movie.mp4?sig=a.m3u8x&exp=1"))
        assertEquals(MediaClass.NONE, c("https://v.example.com/page.html#video.mp4"))
    }

    @Test
    fun manifestHiddenInQueryOrPath() {
        assertEquals(MediaClass.HLS, c("https://player.example.com/play.php?file=movie.m3u8"))
        assertEquals(MediaClass.HLS, c("https://player.example.com/proxy?url=https%3A%2F%2Fcdn.x.com%2Fa%2Fmaster.m3u8%3Ft%3D1"))
        assertEquals(MediaClass.DASH, c("https://player.example.com/embed?src=https://cdn.x.com/v/stream.mpd"))
        assertEquals(MediaClass.HLS, c("https://ams.example.net/video.ism/manifest(format=m3u8-aapl)"))
        assertEquals(MediaClass.DASH, c("https://ams.example.net/video.ism/manifest(format=mpd-time-csf)"))
        assertEquals(MediaClass.NONE, c("https://ams.example.net/video.ism/manifest(format=smooth)"))
        assertEquals(MediaClass.HLS, c("https://cdn.example.com/hls/index.m3u8/token/abc"))
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, c("https://site.example.com/get.php?file=clip.mp4"))
    }

    @Test
    fun masterJsonNeedsProbe() {
        assertEquals(MediaClass.MAYBE, c("https://skyfire.vimeocdn.com/1/2/video/123/master.json?base64_init=1"))
    }

    @Test
    fun progressiveAndAudio() {
        listOf("mp4", "m4v", "webm", "mkv", "mov").forEach {
            assertEquals(it, MediaClass.PROGRESSIVE_VIDEO, c("https://v.example.com/files/movie.$it"))
        }
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, c("https://v.example.com/files/movie.ts"))
        listOf("mp3", "m4a", "ogg", "flac", "opus").forEach {
            assertEquals(it, MediaClass.AUDIO, c("https://a.example.com/song.$it"))
        }
        assertEquals(MediaClass.AUDIO, c("https://a.example.com/podcast/episode.aac"))
    }

    @Test
    fun subtitlesAndImages() {
        listOf("vtt", "srt", "ass", "ssa", "ttml").forEach {
            assertEquals(it, MediaClass.SUBTITLE, c("https://s.example.com/subs/en.$it"))
        }
        assertEquals(MediaClass.IMAGE, c("https://i.example.com/poster.jpg?w=300"))
        assertEquals(MediaClass.IMAGE, c("https://i.example.com/thumb.webp"))
    }

    @Test
    fun segments() {
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/seg-12-v1-a1.ts"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/segment_00045.ts?t=1"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/720p_00012.ts"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/00012.ts"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/index_3_00045.ts"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/dash/chunk-stream0-00012.m4s"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/dash/video.fmp4"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/audio/seg-5-a1.aac"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/audio_123.aac"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/dash/init.mp4"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/dash/frag-12.mp4"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/hls/sub/segment12.vtt"))
    }

    @Test
    fun byteRangeFragments() {
        assertEquals(MediaClass.SEGMENT, c("https://r1.googlevideo.com/videoplayback?itag=22&mime=video%2Fmp4&range=0-123456"))
        assertEquals(MediaClass.SEGMENT, c("https://video.xx.fbcdn.net/v/t42/abc.mp4?bytestart=100&byteend=2000"))
        assertEquals(MediaClass.SEGMENT, c("https://cdn.example.com/stream/range/0-65535"))
    }

    @Test
    fun mimeQueryParameter() {
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, c("https://r1.googlevideo.com/videoplayback?itag=22&mime=video%2Fmp4"))
        assertEquals(MediaClass.AUDIO, c("https://r1.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4"))
    }

    @Test
    fun mimeTypes() {
        val noExt = "https://api.example.com/stream/abc"
        assertEquals(MediaClass.HLS, c(noExt, "application/vnd.apple.mpegurl"))
        assertEquals(MediaClass.HLS, c(noExt, "application/x-mpegURL; charset=utf-8"))
        assertEquals(MediaClass.HLS, c(noExt, "audio/mpegurl"))
        assertEquals(MediaClass.DASH, c(noExt, "application/dash+xml"))
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, c(noExt, "video/mp4"))
        assertEquals(MediaClass.AUDIO, c(noExt, "audio/mpeg"))
        assertEquals(MediaClass.SUBTITLE, c(noExt, "text/vtt"))
        assertEquals(MediaClass.SUBTITLE, c(noExt, "application/x-subrip"))
        assertEquals(MediaClass.MAYBE, c(noExt, "binary/octet-stream"))
        assertEquals(MediaClass.MAYBE, c(noExt, "application/octet-stream"))
        assertEquals(MediaClass.IMAGE, c(noExt, "image/png"))
        assertEquals(MediaClass.NONE, c(noExt, "text/html"))
        assertEquals(MediaClass.NONE, c("https://x.example.com/video.mp4", "text/html"))
        // octet-stream keeps a confident extension
        assertEquals(MediaClass.PROGRESSIVE_VIDEO, c("https://x.example.com/movie.mkv", "application/octet-stream"))
        // a video MIME does not turn a segment into a listable item
        assertEquals(MediaClass.SEGMENT, c("https://x.example.com/hls/seg-3-v1.ts", "video/mp2t"))
        assertEquals(MediaClass.SEGMENT, c("https://x.example.com/dash/chunk-1-00002.m4s", "video/mp4"))
        // playlists served as text/plain
        assertEquals(MediaClass.HLS, c("https://x.example.com/list.m3u8", "text/plain"))
    }

    @Test
    fun maybeHeuristicsAndNonMedia() {
        assertEquals(MediaClass.MAYBE, c("https://cdn.example.com/video/12345"))
        assertEquals(MediaClass.MAYBE, c("https://cdn.example.com/hls/abcdef"))
        assertEquals(MediaClass.NONE, c("https://www.example.com/about"))
        assertEquals(MediaClass.NONE, c("https://www.example.com/video/player.js"))
        assertEquals(MediaClass.NONE, c("https://www.example.com/styles/site.css"))
    }

    @Test
    fun nonHttpSchemes() {
        assertEquals(MediaClass.NONE, c("blob:https://example.com/1234-5678"))
        assertEquals(MediaClass.NONE, c("data:video/mp4;base64,AAAA"))
        assertEquals(MediaClass.NONE, c("about:blank"))
    }

    @Test
    fun adHostsExcludedByDefaultBlocker() {
        assertEquals(MediaClass.NONE, MediaUrlClassifier.classify("https://s0.2mdn.net/videoplayback/ad.mp4"))
        assertEquals(MediaClass.NONE, MediaUrlClassifier.classify("https://pubads.g.doubleclick.net/vast/ad.m3u8"))
        assertEquals(MediaClass.HLS, MediaUrlClassifier.classify("https://cdn.example.com/master.m3u8"))
        val custom = AdBlocker.Builder().block("cdn.example.com").build()
        assertEquals(MediaClass.NONE, MediaUrlClassifier.classify("https://cdn.example.com/master.m3u8", adBlocker = custom))
    }

    @Test
    fun isPlayableAndMimeGuess() {
        assertTrue(MediaClass.HLS.isPlayable)
        assertTrue(MediaClass.AUDIO.isPlayable)
        assertFalse(MediaClass.SEGMENT.isPlayable)
        assertFalse(MediaClass.SUBTITLE.isPlayable)
        assertEquals("video/x-matroska", MediaUrlClassifier.guessMime("https://x/a.mkv", MediaClass.PROGRESSIVE_VIDEO))
        assertEquals("application/x-subrip", MediaUrlClassifier.guessMime("https://x/a.srt?x", MediaClass.SUBTITLE))
        assertEquals("application/x-mpegURL", MediaUrlClassifier.guessMime("https://x/a", MediaClass.HLS))
    }
}
