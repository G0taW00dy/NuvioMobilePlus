package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun SeriesDownloadScreen(
    metaId: String,
    initialSeasonNumber: Int? = null,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val detailsUiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val meta = remember(detailsUiState.meta, metaId) {
        detailsUiState.meta?.takeIf { it.id == metaId } ?: MetaDetailsRepository.peek("series", metaId)
    }

    LaunchedEffect(metaId) {
        if (meta == null || meta.videos.isEmpty()) {
            MetaDetailsRepository.load("series", metaId)
        }
    }

    val downloadsUiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()

    val batchState by SeriesBatchDownloadManager.batchState.collectAsStateWithLifecycle()

    val seasons = remember(meta) {
        meta?.videos?.mapNotNull { it.season }?.distinct()?.sorted() ?: emptyList()
    }

    var selectedSeason by rememberSaveable(metaId, seasons) {
        val initial = initialSeasonNumber ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
        mutableIntStateOf(initial)
    }

    val todayIsoDate = remember { CurrentDateProvider.todayIsoDate() }
    val seasonEpisodes = remember(meta, selectedSeason, todayIsoDate) {
        meta?.releasedEpisodesForSeason(selectedSeason, todayIsoDate) ?: emptyList()
    }

    var selectedEpisodeIds by rememberSaveable(metaId) { mutableStateOf(emptySet<String>()) }

    val seriesCompletedEpisodes = remember(downloadsUiState.items, metaId) {
        downloadsUiState.completedItems.filter { it.parentMetaId == metaId }
    }

    fun isEpisodeDownloaded(ep: MetaVideo): Boolean =
        seriesCompletedEpisodes.any { it.seasonNumber == (ep.season ?: 1) && it.episodeNumber == (ep.episode ?: 1) }

    fun isEpisodeInBatch(ep: MetaVideo): Boolean {
        val s = ep.season ?: 1
        val e = ep.episode ?: 1
        val status = batchState.statusForEpisode(metaId, s, e)
        return status == BatchEpisodeStatus.Queued ||
            status == BatchEpisodeStatus.Resolving ||
            status == BatchEpisodeStatus.Downloading
    }

    val availableForSelection = remember(seasonEpisodes, seriesCompletedEpisodes, batchState.items) {
        seasonEpisodes.filter { !isEpisodeDownloaded(it) && !isEpisodeInBatch(it) }
    }

    val allSeasonSelected = availableForSelection.isNotEmpty() &&
        availableForSelection.all { it.id in selectedEpisodeIds }

    fun toggleSelectAll() {
        if (allSeasonSelected) {
            val toRemove = availableForSelection.map { it.id }.toSet()
            selectedEpisodeIds = selectedEpisodeIds - toRemove
        } else {
            val toAdd = availableForSelection.map { it.id }.toSet()
            selectedEpisodeIds = selectedEpisodeIds + toAdd
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NuvioScreenHeader(
                title = stringResource(Res.string.series_download_title),
                onBack = onBack,
            )

            if (meta == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = tokens.colors.accent,
                        strokeWidth = 3.dp,
                    )
                }
            } else {
                // Header with Show info
                SeriesDownloadHero(meta = meta)

                // Season Selector Tabs / Chips
                if (seasons.size > 1) {
                    SeasonSelectorRow(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        onSelectSeason = { selectedSeason = it },
                    )
                }

                // Season Episodes Subheader with Select All
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val seasonLabel = if (selectedSeason == 0) {
                        stringResource(Res.string.series_download_specials_label)
                    } else {
                        stringResource(Res.string.series_download_season_label, selectedSeason)
                    }
                    Text(
                        text = "$seasonLabel (${seasonEpisodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )

                    if (availableForSelection.isNotEmpty()) {
                        TextButton(onClick = ::toggleSelectAll) {
                            Text(
                                text = stringResource(
                                    if (allSeasonSelected) Res.string.series_download_deselect_all else Res.string.series_download_select_all,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = tokens.colors.accent,
                            )
                        }
                    }
                }

                // Episodes List
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = tokens.spacing.screenHorizontal,
                        end = tokens.spacing.screenHorizontal,
                        bottom = 120.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(seasonEpisodes, key = { it.id }) { episode ->
                        val isDownloaded = isEpisodeDownloaded(episode)
                        val seasonNum = episode.season ?: 1
                        val episodeNum = episode.episode ?: 1
                        val batchItem = batchState.itemForEpisode(metaId, seasonNum, episodeNum)
                        val isSelected = episode.id in selectedEpisodeIds

                        SeriesDownloadEpisodeRow(
                            episode = episode,
                            isDownloaded = isDownloaded,
                            batchItem = batchItem,
                            isSelected = isSelected,
                            onToggleSelection = {
                                if (!isDownloaded && batchItem?.status != BatchEpisodeStatus.Downloading && batchItem?.status != BatchEpisodeStatus.Resolving) {
                                    selectedEpisodeIds = if (isSelected) {
                                        selectedEpisodeIds - episode.id
                                    } else {
                                        selectedEpisodeIds + episode.id
                                    }
                                }
                            },
                            onRetry = {
                                batchItem?.key?.let { SeriesBatchDownloadManager.retryItem(it) }
                            },
                        )
                    }
                }
            }
        }

        // Bottom Docked Bar: shows active batch status & download action button
        SeriesDownloadBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            meta = meta,
            selectedEpisodeIds = selectedEpisodeIds,
            batchState = batchState,
            onClearSelection = { selectedEpisodeIds = emptySet() },
            onStartDownload = {
                if (meta != null && selectedEpisodeIds.isNotEmpty()) {
                    val episodesToEnqueue = meta.videos.filter { it.id in selectedEpisodeIds }
                    val count = episodesToEnqueue.size
                    SeriesBatchDownloadManager.enqueueEpisodes(meta, episodesToEnqueue)
                    selectedEpisodeIds = emptySet()
                    NuvioToastController.show("Added $count episode(s) to download queue")
                }
            },
        )
    }
}

