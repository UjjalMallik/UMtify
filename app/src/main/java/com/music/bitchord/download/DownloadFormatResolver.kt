package com.music.bitchord.download

import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.NerdStats
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

    private fun providerSlug(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "_").trim('_').ifBlank { "src" }

    suspend fun resolveFormats(song: Song): List<DownloadFormatOption> = withContext(Dispatchers.IO) {
        resolveFormatsFlow(song).last().formats
    }

    /**
     * Progressively resolves downloadable formats across ALL active Group 2 Download Providers
     * (active playing stream, UMtify / custom addons, JioSaavn, YouTube Music).
     */
    fun resolveFormatsFlow(song: Song): Flow<DownloadFormatProgress> = channelFlow {
        val durationSec = (song.durationMillis() / 1000).toInt().takeIf { it > 0 } ?: 210
        val cleanTitle = LosslessAudioProvider.cleanTrackTitle(song.title)
        val primaryArtist = LosslessAudioProvider.primaryArtistName(LosslessAudioProvider.cleanTrackTitle(song.artist))
        val target = TrackMatcher.targetOf(song)
        val allowAtmos = AppSettings.dolbyAtmos.value

        val addonSourcesEnabled = SourceRegistry.downloadableAddonSources().isNotEmpty()
        val jioSaavnEnabled = SourceRegistry.isDownloadProviderEnabled { it.kind == SourceKind.JIOSAAVN }
        val youtubeEnabled = SourceRegistry.isDownloadProviderEnabled { it.kind == SourceKind.YOUTUBE }
        val losslessFallbackEnabled = addonSourcesEnabled

        val pendingNames = linkedSetOf<String>()
        if (addonSourcesEnabled) pendingNames.add("UMtify")
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
                        val isActiveOption = draft.id.contains("_active")
                        val resolvedKbps = if (isActiveOption && draft.bitrateKbps > 0) {
                            draft.bitrateKbps
                        } else if (probedBytes != null && durationSec > 0 && (draft.isLossless || draft.bitrateKbps <= 0)) {
                            ((probedBytes * 8L) / (durationSec * 1000L)).toInt().coerceAtLeast(64)
                        } else {
                            draft.bitrateKbps.takeIf { it > 0 } ?: estKbps
                        }
                        // Guard against mislabeling lossy YouTube Opus/AAC (~146-164 kbps / ~3.2 MB)
                        // fallback streams as FLAC Lossless.
                        if (draft.isLossless && probedBytes != null && resolvedKbps in 1..449) {
                            return@async null
                        }
                        DownloadFormatOption(
                            id = draft.id,
                            label = draft.label,
                            codec = draft.codec,
                            bitrateKbps = resolvedKbps,
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
                }.awaitAll().filterNotNull()
            }

            val snapshot = stateMutex.withLock {
                if (markDone && providerName != null) {
                    pendingNames.remove(providerName)
                }
                finalized.forEach { opt ->
                    val slug = providerSlug(opt.sourceName ?: providerName ?: "source")
                    // Dedup by (provider, codec, bitDepth, kbps, url) AND (provider, url)
                    // so active 24-bit UMtify stream emitted in step 1 prevents step 2 from
                    // adding a duplicate 16-bit copy of the exact same URL.
                    val tierKey = "${slug}_${opt.codec}_${opt.bitDepth ?: 0}_${opt.bitrateKbps ?: 0}_${opt.streamUrl}"
                    val urlKey = "url:${slug}:${opt.streamUrl}"
                    val isNewTier = seenProviderFormatKeys.add(tierKey)
                    val isNewUrl = seenProviderFormatKeys.add(urlKey)
                    if (isNewTier && isNewUrl) {
                        // Ensure globally unique option ID across all providers & LazyColumn items
                        val baseId = if (opt.id.startsWith(slug)) opt.id else "${slug}_${opt.id}"
                        val uniqueId = if (collected.any { it.id == baseId }) "${baseId}_${collected.size}" else baseId
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
                        withTimeoutOrNull(5000L) {
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

            // 3. Built-in JioSaavn 320 kbps AAC (runs concurrently in step 4)

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
        val pSlug = providerSlug(srcName)
        val headers = if (stream.headers.any { it.key.equals("User-Agent", ignoreCase = true) }) {
            stream.headers
        } else {
            stream.headers + ("User-Agent" to DEFAULT_UA)
        }

        val codecLower = stream.format.codec?.lowercase(Locale.ROOT).orEmpty()
        val isKnownLossyCodec = codecLower.contains("opus") ||
            codecLower.contains("vorbis") ||
            codecLower.contains("aac") ||
            codecLower.contains("mp4a") ||
            codecLower.contains("mp3")
        val looksLikeFlacUrl = safeUrl.substringBefore('?').endsWith(".flac", ignoreCase = true) ||
            codecLower.contains("flac") ||
            codecLower.contains("alac") ||
            codecLower.contains("wav")

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
            !isKnownLossyCodec &&
                (stream.format.isLossless == true || looksLikeFlacUrl) &&
                (stream.format.kbps == null || stream.format.kbps!! >= 450) -> {
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
        val measured = NerdStats.measuredFormat(id)
        val recordedSource = NerdStats.sourceFor(id)
        val urlLower = candidate.url.substringBefore('?').lowercase(Locale.ROOT)
        // Never misclassify YouTube / JioSaavn CDNs as UMtify lossless streams
        if (urlLower.contains("googlevideo.com") ||
            urlLower.contains("youtube.com") ||
            urlLower.contains("saavncdn") ||
            urlLower.contains("jiosaavn")
        ) {
            return null
        }

        val resolvedName = candidate.sourceName?.takeIf { it.isNotBlank() }
            ?: candidate.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
            ?: recordedSource
            ?: return null
        if (resolvedName.equals("YouTube", ignoreCase = true) ||
            resolvedName.equals("YouTube Music", ignoreCase = true) ||
            resolvedName.equals("JioSaavn", ignoreCase = true)
        ) {
            return null
        }

        val effectiveCodec = (candidate.format.codec ?: measured?.codec ?: declared?.codec)?.lowercase(Locale.ROOT).orEmpty()
        if (effectiveCodec.contains("opus") ||
            effectiveCodec.contains("vorbis") ||
            effectiveCodec.contains("aac") ||
            effectiveCodec.contains("mp4a") ||
            effectiveCodec.contains("mp3")
        ) {
            return null
        }

        val knownKbps = candidate.format.kbps ?: measured?.kbps ?: declared?.kbps
        val isAtmos = candidate.format.isDolbyAtmos || declared?.isDolbyAtmos == true
        val isGenuinelyLossless = candidate.format.isLossless == true ||
            declared?.isLossless == true ||
            measured?.isLossless == true ||
            urlLower.endsWith(".flac") ||
            effectiveCodec.contains("flac") ||
            effectiveCodec.contains("alac") ||
            effectiveCodec.contains("wav")
        if (!isAtmos && !isGenuinelyLossless) return null
        if (!isAtmos && knownKbps != null && knownKbps < 450) return null

        val inferredCodec = candidate.format.codec
            ?: measured?.codec
            ?: declared?.codec
            ?: when {
                isAtmos -> "eac3-joc"
                urlLower.endsWith(".flac") || isGenuinelyLossless -> "flac"
                else -> null
            }
        val bestBitDepth = maxOf(
            candidate.format.bitDepth ?: 0,
            declared?.bitDepth ?: 0,
            measured?.bitDepth ?: 0,
        ).takeIf { it > 0 }
        val bestSampleRate = maxOf(
            candidate.format.sampleRateHz ?: 0,
            declared?.sampleRateHz ?: 0,
            measured?.sampleRateHz ?: 0,
        ).takeIf { it > 0 }
        val bestKbps = when {
            (measured?.kbps ?: 0) > 0 && (measured?.isLossless == true || declared?.isLossless == true || candidate.format.isLossless == true) ->
                maxOf(measured?.kbps ?: 0, declared?.kbps ?: 0, candidate.format.kbps ?: 0).takeIf { it > 0 }
            declared?.kbps != null && (declared.isLossless == true || candidate.format.isLossless == true) ->
                maxOf(declared.kbps ?: 0, candidate.format.kbps ?: 0).takeIf { it > 0 }
            else -> candidate.format.kbps ?: measured?.kbps ?: declared?.kbps
        }
        val mergedFormat = candidate.format.copy(
            codec = inferredCodec,
            bitDepth = bestBitDepth,
            sampleRateHz = bestSampleRate,
            kbps = bestKbps,
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
