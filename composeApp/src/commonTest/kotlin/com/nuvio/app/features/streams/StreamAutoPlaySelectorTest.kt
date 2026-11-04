package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAutoPlaySelectorTest {

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group",
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group",
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(allowedPluginMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode remains manual even with matching bingeGroup`() {
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertNull(selected)
    }

    @Test
    fun `first stream mode can select direct debrid candidate without resolved URL`() {
        val directDebrid = stream(
            addonName = "Torbox Instant",
            url = null,
            name = "TB Instant",
            directDebrid = true,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(directDebrid),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(directDebrid, selected)
    }

    @Test
    fun `first stream mode does not auto select external url browser link`() {
        val external = stream(
            addonName = "External Addon",
            externalUrl = "https://example.com/watch",
            name = "Watch on site",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(external),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("External Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun `timeout evaluation keeps pending regex debrid candidate open`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertTrue(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `timeout evaluation still selects direct link while debrid candidate is pending`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )
        val direct = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "The Show 1080p",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending, direct),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio", "Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertEquals(direct, evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `direct debrid candidate must match active resolver`() {
        val torbox = stream(
            addonName = "Comet",
            name = "TB Instant",
            directDebrid = true,
            directDebridService = "torbox",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(torbox),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Comet"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `nested exclusions do not crash regex selection`() {
        val stream = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "Movie 1080p WEB",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "^(?!.*\\b(CAM(?!RIP)|TS)\\b).*1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(stream, selected)
    }


    @Test
    fun `download mode filters streams exceeding downloadMaxSizeGb`() {
        val hugeRemux = stream(
            addonName = "Torrentio",
            url = "https://example.com/huge.mkv",
            name = "4K Remux (35.4 GB)",
            description = "💾 35.4 GB",
        )
        val normalStream = stream(
            addonName = "Torrentio",
            url = "https://example.com/normal.mkv",
            name = "1080p (3.2 GB)",
            description = "💾 3.2 GB",
        )

        val playSelected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(hugeRemux, normalStream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            isDownloadMode = false,
            downloadMaxSizeGb = 5,
        )
        assertEquals(hugeRemux, playSelected)

        val downloadSelected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(hugeRemux, normalStream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            isDownloadMode = true,
            downloadMaxSizeGb = 5,
        )
        assertEquals(normalStream, downloadSelected)
    }

    @Test
    fun `download mode returns null when all streams exceed downloadMaxSizeGb`() {
        val huge1 = stream(
            addonName = "Torrentio",
            url = "https://example.com/huge1.mkv",
            description = "💾 25 GB",
        )
        val huge2 = stream(
            addonName = "Torrentio",
            url = "https://example.com/huge2.mkv",
            description = "💾 18 GB",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(huge1, huge2),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            isDownloadMode = true,
            downloadMaxSizeGb = 5,
        )
        assertNull(selected)
    }


    @Test
    fun `download mode with regex 1080p selects highest quality under max download size`() {
        val remux28g = stream(
            addonName = "Torrentio",
            url = "https://example.com/remux.mkv",
            name = "[RD+] Torrentio\n1080p",
            title = "Movie.2024.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-GROUP\n👤 150 💾 28.45 GB ⚙️ Torrentio",
            description = "Movie.2024.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-GROUP\n👤 150 💾 28.45 GB ⚙️ Torrentio",
        )
        val bluray12g = stream(
            addonName = "Torrentio",
            url = "https://example.com/bluray.mkv",
            name = "[RD+] Torrentio\n1080p",
            title = "Movie.2024.1080p.BluRay.x264-GROUP\n👤 80 💾 12.1 GB ⚙️ Torrentio",
            description = "Movie.2024.1080p.BluRay.x264-GROUP\n👤 80 💾 12.1 GB ⚙️ Torrentio",
        )
        val webdl4g = stream(
            addonName = "Torrentio",
            url = "https://example.com/webdl.mkv",
            name = "[RD+] Torrentio\n1080p",
            title = "Movie.2024.1080p.WEB-DL.DDP5.1.Atmos.H.264-GROUP\n👤 95 💾 4.5 GB ⚙️ Torrentio",
            description = "Movie.2024.1080p.WEB-DL.DDP5.1.Atmos.H.264-GROUP\n👤 95 💾 4.5 GB ⚙️ Torrentio",
        )
        val webrip2g = stream(
            addonName = "Torrentio",
            url = "https://example.com/webrip.mkv",
            name = "[RD+] Torrentio\n1080p",
            title = "Movie.2024.1080p.WEBRip.x265-GROUP\n👤 40 💾 2.1 GB ⚙️ Torrentio",
            description = "Movie.2024.1080p.WEBRip.x265-GROUP\n👤 40 💾 2.1 GB ⚙️ Torrentio",
        )
        val hdtv720p = stream(
            addonName = "Torrentio",
            url = "https://example.com/720p.mkv",
            name = "[RD+] Torrentio\n720p",
            title = "Movie.2024.720p.HDTV.x264-GROUP\n👤 10 💾 1.2 GB ⚙️ Torrentio",
            description = "Movie.2024.720p.HDTV.x264-GROUP\n👤 10 💾 1.2 GB ⚙️ Torrentio",
        )

        // When streaming/playing without download mode: picks first 1080p match (remux 28 GB)
        val playSelected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(remux28g, bluray12g, webdl4g, webrip2g, hdtv720p),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            isDownloadMode = false,
            downloadMaxSizeGb = 5,
        )
        assertEquals(remux28g, playSelected)

        // When downloading with max 5 GB limit: strictly filters out 28.45 GB and 12.1 GB, selects 4.5 GB
        val downloadSelected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(remux28g, bluray12g, webdl4g, webrip2g, hdtv720p),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            isDownloadMode = true,
            downloadMaxSizeGb = 5,
        )
        assertEquals(webdl4g, downloadSelected)
    }

    @Test
    fun `download mode respects size parsed from filename with underscores or units like Go`() {
        val hugeFilename = stream(
            addonName = "Torrentio",
            url = "https://example.com/huge.mkv",
            name = "Movie 1080p",
            filename = "Movie_2024_1080p_BluRay_22GB.mkv",
        )
        val validFilename = stream(
            addonName = "Torrentio",
            url = "https://example.com/valid.mkv",
            name = "Movie 1080p",
            filename = "Movie_2024_1080p_WEB-DL_4.2Go.mkv",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(hugeFilename, validFilename),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            isDownloadMode = true,
            downloadMaxSizeGb = 5,
        )
        assertEquals(validFilename, selected)
    }

    @Test
    fun `download mode with selected addons filters out unselected addons`() {
        val addon1Stream = stream(
            addonName = "AddonOne",
            url = "https://example.com/one.mkv",
            name = "Stream 1",
            videoSize = 2_000_000_000L,
        )
        val addon2Stream = stream(
            addonName = "AddonTwo",
            url = "https://example.com/two.mkv",
            name = "Stream 2",
            videoSize = 2_000_000_000L,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(addon1Stream, addon2Stream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = setOf("AddonOne", "AddonTwo"),
            selectedAddons = setOf("AddonTwo"),
            selectedPlugins = emptySet(),
            isDownloadMode = true,
            downloadMaxSizeGb = 5,
        )
        assertEquals(addon2Stream, selected)
    }

    @Test
    fun `download mode with enabled plugins only filters out standard addons`() {
        val standardAddonStream = stream(
            addonName = "StandardAddon",
            url = "https://example.com/standard.mkv",
            name = "Stream 1",
            videoSize = 2_000_000_000L,
        )
        val pluginStream = stream(
            addonName = "PluginScraper",
            url = "https://example.com/plugin.mkv",
            name = "Stream 2",
            videoSize = 2_000_000_000L,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(standardAddonStream, pluginStream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("StandardAddon"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginScraper"),
            isDownloadMode = true,
            downloadMaxSizeGb = 5,
        )
        assertEquals(pluginStream, selected)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        externalUrl: String? = null,
        name: String? = null,
        title: String? = null,
        bingeGroup: String? = null,
        directDebrid: Boolean = false,
        directDebridService: String = "torbox",
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        description: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): StreamItem = StreamItem(
        name = name,
        title = title ?: name,
        description = description,
        url = url,
        externalUrl = externalUrl,
        infoHash = infoHash,
        addonName = addonName,
        addonId = "addon:$addonName",
        clientResolve = if (directDebrid) {
            StreamClientResolve(
                type = "debrid",
                service = directDebridService,
                isCached = true,
                infoHash = "hash",
            )
        } else {
            null
        },
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(
                providerId = "premiumize",
                providerName = "Premiumize",
                state = state,
            )
        },
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
            videoSize = videoSize,
            filename = filename,
        ),
    )
}
