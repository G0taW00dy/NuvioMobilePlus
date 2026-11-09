package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.player.skip.shouldAutoSkip
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.estimatedSizeBytes
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cybervulcan build: buffering helpers driven by the player screen.
 *
 * - Next episode pre-buffer: once this episode passes the trigger point, look up the next
 *   episode's sources, pick the same stream auto-play will pick, and fetch its first seconds
 *   in the background (into the disk cache for ExoPlayer; a link warm-up for libmpv).
 * - Skip-aware loading: tell ExoPlayer to stop loading at the start of the next section that
 *   will be auto-skipped, so that bandwidth isn't spent on intros and credits.
 */
internal expect object NextEpisodePrefetcher {
    fun prefetch(url: String, headers: Map<String, String>, bytes: Long)
    fun cancel()
}

private object NextEpisodePrebufferState {
    var startedForVideoId: String? = null
    var job: Job? = null
}

internal fun PlayerScreenRuntime.updateSmartBuffering() {
    val tuning = PlayerTuningRepository.current()
    updateLoadCeiling(tuning)
    maybeStartNextEpisodePrebuffer(tuning)
}

private fun PlayerScreenRuntime.updateLoadCeiling(tuning: PlayerTuningSettings) {
    if (!tuning.skipBufferingSkippedSections || !playerSettingsUiState.skipIntroEnabled) {
        PlaybackLoadCeiling.section = null
        return
    }
    val positionMs = playbackSnapshot.positionMs
    PlaybackLoadCeiling.section = skipIntervals
        .filter { interval ->
            interval.shouldAutoSkip(playerSettingsUiState.autoSkipSegmentTypes) &&
                interval !in autoSkippedIntervals
        }
        .map { SkippedSection((it.startTime * 1000.0).toLong(), (it.endTime * 1000.0).toLong()) }
        // Close to (or past) the start, lift the limit: if the auto-skip doesn't happen for
        // any reason, playback must never sit waiting on data it isn't allowed to load.
        .filter { section -> section.startMs - positionMs > CEILING_RELEASE_MARGIN_MS && section.endMs > section.startMs }
        .minByOrNull { it.startMs }
}

private fun PlayerScreenRuntime.maybeStartNextEpisodePrebuffer(tuning: PlayerTuningSettings) {
    if (!tuning.prebufferNextEpisode) return
    val nextEp = nextEpisodeInfo ?: return
    if (!nextEp.hasAired) return
    if (NextEpisodePrebufferState.startedForVideoId == nextEp.videoId) return
    val durationMs = playbackSnapshot.durationMs
    if (durationMs <= 0L || isShortPlaceholderDuration(durationMs)) return
    if (playbackSnapshot.positionMs * 100L < durationMs * tuning.prebufferTriggerPercent) return
    if (isNetworkOffline()) return
    val type = contentType ?: return

    NextEpisodePrebufferState.startedForVideoId = nextEp.videoId
    NextEpisodePrebufferState.job?.cancel()
    val settings = playerSettingsUiState
    val bingeGroup = currentStreamBingeGroup
    val episodeDurationMs = durationMs
    // Same source list auto-play uses; fetching it now also makes the switch itself faster.
    if (!nextEpisodePreloadTriggered) {
        nextEpisodePreloadTriggered = true
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextEp.videoId,
            season = nextEp.season,
            episode = nextEp.episode,
        )
    }
    NextEpisodePrebufferState.job = scope.launch {
        val state = withTimeoutOrNull(STREAM_WAIT_MS) {
            PlayerStreamsRepository.episodeStreamsState.first { state ->
                !state.isAnyLoading && state.groups.isNotEmpty()
            }
        } ?: return@launch
        val streams = state.groups.flatMap { it.streams }
        val candidate = nextEpisodeAutoPlayCandidate(settings, bingeGroup, streams) ?: return@launch
        // Streams that need a debrid lookup first are resolved at play time; skip those.
        if (candidate.clientResolve != null) return@launch
        val url = candidate.playableDirectUrl ?: return@launch
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@launch
        val headers = sanitizePlaybackHeaders(candidate.behaviorHints.proxyHeaders?.request)
        NextEpisodePrefetcher.prefetch(
            url = url,
            headers = headers,
            bytes = prebufferBytes(candidate, episodeDurationMs, tuning.prebufferSeconds),
        )
    }
}

