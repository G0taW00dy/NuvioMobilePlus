package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PlayerTuningRepository
import com.nuvio.app.features.player.PlayerTuningSettings as Limits

// Cybervulcan build: settings pages for libmpv buffering and smart buffering.

@Composable
internal fun PlaybackMpvBufferSettings(isTablet: Boolean) {
    val tuning by PlayerTuningRepository.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(title = "libmpv buffer", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Custom libmpv buffer",
                    description = "Use the values below instead of libmpv's small default cache. " +
                        "Applies to libmpv only (anime on Auto). Takes effect on the next video.",
                    checked = tuning.mpvCustomBufferEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerTuningRepository.update { it.copy(mpvCustomBufferEnabled = enabled) }
                    },
                )
                if (tuning.mpvCustomBufferEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSliderRow(
                        title = "Read ahead",
                        value = tuning.mpvReadAheadSec,
                        valueText = { seconds -> secondsLabel(seconds) },
                        valueRange = Limits.MIN_MPV_READ_AHEAD_SEC..Limits.MAX_MPV_READ_AHEAD_SEC,
                        step = Limits.MPV_READ_AHEAD_STEP_SEC,
                        isTablet = isTablet,
                        onValueChange = { value -> PlayerTuningRepository.update { it.copy(mpvReadAheadSec = value) } },
                    )
                    SettingsSliderRow(
                        title = "Cache size (memory)",
                        value = tuning.mpvCacheMb,
                        valueText = { mb -> "$mb MB" },
                        valueRange = Limits.MIN_MPV_CACHE_MB..Limits.MAX_MPV_CACHE_MB,
                        step = Limits.MPV_CACHE_STEP_MB,
                        isTablet = isTablet,
                        onValueChange = { value -> PlayerTuningRepository.update { it.copy(mpvCacheMb = value) } },
                    )
                    SettingsSliderRow(
                        title = "Back cache (for rewinding)",
                        value = tuning.mpvBackCacheMb,
                        valueText = { mb -> "$mb MB" },
                        valueRange = 0..Limits.MAX_MPV_BACK_CACHE_MB,
                        step = Limits.MPV_CACHE_STEP_MB,
                        isTablet = isTablet,
                        onValueChange = { value -> PlayerTuningRepository.update { it.copy(mpvBackCacheMb = value) } },
                    )
                    SettingsSliderRow(
                        title = "Resume after a stall",
                        value = tuning.mpvResumeAfterStallSec,
                        valueText = { seconds -> "$seconds s buffered" },
                        valueRange = 1..Limits.MAX_MPV_RESUME_SEC,
                        step = 1,
                        isTablet = isTablet,
                        onValueChange = { value -> PlayerTuningRepository.update { it.copy(mpvResumeAfterStallSec = value) } },
                    )
                    HintText(
                        text = "Read ahead stops at whichever is reached first: the time or the cache size. " +
                            "At around 10 Mbit/s, 60 s needs about 75 MB. Higher values use more phone memory.",
                        isTablet = isTablet,
                    )
                }
            }
        }
        RetryWindowSection(tuning.networkRetryWindowSec, isTablet)
    }
}

@Composable
internal fun PlaybackSmartBufferingSettings(isTablet: Boolean, settings: PlayerSettingsUiState) {
    val tuning by PlayerTuningRepository.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(title = "Next episode", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Pre-buffer next episode",
                    description = "Near the end of an episode, find the next episode's source (the one " +
                        "auto-play will use) and fetch its start in the background.",
                    checked = tuning.prebufferNextEpisode,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerTuningRepository.update { it.copy(prebufferNextEpisode = enabled) }
                    },
                )
                if (tuning.prebufferNextEpisode) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSliderRow(
                        title = "Start when this episode reaches",
                        value = tuning.prebufferTriggerPercent,
                        valueText = { percent -> "$percent%" },
                        valueRange = Limits.MIN_PREBUFFER_TRIGGER_PERCENT..Limits.MAX_PREBUFFER_TRIGGER_PERCENT,
                        step = 1,
                        isTablet = isTablet,
                        onValueChange = { value -> PlayerTuningRepository.update { it.copy(prebufferTriggerPercent = value) } },
                    )
                    SettingsSliderRow(
                        title = "Amount to pre-buffer",
                        value = tuning.prebufferSeconds,
                        valueText = { seconds -> secondsLabel(seconds) },
                        valueRange = Limits.MIN_PREBUFFER_SEC..Limits.MAX_PREBUFFER_SEC,
                        step = Limits.PREBUFFER_STEP_SEC,
                        isTablet = isTablet,
                        onValueChange = { value -> PlayerTuningRepository.update { it.copy(prebufferSeconds = value) } },
                    )
                    HintText(
                        text = if (settings.vodCacheEnabled) {
                            "ExoPlayer: saved to the disk cache, so the next episode starts from storage. " +
                                "libmpv: the link is prepared in advance (it has no disk cache)."
                        } else {
                            "Turn on the disk cache in ExoPlayer buffer and cache to store the pre-buffer. " +
                                "Without it, the next link is only prepared in advance."
                        },
                        isTablet = isTablet,
                    )
                }
            }
        }
        SettingsSection(title = "Skipped sections", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Don't buffer auto-skipped sections",
                    description = "ExoPlayer stops loading at the start of an intro, recap, outro or credits " +
                        "that auto-skip will jump over, and buffers the other side of it instead, so the " +
                        "skipped part is never downloaded. Needs auto-skip on. libmpv isn't affected.",
                    checked = tuning.skipBufferingSkippedSections,
                    enabled = settings.skipIntroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerTuningRepository.update { it.copy(skipBufferingSkippedSections = enabled) }
                    },
                )
                HintText(
                    text = if (settings.vodCacheEnabled) {
                        "Your max buffer is shared across the skip: time buffered before the section " +
                            "counts, the rest (up to 5 min) is buffered after it, using the skip times and " +
                            "the file's own index. Takes effect from the next video."
                    } else {
                        "Turn on the disk cache in ExoPlayer buffer and cache to buffer past the skip. " +
                            "Without it, loading just stops at the section and restarts after the jump."
                    },
                    isTablet = isTablet,
                )
            }
        }
        RetryWindowSection(tuning.networkRetryWindowSec, isTablet)
    }
}

@Composable
private fun RetryWindowSection(windowSec: Int, isTablet: Boolean) {
    SettingsSection(title = "Connection drops (both players)", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSliderRow(
                title = "Keep retrying for",
                value = windowSec,
                valueText = { seconds -> secondsLabel(seconds) },
                valueRange = Limits.MIN_RETRY_WINDOW_SEC..Limits.MAX_RETRY_WINDOW_SEC,
                step = Limits.RETRY_WINDOW_STEP_SEC,
                isTablet = isTablet,
                onValueChange = { value -> PlayerTuningRepository.update { it.copy(networkRetryWindowSec = value) } },
            )
            HintText(
                text = "How long a stream keeps trying to reconnect when the signal drops, before giving " +
                    "up. ExoPlayer also reopens the stream a few more times after this. Dead links " +
                    "still fail straight away.",
                isTablet = isTablet,
            )
        }
    }
}

@Composable
private fun HintText(text: String, isTablet: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
    )
}

private fun secondsLabel(seconds: Int): String =
    if (seconds >= 120 && seconds % 60 == 0) "${seconds / 60} min" else if (seconds >= 60) {
        val minutes = seconds / 60
        val rest = seconds % 60
        if (rest == 0) "$minutes min" else "$minutes min $rest s"
    } else {
        "$seconds s"
    }