@Composable
private fun SeriesDownloadHero(meta: MetaDetails) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val posterUrl = meta.poster ?: meta.background
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(48.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val info = listOfNotNull(
                meta.releaseInfo?.takeIf { it.isNotBlank() },
                meta.ageRating?.takeIf { it.isNotBlank() },
                meta.imdbRating?.let { "★ $it" },
            ).joinToString(" • ")

            if (info.isNotBlank()) {
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SeasonSelectorRow(
    seasons: List<Int>,
    selectedSeason: Int,
    onSelectSeason: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        seasons.forEach { season ->
            val isSelected = season == selectedSeason
            val label = if (season == 0) {
                stringResource(Res.string.series_download_specials_label)
            } else {
                stringResource(Res.string.series_download_season_label, season)
            }

            Surface(
                onClick = { onSelectSeason(season) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) tokens.colors.accent.copy(alpha = 0.2f) else tokens.colors.surfaceCard,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, tokens.colors.accent) else null,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) tokens.colors.accent else tokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SeriesDownloadEpisodeRow(
    episode: MetaVideo,
    isDownloaded: Boolean,
    batchItem: BatchEpisodeItem?,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onRetry: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val season = episode.season ?: 1
    val epNum = episode.episode ?: 1
    val epCode = "E${if (epNum < 10) "0$epNum" else "$epNum"}"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isDownloaded && batchItem?.status != BatchEpisodeStatus.Downloading && batchItem?.status != BatchEpisodeStatus.Resolving) {
                onToggleSelection()
            },
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) tokens.colors.accent.copy(alpha = 0.08f) else tokens.colors.surfaceCard,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.accent.copy(alpha = 0.5f)) else null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.colors.background),
                ) {
                    if (!episode.thumbnail.isNullOrBlank()) {
                        AsyncImage(
                            model = episode.thumbnail,
                            contentDescription = episode.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Episode code pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = epCode,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = episode.title.ifBlank { "Episode $epNum" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    episode.released?.takeIf { it.isNotBlank() }?.let { date ->
                        Text(
                            text = date,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                        )
                    }

                    if (!episode.overview.isNullOrBlank()) {
                        Text(
                            text = episode.overview,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Action / Status Indicator
                when {
                    isDownloaded -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0xFF2E7D32).copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(Res.string.series_download_status_downloaded),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    batchItem?.status == BatchEpisodeStatus.Downloading -> {
                        val pct = ((batchItem.progressFraction) * 100).toInt().coerceIn(0, 100)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(tokens.colors.accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = tokens.colors.accent,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.accent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    batchItem?.status == BatchEpisodeStatus.Resolving -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0xFFFFA000).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFFA000),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(Res.string.series_download_status_resolving),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFA000),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    batchItem?.status == BatchEpisodeStatus.Queued -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(tokens.colors.textMuted.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = "Queued",
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(Res.string.series_download_status_queued),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    batchItem?.status == BatchEpisodeStatus.Failed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Failed",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = tokens.colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    else -> {
                        // Interactive Checkbox
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) tokens.colors.accent else tokens.colors.textMuted.copy(alpha = 0.5f),
                                    shape = CircleShape,
                                )
                                .background(if (isSelected) tokens.colors.accent else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Live progress bar if actively downloading
            if (batchItem?.status == BatchEpisodeStatus.Downloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { batchItem.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = tokens.colors.accent,
                    trackColor = tokens.colors.accent.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun SeriesDownloadBottomBar(
    modifier: Modifier = Modifier,
    meta: MetaDetails?,
    selectedEpisodeIds: Set<String>,
    batchState: SeriesBatchState,
    onClearSelection: () -> Unit,
    onStartDownload: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val hasActiveBatch = batchState.hasActiveOrQueued
    val selectedCount = selectedEpisodeIds.size

    AnimatedVisibility(
        visible = hasActiveBatch || selectedCount > 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = tokens.colors.surfaceSheet.copy(alpha = 0.95f),
            shadowElevation = 12.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Active Batch Status Header (if running)
                if (hasActiveBatch) {
                    val active = batchState.activeItem
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            val activeText = if (active != null) {
                                "${active.episodeCode} • ${active.episodeTitle}"
                            } else {
                                stringResource(Res.string.series_batch_download_active)
                            }
                            Text(
                                text = activeText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = tokens.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtext = "${batchState.completedCount} completed • ${batchState.pendingCount} remaining"
                            Text(
                                text = subtext,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (batchState.isPaused) {
                                        SeriesBatchDownloadManager.resumeBatch()
                                    } else {
                                        SeriesBatchDownloadManager.pauseBatch()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (batchState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (batchState.isPaused) "Resume" else "Pause",
                                    tint = tokens.colors.accent,
                                )
                            }

                            IconButton(onClick = { SeriesBatchDownloadManager.cancelBatch() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel All",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (active?.status == BatchEpisodeStatus.Downloading) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { active.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = tokens.colors.accent,
                            trackColor = tokens.colors.accent.copy(alpha = 0.2f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Download Button for currently selected episodes
                if (selectedCount > 0) {
                    Button(
                        onClick = onStartDownload,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.colors.accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val btnLabel = if (selectedCount == 1) {
                            stringResource(Res.string.series_download_action_single)
                        } else {
                            stringResource(Res.string.series_download_action, selectedCount)
                        }
                        Text(
                            text = btnLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
