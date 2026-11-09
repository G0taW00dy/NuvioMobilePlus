package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cybervulcan build: extra playback tuning that sits beside the official player settings.
 *
 * Kept in its own small store (one JSON value, per device) so it doesn't touch the official
 * settings sync and merges cleanly with upstream Nuvio. Buffer behaviour is a property of the
 * device and its connection, so it is deliberately not per profile.
 */
@Serializable
internal data class PlayerTuningSettings(
    // libmpv buffering (ExoPlayer has its own section in the official settings).
    val mpvCustomBufferEnabled: Boolean = false,
    val mpvReadAheadSec: Int = DEFAULT_MPV_READ_AHEAD_SEC,
    val mpvCacheMb: Int = DEFAULT_MPV_CACHE_MB,
    val mpvBackCacheMb: Int = DEFAULT_MPV_BACK_CACHE_MB,
    val mpvResumeAfterStallSec: Int = DEFAULT_MPV_RESUME_SEC,
    // Both players: how long to keep retrying when the connection drops.
    val networkRetryWindowSec: Int = DEFAULT_RETRY_WINDOW_SEC,
    // Next episode: fetch the start of the next episode before this one ends.
    val prebufferNextEpisode: Boolean = true,
    val prebufferTriggerPercent: Int = DEFAULT_PREBUFFER_TRIGGER_PERCENT,
    val prebufferSeconds: Int = DEFAULT_PREBUFFER_SEC,
    // ExoPlayer: stop loading at the start of a section that will be auto-skipped.
    val skipBufferingSkippedSections: Boolean = false,
) {
    fun sanitized(): PlayerTuningSettings = copy(
        mpvReadAheadSec = mpvReadAheadSec.coerceIn(MIN_MPV_READ_AHEAD_SEC, MAX_MPV_READ_AHEAD_SEC),
        mpvCacheMb = mpvCacheMb.coerceIn(MIN_MPV_CACHE_MB, MAX_MPV_CACHE_MB),
        mpvBackCacheMb = mpvBackCacheMb.coerceIn(0, MAX_MPV_BACK_CACHE_MB),
        mpvResumeAfterStallSec = mpvResumeAfterStallSec.coerceIn(1, MAX_MPV_RESUME_SEC),
        networkRetryWindowSec = networkRetryWindowSec.coerceIn(MIN_RETRY_WINDOW_SEC, MAX_RETRY_WINDOW_SEC),
        prebufferTriggerPercent = prebufferTriggerPercent.coerceIn(MIN_PREBUFFER_TRIGGER_PERCENT, MAX_PREBUFFER_TRIGGER_PERCENT),
        prebufferSeconds = prebufferSeconds.coerceIn(MIN_PREBUFFER_SEC, MAX_PREBUFFER_SEC),
    )

    companion object {
        const val DEFAULT_MPV_READ_AHEAD_SEC = 60
        const val MIN_MPV_READ_AHEAD_SEC = 10
        const val MAX_MPV_READ_AHEAD_SEC = 1_200
        const val MPV_READ_AHEAD_STEP_SEC = 10

        const val DEFAULT_MPV_CACHE_MB = 150
        const val MIN_MPV_CACHE_MB = 32
        const val MAX_MPV_CACHE_MB = 1_024
        const val MPV_CACHE_STEP_MB = 16

        const val DEFAULT_MPV_BACK_CACHE_MB = 50
        const val MAX_MPV_BACK_CACHE_MB = 512

        const val DEFAULT_MPV_RESUME_SEC = 5
        const val MAX_MPV_RESUME_SEC = 60

        const val DEFAULT_RETRY_WINDOW_SEC = 120
        const val MIN_RETRY_WINDOW_SEC = 30
        const val MAX_RETRY_WINDOW_SEC = 600
        const val RETRY_WINDOW_STEP_SEC = 30

        const val DEFAULT_PREBUFFER_TRIGGER_PERCENT = 90
        const val MIN_PREBUFFER_TRIGGER_PERCENT = 70
        const val MAX_PREBUFFER_TRIGGER_PERCENT = 98

        const val DEFAULT_PREBUFFER_SEC = 30
        const val MIN_PREBUFFER_SEC = 10
        const val MAX_PREBUFFER_SEC = 120
        const val PREBUFFER_STEP_SEC = 5
    }
}

internal expect object PlayerTuningStorage {
    fun load(): String?
    fun save(json: String)
}

internal object PlayerTuningRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var loaded = false
    private val _state = MutableStateFlow(PlayerTuningSettings())
    val state: StateFlow<PlayerTuningSettings>
        get() {
            ensureLoaded()
            return _state.asStateFlow()
        }

    /** Snapshot for code that runs when a player is created. */
    fun current(): PlayerTuningSettings {
        ensureLoaded()
        return _state.value
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching { PlayerTuningStorage.load() }.getOrNull()
        if (!stored.isNullOrBlank()) {
            runCatching { json.decodeFromString(PlayerTuningSettings.serializer(), stored) }
                .getOrNull()
                ?.let { _state.value = it.sanitized() }
        }
    }

    fun update(transform: (PlayerTuningSettings) -> PlayerTuningSettings) {
        ensureLoaded()
        val next = transform(_state.value).sanitized()
        if (next == _state.value) return
        _state.value = next
        runCatching { PlayerTuningStorage.save(json.encodeToString(PlayerTuningSettings.serializer(), next)) }
    }
}

/** A section that auto-skip will jump over, in media time. */
internal data class SkippedSection(val startMs: Long, val endMs: Long)

/**
 * The next section auto-skip will jump over. ExoPlayer stops loading at its start, and the
 * jump-ahead buffer fills from its end. Null means no limit. Set by the player screen, read by
 * the Android player.
 */
internal object PlaybackLoadCeiling {
    @kotlin.concurrent.Volatile
    var section: SkippedSection? = null

    val ceilingMs: Long? get() = section?.startMs
}
