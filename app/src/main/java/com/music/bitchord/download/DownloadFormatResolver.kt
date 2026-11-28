package com.music.bitchord.download

import com.music.bitchord.data.ExtensionRepoManager
import com.music.bitchord.data.Http
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.SflxRuntimeBridge
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.JioSaavnSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealthTracker
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DownloadFormatProgress(
    val formats: List<DownloadFormatOption>,
    val pendingProviders: List<String>,
    val isComplete: Boolean,
)

object DownloadFormatResolver {

    private const val DEFAULT_UA = LosslessAudioProvider.BROWSER_USER_AGENT

    suspend fun resolveFormats(song: Song): List<DownloadFormatOption> = withContext(Dispatchers.IO) {
        resolveFormatsFlow(song).last().formats
    }

    /**
     * Progressively resolves downloadable formats across ALL active Group 2 Download Providers.
     * Fast sources (active playing stream, UMtify/addons, JioSaavn, YouTube Music) emit right away (<1s),
     * while `.sflx` QuickJS providers (Tidal, Qobuz, Deezer, Amazon Music, Beatport) stream in as they complete.
     */
    fun resolveFormatsFlow(song: Song): Flow<DownloadFormatProgress> = channelFlow {
        val durationSec = (song.durationMillis() / 1000).toInt().takeIf { it > 0 } ?: 210
        val cleanTitle = SflxRuntimeBridge.cleanTrackTitle(song.title)
        val primaryArtist = SflxRuntimeBridge.primaryArtistName(SflxRuntimeBridge.cleanTrackTitle(song.artist))
        val target = TrackMatcher.targetOf(song)
        val allowAtmos = AppSettings.dolbyAtmos.value

        val addonSourcesEnabled = SourceRegistry.downloadableAddonSources().isNotEmpty()
        val extSources = SourceRegistry.downloadableExtensionSources()
        val jioSaavnEnabled = SourceRegistry.isDownloadProviderEnabled { it.kind == SourceKind.JIOSAAVN }
        val youtubeEnabled = SourceRegistry.isDownloadProviderEnabled { it.kind == SourceKind.YOUTUBE }
        val losslessFallbackEnabled = addonSourcesEnabled || extSources.isNotEmpty()

        val pendingNames = linkedSetOf<String>()
        if (addonSourcesEnabled) pendingNames.add("UMtify")
        extSources.forEach { pendingNames.add(it.displayName) }
        if (jioSaavnEnabled) pendingNames.add("JioSaavn")
        if (youtubeEnabled) pendingNames.add("YouTube Music")

        val stateMutex = Mutex()
        val collected = mutableListOf<DownloadFormatOption>()
        val seenProviderFormatKeys = HashSet<String>()

        fun sortOptions(list: List<DownloadFormatOption>): List<DownloadFormatOption> =
            list.sortedWith(
                compareByDescending<DownloadFormatOption> { it.isDolbyAtmos }
                    .thenByDescending { it.isLossless && (it.bitDepth ?: 16) > 16 }
                    .thenByDescending { it.isLossless }
                    .thenByDescending { it.fileExtension.equals("m4a", ignoreCase = true) && (it.bitrateKbps ?: 0) >= 256 }
                    .thenByDescending { it.bitrateKbps ?: 0 },
            )

        suspend fun finalizeDraftsAndEmit(
            providerName: String?,
            drafts: List<OptionDraft>,
            markDone: Boolean = true,
        ) {
            val finalized = coroutineScope {
                drafts.map { draft ->
                    async(Dispatchers.IO) {
                        val probedBytes = probeContentLength(draft.streamUrl, draft.headers)
                        val isEst = probedBytes == null
                        val estKbps = when {
                            draft.isLossless -> draft.bitrateKbps.takeIf { it > 0 } ?: if ((draft.bitDepth ?: 16) > 16) 2304 else 1411
                            else -> draft.bitrateKbps
                        }
                        val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = estKbps, durationSec = durationSec)
                        DownloadFormatOption(
                            id = draft.id,
                            label = draft.label,
                            codec = draft.codec,
                            bitrateKbps = draft.bitrateKbps,
                            sampleRateHz = draft.sampleRateHz,
                            bitDepth = draft.bitDepth,
                            sizeBytes = bytes,
                            formattedSize = formatSize(bytes, isEst),
                            streamUrl = draft.streamUrl,
                            fileExtension = draft.fileExtension,
                            isLossless = draft.isLossless,
                            isDolbyAtmos = draft.isDolbyAtmos,
                            headers = draft.headers,
                            sourceName = draft.sourceName,
                        )
                    }
                }.awaitAll()
            }

            val snapshot = stateMutex.withLock {
                if (markDone && providerName != null) {
                    pendingNames.remove(providerName)
                }
                finalized.forEach { opt ->
                    val slug = SflxRuntimeBridge.providerSlug(opt.sourceName ?: providerName ?: "source")
                    val tierKey = "${slug}_${opt.codec}_${opt.bitDepth ?: 0}_${opt.bitrateKbps ?: 0}_${opt.streamUrl}"
                    if (seenProviderFormatKeys.add(tierKey)) {
                        // Ensure unique option ID across all providers
                        val uniqueId = if (opt.id.startsWith(slug)) opt.id else "${slug}_${opt.id}"
                        collected.add(opt.copy(id = uniqueId))
                    }
                }
                DownloadFormatProgress(
                    formats = sortOptions(collected),
                    pendingProviders = pendingNames.toList(),
                    isComplete = pendingNames.isEmpty(),
                )
            }
            send(snapshot)
        }

