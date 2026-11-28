package com.music.bitchord.data

import android.util.Log
import com.music.bitchord.data.sources.SourceHealthTracker
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.DownloadFormatOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 100% Native Kotlin (OkHttp + org.json) multi-provider lossless download resolver.
 *
 * Replaces QuickJS execution for Download Providers (`Tidal`, `Qobuz`, `Deezer`,
 * `Amazon Music`, `Beatport`) so release builds with R8/ProGuard never hit
 * `dokar3/quickjs-kt` `No such type converter` crashes.
 */
object NativeDownloadProviders {
    private const val TAG = "NativeDownloadProviders"
    private const val RESOLUTION_TIMEOUT_MS = 4500L

    private val probeClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    data class ResolutionResult(
        val options: List<DownloadFormatOption>,
        val failureReason: String? = null,
    )

    fun cleanTrackTitle(raw: String): String =
        raw.replace(
            Regex(
                """\s*[\(\[](?:[^)\]]*(?:version|ver\.?|official|video|audio|lyric|lyrics|visualizer|remaster|deluxe|explicit|clean|from|edit|mix|ost|soundtrack|live|acoustic|bonus|feat\.?|ft\.?)[^)\]]*)[\)\]]""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
            .replace(Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+[-–—]\s+(?:official|lyric|audio|video|remastered|from).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { raw.trim() }

    fun primaryArtistName(raw: String): String =
        raw.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?\b|\bft\.?\b|\bx\b)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: raw.trim()

    fun providerSlug(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "_").trim('_').ifBlank { "ext" }

    /**
     * Honest native HTTP 200 health check for an enabled Download Provider.
     * Never invokes QuickJS or emits raw QuickJS exceptions.
     */
    suspend fun probeHealth(extensionId: String, configId: String): Result<Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val idLower = extensionId.lowercase(Locale.ROOT)
        val endpoints = LosslessAudioProvider.getPreferredEndpointsForExtension(
            extensionId = extensionId,
            extraEndpoints = ExtensionRepoManager.getExtractedEndpointsFor(extensionId),
        )

        val mirrorResult = LosslessAudioProvider.probeEndpointHealth(endpoints, providerKey = extensionId)
        if (mirrorResult.isSuccess) {
            val latencyMs = mirrorResult.getOrDefault((System.currentTimeMillis() - start).coerceAtLeast(1L))
            SourceHealthTracker.recordReady(configId, latencyMs)
            return@withContext Result.success(latencyMs)
        }

        if ("deezer" in idLower) {
            val deezerOk = probeJson200("https://api.deezer.com/search?q=Blinding+Lights&limit=1")
            if (deezerOk) {
                val latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                SourceHealthTracker.recordReady(configId, latencyMs)
                return@withContext Result.success(latencyMs)
            }
        }

        val reason = mirrorResult.exceptionOrNull()?.message
            ?.takeIf { it.isNotBlank() && !it.contains("quickjs", ignoreCase = true) }
            ?: "Mirrors unavailable"
        SourceHealthTracker.recordError(configId, reason)
        Result.failure(IllegalStateException(reason))
    }

    /**
     * Resolves all available lossless and high-bitrate download tiers for a single
     * enabled Download Provider using pure Kotlin `OkHttpClient` calls.
     */
    suspend fun resolveFormats(
        extensionId: String,
        displayName: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Int = 0,
        isrc: String? = null,
    ): ResolutionResult = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext ResolutionResult(emptyList(), "Empty title")

        val startMs = System.currentTimeMillis()
        val cleanedTitle = cleanTrackTitle(title)
        val cleanArtist = cleanTrackTitle(artist)
        val primaryArtist = primaryArtistName(cleanArtist)
        val pSlug = providerSlug(displayName)
        val configId = SourceRegistry.repoExtensionConfigId(extensionId)
        val endpoints = LosslessAudioProvider.getPreferredEndpointsForExtension(
            extensionId = extensionId,
            extraEndpoints = ExtensionRepoManager.getExtractedEndpointsFor(extensionId),
        )

        val out = mutableListOf<DownloadFormatOption>()
        val seenKeys = HashSet<String>()

        val resolved = withTimeoutOrNull(RESOLUTION_TIMEOUT_MS) {
            coroutineScope {
                val multiTierDeferred = async {
                    runCatching {
                        LosslessAudioProvider.resolveMultiTierForExtension(
                            title = cleanedTitle,
                            artist = primaryArtist,
                            durationSec = durationSec.takeIf { it > 0 },
                            endpointsOverride = endpoints,
                            sourceLabel = displayName,
                            timeoutMs = 4000L,
                        )
                    }.getOrNull().orEmpty()
                }

                val deezerIsrcDeferred = if ("deezer" in extensionId.lowercase(Locale.ROOT) && isrc.isNullOrBlank()) {
                    async { fetchDeezerTrackInfo(cleanedTitle, primaryArtist) }
                } else null

                val tiers = multiTierDeferred.await()
                val deezerInfo = deezerIsrcDeferred?.await()
                val resolvedIsrc = isrc?.takeIf { it.isNotBlank() } ?: deezerInfo?.isrc

                val finalTiers = if (tiers.isEmpty() && !resolvedIsrc.isNullOrBlank()) {
                    runCatching {
                        LosslessAudioProvider.resolveMultiTierForExtension(
                            title = resolvedIsrc,
                            artist = "",
                            durationSec = durationSec.takeIf { it > 0 },
                            endpointsOverride = endpoints,
                            sourceLabel = displayName,
                            timeoutMs = 3000L,
                        )
                    }.getOrNull().orEmpty()
                } else {
                    tiers
                }

                finalTiers.forEachIndexed { idx, res ->
                    val safeUrl = LosslessAudioProvider.forceHttps(res.streamUrl)
                    if (!safeUrl.startsWith("https://")) return@forEachIndexed

                    val extLower = res.fileExtension.lowercase(Locale.ROOT).ifBlank { "flac" }
                    val isLossless = extLower == "flac" || extLower == "alac" || extLower == "wav"
                    val is24 = res.bitDepth > 16 || res.sampleRate > 48000
                    val estKbps = when {
                        isLossless && is24 -> 2304
                        isLossless -> 1411
                        else -> 320
                    }
                    val dedupKey = "${pSlug}_${extLower}_${res.bitDepth}_${estKbps}_$safeUrl"
                    if (!seenKeys.add(dedupKey)) return@forEachIndexed

                    val khz = String.format(Locale.US, "%.1f", res.sampleRate / 1000.0)
                    val label = when {
                        isLossless && is24 -> "FLAC • ${res.bitDepth}-bit Hi-Res • $khz kHz ($displayName)"
                        isLossless -> "FLAC • ${res.bitDepth}-bit Lossless • $khz kHz ($displayName)"
                        else -> "${extLower.uppercase(Locale.ROOT)} • $estKbps kbps ($displayName)"
                    }
                    val estBytes = (estKbps * 1000L / 8L) * durationSec.coerceAtLeast(30)
                    val mb = estBytes / (1024.0 * 1024.0)
                    val formattedSize = String.format(Locale.US, "~%.1f MB", mb)
                    val tierTag = if (isLossless) "flac_${res.bitDepth}" else "${extLower}_$estKbps"

                    out.add(
                        DownloadFormatOption(
                            id = "${pSlug}_${tierTag}_$idx",
                            label = label,
                            codec = if (isLossless) "flac" else extLower,
                            bitrateKbps = estKbps,
                            sampleRateHz = res.sampleRate,
                            bitDepth = res.bitDepth,
                            sizeBytes = estBytes,
                            formattedSize = formattedSize,
                            streamUrl = safeUrl,
                            fileExtension = extLower,
                            isLossless = isLossless,
                            isDolbyAtmos = false,
                            headers = mapOf("User-Agent" to LosslessAudioProvider.BROWSER_USER_AGENT),
                            sourceName = displayName,
                        ),
                    )
                }
                true
            }
        }

        val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        return@withContext if (out.isNotEmpty()) {
            SourceHealthTracker.recordReady(configId, latencyMs)
            ResolutionResult(out, null)
        } else {
            val reason = if (resolved == null) "Mirror timeout" else "No matching streams"
            Log.w(TAG, "$displayName ($extensionId) returned 0 formats: $reason")
            SourceHealthTracker.recordError(configId, reason)
            ResolutionResult(emptyList(), reason)
        }
    }

    private fun probeJson200(url: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(LosslessAudioProvider.forceHttps(url))
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        probeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val body = resp.body?.string()?.trim().orEmpty()
            body.startsWith("{") || body.startsWith("[")
        }
    }.getOrDefault(false)

    private data class DeezerTrackInfo(val id: String, val isrc: String?)

    private fun fetchDeezerTrackInfo(title: String, artist: String): DeezerTrackInfo? = runCatching {
        val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://api.deezer.com/search?q=$q&limit=3")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val firstId = probeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val json = JSONObject(resp.body?.string().orEmpty())
            json.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
        } ?: return@runCatching null

        val trackReq = Request.Builder()
            .url("https://api.deezer.com/track/$firstId")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        probeClient.newCall(trackReq).execute().use { resp ->
            if (!resp.isSuccessful) return@use DeezerTrackInfo(firstId, null)
            val obj = JSONObject(resp.body?.string().orEmpty())
            DeezerTrackInfo(firstId, obj.optString("isrc").takeIf { it.isNotBlank() })
        }
    }.getOrNull()
}
