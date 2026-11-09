package com.nuvio.app.features.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Cybervulcan build: wraps the normal load control and stops loading ahead once the buffer
 * reaches the start of a section that will be auto-skipped (see [PlaybackLoadCeiling]).
 * Everything else is passed straight through.
 */
@UnstableApi
internal class SkipAwareLoadControl(private val delegate: LoadControl) : LoadControl {

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val ceilingMs = PlaybackLoadCeiling.ceilingMs
        if (ceilingMs != null && ceilingMs > 0L && !parameters.rebuffering &&
            parameters.bufferedDurationUs >= MIN_KEEP_BUFFERED_US
        ) {
            val bufferedEndUs = parameters.playbackPositionUs + parameters.bufferedDurationUs
            if (bufferedEndUs >= ceilingMs * 1_000L) return false
        }
        return delegate.shouldContinueLoading(parameters)
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(): Allocator = delegate.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    private companion object {
        // Never hold back below this much buffered media, so playback can't starve.
        const val MIN_KEEP_BUFFERED_US = 2_000_000L
    }
}
