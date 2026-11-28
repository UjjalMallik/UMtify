package com.music.bitchord.download

import com.music.bitchord.data.ExtensionRepoManager
import com.music.bitchord.data.Http
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.JioSaavnSource
import com.music.bitchord.data.sources.SourceConfig
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

object DownloadFormatResolver {

    private const val DEFAULT_UA = LosslessAudioProvider.BROWSER_USER_AGENT

    suspend fun resolveFormats(song: Song): List<DownloadFormatOption> = withContext(Dispatchers.IO) {
        val durationSec = (song.durationMillis() / 1000).toInt().takeIf { it > 0 } ?: 210
        val target = TrackMatcher.targetOf(song)
        val allowAtmos = AppSettings.dolbyAtmos.value

        // 1. Capture active / recently resolved playing stream immediately (zero wait)
        val activeStream = resolveActivePlayingStream(song)

        coroutineScope {
            // 2. Query ALL configured custom addons (UMtify, custom servers) in parallel
            val addonsDeferred = async {
                runCatching {
                    withTimeoutOrNull(6000L) {
                        SourceResolver.resolveAllAddonDownloadStreams(
                            target = target,
                            wantsDolbyAtmos = allowAtmos,
                            wantsLossless = true,
                        )
                    }
                }.getOrNull().orEmpty()
            }

            // 3. Query each enabled Extension Repo lossless provider (Tidal, Qobuz, Deezer, etc.) in parallel
            val extSources = SourceRegistry.downloadableExtensionSources()
            val extDeferreds = extSources.map { ext ->
                async {
                    runCatching {
                        withTimeoutOrNull(5500L) {
                            val extId = SourceRegistry.extensionIdFor(ext.configId) ?: ext.configId
                            val endpoints = (
                                ExtensionRepoManager.getExtractedEndpointsFor(extId) +
                                    LosslessAudioProvider.endpointsForExtension(extId)
                                ).distinct()
                            LosslessAudioProvider.resolveLosslessTrack(
                                title = song.title,
                                artist = song.artist,
                                durationSec = durationSec,
                                preferredQuality = "HI_RES_LOSSLESS",
                                timeoutMs = 5000L,
                                endpointsOverride = endpoints,
                                sourceLabel = ext.displayName,
                            )?.let { ext.displayName to it }
                        }
                    }.getOrNull()
                }
            }

            // 4. Fallback benchmarked LosslessAudioProvider mirrors (Hi-Res & 16-bit)
            val flacHiResDeferred = async {
                runCatching {
                    withTimeoutOrNull(5500L) {
                        LosslessAudioProvider.resolveLosslessTrack(
                            title = song.title,
                            artist = song.artist,
                            durationSec = durationSec,
                            preferredQuality = "HI_RES_LOSSLESS",
                            timeoutMs = 5000L,
                        )
                    }
                }.getOrNull()
            }

            val flacLosslessDeferred = async {
                runCatching {
                    withTimeoutOrNull(5500L) {
                        LosslessAudioProvider.resolveLosslessTrack(
                            title = song.title,
                            artist = song.artist,
                            durationSec = durationSec,
                            preferredQuality = "LOSSLESS",
                            timeoutMs = 5000L,
                        )
                    }
                }.getOrNull()
            }

            // 5. JioSaavn 320 kbps AAC
            val jioSaavnDeferred = async {
                runCatching {
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
                            val stream = source.stream(trackId, StreamRequest.Best)
                            if (stream != null && stream.url.isNotBlank() && (stream.format.kbps ?: 0) >= 160) {
                                stream.copy(sourceName = "JioSaavn")
                            } else null
                        } else null
                    }
                }.getOrNull()
            }

            // 6. YouTube Music (Opus High, M4A / AAC, Opus Medium)
            val opusHighDeferred = async {
                runCatching {
                    withTimeoutOrNull(3500L) {
                        StreamResolver.resolveForDownload(song.videoId, maxKbps = 256, requireM4a = false)
                    }
                }.getOrNull()
            }

            val m4aDeferred = async {
                runCatching {
                    withTimeoutOrNull(3500L) {
                        StreamResolver.resolveForDownload(song.videoId, maxKbps = 256, requireM4a = true)
                    }
                }.getOrNull()
            }

