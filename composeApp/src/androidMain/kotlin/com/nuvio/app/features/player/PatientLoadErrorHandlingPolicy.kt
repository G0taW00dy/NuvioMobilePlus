package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Keeps retrying a stream while the connection drops out (patchy mobile signal) instead of
 * giving up after ExoPlayer's default handful of quick retries. Retries back off up to 5 s and
 * last for the "keep retrying for" window (Settings → Playback, default two minutes), so a
 * signal loss plays on from the buffer and then resumes. Dead links (403/404/410 etc.) still
 * fail straight away via the default policy.
 */
@UnstableApi
internal class PatientLoadErrorHandlingPolicy(
    private val networkRetryCount: Int = retryCountForWindow(PlayerTuningRepository.current().networkRetryWindowSec),
) : DefaultLoadErrorHandlingPolicy(networkRetryCount) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (!error.isConnectivityFailure()) return super.getRetryDelayMsFor(loadErrorInfo)
        val attempt = loadErrorInfo.errorCount.coerceAtLeast(1)
        return (attempt * 1_000L).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = networkRetryCount

    private fun IOException.isConnectivityFailure(): Boolean {
        if (this is HttpDataSource.InvalidResponseCodeException) return false
        return generateSequence<Throwable>(this) { it.cause }.any { cause ->
            cause is UnknownHostException ||
                cause is ConnectException ||
                cause is SocketTimeoutException ||
                cause is SocketException ||
                (cause is HttpDataSource.HttpDataSourceException &&
                    cause !is HttpDataSource.InvalidResponseCodeException &&
                    cause !is HttpDataSource.InvalidContentTypeException)
        }
    }

    companion object {
        private const val MAX_RETRY_DELAY_MS = 5_000L

        /** Delays run 1, 2, 3, 4, 5 s, then 5 s each: 15 s for the first five tries. */
        fun retryCountForWindow(windowSec: Int): Int {
            val window = windowSec.coerceAtLeast(15)
            return (5 + (window - 15) / 5).coerceIn(5, 200)
        }
    }
}
