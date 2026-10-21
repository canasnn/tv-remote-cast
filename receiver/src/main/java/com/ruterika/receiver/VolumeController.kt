package com.ruterika.receiver

import android.content.Context
import android.media.AudioManager

/**
 * TV volume (STREAM_MUSIC). Works whether or not the player is open, so the
 * phone's volume keys and slider control the TV like a remote would.
 */
class VolumeController(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    /** 0..1, or -1 when the TV doesn't expose it (fixed volume, e.g. passthrough). */
    val level: Float
        get() {
            if (audio.isVolumeFixed) return -1f
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }

    val muted: Boolean get() = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    fun set(level: Float) {
        if (audio.isVolumeFixed) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (level.coerceIn(0f, 1f) * max).let { Math.round(it) }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
    }

    fun adjust(direction: Int) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun setMuted(muted: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