        // 1. Emit active playing FLAC / Hi-Res / Dolby Atmos stream immediately (<100ms)
        val activeStream = resolveActivePlayingStream(song)
        if (activeStream != null) {
            val srcName = activeStream.sourceName?.takeIf { it.isNotBlank() } ?: "UMtify"
            val draft = draftFromStream("active", activeStream, srcName)
            if (draft != null) {
                finalizeDraftsAndEmit(null, listOf(draft), markDone = false)
            }
        }

        // Launch all providers concurrently on Dispatchers.IO
        coroutineScope {
            // 2. UMtify / Custom Addons
            if (addonSourcesEnabled) {
                launch(Dispatchers.IO) {
                    val addonStreams = runCatching {
                        withTimeoutOrNull(4500L) {
                            SourceResolver.resolveAllAddonDownloadStreams(
                                target = target,
                                wantsDolbyAtmos = allowAtmos,
                                wantsLossless = true,
                            )
                        }
                    }.getOrNull().orEmpty()
                    val drafts = addonStreams.mapIndexedNotNull { idx, stream ->
                        draftFromStream("addon_$idx", stream, "UMtify")
                    }
                    finalizeDraftsAndEmit("UMtify", drafts)
                }
            }

            // 3. Enabled .sflx QuickJS Download Providers (Tidal, Qobuz, Deezer, Amazon Music, Beatport)
            extSources.forEach { ext ->
                launch(Dispatchers.IO) {
                    val startMs = System.currentTimeMillis()
                    val extId = SourceRegistry.extensionIdFor(ext.configId) ?: ext.configId
                    val drafts = mutableListOf<OptionDraft>()

                    // A. Run QuickJS .sflx extension script (6.5s timeout)
                    val sflxOptions = runCatching {
                        SflxRuntimeBridge.resolveDownloadFormats(
                            extensionId = extId,
                            displayName = ext.displayName,
                            title = song.title,
                            artist = song.artist,
                            album = song.albumName,
                            durationSec = durationSec,
                        )
                    }.getOrNull().orEmpty()

                    sflxOptions.forEach { opt ->
                        val safeUrl = LosslessAudioProvider.forceHttps(opt.streamUrl)
                        if (safeUrl.startsWith("https://")) {
                            drafts.add(
                                OptionDraft(
                                    id = opt.id,
                                    label = opt.label,
                                    codec = opt.codec,
                                    bitrateKbps = opt.bitrateKbps ?: if (opt.isLossless) 1411 else 320,
                                    sampleRateHz = opt.sampleRateHz ?: 44100,
                                    bitDepth = opt.bitDepth ?: 16,
                                    streamUrl = safeUrl,
                                    fileExtension = opt.fileExtension,
                                    isLossless = opt.isLossless,
                                    isDolbyAtmos = opt.isDolbyAtmos,
                                    headers = opt.headers,
                                    sourceName = ext.displayName,
                                )
                            )
                        }
                    }

                    // B. If .sflx returned no direct URL (or partial tiers), query verified HTTPS lossless mirrors for this provider
                    if (drafts.isEmpty()) {
                        val endpoints = LosslessAudioProvider.getPreferredEndpointsForExtension(
                            extensionId = extId,
                            extraEndpoints = ExtensionRepoManager.getExtractedEndpointsFor(extId),
                        )
                        val pSlug = SflxRuntimeBridge.providerSlug(ext.displayName)
                        val (hiRes, loRes) = coroutineScope {
                            val hDef = async {
                                runCatching {
                                    withTimeoutOrNull(4500L) {
                                        LosslessAudioProvider.resolveLosslessTrack(
                                            title = cleanTitle,
                                            artist = primaryArtist,
                                            durationSec = durationSec,
                                            preferredQuality = "HI_RES_LOSSLESS",
                                            timeoutMs = 4000L,
                                            endpointsOverride = endpoints,
                                            sourceLabel = ext.displayName,
                                        )
                                    }
                                }.getOrNull()
                            }
                            val lDef = async {
                                runCatching {
                                    withTimeoutOrNull(4500L) {
                                        LosslessAudioProvider.resolveLosslessTrack(
                                            title = cleanTitle,
                                            artist = primaryArtist,
                                            durationSec = durationSec,
                                            preferredQuality = "LOSSLESS",
                                            timeoutMs = 4000L,
                                            endpointsOverride = endpoints,
                                            sourceLabel = ext.displayName,
                                        )
                                    }
                                }.getOrNull()
                            }
                            hDef.await() to lDef.await()
                        }

                        if (hiRes != null && hiRes.streamUrl.isNotBlank()) {
                            val khz = String.format(Locale.US, "%.1f", hiRes.sampleRate / 1000.0)
                            val is24 = hiRes.bitDepth > 16 || hiRes.sampleRate > 48000
                            val estKbps = if (is24) 2304 else 1411
                            drafts.add(
                                OptionDraft(
                                    id = "${pSlug}_flac_${hiRes.bitDepth}",
                                    label = "FLAC • ${hiRes.bitDepth}-bit • $khz kHz (${ext.displayName})",
                                    codec = "flac",
                                    bitrateKbps = estKbps,
                                    sampleRateHz = hiRes.sampleRate,
                                    bitDepth = hiRes.bitDepth,
                                    streamUrl = LosslessAudioProvider.forceHttps(hiRes.streamUrl),
                                    fileExtension = "flac",
                                    isLossless = true,
                                    headers = mapOf("User-Agent" to DEFAULT_UA),
                                    sourceName = ext.displayName,
                                )
                            )
                        }
                        if (loRes != null && loRes.streamUrl.isNotBlank() && loRes.streamUrl != hiRes?.streamUrl) {
                            val khz = String.format(Locale.US, "%.1f", loRes.sampleRate / 1000.0)
                            drafts.add(
                                OptionDraft(
                                    id = "${pSlug}_flac_${loRes.bitDepth}_16",
                                    label = "FLAC • ${loRes.bitDepth}-bit • $khz kHz (${ext.displayName})",
                                    codec = "flac",
                                    bitrateKbps = 1411,
                                    sampleRateHz = loRes.sampleRate,
                                    bitDepth = loRes.bitDepth,
                                    streamUrl = LosslessAudioProvider.forceHttps(loRes.streamUrl),
                                    fileExtension = "flac",
                                    isLossless = true,
                                    headers = mapOf("User-Agent" to DEFAULT_UA),
                                    sourceName = ext.displayName,
                                )
                            )
                        }
                    }

                    if (drafts.isNotEmpty()) {
                        SourceHealthTracker.recordReady(ext.configId, System.currentTimeMillis() - startMs)
                    }
                    finalizeDraftsAndEmit(ext.displayName, drafts)
                }
            }

            // 4. JioSaavn 320 kbps AAC
            if (jioSaavnEnabled) {
                launch(Dispatchers.IO) {
                    val stream = runCatching {
                        withTimeoutOrNull(3500L) {
                            val jioSaavnConfig = SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.JIOSAAVN }
                                ?: SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true)
                            val source = JioSaavnSource(jioSaavnConfig)
                            val queries = TrackMatcher.queries(target)
                            var bestMatch: Song? = null
                            for (q in queries) {
                                val candidates = source.search(q, limit = 10)
                                if (candidates.isEmpty()) continue
                                val ranked = TrackMatcher.ranked(candidates, target)
                                val close = ranked.firstOrNull { TrackMatcher.withinSeconds(it, target, 3) }
                                    ?: ranked.firstOrNull()
                                if (close != null) {
                                    bestMatch = close
                                    break
                                }
                            }
                            if (bestMatch != null) {
                                val trackId = SourceRegistry.parseTrackKey(bestMatch.videoId)?.second ?: bestMatch.videoId
                                val st = source.stream(trackId, StreamRequest.Best)
                                if (st != null && st.url.isNotBlank() && (st.format.kbps ?: 0) >= 160) {
                                    st.copy(sourceName = "JioSaavn")
                                } else null
                            } else null
                        }
                    }.getOrNull()

                    val drafts = if (stream != null) {
                        val kbps = stream.format.kbps ?: 320
                        listOf(
                            OptionDraft(
                                id = "jiosaavn_m4a_$kbps",
                                label = "M4A / AAC • $kbps kbps (JioSaavn)",
                                codec = "aac",
                                bitrateKbps = kbps,
                                sampleRateHz = 44100,
                                bitDepth = 16,
                                streamUrl = LosslessAudioProvider.forceHttps(stream.url),
                                fileExtension = "m4a",
                                isLossless = false,
                                headers = stream.headers,
                                sourceName = "JioSaavn",
                            )
                        )
                    } else emptyList()
                    finalizeDraftsAndEmit("JioSaavn", drafts)
                }
            }

            // 5. YouTube Music (Opus High, M4A / AAC, Opus Medium)
            if (youtubeEnabled) {
                launch(Dispatchers.IO) {
                    val (opusHigh, m4a, opusMed) = coroutineScope {
                        val oH = async {
                            runCatching { withTimeoutOrNull(3500L) { StreamResolver.resolveForDownload(song.videoId, maxKbps = 256, requireM4a = false) } }.getOrNull()
                        }
                        val m = async {
                            runCatching { withTimeoutOrNull(3500L) { StreamResolver.resolveForDownload(song.videoId, maxKbps = 256, requireM4a = true) } }.getOrNull()
                        }
                        val oM = async {
                            runCatching { withTimeoutOrNull(3000L) { StreamResolver.resolveForDownload(song.videoId, maxKbps = 96, requireM4a = false) } }.getOrNull()
                        }
                        Triple(oH.await(), m.await(), oM.await())
                    }
                    val drafts = mutableListOf<OptionDraft>()
                    if (opusHigh != null && opusHigh.url.isNotBlank()) {
                        drafts.add(
                            OptionDraft(
                                id = "youtube_music_opus_high",
                                label = "Opus • High (${opusHigh.kbps} kbps)",
                                codec = "opus",
                                bitrateKbps = opusHigh.kbps,
                                sampleRateHz = 48000,
                                bitDepth = 16,
                                streamUrl = LosslessAudioProvider.forceHttps(opusHigh.url),
                                fileExtension = "opus",
                                isLossless = false,
                                sourceName = "YouTube Music",
                            )
                        )
                    }
                    if (m4a != null && m4a.url.isNotBlank()) {
                        drafts.add(
                            OptionDraft(
                                id = "youtube_music_m4a_high",
                                label = "M4A / AAC • Standard (${m4a.kbps} kbps)",
                                codec = "aac",
                                bitrateKbps = m4a.kbps,
                                sampleRateHz = 44100,
                                bitDepth = 16,
                                streamUrl = LosslessAudioProvider.forceHttps(m4a.url),
                                fileExtension = "m4a",
                                isLossless = false,
                                sourceName = "YouTube Music",
                            )
                        )
                    }
                    if (opusMed != null && opusMed.url.isNotBlank() && (opusHigh == null || opusMed.kbps < opusHigh.kbps)) {
                        drafts.add(
                            OptionDraft(
                                id = "youtube_music_opus_med",
                                label = "Opus • Medium (${opusMed.kbps} kbps)",
                                codec = "opus",
                                bitrateKbps = opusMed.kbps,
                                sampleRateHz = 48000,
                                bitDepth = 16,
                                streamUrl = LosslessAudioProvider.forceHttps(opusMed.url),
                                fileExtension = "opus",
                                isLossless = false,
                                sourceName = "YouTube Music",
                            )
                        )
                    }
                    finalizeDraftsAndEmit("YouTube Music", drafts)
                }
            }

            // If zero providers were enabled at all, emit empty completed state immediately
            if (pendingNames.isEmpty() && activeStream == null) {
                send(DownloadFormatProgress(emptyList(), emptyList(), isComplete = true))
            }
        }

        // Ensure final completed state is emitted when coroutineScope completes
        val finalState = stateMutex.withLock {
            pendingNames.clear()
            DownloadFormatProgress(
                formats = sortOptions(collected),
                pendingProviders = emptyList(),
                isComplete = true,
            )
        }
        send(finalState)
    }

    private fun draftFromStream(
        idSuffix: String,
        stream: SourceStream,
        fallbackSourceName: String,
    ): OptionDraft? {
        val safeUrl = LosslessAudioProvider.forceHttps(stream.url)
        if (!safeUrl.startsWith("https://")) return null
        val srcName = stream.sourceName?.takeIf { it.isNotBlank() }
            ?: stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
            ?: fallbackSourceName
        val pSlug = SflxRuntimeBridge.providerSlug(srcName)
        val headers = if (stream.headers.any { it.key.equals("User-Agent", ignoreCase = true) }) {
            stream.headers
        } else {
            stream.headers + ("User-Agent" to DEFAULT_UA)
        }

        return when {
            stream.format.isDolbyAtmos -> {
                val kbps = stream.format.kbps ?: 768
                OptionDraft(
                    id = "${pSlug}_atmos_$idSuffix",
                    label = "Dolby Atmos • E-AC-3 ($srcName)",
                    codec = stream.format.codec ?: "eac3-joc",
                    bitrateKbps = kbps,
                    sampleRateHz = stream.format.sampleRateHz ?: 48000,
                    bitDepth = 16,
                    streamUrl = safeUrl,
                    fileExtension = "m4a",
                    isLossless = false,
                    isDolbyAtmos = true,
                    headers = headers,
                    sourceName = srcName,
                )
            }
            stream.format.isLossless == true || safeUrl.substringBefore('?').endsWith(".flac", ignoreCase = true) -> {
                val bd = stream.format.bitDepth ?: 16
                val sr = stream.format.sampleRateHz ?: if (bd > 16) 96000 else 44100
                val khz = String.format(Locale.US, "%.1f", sr / 1000.0)
                val isHiRes = bd > 16 || sr > 48000
                val estKbps = stream.format.kbps ?: if (isHiRes) 2304 else 1411
                OptionDraft(
                    id = "${pSlug}_flac_${bd}_$idSuffix",
                    label = "FLAC • $bd-bit • $khz kHz • $estKbps kbps ($srcName)",
                    codec = "flac",
                    bitrateKbps = estKbps,
                    sampleRateHz = sr,
                    bitDepth = bd,
                    streamUrl = safeUrl,
                    fileExtension = "flac",
                    isLossless = true,
                    isDolbyAtmos = false,
                    headers = headers,
                    sourceName = srcName,
                )
            }
            (stream.format.kbps ?: 0) >= 256 -> {
                val kbps = stream.format.kbps ?: 320
                OptionDraft(
                    id = "${pSlug}_aac_${kbps}_$idSuffix",
                    label = "M4A / AAC • $kbps kbps ($srcName)",
                    codec = stream.format.codec ?: "aac",
                    bitrateKbps = kbps,
                    sampleRateHz = stream.format.sampleRateHz ?: 44100,
                    bitDepth = 16,
                    streamUrl = safeUrl,
                    fileExtension = "m4a",
                    isLossless = false,
                    isDolbyAtmos = false,
                    headers = headers,
                    sourceName = srcName,
                )
            }
            else -> null
        }
    }

    private fun resolveActivePlayingStream(song: Song): SourceStream? {
        val id = song.videoId
        val rawTrackId = SourceRegistry.parseTrackKey(id)?.second
        val candidate = StreamChoice.downloadCandidateFor(id)
            ?: QualityUpgrade.forcedForMediaId(id)
            ?: StreamChoice.of(id)
            ?: rawTrackId?.let {
                StreamChoice.downloadCandidateFor(it) ?: StreamChoice.of(it)
            }
            ?: return null
        if (candidate.url.isBlank()) return null

        val declared = NerdStats.declaredFormat(id)
        val recordedSource = NerdStats.sourceFor(id)
        val urlLower = candidate.url.substringBefore('?').lowercase(Locale.ROOT)
        val isManifest = com.music.bitchord.playback.StreamContainer.isManifest(candidate.url)
        val isLosslessOrPremium = candidate.format.isLossless == true ||
            candidate.format.isDolbyAtmos ||
            declared?.isLossless == true ||
            declared?.isDolbyAtmos == true ||
            urlLower.endsWith(".flac") ||
            isManifest ||
            (candidate.format.kbps ?: 0) >= 256
        if (!isLosslessOrPremium) return null

        val resolvedName = candidate.sourceName?.takeIf { it.isNotBlank() }
            ?: candidate.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
            ?: recordedSource
            ?: if (candidate.format.isDolbyAtmos || declared?.isDolbyAtmos == true) "Dolby Atmos" else "UMtify"

        val inferredCodec = candidate.format.codec
            ?: declared?.codec
            ?: when {
                urlLower.endsWith(".flac") -> "flac"
                declared?.isLossless == true -> "flac"
                else -> null
            }
        val mergedFormat = candidate.format.copy(
            codec = inferredCodec,
            bitDepth = candidate.format.bitDepth ?: declared?.bitDepth,
            sampleRateHz = candidate.format.sampleRateHz ?: declared?.sampleRateHz,
            kbps = candidate.format.kbps ?: declared?.kbps,
        )
        return candidate.copy(
            format = mergedFormat,
            sourceName = resolvedName,
        )
    }

    private data class OptionDraft(
        val id: String,
        val label: String,
        val codec: String,
        val bitrateKbps: Int,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val streamUrl: String,
        val fileExtension: String,
        val isLossless: Boolean,
        val isDolbyAtmos: Boolean = false,
        val headers: Map<String, String> = emptyMap(),
        val sourceName: String? = null,
    )

    private fun probeContentLength(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Long? {
        val safeUrl = LosslessAudioProvider.forceHttps(url)
        if (!safeUrl.startsWith("https://")) return null
        return runCatching {
            val probeClient = Http.client.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()

            fun Request.Builder.applyHeaders(): Request.Builder {
                if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", DEFAULT_UA)
                }
                headers.forEach { (k, v) -> header(k, v) }
                return this
            }

            val headRequest = Request.Builder()
                .url(safeUrl)
                .head()
                .applyHeaders()
                .build()

            val headLen = probeClient.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                } else null
            }
            if (headLen != null) return@runCatching headLen

            val rangeRequest = Request.Builder()
                .url(safeUrl)
                .applyHeaders()
                .header("Range", "bytes=0-0")
                .build()
            probeClient.newCall(rangeRequest).execute().use { response ->
                val contentRange = response.header("Content-Range")
                if (contentRange != null && contentRange.contains("/")) {
                    contentRange.substringAfterLast("/").trim().toLongOrNull()?.takeIf { it > 0L }
                } else null
            }
        }.getOrNull()
    }

    private fun calculateEstimatedBytes(bitrateKbps: Int, durationSec: Int): Long {
        val kbps = bitrateKbps.coerceAtLeast(64).toLong()
        return (kbps * 1000L / 8L) * durationSec.coerceAtLeast(30)
    }

    private fun formatSize(bytes: Long, isEstimated: Boolean): String {
        val prefix = if (isEstimated) "~" else ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%s%.1f MB", prefix, mb)
        } else {
            val kb = bytes / 1024.0
            String.format(Locale.US, "%s%.0f KB", prefix, kb)
        }
    }
}
