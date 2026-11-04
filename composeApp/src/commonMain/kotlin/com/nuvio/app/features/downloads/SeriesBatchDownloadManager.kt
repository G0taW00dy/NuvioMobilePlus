package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamBadgePresentation
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BatchEpisodeStatus {
    Queued,
    Resolving,
    Downloading,
    Completed,
    Failed,
    Cancelled,
}

data class BatchEpisodeItem(
    val metaId: String,
    val metaType: String,
    val metaTitle: String,
    val metaLogo: String?,
    val metaPoster: String?,
    val metaBackground: String?,
    val videoId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val episodeThumbnail: String?,
    val episodeOverview: String?,
    val status: BatchEpisodeStatus = BatchEpisodeStatus.Queued,
    val progressFraction: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val activeDownloadId: String? = null,
    val enqueuedAtEpochMs: Long = 0L,
) {
    val key: String
        get() = "$metaId:$seasonNumber:$episodeNumber"

    val episodeCode: String
        get() {
            val s = if (seasonNumber < 10) "0$seasonNumber" else "$seasonNumber"
            val e = if (episodeNumber < 10) "0$episodeNumber" else "$episodeNumber"
            return "S${s}E${e}"
        }
}

data class SeriesBatchState(
    val items: List<BatchEpisodeItem> = emptyList(),
    val isPaused: Boolean = false,
    val currentActiveKey: String? = null,
) {
    val isRunning: Boolean
        get() = currentActiveKey != null && !isPaused

    val activeItem: BatchEpisodeItem?
        get() = items.firstOrNull { it.key == currentActiveKey }

    val hasActiveOrQueued: Boolean
        get() = items.any {
            it.status == BatchEpisodeStatus.Queued ||
                it.status == BatchEpisodeStatus.Resolving ||
                it.status == BatchEpisodeStatus.Downloading
        }

    val totalCount: Int
        get() = items.size

    val completedCount: Int
        get() = items.count { it.status == BatchEpisodeStatus.Completed }

    val pendingCount: Int
        get() = items.count {
            it.status == BatchEpisodeStatus.Queued ||
                it.status == BatchEpisodeStatus.Resolving ||
                it.status == BatchEpisodeStatus.Downloading
        }

    fun statusForEpisode(metaId: String, season: Int, episode: Int): BatchEpisodeStatus? =
        items.firstOrNull { it.metaId == metaId && it.seasonNumber == season && it.episodeNumber == episode }?.status

    fun itemForEpisode(metaId: String, season: Int, episode: Int): BatchEpisodeItem? =
        items.firstOrNull { it.metaId == metaId && it.seasonNumber == season && it.episodeNumber == episode }
}

object SeriesBatchDownloadManager {
    private val log = Logger.withTag("SeriesBatchDownloader")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _batchState = MutableStateFlow(SeriesBatchState())
    val batchState: StateFlow<SeriesBatchState> = _batchState.asStateFlow()

    private var processingJob: Job? = null

    private const val RATE_LIMIT_PACING_MS = 1200L
    private const val RATE_LIMIT_BETWEEN_EPISODES_MS = 1500L
    private const val RATE_LIMIT_ERROR_BACKOFF_MS = 4000L

