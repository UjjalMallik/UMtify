package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
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
        val durationSec = song.durationSec?.takeIf { it > 0 }
            ?: (song.durationMillis() / 1000).toInt().takeIf { it > 0 }
            ?: 210

        coroutineScope {
            val flacHiResDeferred = async {
                runCatching {
                    withTimeoutOrNull(3500L) {
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
                    withTimeoutOrNull(3500L) {
                        LosslessAudioProvider.resolveLosslessTrack(
                            title = song.title,
                            artist = song.artist,
                            durationSec = durationSec,
                            preferredQuality = "LOSSLESS",
                        )
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
            val opusHigh = opusHighDeferred.await()
            val m4a = m4aDeferred.await()
            val opusMed = opusMedDeferred.await()

            val options = mutableListOf<DownloadFormatOption>()
            val seenUrls = mutableSetOf<String>()

            // 1. Hi-Res FLAC (24-bit / up to 192kHz)
            if (flacHiRes != null && flacHiRes.streamUrl.isNotBlank() && flacHiRes.bitDepth > 16) {
                val probedBytes = probeContentLength(flacHiRes.streamUrl)
                val isEst = probedBytes == null
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = 2400, durationSec = durationSec)
                val sampleRateKhz = flacHiRes.sampleRate / 1000.0
                options.add(
                    DownloadFormatOption(
                        id = "flac_hires",
                        label = "FLAC • ${flacHiRes.bitDepth}-bit / ${String.format(Locale.US, "%.1f", sampleRateKhz)} kHz",
                        codec = "flac",
                        bitrateKbps = 2400,
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
                val bytes = probedBytes ?: calculateEstimatedBytes(bitrateKbps = 800, durationSec = durationSec)
                options.add(
                    DownloadFormatOption(
                        id = "flac_16",
                        label = "FLAC • 16-bit Lossless (44.1 kHz)",
                        codec = "flac",
                        bitrateKbps = 800,
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

            // 3. Opus High
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

            // 4. M4A / AAC Standard
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

            // 5. Opus Medium (if distinct from Opus High)
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
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "UMtify/1.0 (Android; Audio Format Probe)")
                .build()
            Http.client.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (response.isSuccessful) {
                        val len = response.header("Content-Length")?.toLongOrNull()
                        if (len != null && len > 0L) len else null
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
