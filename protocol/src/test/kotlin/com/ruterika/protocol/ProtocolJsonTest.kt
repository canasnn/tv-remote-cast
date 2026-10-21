package com.ruterika.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolJsonTest {

    @Test
    fun roundTripsEveryKindOfMessage() {
        val messages = listOf(
            Hello("tok", "Pixel"),
            GetApps(),
            Load(
                sessionId = "s1",
                items = listOf(
                    MediaItem(
                        url = "https://example.com/a.m3u8",
                        title = "A",
                        headers = mapOf("Referer" to "https://example.com/"),
                        subtitles = listOf(SubtitleTrack("http://192.168.1.2:8016/sub.srt", language = "tr"))
                    )
                ),
                startPositionMs = 1234
            ),
            Play, Pause, Stop, Next, Previous, GetStatus,
            Seek(5000), SeekBy(-10_000), SetSpeed(1.5f),
            SelectTrack(TrackType.TEXT, null),
            SetSubtitleStyle(SubtitleStyle(bold = true)),
            SetSubtitleOffset(-1500), Load("s2", listOf(MediaItem("u")), subtitleOffsetMs = 2500),
            Ping(1), Pong(1),
            Welcome("TV", Protocol.VERSION, "1.0", 34, canSelfLaunch = false),
            Apps(listOf(TvAppInfo("com.netflix.ninja/.MainActivity", "Netflix"))),
            LoadResult("s1", accepted = true, needsLaunch = true),
            Status(PlaybackStatus(state = PlayerState.PLAYING, positionMs = 10)),
            ErrorMessage("x", "y"),
            Enqueue(listOf(MediaItem("u", skipIntroMs = 5000))), Retry,
            SetVolume(0.5f), AdjustVolume(-1), SetMuted(true),
            SetRepeatMode(RepeatMode.ALL), SetAutoAdvance(false),
            SetVideoTransform(90, 1.2f), SetResizeMode(ResizeMode.ZOOM), SetSlideshow(null),
            PlayerEvent(PlayerEventType.INTRO_SKIPPED),
            Load("s2", listOf(MediaItem("v")), startPositionMs = null, repeatMode = RepeatMode.ONE)
        )
        for (message in messages) {
            val text = ProtocolJson.encode(message)
            assertEquals(text, message, ProtocolJson.decode(text))
        }
    }

    @Test
    fun usesTypeDiscriminator() {
        assertTrue(ProtocolJson.encode(Play).contains("\"type\":\"play\""))
    }

    @Test
    fun unknownMessageTypeDecodesToNull() {
        assertNull(ProtocolJson.decode("""{"type":"fromTheFuture","x":1}"""))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        assertEquals(Seek(5), ProtocolJson.decode("""{"type":"seek","positionMs":5,"extra":true}"""))
    }

    @Test
    fun deepLinksAreEncoded() {
        assertEquals(
            "ruterika-receiver://launch?c=com.a%2F.Main",
            Protocol.launchLink("com.a/.Main")
        )
    }
}
