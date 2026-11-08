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
 * last roughly two minutes in total, so a short signal loss plays on from the buffer and then
 * resumes. Dead links (403/404/410 etc.) still fail straight away via the default policy.
 */
@UnstableApi
internal class PatientLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(NETWORK_RETRY_COUNT) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (!error.isConnectivityFailure()) return super.getRetryDelayMsFor(loadErrorInfo)
        val attempt = loadErrorInfo.errorCount.coerceAtLeast(1)
        return (attempt * 1_000L).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = NETWORK_RETRY_COUNT

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

    private companion object {
        const val NETWORK_RETRY_COUNT = 30
        const val MAX_RETRY_DELAY_MS = 5_000L
    }
}