            val opusMedDeferred = async {
                runCatching {
                    withTimeoutOrNull(3000L) {
                        StreamResolver.resolveForDownload(song.videoId, maxKbps = 96, requireM4a = false)
                    }
                }.getOrNull()
            }

            val addonStreams = addonsDeferred.await()
            val extResults = extDeferreds.awaitAll().filterNotNull()
            val flacHiRes = flacHiResDeferred.await()
            val flacLossless = flacLosslessDeferred.await()
            val jioSaavnStream = jioSaavnDeferred.await()
            val opusHigh = opusHighDeferred.await()
            val m4a = m4aDeferred.await()
            val opusMed = opusMedDeferred.await()

            val rawDrafts = mutableListOf<OptionDraft>()
            val seenUrls = mutableSetOf<String>()

            fun addStreamDraft(
                idPrefix: String,
                stream: SourceStream,
                fallbackSourceName: String,
            ) {
                if (stream.url.isBlank() || !seenUrls.add(stream.url)) return
                val srcName = stream.sourceName?.takeIf { it.isNotBlank() }
                    ?: stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: fallbackSourceName
                val headers = stream.headers.ifEmpty { mapOf("User-Agent" to DEFAULT_UA) }

                when {
                    stream.format.isDolbyAtmos -> {
                        val kbps = stream.format.kbps ?: 768
                        rawDrafts.add(
                            OptionDraft(
                                id = "${idPrefix}_atmos_${rawDrafts.size}",
                                label = "Dolby Atmos • E-AC-3 JOC ($srcName)",
                                codec = stream.format.codec ?: "eac3-joc",
                                bitrateKbps = kbps,
                                sampleRateHz = stream.format.sampleRateHz ?: 48000,
                                bitDepth = 16,
                                streamUrl = stream.url,
                                fileExtension = "m4a",
                                isLossless = false,
                                isDolbyAtmos = true,
                                headers = headers,
                                sourceName = srcName,
                            )
                        )
                    }
                    stream.format.isLossless == true || stream.url.substringBefore('?').endsWith(".flac", ignoreCase = true) -> {
                        val bd = stream.format.bitDepth ?: 16
                        val sr = stream.format.sampleRateHz ?: if (bd > 16) 96000 else 44100
                        val khz = String.format(Locale.US, "%.1f", sr / 1000.0)
                        val isHiRes = bd > 16 || sr > 48000
                        val estKbps = if (isHiRes) 2200 else 1411
                        val id = if (isHiRes) "${idPrefix}_hires_${rawDrafts.size}" else "${idPrefix}_16_${rawDrafts.size}"
                        rawDrafts.add(
                            OptionDraft(
                                id = id,
                                label = "FLAC • $bd-bit / $khz kHz ($srcName)",
                                codec = "flac",
                                bitrateKbps = estKbps,
                                sampleRateHz = sr,
                                bitDepth = bd,
                                streamUrl = stream.url,
                                fileExtension = "flac",
                                isLossless = true,
                                isDolbyAtmos = false,
                                headers = headers,
                                sourceName = srcName,
                            )
                        )
                    }
                    (stream.format.kbps ?: 0) >= 256 -> {
                        val kbps = stream.format.kbps ?: 320
                        rawDrafts.add(
                            OptionDraft(
                                id = "${idPrefix}_hq_${rawDrafts.size}",
                                label = "M4A / AAC • $kbps kbps ($srcName)",
                                codec = stream.format.codec ?: "aac",
                                bitrateKbps = kbps,
                                sampleRateHz = stream.format.sampleRateHz ?: 44100,
                                bitDepth = 16,
                                streamUrl = stream.url,
                                fileExtension = "m4a",
                                isLossless = false,
                                isDolbyAtmos = false,
                                headers = headers,
                                sourceName = srcName,
                            )
                        )
                    }
                }
            }

            // A. Active playing / cached stream first (instant!)
            if (activeStream != null) {
                addStreamDraft("flac_active", activeStream, "UMtify")
            }

            // B. Addon streams (UMtify + custom addons)
            addonStreams.forEach { stream ->
                addStreamDraft("flac_addon", stream, "UMtify")
            }