    fun enqueueEpisodes(
        meta: MetaDetails,
        episodes: List<MetaVideo>,
    ) {
        if (episodes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val now = DownloadsClock.nowEpochMs()
                val currentItems = _batchState.value.items.toMutableList()
                val newItems = mutableListOf<BatchEpisodeItem>()

                episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).forEach { ep ->
                    val season = ep.season ?: 1
                    val episode = ep.episode ?: 1
                    val playbackVideoId = buildPlaybackVideoId(
                        parentMetaId = meta.id,
                        seasonNumber = season,
                        episodeNumber = episode,
                        fallbackVideoId = ep.id,
                    )
                    val effectiveVideoId = ep.id.takeIf { it.isNotBlank() } ?: playbackVideoId
                    val key = "${meta.id}:$season:$episode"

                    // Check if already completed in DownloadsRepository
                    val alreadyDownloaded = DownloadsRepository.findPlayableDownload(
                        parentMetaId = meta.id,
                        seasonNumber = season,
                        episodeNumber = episode,
                        videoId = effectiveVideoId,
                    )
                    if (alreadyDownloaded != null) {
                        return@forEach
                    }

                    // Check if already in queue
                    val existingIndex = currentItems.indexOfFirst { it.key == key }
                    if (existingIndex >= 0) {
                        val existing = currentItems[existingIndex]
                        if (existing.status == BatchEpisodeStatus.Failed || existing.status == BatchEpisodeStatus.Cancelled) {
                            currentItems[existingIndex] = existing.copy(
                                status = BatchEpisodeStatus.Queued,
                                errorMessage = null,
                                progressFraction = 0f,
                                downloadedBytes = 0L,
                                enqueuedAtEpochMs = now,
                            )
                        }
                    } else {
                        val item = BatchEpisodeItem(
                            metaId = meta.id,
                            metaType = meta.type.ifBlank { "series" },
                            metaTitle = meta.name,
                            metaLogo = meta.logo,
                            metaPoster = meta.poster,
                            metaBackground = meta.background,
                            videoId = effectiveVideoId,
                            seasonNumber = season,
                            episodeNumber = episode,
                            episodeTitle = ep.title.ifBlank { "Episode $episode" },
                            episodeThumbnail = ep.thumbnail,
                            episodeOverview = ep.overview,
                            status = BatchEpisodeStatus.Queued,
                            enqueuedAtEpochMs = now,
                        )
                        newItems.add(item)
                    }
                }

                currentItems.addAll(newItems)
                _batchState.update { it.copy(items = currentItems) }
            }

