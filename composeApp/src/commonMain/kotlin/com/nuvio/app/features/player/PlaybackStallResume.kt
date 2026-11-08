package com.nuvio.app.features.player

internal enum class PlaybackSourceFailure {
    None,
    ClosedStream,
    ReadPositionOutOfRange,
}

/**
 * Remembers where a source that already started playing should be reopened.
 * The prepare position is the value written to the player as startPositionMs.
 */
internal class PlaybackReopenSession(
    private val initialPositionMs: Long?,
) {
    var fallbackStartPositionMs: Long? = null
        private set
    var probeAttempted: Boolean = false
        private set
    var resumeGeneration: Int = 0
        private set
    /** Reopen attempts since playback was last ready; several are allowed for patchy signal. */
    var reopenAttempts: Int = 0
        private set

    fun onReady() {
        fallbackStartPositionMs = null
        reopenAttempts = 0
    }

    fun prepareStartPositionMs(decoderFallbackMs: Long? = null): Long? =
        decoderFallbackMs ?: fallbackStartPositionMs ?: initialPositionMs?.takeIf { it > 0L }

    fun isReopening(decoderFallbackMs: Long? = null): Boolean =
        decoderFallbackMs != null || fallbackStartPositionMs != null

    fun onFailure(failure: PlaybackSourceFailure, positionMs: Long, durationMs: Long): Boolean {
        val reopenAtMs = stalledReopenPositionMs(
            failure = failure,
            positionMs = positionMs,
            durationMs = durationMs,
            resumeAlreadyAttempted = reopenAttempts >= MAX_REOPEN_ATTEMPTS,
        ) ?: return false
        reopenAttempts += 1
        probeAttempted = true
        fallbackStartPositionMs = reopenAtMs
        resumeGeneration += 1
        return true
    }

    private companion object {
        const val MAX_REOPEN_ATTEMPTS = 5
    }

    fun markMimeProbe() {
        probeAttempted = true
    }
}

internal fun stalledReopenPositionMs(
    failure: PlaybackSourceFailure,
    positionMs: Long,
    durationMs: Long,
    resumeAlreadyAttempted: Boolean,
): Long? {
    val stalled = failure == PlaybackSourceFailure.ClosedStream ||
        failure == PlaybackSourceFailure.ReadPositionOutOfRange
    if (!stalled || resumeAlreadyAttempted || durationMs <= 0L || positionMs < 0L) return null
    return positionMs
}

internal fun useVodCacheForSource(
    vodCacheEnabled: Boolean,
    youtubeChunked: Boolean,
    adaptive: Boolean,
    loopback: Boolean,
): Boolean = vodCacheEnabled && !youtubeChunked && !adaptive && !loopback