            // C. Enabled Repo Extensions (Tidal, Qobuz, Deezer, etc.)
            extResults.forEach { (extName, res) ->
                if (res.streamUrl.isNotBlank() && seenUrls.add(res.streamUrl)) {
                    val isHiRes = res.bitDepth > 16 || res.sampleRate > 48000
                    val khz = String.format(Locale.US, "%.1f", res.sampleRate / 1000.0)
                    rawDrafts.add(
                        OptionDraft(
                            id = if (isHiRes) "flac_hires_ext_${rawDrafts.size}" else "flac_16_ext_${rawDrafts.size}",
                            label = "FLAC • ${res.bitDepth}-bit / $khz kHz ($extName)",
                            codec = "flac",
                            bitrateKbps = if (isHiRes) 2200 else 1411,
                            sampleRateHz = res.sampleRate,
                            bitDepth = res.bitDepth,
                            streamUrl = res.streamUrl,
                            fileExtension = "flac",
                            isLossless = true,
                            isDolbyAtmos = false,
                            headers = mapOf("User-Agent" to DEFAULT_UA),
                            sourceName = extName,
                        )
                    )
                }
            }

            // D. LosslessAudioProvider Hi-Res & 16-bit mirrors
            if (flacHiRes != null && flacHiRes.streamUrl.isNotBlank() && flacHiRes.bitDepth > 16 && seenUrls.add(flacHiRes.streamUrl)) {
                val khz = String.format(Locale.US, "%.1f", flacHiRes.sampleRate / 1000.0)
                val src = flacHiRes.sourceName.takeIf { it.isNotBlank() } ?: "Lossless"
                rawDrafts.add(
                    OptionDraft(
                        id = "flac_hires",
                        label = "FLAC • ${flacHiRes.bitDepth}-bit / $khz kHz",
                        codec = "flac",
                        bitrateKbps = 2200,
                        sampleRateHz = flacHiRes.sampleRate,
                        bitDepth = flacHiRes.bitDepth,
                        streamUrl = flacHiRes.streamUrl,
                        fileExtension = "flac",
                        isLossless = true,
                        headers = mapOf("User-Agent" to DEFAULT_UA),
                        sourceName = src,
                    )
                )
            }

            val losslessCandidate = when {
                flacLossless != null && flacLossless.streamUrl.isNotBlank() && flacLossless.streamUrl !in seenUrls -> flacLossless
                flacHiRes != null && flacHiRes.streamUrl.isNotBlank() && flacHiRes.bitDepth <= 16 && flacHiRes.streamUrl !in seenUrls -> flacHiRes
                else -> null
            }
            if (losslessCandidate != null && seenUrls.add(losslessCandidate.streamUrl)) {
                val khz = String.format(Locale.US, "%.1f", losslessCandidate.sampleRate / 1000.0)
                val src = losslessCandidate.sourceName.takeIf { it.isNotBlank() } ?: "Lossless"
                rawDrafts.add(
                    OptionDraft(
                        id = "flac_16",
                        label = "FLAC • ${losslessCandidate.bitDepth}-bit Lossless ($khz kHz)",
                        codec = "flac",
                        bitrateKbps = 1411,
                        sampleRateHz = losslessCandidate.sampleRate,
                        bitDepth = losslessCandidate.bitDepth,
                        streamUrl = losslessCandidate.streamUrl,
                        fileExtension = "flac",
                        isLossless = true,
                        headers = mapOf("User-Agent" to DEFAULT_UA),
                        sourceName = src,
                    )
                )
            }

            // E. JioSaavn 320 kbps
            if (jioSaavnStream != null && jioSaavnStream.url.isNotBlank() && seenUrls.add(jioSaavnStream.url)) {
                val kbps = jioSaavnStream.format.kbps ?: 320
                rawDrafts.add(
                    OptionDraft(
                        id = "jiosaavn_320",
                        label = "M4A / AAC • $kbps kbps (JioSaavn)",
                        codec = "aac",
                        bitrateKbps = kbps,
                        sampleRateHz = 44100,
                        bitDepth = 16,
                        streamUrl = jioSaavnStream.url,
                        fileExtension = "m4a",
                        isLossless = false,
                        headers = jioSaavnStream.headers,
                        sourceName = "JioSaavn",
                    )
                )
            }

