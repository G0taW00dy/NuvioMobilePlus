@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cybervulcan build: jump-ahead buffering for ExoPlayer.
 *
 * ExoPlayer stops loading at the start of a section auto-skip will jump over
 * ([SkipAwareLoadControl]). This fills the rest of the buffer budget from the end of that section
 * into the disk cache, so after the jump playback continues from storage and the skipped section
 * is never downloaded. The intro end time comes from IntroDB / Anime Skip; the file's own index
 * (the extractor's [SeekMap], e.g. Matroska Cues) turns that time into an exact byte range.
 */
internal object SkipJumpAhead {
    private const val TAG = "SkipJumpAhead"
    private const val MAX_AFTER_MS = 300_000L
    private const val MIN_STEP_MS = 15_000L
    private const val LOADING_DONE_MARGIN_MS = 1_500L
    private const val END_MARGIN_MS = 5_000L

    @Volatile
    private var seekMap: SeekMap? = null

    private var section: SkippedSection? = null
    private var prefetchedAfterMs = 0L
    private var prefetchedEndByte = 0L
    private var job: Job? = null

    @Volatile
    private var writer: CacheWriter? = null

    /** Called from the extractor thread once the video's index is known. */
    fun onSeekMap(map: SeekMap) {
        seekMap = map.takeIf { it.isSeekable }
    }

    fun reset() {
        cancelFill()
        seekMap = null
        section = null
    }

    /**
     * Runs on the main thread every couple of seconds while an ExoPlayer stream plays.
     * [budgetMs] is the player's buffer size; time already buffered before the section counts
     * against it, and the rest is buffered after the section.
     */
    fun tick(
        scope: CoroutineScope,
        context: Context,
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
        budgetMs: Long,
        settings: PlayerSettingsUiState,
    ) {
        val target = PlaybackLoadCeiling.section
        val map = seekMap
        if (target == null || map == null || durationMs <= 0L || target.endMs >= durationMs - END_MARGIN_MS) {
            if (target != section) cancelFill()
            return
        }
        if (target != section) {
            cancelFill()
            section = target
            prefetchedAfterMs = 0L
            prefetchedEndByte = 0L
        }
        // The part you watch first gets the bandwidth: wait until ExoPlayer has stopped at the section.
        if (bufferedPositionMs < target.startMs - LOADING_DONE_MARGIN_MS) return
        if (job?.isActive == true) return

        val beforeMs = (target.startMs - positionMs).coerceAtLeast(0L)
        val afterMs = (budgetMs - beforeMs)
            .coerceAtMost(MAX_AFTER_MS)
            .coerceAtMost(durationMs - target.endMs)
        if (afterMs - prefetchedAfterMs < MIN_STEP_MS) return

        val startByte = if (prefetchedAfterMs == 0L) {
            map.getSeekPoints(target.endMs * 1_000L).first.position
        } else {
            prefetchedEndByte
        }
        val endPoints = map.getSeekPoints((target.endMs + afterMs) * 1_000L)
        val endByte = maxOf(endPoints.first.position, endPoints.second.position)
        if (endByte <= startByte) return

        job = scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    VodCache.prefetchRange(
                        context = context,
                        upstream = PlatformPlaybackDataSourceFactory.create(
                            context = context,
                            defaultRequestHeaders = headers,
                            defaultResponseHeaders = emptyMap(),
                            useYoutubeChunkedPlayback = false,
                        ),
                        url = url,
                        sizeMode = settings.vodCacheSizeMode,
                        sizeMb = settings.vodCacheSizeMb,
                        position = startByte,
                        length = endByte - startByte,
                        protect = false,
                        onWriter = { writer = it },
                    )
                }.onFailure { error ->
                    Log.i(TAG, "stopped: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
                }.getOrDefault(0L)
            }
            writer = null
            if (section == target && cached > 0L) {
                prefetchedAfterMs = afterMs
                prefetchedEndByte = startByte + cached
                Log.i(TAG, "buffered ${afterMs / 1000}s after skipped section (${cached / (1024L * 1024L)}MB)")
            }
        }
    }

    private fun cancelFill() {
        writer?.cancel()
        writer = null
        job?.cancel()
        job = null
    }
}

/**
 * Passes the video's index ([SeekMap]) to [SkipJumpAhead]. Only extractors that declare a video
 * track report it, so a separate audio stream can't replace the video's index.
 */
internal class SeekMapCapturingExtractorsFactory(
    private val delegate: ExtractorsFactory,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array<Extractor>(extractors.size) { index -> SeekMapCapturingExtractor(extractors[index]) }
}

private class SeekMapCapturingExtractor(private val delegate: Extractor) : RewrappableExtractor {
    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = SeekMapCapturingExtractor(inner)
    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.getSniffFailureDetails()
    override fun init(output: ExtractorOutput) = delegate.init(SeekMapCapturingOutput(output))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.getUnderlyingImplementation()
}

private class SeekMapCapturingOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
    private var hasVideo = false
    private var seekMap: SeekMap? = null

    override fun track(id: Int, type: Int): TrackOutput {
        if (type == C.TRACK_TYPE_VIDEO) {
            hasVideo = true
            publish()
        }
        return delegate.track(id, type)
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
        publish()
        delegate.seekMap(seekMap)
    }

    private fun publish() {
        val map = seekMap ?: return
        if (hasVideo) SkipJumpAhead.onSeekMap(map)
    }
}
