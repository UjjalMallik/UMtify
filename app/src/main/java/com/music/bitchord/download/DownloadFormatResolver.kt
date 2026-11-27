package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.JioSaavnSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

object DownloadFormatResolver {

    suspend fun resolveFormats(song: Song): List<DownloadFormatOption> = withContext(Dispatchers.IO) {
        val durationSec = (song.durationMillis() / 1000).toInt().takeIf { it > 0 } ?: 210

        coroutineScope {
            val flacHiResDeferred = async {
                runCatching {
                    withTimeoutOrNull(4000L) {
                        LosslessAudioProvider.resolveLosslessTrack(
                            title = song.title,
                            artist = song.artist,
                            durationSec = durationSec,
                            preferredQuality = "HI_RES_LOSSLESS",
                        )
                    }
                }.getOrNull()
            }

            val flacLosslessDeferred = async {
                runCatching {
                    withTimeoutOrNull(4000L) {
                        LosslessAudioProvider.resolveLosslessTrack(
                            title = song.title,
                            artist = song.artist,
                            durationSec = durationSec,
                            preferredQuality = "LOSSLESS",
                        )
                    }
                }.getOrNull()
            }

            val jioSaavnDeferred = async {
                runCatching {
                    withTimeoutOrNull(3500L) {
                        val jioSaavnConfig = SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.JIOSAAVN }
                            ?: SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true)
                        val source = JioSaavnSource(jioSaavnConfig)
                        val target = TrackMatcher.targetOf(song)
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
                                val kbps = stream.format.kbps ?: 320
                                val probedBytes = probeContentLength(stream.url)
                                val isEst = probedBytes == null
                                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = kbps, durationSec = durationSec)
                                DownloadFormatOption(
                                    id = "jiosaavn_320",
                                    label = "M4A / AAC • ${kbps} kbps (JioSaavn)",
                                    codec = "aac",
                                    bitrateKbps = kbps,
                                    sampleRateHz = 44100,
                                    bitDepth = 16,
                                    sizeBytes = bytes,
                                    formattedSize = formatSize(bytes, isEst),
                                    streamUrl = stream.url,
                                    fileExtension = "m4a",
                                    isLossless = false,
                                )
                            } else null
                        } else null
                    }
                }.getOrNull()
            }

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

            val flacHiRes = flacHiResDeferred.await()
            val flacLossless = flacLosslessDeferred.await()
            val jioSaavnOption = jioSaavnDeferred.await()
            val opusHigh = opusHighDeferred.await()
            val m4a = m4aDeferred.await()
            val opusMed = opusMedDeferred.await()

            val options = mutableListOf<DownloadFormatOption>()
            val seenUrls = mutableSetOf<String>()

            // 1. Hi-Res FLAC (24-bit / up to 192kHz)
            if (flacHiRes != null && flacHiRes.streamUrl.isNotBlank() && flacHiRes.bitDepth > 16) {
                val probedBytes = probeContentLength(flacHiRes.streamUrl)
                val isEst = probedBytes == null
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = 2200, durationSec = durationSec)
                val sampleRateKhz = flacHiRes.sampleRate / 1000.0
                options.add(
                    DownloadFormatOption(
                        id = "flac_hires",
                        label = "FLAC • ${flacHiRes.bitDepth}-bit / ${String.format(Locale.US, "%.1f", sampleRateKhz)} kHz",
                        codec = "flac",
                        bitrateKbps = 2200,
                        sampleRateHz = flacHiRes.sampleRate,
                        bitDepth = flacHiRes.bitDepth,
                        sizeBytes = bytes,
                        formattedSize = formatSize(bytes, isEst),
                        streamUrl = flacHiRes.streamUrl,
                        fileExtension = "flac",
                        isLossless = true,
                    )
                )
                seenUrls.add(flacHiRes.streamUrl)
            }

            // 2. 16-bit Lossless FLAC (16-bit / 44.1kHz)
            val losslessCandidate = when {
                flacLossless != null && flacLossless.streamUrl !in seenUrls -> flacLossless
                flacHiRes != null && flacHiRes.bitDepth <= 16 && flacHiRes.streamUrl !in seenUrls -> flacHiRes
                else -> null
            }
            if (losslessCandidate != null && losslessCandidate.streamUrl.isNotBlank()) {
                val probedBytes = probeContentLength(losslessCandidate.streamUrl)
                val isEst = probedBytes == null
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = 950, durationSec = durationSec)
                options.add(
                    DownloadFormatOption(
                        id = "flac_16",
                        label = "FLAC • 16-bit Lossless (44.1 kHz)",
                        codec = "flac",
                        bitrateKbps = 950,
                        sampleRateHz = losslessCandidate.sampleRate,
                        bitDepth = 16,
                        sizeBytes = bytes,
                        formattedSize = formatSize(bytes, isEst),
                        streamUrl = losslessCandidate.streamUrl,
                        fileExtension = "flac",
                        isLossless = true,
                    )
                )
                seenUrls.add(losslessCandidate.streamUrl)
            }

            // 3. JioSaavn 320 kbps (High Quality M4A / AAC)
            if (jioSaavnOption != null && jioSaavnOption.streamUrl !in seenUrls) {
                options.add(jioSaavnOption)
                seenUrls.add(jioSaavnOption.streamUrl)
            }

            // 4. Opus High
            if (opusHigh != null && opusHigh.url.isNotBlank() && opusHigh.url !in seenUrls) {
                val probedBytes = probeContentLength(opusHigh.url)
                val isEst = probedBytes == null
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = opusHigh.kbps, durationSec = durationSec)
                options.add(
                    DownloadFormatOption(
                        id = "opus_high",
                        label = "Opus • High (${opusHigh.kbps} kbps)",
                        codec = "opus",
                        bitrateKbps = opusHigh.kbps,
                        sampleRateHz = 48000,
                        bitDepth = 16,
                        sizeBytes = bytes,
                        formattedSize = formatSize(bytes, isEst),
                        streamUrl = opusHigh.url,
                        fileExtension = "opus",
                        isLossless = false,
                    )
                )
                seenUrls.add(opusHigh.url)
            }

            // 5. M4A / AAC Standard
            if (m4a != null && m4a.url.isNotBlank() && m4a.url !in seenUrls) {
                val probedBytes = probeContentLength(m4a.url)
                val isEst = probedBytes == null
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = m4a.kbps, durationSec = durationSec)
                options.add(
                    DownloadFormatOption(
                        id = "m4a_high",
                        label = "M4A / AAC • Standard (${m4a.kbps} kbps)",
                        codec = "aac",
                        bitrateKbps = m4a.kbps,
                        sampleRateHz = 44100,
                        bitDepth = 16,
                        sizeBytes = bytes,
                        formattedSize = formatSize(bytes, isEst),
                        streamUrl = m4a.url,
                        fileExtension = "m4a",
                        isLossless = false,
                    )
                )
                seenUrls.add(m4a.url)
            }

            // 6. Opus Medium (if distinct from Opus High)
            if (opusMed != null && opusMed.url.isNotBlank() && opusMed.url !in seenUrls && (opusHigh == null || opusMed.kbps < opusHigh.kbps)) {
                val probedBytes = probeContentLength(opusMed.url)
                val isEst = probedBytes == null
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = opusMed.kbps, durationSec = durationSec)
                options.add(
                    DownloadFormatOption(
                        id = "opus_med",
                        label = "Opus • Medium (${opusMed.kbps} kbps)",
                        codec = "opus",
                        bitrateKbps = opusMed.kbps,
                        sampleRateHz = 48000,
                        bitDepth = 16,
                        sizeBytes = bytes,
                        formattedSize = formatSize(bytes, isEst),
                        streamUrl = opusMed.url,
                        fileExtension = "opus",
                        isLossless = false,
                    )
                )
                seenUrls.add(opusMed.url)
            }

            options
        }
    }

    private fun probeContentLength(url: String): Long? {
        if (!url.startsWith("http")) return null
        return runCatching {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val probeClient = Http.client.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()

            val headLen = probeClient.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                } else null
            }
            if (headLen != null) return@runCatching headLen

            // Fallback: Range request for Content-Range header
            val rangeRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
