package com.nuvio.app.features.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Cybervulcan build: wraps the normal load control and holds loading at the start of a section
 * that will be auto-skipped (see [PlaybackLoadCeiling]) until the skip happens. If playback is
 * stuck there for a few seconds without the skip, loading continues. Everything else is passed
 * straight through.
 */
@UnstableApi
internal class SkipAwareLoadControl(private val delegate: LoadControl) : LoadControl {

    private var lastPositionUs = C.TIME_UNSET
    private var positionUnchangedSinceMs = 0L

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val section = PlaybackLoadCeiling.section
        val ceilingMs = PlaybackLoadCeiling.ceilingMs
        if (section != null && ceilingMs != null) {
            val bufferedEndUs = parameters.playbackPositionUs + parameters.bufferedDurationUs
            if (bufferedEndUs >= ceilingMs * 1_000L) {
                if (!skipOverdue(parameters)) return false
                // Playback should be running but hasn't moved: the skip isn't coming, so load
                // the section rather than leave playback stuck.
                PlaybackLoadCeiling.released = section
            }
        }
        return delegate.shouldContinueLoading(parameters)
    }

    private fun skipOverdue(parameters: LoadControl.Parameters): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!parameters.playWhenReady || parameters.playbackPositionUs != lastPositionUs) {
            lastPositionUs = parameters.playbackPositionUs
            positionUnchangedSinceMs = now
            return false
        }
        return now - positionUnchangedSinceMs > STALL_RELEASE_MS
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
        const val STALL_RELEASE_MS = 3_000L
    }
}
