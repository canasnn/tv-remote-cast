package com.ruterika.receiver

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput

/**
 * Live subtitle timing: the text renderer is asked for the cues of
 * `position - offset` instead of `position`, so a positive offset shows every
 * cue later and a negative one earlier. Nothing is reloaded.
 *
 * Works for every track Media3 parses during extraction (the default since
 * 1.4): sideloaded SRT/VTT/ASS/TTML and subtitles inside MKV/MP4/HLS/DASH.
 * Limits:
 * - CEA-608/708 captions are decoded inside the renderer and are not shifted.
 * - A negative offset needs cues that are already buffered; sideloaded files
 *   are read at once, embedded tracks only as far as the buffer reaches.
 * - Right after a change the cue on screen may stay until its own end, and
 *   cues already passed are not brought back when the offset grows.
 */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderer(renderer: Renderer, private val offsetUs: () -> Long) : ForwardingRenderer(renderer) {

    /** The current stream holds parsed cues (application/x-media3-cues). */
    @Volatile
    private var shiftable = false

    private fun shift(positionUs: Long): Long = if (shiftable) positionUs - offsetUs() else positionUs

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        shiftable = canShift(formats)
        super.enable(configuration, formats, stream, shift(positionUs), joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        shiftable = canShift(formats)
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun resetPosition(positionUs: Long) = super.resetPosition(shift(positionUs))

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = super.render(shift(positionUs), elapsedRealtimeUs)

    /**
     * With a positive offset the last cues end after the video does; the
     * player must still be able to end, so a fully read final stream counts as ended.
     */
    override fun isEnded(): Boolean =
        super.isEnded() || (shiftable && offsetUs() != 0L && isCurrentStreamFinal && hasReadStreamToEnd())

    private fun canShift(formats: Array<out Format>): Boolean =
        formats.any { it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES }
}

/** [DefaultRenderersFactory] whose text renderers follow [offsetUs] (see [SubtitleOffsetRenderer]). */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderersFactory(context: Context, private val offsetUs: () -> Long) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val start = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in start until out.size) out[i] = SubtitleOffsetRenderer(out[i], offsetUs)
    }
}
