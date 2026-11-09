package com.nuvio.app.features.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cybervulcan build: fetches the start of the next episode in the background.
 *
 * ExoPlayer with the disk cache on: the first seconds go into the cache, so the next episode
 * starts from disk. libmpv (or cache off): a short read that makes the addon and debrid service
 * resolve the link ahead of time, which is most of the wait when switching episodes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal actual object NextEpisodePrefetcher {
    private const val TAG = "NextEpPrebuffer"
    private const val WARM_UP_BYTES = 512L * 1024L

    @Volatile
    var lastEngineWasLibmpv: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var writer: CacheWriter? = null

    actual fun prefetch(url: String, headers: Map<String, String>, bytes: Long) {
        cancel()
        val context = PlayerSettingsStorage.applicationContext() ?: return
        val settings = PlayerSettingsRepository.uiState.value
        val useDiskCache = settings.vodCacheEnabled && !lastEngineWasLibmpv && !settings.externalPlayerEnabled
        job = scope.launch {
            runCatching {
                val upstream = PlatformPlaybackDataSourceFactory.create(
                    context = context,
                    defaultRequestHeaders = headers,
                    defaultResponseHeaders = emptyMap(),
                    useYoutubeChunkedPlayback = false,
                )
                if (useDiskCache) {
                    val cached = VodCache.prefetchRange(
                        context = context,
                        upstream = upstream,
                        url = url,
                        sizeMode = settings.vodCacheSizeMode,
                        sizeMb = settings.vodCacheSizeMb,
                        position = 0L,
                        length = bytes,
                        protect = true,
                        onWriter = { writer = it },
                    )
                    Log.i(TAG, "pre-buffered ${cached / (1024L * 1024L)}MB of next episode")
                } else {
                    val dataSource = upstream.createDataSource()
                    try {
                        dataSource.open(
                            DataSpec.Builder()
                                .setUri(Uri.parse(url))
                                .setPosition(0L)
                                .setLength(WARM_UP_BYTES)
                                .build(),
                        )
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (total < WARM_UP_BYTES) {
                            val read = dataSource.read(buffer, 0, buffer.size)
                            if (read < 0) break
                            total += read
                        }
                        Log.i(TAG, "warmed up next episode link (${total / 1024}KB)")
                    } finally {
                        runCatching { dataSource.close() }
                    }
                }
            }.onFailure { error ->
                Log.i(TAG, "pre-buffer stopped: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
            }
        }
    }

    actual fun cancel() {
        writer?.cancel()
        writer = null
        job?.cancel()
        job = null
    }
}