/** Forget the pre-buffer when the player closes, so reopening the episode can start it again. */
internal fun resetNextEpisodePrebuffer() {
    NextEpisodePrebufferState.job?.cancel()
    NextEpisodePrebufferState.job = null
    NextEpisodePrebufferState.startedForVideoId = null
    NextEpisodePrefetcher.cancel()
    PlaybackLoadCeiling.section = null
}

internal fun prebufferBytes(stream: StreamItem, episodeDurationMs: Long, seconds: Int): Long {
    val size = stream.estimatedSizeBytes()
    val durationSec = (episodeDurationMs / 1000L).coerceAtLeast(60L)
    val raw = if (size != null && size > 0L) {
        // Bytes per second of the next file, with a little headroom for the container header.
        (size / durationSec) * seconds * 115L / 100L
    } else {
        seconds * FALLBACK_BYTES_PER_SECOND
    }
    return raw.coerceIn(MIN_PREBUFFER_BYTES, MAX_PREBUFFER_BYTES)
}

/**
 * The stream next-episode auto-play would choose from [streams]. Mirrors the selection in
 * launchPlayerNextEpisodeAutoPlay so the pre-buffered file is the one that actually plays.
 */
internal fun nextEpisodeAutoPlayCandidate(
    settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
    streams: List<StreamItem>,
): StreamItem? {
    if (streams.isEmpty()) return null
    val shouldAutoSelectInManualMode =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (settings.streamAutoPlayNextEpisodeEnabled || settings.streamAutoPlayPreferBingeGroup)
    if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) return null
    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            (!settings.streamAutoPlayNextEpisodeEnabled || !settings.streamAutoPlayNextEpisodeFallbackEnabled) &&
            settings.streamAutoPlayPreferBingeGroup
    val effectiveMode = if (shouldAutoSelectInManualMode) StreamAutoPlayMode.FIRST_STREAM else settings.streamAutoPlayMode
    val effectiveSource = if (shouldAutoSelectInManualMode) StreamAutoPlaySource.ALL_SOURCES else settings.streamAutoPlaySource
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) currentStreamBingeGroup else null
    val debridSettings = DebridSettingsRepository.snapshot()
    val installedAddonNames = AddonRepository.uiState.value.addons
        .enabledAddons()
        .map { it.displayTitle }
        .toSet()

    fun select(preferBinge: Boolean, bingeOnly: Boolean): StreamItem? =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = effectiveMode,
            regexPattern = if (shouldAutoSelectInManualMode) "" else settings.streamAutoPlayRegex,
            source = effectiveSource,
            installedAddonNames = installedAddonNames,
            selectedAddons = if (shouldAutoSelectInManualMode) emptySet() else settings.streamAutoPlaySelectedAddons,
            selectedPlugins = if (shouldAutoSelectInManualMode) emptySet() else settings.streamAutoPlaySelectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBinge,
            bingeGroupOnly = bingeOnly,
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
        )

    if (preferredBingeGroup != null && settings.streamAutoPlayPreferBingeGroup) {
        select(preferBinge = true, bingeOnly = true)?.let { return it }
    }
    return select(
        preferBinge = settings.streamAutoPlayPreferBingeGroup,
        bingeOnly = bingeGroupOnlyManualMode,
    )
}

private const val CEILING_RELEASE_MARGIN_MS = 4_000L
private const val STREAM_WAIT_MS = 90_000L
private const val FALLBACK_BYTES_PER_SECOND = 1_500_000L // ~12 Mbit/s
private const val MIN_PREBUFFER_BYTES = 4L * 1024L * 1024L
private const val MAX_PREBUFFER_BYTES = 400L * 1024L * 1024L