            // F. YouTube Music options
            if (opusHigh != null && opusHigh.url.isNotBlank() && seenUrls.add(opusHigh.url)) {
                rawDrafts.add(
                    OptionDraft(
                        id = "opus_high",
                        label = "Opus • High (${opusHigh.kbps} kbps)",
                        codec = "opus",
                        bitrateKbps = opusHigh.kbps,
                        sampleRateHz = 48000,
                        bitDepth = 16,
                        streamUrl = opusHigh.url,
                        fileExtension = "opus",
                        isLossless = false,
                        sourceName = "YouTube Music",
                    )
                )
            }

            if (m4a != null && m4a.url.isNotBlank() && seenUrls.add(m4a.url)) {
                rawDrafts.add(
                    OptionDraft(
                        id = "m4a_high",
                        label = "M4A / AAC • Standard (${m4a.kbps} kbps)",
                        codec = "aac",
                        bitrateKbps = m4a.kbps,
                        sampleRateHz = 44100,
                        bitDepth = 16,
                        streamUrl = m4a.url,
                        fileExtension = "m4a",
                        isLossless = false,
                        sourceName = "YouTube Music",
                    )
                )
            }

            if (opusMed != null && opusMed.url.isNotBlank() && seenUrls.add(opusMed.url) && (opusHigh == null || opusMed.kbps < opusHigh.kbps)) {
                rawDrafts.add(
                    OptionDraft(
                        id = "opus_med",
                        label = "Opus • Medium (${opusMed.kbps} kbps)",
                        codec = "opus",
                        bitrateKbps = opusMed.kbps,
                        sampleRateHz = 48000,
                        bitDepth = 16,
                        streamUrl = opusMed.url,
                        fileExtension = "opus",
                        isLossless = false,
                        sourceName = "YouTube Music",
                    )
                )
            }

            // Probe all stream Content-Lengths concurrently so the sheet stays fast
            rawDrafts.map { draft ->
                async {
                    val probedBytes = probeContentLength(draft.streamUrl, draft.headers)
                    val isEst = probedBytes == null
                    val estKbps = when {
                        draft.isLossless -> if ((draft.bitDepth ?: 16) > 16) 2200 else 950
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
    }

    private fun resolveActivePlayingStream(song: Song): SourceStream? {
        val id = song.videoId
        val rawTrackId = SourceRegistry.parseTrackKey(id)?.second
        val candidate = QualityUpgrade.forcedForMediaId(id)
            ?: StreamChoice.of(id)
            ?: rawTrackId?.let { StreamChoice.of(it) }
            ?: return null
        if (candidate.url.isBlank()) return null

        val urlLower = candidate.url.substringBefore('?').lowercase(Locale.ROOT)
        val isLosslessOrPremium = candidate.format.isLossless == true ||
            candidate.format.isDolbyAtmos ||
            urlLower.endsWith(".flac") ||
            (candidate.format.kbps ?: 0) >= 256
        if (!isLosslessOrPremium) return null

        val declared = NerdStats.declaredFormat(id)
        val recordedSource = NerdStats.sourceFor(id)
        val resolvedName = candidate.sourceName?.takeIf { it.isNotBlank() }
            ?: candidate.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
            ?: recordedSource
            ?: if (candidate.format.isDolbyAtmos) "Dolby Atmos" else "UMtify"

        val mergedFormat = candidate.format.copy(
            codec = candidate.format.codec ?: declared?.codec ?: if (urlLower.endsWith(".flac")) "flac" else null,
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
        if (!url.startsWith("http")) return null
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
                .url(url)
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
                .url(url)
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
        return (bitrateKbps.toLong() * 1000L / 8L) * durationSec.coerceAtLeast(1)
    }

    private fun formatSize(bytes: Long, isEstimate: Boolean): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        val prefix = if (isEstimate) "~" else ""
        return if (mb >= 1.0) {
            String.format(Locale.US, "%s%.1f MB", prefix, mb)
        } else {
            val kb = bytes.toDouble() / 1024.0
            String.format(Locale.US, "%s%.0f KB", prefix, kb)
        }
    }
}