            ensureProcessingStarted()
        }
    }

    fun pauseBatch() {
        scope.launch {
            mutex.withLock {
                _batchState.update { it.copy(isPaused = true) }
                val activeKey = _batchState.value.currentActiveKey
                val activeItem = _batchState.value.items.firstOrNull { it.key == activeKey }
                activeItem?.activeDownloadId?.let { downloadId ->
                    DownloadsRepository.pauseDownload(downloadId)
                }
            }
        }
    }

    fun resumeBatch() {
        scope.launch {
            mutex.withLock {
                _batchState.update { it.copy(isPaused = false) }
                val activeKey = _batchState.value.currentActiveKey
                val activeItem = _batchState.value.items.firstOrNull { it.key == activeKey }
                activeItem?.activeDownloadId?.let { downloadId ->
                    DownloadsRepository.resumeDownload(downloadId)
                }
            }
            ensureProcessingStarted()
        }
    }

    fun cancelBatch() {
        scope.launch {
            mutex.withLock {
                processingJob?.cancel()
                processingJob = null

                val activeKey = _batchState.value.currentActiveKey
                val activeItem = _batchState.value.items.firstOrNull { it.key == activeKey }
                activeItem?.activeDownloadId?.let { downloadId ->
                    DownloadsRepository.cancelDownload(downloadId)
                }

                _batchState.update { current ->
                    current.copy(
                        isPaused = false,
                        currentActiveKey = null,
                        items = current.items.map { item ->
                            if (item.status == BatchEpisodeStatus.Queued ||
                                item.status == BatchEpisodeStatus.Resolving ||
                                item.status == BatchEpisodeStatus.Downloading
                            ) {
                                item.copy(status = BatchEpisodeStatus.Cancelled)
                            } else {
                                item
                            }
                        },
                    )
                }
            }
        }
    }

    fun cancelItem(key: String) {
        scope.launch {
            mutex.withLock {
                val item = _batchState.value.items.firstOrNull { it.key == key } ?: return@withLock
                if (_batchState.value.currentActiveKey == key) {
                    item.activeDownloadId?.let { DownloadsRepository.cancelDownload(it) }
                    _batchState.update { current ->
                        current.copy(
                            currentActiveKey = null,
                            items = current.items.map { if (it.key == key) it.copy(status = BatchEpisodeStatus.Cancelled) else it },
                        )
                    }
                } else {
                    _batchState.update { current ->
                        current.copy(
                            items = current.items.map { if (it.key == key) it.copy(status = BatchEpisodeStatus.Cancelled) else it },
                        )
                    }
                }
            }
            ensureProcessingStarted()
        }
    }

    fun retryItem(key: String) {
        scope.launch {
            mutex.withLock {
                _batchState.update { current ->
                    current.copy(
                        items = current.items.map {
                            if (it.key == key) {
                                it.copy(
                                    status = BatchEpisodeStatus.Queued,
                                    errorMessage = null,
                                    progressFraction = 0f,
                                    downloadedBytes = 0L,
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
            }
            ensureProcessingStarted()
        }
    }

    fun getBatchProgress(item: DownloadItem): String? {
        if (!item.isEpisode) return null
        val state = _batchState.value
        val seriesItems = state.items.filter { it.metaId == item.parentMetaId }
        if (seriesItems.size <= 1) return null

        val matchIndex = seriesItems.indexOfFirst {
            it.seasonNumber == item.seasonNumber && it.episodeNumber == item.episodeNumber
        }
        if (matchIndex < 0) return null
        return "${matchIndex + 1} of ${seriesItems.size}"
    }

    private fun ensureProcessingStarted() {
        if (processingJob?.isActive == true) return
        processingJob = scope.launch {
            runProcessingLoop()
        }
    }

    private suspend fun runProcessingLoop() {
        while (true) {
            val nextItem = mutex.withLock {
                if (_batchState.value.isPaused) return@withLock null
                _batchState.value.items.firstOrNull { it.status == BatchEpisodeStatus.Queued }
            } ?: break

            val itemKey = nextItem.key
            log.d { "Starting batch download for ${nextItem.episodeCode} (${nextItem.episodeTitle})" }

            mutex.withLock {
                _batchState.update { current ->
                    current.copy(
                        currentActiveKey = itemKey,
                        items = current.items.map {
                            if (it.key == itemKey) it.copy(status = BatchEpisodeStatus.Resolving) else it
                        },
                    )
                }
            }

            // Rate-limit safety pause before querying streams
            delay(RATE_LIMIT_PACING_MS)

            val resolvedPlayableStream = runCatching {
                resolveEpisodePlayableStream(nextItem)
            }.getOrElse { err ->
                log.w(err) { "Failed to resolve stream for ${nextItem.episodeCode}" }
                null
            }

            if (resolvedPlayableStream == null || resolvedPlayableStream.playableDirectUrl.isNullOrBlank()) {
                log.w { "No stream found or debrid resolution failed for ${nextItem.episodeCode}" }
                mutex.withLock {
                    _batchState.update { current ->
                        current.copy(
                            currentActiveKey = null,
                            items = current.items.map {
                                if (it.key == itemKey) {
                                    it.copy(
                                        status = BatchEpisodeStatus.Failed,
                                        errorMessage = "No stream found under size limit or debrid error",
                                    )
                                } else it
                            },
                        )
                    }
                }
                // Backoff delay before attempting next queued episode to avoid rate-limit hammering
                delay(RATE_LIMIT_ERROR_BACKOFF_MS)
                continue
            }

            // Enqueue into DownloadsRepository
            val enqueueResult = DownloadsRepository.enqueueFromStream(
                contentType = nextItem.metaType,
                videoId = nextItem.videoId,
                parentMetaId = nextItem.metaId,
                parentMetaType = nextItem.metaType,
                title = nextItem.metaTitle,
                logo = nextItem.metaLogo,
                poster = nextItem.metaPoster,
                background = nextItem.metaBackground,
                seasonNumber = nextItem.seasonNumber,
                episodeNumber = nextItem.episodeNumber,
                episodeTitle = nextItem.episodeTitle,
                episodeThumbnail = nextItem.episodeThumbnail,
                stream = resolvedPlayableStream,
            )

            if (enqueueResult == DownloadEnqueueResult.MissingUrl || enqueueResult == DownloadEnqueueResult.UnsupportedFormat) {
                mutex.withLock {
                    _batchState.update { current ->
                        current.copy(
                            currentActiveKey = null,
                            items = current.items.map {
                                if (it.key == itemKey) it.copy(status = BatchEpisodeStatus.Failed, errorMessage = "Unsupported stream URL") else it
                            },
                        )
                    }
                }
                delay(RATE_LIMIT_ERROR_BACKOFF_MS)
                continue
            }

            // Find the active download item in DownloadsRepository
            val activeDownloadItem = DownloadsRepository.findPlayableDownload(
                parentMetaId = nextItem.metaId,
                seasonNumber = nextItem.seasonNumber,
                episodeNumber = nextItem.episodeNumber,
                videoId = nextItem.videoId,
            ) ?: DownloadsRepository.uiState.value.items.firstOrNull {
                it.parentMetaId == nextItem.metaId &&
                    it.seasonNumber == nextItem.seasonNumber &&
                    it.episodeNumber == nextItem.episodeNumber
            }

            val downloadId = activeDownloadItem?.id
            mutex.withLock {
                _batchState.update { current ->
                    current.copy(
                        items = current.items.map {
                            if (it.key == itemKey) {
                                it.copy(
                                    status = BatchEpisodeStatus.Downloading,
                                    activeDownloadId = downloadId,
                                )
                            } else it
                        },
                    )
                }
            }

            // Monitor download progress until completed or failed
            var finalStatus: DownloadStatus? = null
            var finalError: String? = null

            DownloadsRepository.uiState
                .mapNotNull { state ->
                    state.items.firstOrNull {
                        (downloadId != null && it.id == downloadId) ||
                            (it.parentMetaId == nextItem.metaId && it.seasonNumber == nextItem.seasonNumber && it.episodeNumber == nextItem.episodeNumber)
                    }
                }
                .takeWhile { currentDownload ->
                    mutex.withLock {
                        _batchState.update { state ->
                            state.copy(
                                items = state.items.map {
                                    if (it.key == itemKey) {
                                        it.copy(
                                            progressFraction = currentDownload.progressFraction,
                                            downloadedBytes = currentDownload.downloadedBytes,
                                            totalBytes = currentDownload.totalBytes,
                                        )
                                    } else it
                                },
                            )
                        }
                    }

                    val isFinished = currentDownload.status == DownloadStatus.Completed ||
                        currentDownload.status == DownloadStatus.Failed
                    if (isFinished) {
                        finalStatus = currentDownload.status
                        finalError = currentDownload.errorMessage
                    }
                    !isFinished
                }
                .collect()

            // Update item state based on outcome
            mutex.withLock {
                val outcomeStatus = if (finalStatus == DownloadStatus.Completed) {
                    BatchEpisodeStatus.Completed
                } else {
                    BatchEpisodeStatus.Failed
                }

                _batchState.update { current ->
                    current.copy(
                        currentActiveKey = null,
                        items = current.items.map {
                            if (it.key == itemKey) {
                                it.copy(
                                    status = outcomeStatus,
                                    errorMessage = if (outcomeStatus == BatchEpisodeStatus.Failed) {
                                        finalError ?: "Download failed"
                                    } else null,
                                )
                            } else it
                        },
                    )
                }
            }

            log.d { "Batch download finished for ${nextItem.episodeCode}: status=$finalStatus" }

            // Pacing delay between completed downloads before starting next
            delay(RATE_LIMIT_BETWEEN_EPISODES_MS)
        }
    }

    private suspend fun resolveEpisodePlayableStream(item: BatchEpisodeItem): StreamItem? {
        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()

        val sourceScope = playerSettings.streamAutoDownloadSource
        val selectedAddons = playerSettings.streamAutoDownloadSelectedAddons
        val selectedPlugins = playerSettings.streamAutoDownloadSelectedPlugins
        val downloadMaxSizeGb = playerSettings.streamAutoPlayDownloadMaxSizeGb

        val isNativeServerRequest = ServerStreams.isNativeRequest(item.videoId)
        val allParsedStreams = mutableListOf<StreamItem>()

        // 1. Installed Addons
        if (sourceScope != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY && !isNativeServerRequest) {
            val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
            val streamAddons = installedAddons.mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                if (!manifest.supportsStream("series", item.videoId)) return@mapNotNull null
                val addonName = addon.displayTitle.ifBlank { manifest.name }
                if (selectedAddons.isNotEmpty() && addonName !in selectedAddons) return@mapNotNull null
                InstalledStreamAddonTarget(
                    addonName = addonName,
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

            streamAddons.forEach { addon ->
                runCatching {
                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = "series",
                        id = item.videoId,
                    )
                    val jsonText = fetchAddonResponseText(url)
                    val parsed = StreamParser.parse(
                        payload = jsonText,
                        addonName = addon.addonName,
                        addonId = addon.addonId,
                        addonLogo = addon.manifest.logoUrl,
                    )
                    allParsedStreams.addAll(parsed)
                }.onFailure { err ->
                    log.w(err) { "Failed to fetch streams from addon ${addon.addonName}" }
                }
            }
        }

        // 2. Plugins (if enabled and applicable)
        if (AppFeaturePolicy.pluginsEnabled && sourceScope != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY && !isNativeServerRequest) {
            runCatching {
                val scrapers = PluginRepository.getEnabledScrapersForType("series")
                    .filter { selectedPlugins.isEmpty() || it.name in selectedPlugins }
                val pluginUiState = PluginRepository.uiState.value
                val providerGroups = scrapers.toPluginProviderGroups(
                    repositories = pluginUiState.repositories,
                    groupByRepository = pluginUiState.groupStreamsByRepository,
                )
                val tmdbId = pluginContentId(item.videoId, item.seasonNumber, item.episodeNumber)

                providerGroups.forEach { providerGroup ->
                    providerGroup.scrapers.forEach { scraper ->
                        runCatching {
                            PluginRepository.executeScraper(
                                scraper = scraper,
                                tmdbId = tmdbId,
                                mediaType = "series",
                                season = item.seasonNumber,
                                episode = item.episodeNumber,
                            ).onSuccess { results ->
                                allParsedStreams.addAll(
                                    results.map {
                                        it.toStreamItem(
                                            scraper = scraper,
                                            addonName = providerGroup.addonName,
                                            addonId = providerGroup.addonId,
                                            includeScraperNameInSubtitle = false,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Server Sources
        runCatching {
            val serverSources = ServerStreams.sources("series", item.videoId, item.seasonNumber, item.episodeNumber)
            serverSources.forEach { source ->
                runCatching {
                    val group = source.load()
                    allParsedStreams.addAll(group.streams)
                }
            }
        }

        if (allParsedStreams.isEmpty()) return null

        // 4. Debrid Annotations & Presentation
        val initialGroup = AddonStreamGroup(
            addonName = "BatchGroup",
            addonId = "batch_group",
            streams = allParsedStreams,
            isLoading = false,
        )
        val presentedGroup = StreamBadgePresentation.apply(
            groups = listOf(initialGroup),
            rules = streamBadgeRules,
        ).firstOrNull() ?: initialGroup

        val cachedAvailabilityGroup = LocalDebridAvailabilityService.annotateCachedAvailability(
            groups = listOf(presentedGroup),
            eligibleGroupIds = setOf("batch_group"),
        ).firstOrNull() ?: presentedGroup

        val finalGroup = DebridStreamPresentation.apply(
            groups = listOf(cachedAvailabilityGroup),
            settings = debridSettings,
        ).firstOrNull() ?: cachedAvailabilityGroup

        // 5. Evaluate AutoPlay / AutoDownload selection
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = finalGroup.streams,
            mode = playerSettings.streamAutoPlayMode,
            regexPattern = playerSettings.streamAutoPlayRegex,
            source = sourceScope,
            installedAddonNames = allParsedStreams.map { it.addonName }.toSet(),
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            isDownloadMode = true,
            downloadMaxSizeGb = downloadMaxSizeGb,
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
        )

        val candidateStream = evaluation.stream ?: return null

        // 6. Resolve playable link (debrid resolve if needed)
        val playableResult = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = candidateStream,
            season = item.seasonNumber,
            episode = item.episodeNumber,
        )
        return (playableResult as? DirectDebridPlayableResult.Success)?.stream
    }
}
