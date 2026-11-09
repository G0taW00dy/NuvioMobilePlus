package com.nuvio.app.features.player

import android.os.Build
import android.util.Log
import `is`.xyz.mpv.MPV

private const val TAG = "LibmpvBuffer"

/**
 * Cybervulcan build: buffer and reconnect options for libmpv, set before the player starts.
 *
 * Off (default): the same 32/64 MB demuxer cache the official build uses.
 * On: the read-ahead, cache size, back cache and resume-after-stall values from
 * Settings → Playback → libmpv buffer.
 *
 * Reconnect is always on: if the connection drops, ffmpeg keeps retrying (with growing
 * delays) for about the "keep retrying for" window instead of ending the stream.
 */
internal fun applyLibmpvBufferOptions(mpv: MPV, tuning: PlayerTuningSettings) {
    val settings = tuning.sanitized()
    if (settings.mpvCustomBufferEnabled) {
        mpv.setOptionString("cache", "yes").logIfFailed("cache")
        mpv.setOptionString("demuxer-max-bytes", "${settings.mpvCacheMb}MiB").logIfFailed("demuxer-max-bytes")
        mpv.setOptionString("demuxer-max-back-bytes", "${settings.mpvBackCacheMb}MiB").logIfFailed("demuxer-max-back-bytes")
        mpv.setOptionString("demuxer-readahead-secs", "${settings.mpvReadAheadSec}").logIfFailed("demuxer-readahead-secs")
        mpv.setOptionString("cache-secs", "${settings.mpvReadAheadSec}").logIfFailed("cache-secs")
        mpv.setOptionString("cache-pause", "yes").logIfFailed("cache-pause")
        mpv.setOptionString("cache-pause-wait", "${settings.mpvResumeAfterStallSec}").logIfFailed("cache-pause-wait")
        mpv.setOptionString("cache-pause-initial", "yes").logIfFailed("cache-pause-initial")
        Log.i(
            TAG,
            "custom readAhead=${settings.mpvReadAheadSec}s cache=${settings.mpvCacheMb}MiB " +
                "back=${settings.mpvBackCacheMb}MiB resume=${settings.mpvResumeAfterStallSec}s",
        )
    } else {
        val bytes = defaultLibmpvCacheBytes()
        mpv.setOptionString("demuxer-max-bytes", "$bytes").logIfFailed("demuxer-max-bytes")
        mpv.setOptionString("demuxer-max-back-bytes", "$bytes").logIfFailed("demuxer-max-back-bytes")
    }
    val window = settings.networkRetryWindowSec
    // ffmpeg doubles the wait between attempts up to reconnect_delay_max, so the total time
    // spent retrying is roughly twice that value.
    val delayMax = (window / 2).coerceIn(5, 300)
    mpv.setOptionString(
        "stream-lavf-o",
        "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1," +
            "reconnect_delay_max=$delayMax,reconnect_delay_total_max=$window",
    ).logIfFailed("stream-lavf-o")
}

private fun defaultLibmpvCacheBytes(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 * 1024 * 1024 else 32 * 1024 * 1024

private fun Int.logIfFailed(option: String) {
    if (this < 0) Log.w(TAG, "libmpv option failed: $option status=$this")
}
