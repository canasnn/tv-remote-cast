package com.ruterika.castcore.settings

import kotlinx.serialization.Serializable

/**
 * Options of the in-app (phone) player, plus the
 * levels it remembers between videos. Nested in [CastSettings.phonePlayer].
 */
@Serializable
data class PhonePlayerSettings(
    /** Library taps play on the phone instead of casting. */
    val alwaysPlayInApp: Boolean = false,
    // Gestures (each needs [CastSettings.phoneGestures] on as well)
    val gestureDoubleTap: Boolean = true,
    val gestureSwipeSeek: Boolean = true,
    val gestureSwipeBrightness: Boolean = true,
    val gestureSwipeVolume: Boolean = true,
    // Volume
    val rememberVolume: Boolean = true,
    /** 0..1, or up to 2 with [volumeBoost] (above 1 = LoudnessEnhancer gain). */
    val volumeLevel: Float = 1f,
    val volumeBoost: Boolean = false,
    /** Gain added at the top of the boost range, in dB. */
    val boostMaxDb: Int = 6,
    /** Videos start muted (audio-only items are not muted). */
    val startMuted: Boolean = false,
    /** Every video starts at [LOW_VOLUME] instead of the last level. */
    val lowerVolumeBeforePlay: Boolean = false,
    // Brightness / orientation
    val rememberBrightness: Boolean = true,
    /** Window brightness 0..1, -1 = follow the system. */
    val brightnessLevel: Float = -1f,
    val rememberOrientation: Boolean = true,
    /** android ActivityInfo.SCREEN_ORIENTATION_*, -1 = not set. */
    val orientation: Int = -1,
    // Network
    /** Seconds of media buffered ahead (min = max preset). */
    val bufferSeconds: Int = 50,
    /** Disk cache for progressive (MP4, MKV…) media in MB, 0 = off. */
    val cacheMb: Int = 256,
    /** Offer tracks the decoder reports as unsupported. */
    val allowAllTracks: Boolean = false,
    /** Own subtitle style for the phone; null = same as the TV. */
    val subtitleLook: SubtitleLook? = null
) {
    val maxVolume: Float get() = if (volumeBoost) 2f else 1f

    /** Buffer durations in ms: min, max, for playback, after rebuffer. */
    val bufferDurationsMs: IntArray
        get() {
            val ms = bufferSeconds.coerceIn(5, 600) * 1000
            return intArrayOf(ms, ms, 1_000, 2_000.coerceAtMost(ms))
        }

    /** LoudnessEnhancer target gain (millibels) for a volume above 1, else 0. */
    fun boostGainMb(volume: Float): Int =
        if (!volumeBoost || volume <= 1f) 0 else kotlin.math.ceil((volume.coerceAtMost(2f) - 1f) * boostMaxDb * 100).toInt()

    companion object {
        const val LOW_VOLUME = 0.25f
        val BUFFER_CHOICES = listOf(15, 30, 50, 120, 300)
        val CACHE_CHOICES_MB = listOf(0, 256, 512, 1024, 2048)
        val BOOST_CHOICES_DB = listOf(3, 6, 9, 12)
    }
}
