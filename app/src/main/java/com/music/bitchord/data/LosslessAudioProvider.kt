package com.music.bitchord.data

import android.util.Base64
import android.util.LruCache
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LosslessStreamResult(
    val streamUrl: String,
    val mimeType: String = "audio/flac",
    val bitDepth: Int = 16,
    val sampleRate: Int = 44100,
    val fileExtension: String = "flac",
    val sourceName: String = "Hi-Res FLAC",
)

object LosslessAudioProvider {
    private const val TAG = "LosslessAudioProvider"

    val BUILTIN_ENDPOINTS = listOf(
        "https://api.zarz.moe",
        "https://wolf.qqdl.site",
        "https://maus.qqdl.site",
        "https://katze.qqdl.site",
        "https://hund.qqdl.site",
        "https://vogel.qqdl.site",
        "https://api.monochrome.tf",
        "https://hifi.monochrome.tf",
        "https://trident.monochrome.tf",
        "https://tidal.kinoko.eu.org",
        "https://hifi-api.vercel.app",
    )

    @Volatile
    private var lastHealthyEndpoint: String? = null

    private data class CacheEntry(val result: LosslessStreamResult, val timestamp: Long)
    private val memoryCache = object : LruCache<String, CacheEntry>(150) {}
    private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes

    private val httpClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .callTimeout(5000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    private fun getCandidateEndpoints(): List<String> {
        val custom = AppSettings.customLosslessEndpoint.value.trim().trimEnd('/')
        val endpoints = mutableListOf<String>()
        if (custom.isNotBlank()) {
            endpoints.add(custom)
        }
        val healthy = lastHealthyEndpoint
        if (healthy != null && healthy !in endpoints) {
            endpoints.add(healthy)
        }
        BUILTIN_ENDPOINTS.forEach {
            if (it !in endpoints) endpoints.add(it)
        }
        return endpoints
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\((?:official|music|lyric|audio|video|visualizer|remastered|deluxe|explicit|clean).*?\)|\[.*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[-_]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalize(s: String): String {
        return s.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")
    }

    private data class TrackCandidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationSec: Int,
        val quality: String,
        val score: Int,
    )

    suspend fun resolveLosslessTrack(
        title: String,
        artist: String,
        durationSec: Int? = null,
        preferredQuality: String? = null,
    ): LosslessStreamResult? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val qualityTier = preferredQuality ?: if (AppSettings.losslessQualityTier.value == "LOSSLESS") "LOSSLESS" else "HI_RES_LOSSLESS"
        val cacheKey = "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}|$qualityTier"
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                return@withContext cached.result
            }
        }

        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanTitle(artist)
        val query = "$cleanedTitle $cleanedArtist".trim()
        val encodedQuery = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)

        val normTitle = normalize(cleanedTitle)
        val normArtist = normalize(cleanedArtist)

        val endpoints = getCandidateEndpoints()

        // 1. Fast path: try cached healthy endpoint first
        val healthy = lastHealthyEndpoint
        if (healthy != null && healthy in endpoints) {
            try {
                val candidate = searchCandidates(healthy, encodedQuery, normTitle, normArtist, durationSec)
                if (candidate != null) {
                    val streamResult = fetchStream(healthy, candidate.id, qualityTier)
                    if (streamResult != null) {
                        synchronized(memoryCache) {
                            memoryCache.put(cacheKey, CacheEntry(streamResult, System.currentTimeMillis()))
                        }
                        Log.d(TAG, "Resolved lossless track '$title' from healthy $healthy: ${streamResult.streamUrl}")
                        return@withContext streamResult
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cached healthy endpoint $healthy failed, probing mirrors: ${e.message}")
            }
        }

        // 2. Parallel probe across candidate mirrors with fast failover
        val probeEndpoints = endpoints.filter { it != healthy }.take(5)
        val deferredList = probeEndpoints.map { ep ->
            async {
                runCatching {
                    val candidate = searchCandidates(ep, encodedQuery, normTitle, normArtist, durationSec) ?: return@runCatching null
                    val streamResult = fetchStream(ep, candidate.id, qualityTier) ?: return@runCatching null
                    Pair(ep, streamResult)
                }.getOrNull()
            }
        }

        for (deferred in deferredList) {
            val resolved = deferred.await()
            if (resolved != null) {
                val (ep, streamResult) = resolved
                lastHealthyEndpoint = ep
                synchronized(memoryCache) {
                    memoryCache.put(cacheKey, CacheEntry(streamResult, System.currentTimeMillis()))
                }
                Log.d(TAG, "Resolved lossless track '$title' from $ep: ${streamResult.streamUrl}")
                return@withContext streamResult
            }
        }

        null
    }

    private fun searchCandidates(
        endpoint: String,
        encodedQuery: String,
        normTitle: String,
        normArtist: String,
        targetDurationSec: Int?,
    ): TrackCandidate? {
        val base = endpoint.trimEnd('/')
        val searchUrls = listOf(
            "$base/v1/search?q=$encodedQuery",
            "$base/search/?s=$encodedQuery",
            "$base/search?s=$encodedQuery",
            "$base/search/?q=$encodedQuery",
            "$base/search?q=$encodedQuery",
        )

        for (url in searchUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "UMtify/1.0 (Android; Hi-Res Audio)")
                .header("Accept", "application/json")
                .build()

            val response = runCatching { httpClient.newCall(request).execute() }.getOrNull() ?: continue
            val body = response.use { if (it.isSuccessful) it.body?.string() else null } ?: continue
            val candidates = parseSearchResults(body, normTitle, normArtist, targetDurationSec)
            if (candidates.isNotEmpty()) {
                return candidates.maxByOrNull { it.score }
            }
        }
        return null
    }

    private fun parseSearchResults(
        body: String,
        normTitle: String,
        normArtist: String,
        targetDurationSec: Int?,
    ): List<TrackCandidate> {
        val list = mutableListOf<TrackCandidate>()
        runCatching {
            val trimmed = body.trim()
            val itemsArray: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    when {
                        root.has("results") -> {
                            val results = root.optJSONArray("results")
                            val combined = JSONArray()
                            if (results != null) {
                                for (j in 0 until results.length()) {
                                    val rObj = results.optJSONObject(j) ?: continue
                                    val items = rObj.optJSONArray("items") ?: continue
                                    for (k in 0 until items.length()) {
                                        items.optJSONObject(k)?.let { combined.put(it) }
                                    }
                                }
                            }
                            combined
                        }
                        root.has("data") -> {
                            val data = root.get("data")
                            if (data is JSONArray) data
                            else if (data is JSONObject && data.has("items")) data.getJSONArray("items")
                            else if (data is JSONObject && data.has("tracks")) data.getJSONArray("tracks")
                            else JSONArray()
                        }
                        root.has("items") -> root.getJSONArray("items")
                        root.has("tracks") -> {
                            val tracks = root.get("tracks")
                            if (tracks is JSONArray) tracks
                            else if (tracks is JSONObject && tracks.has("items")) tracks.getJSONArray("items")
                            else JSONArray()
                        }
                        else -> JSONArray()
                    }
                }
                else -> JSONArray()
            }

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("trackId") }.ifBlank { item.optString("track_id") }
                if (id.isBlank()) continue
                val title = item.optString("title").ifBlank { item.optString("name") }
                val artistObj = item.opt("artist")
                val artist = when (artistObj) {
                    is JSONObject -> artistObj.optString("name")
                    is String -> artistObj
                    else -> item.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                }
                val duration = item.optInt("duration", 0)
                val quality = item.optString("audioQuality").ifBlank {
                    item.optString("quality", "LOSSLESS")
                }

                val cNormTitle = normalize(title)
                val cNormArtist = normalize(artist)

                var score = 0
                if (cNormTitle == normTitle) {
                    score += 50
                } else if (cNormTitle.contains(normTitle) || normTitle.contains(cNormTitle)) {
                    score += 30
                }

                if (normArtist.isNotBlank()) {
                    if (cNormArtist == normArtist) {
                        score += 30
                    } else if (cNormArtist.contains(normArtist) || normArtist.contains(cNormArtist)) {
                        score += 20
                    }
                }

                if (targetDurationSec != null && targetDurationSec > 0 && duration > 0) {
                    val diff = abs(duration - targetDurationSec)
                    when {
                        diff <= 3 -> score += 40
                        diff <= 6 -> score += 25
                        diff <= 10 -> score += 10
                        diff > 20 -> score -= 30
                    }
                }

                if (quality.contains("HI_RES", ignoreCase = true)) {
                    score += 15
                } else if (quality.contains("LOSSLESS", ignoreCase = true)) {
                    score += 10
                }

                list.add(TrackCandidate(id, title, artist, duration, quality, score))
            }
        }
        return list
    }

    private fun fetchStream(
        endpoint: String,
        trackId: String,
        qualityTier: String,
    ): LosslessStreamResult? {
        val base = endpoint.trimEnd('/')
        val trackUrls = listOf(
            "$base/track/?id=$trackId&quality=$qualityTier",
            "$base/track?id=$trackId&quality=$qualityTier",
            "$base/v1/track?id=$trackId&quality=$qualityTier",
            "$base/dl/dzr?id=$trackId",
            "$base/dl/tid?id=$trackId",
        )

        for (url in trackUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "UMtify/1.0 (Android; Hi-Res Audio)")
                .header("Accept", "application/json")
                .build()

            val response = runCatching { httpClient.newCall(request).execute() }.getOrNull() ?: continue
            val body = response.use { if (it.isSuccessful) it.body?.string() else null } ?: continue

            val result = parseTrackResponse(body, qualityTier)
            if (result != null && isStreamUrlPlayable(result.streamUrl)) return result
        }
        return null
    }

    private fun parseTrackResponse(body: String, qualityTier: String): LosslessStreamResult? {
        return runCatching {
            val root = JSONObject(body)
            val dataObj = root.optJSONObject("data") ?: root

            var streamUrl = dataObj.optString("url").takeIf { it.isNotBlank() }
                ?: dataObj.optString("OriginalTrackUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("flacUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("streamUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("downloadUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("directURL").takeIf { it.isNotBlank() }
                ?: root.optString("url").takeIf { it.isNotBlank() }
                ?: root.optString("OriginalTrackUrl").takeIf { it.isNotBlank() }
                ?: root.optString("flacUrl").takeIf { it.isNotBlank() }
                ?: root.optString("streamUrl").takeIf { it.isNotBlank() }
                ?: root.optString("downloadUrl").takeIf { it.isNotBlank() }
                ?: root.optString("directURL").takeIf { it.isNotBlank() }

            val manifestStr = dataObj.optString("manifest").takeIf { it.isNotBlank() }
                ?: root.optString("manifest").takeIf { it.isNotBlank() }
                ?: dataObj.optJSONObject("track")?.optString("manifest")?.takeIf { it.isNotBlank() }

            var bitDepth = if (qualityTier == "HI_RES_LOSSLESS") 24 else 16
            var sampleRate = if (qualityTier == "HI_RES_LOSSLESS") 96000 else 44100

            if (dataObj.has("bitDepth")) bitDepth = dataObj.optInt("bitDepth", bitDepth)
            if (dataObj.has("sampleRate")) sampleRate = dataObj.optInt("sampleRate", sampleRate)

            if (streamUrl == null && manifestStr != null) {
                val decoded = runCatching {
                    String(Base64.decode(manifestStr, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()

                if (decoded != null) {
                    if (decoded.startsWith("{")) {
                        val manifestJson = JSONObject(decoded)
                        val urls = manifestJson.optJSONArray("urls")
                        if (urls != null && urls.length() > 0) {
                            streamUrl = urls.optString(0).takeIf { it.isNotBlank() }
                        } else {
                            streamUrl = manifestJson.optString("url").takeIf { it.isNotBlank() }
                                ?: manifestJson.optString("flacUrl").takeIf { it.isNotBlank() }
                                ?: manifestJson.optString("streamUrl").takeIf { it.isNotBlank() }
                        }
                        if (manifestJson.has("bitDepth")) bitDepth = manifestJson.optInt("bitDepth", bitDepth)
                        if (manifestJson.has("sampleRate")) sampleRate = manifestJson.optInt("sampleRate", sampleRate)
                    } else if (decoded.contains("<BaseURL>")) {
                        val base = decoded.substringAfter("<BaseURL>").substringBefore("</BaseURL>").trim()
                        if (base.startsWith("http")) {
                            streamUrl = base
                        }
                    }
                }
            }

            if (streamUrl != null && streamUrl.startsWith("http")) {
                val sourceName = if (bitDepth > 16 || sampleRate > 48000) "Hi-Res FLAC" else "FLAC Lossless"
                LosslessStreamResult(
                    streamUrl = streamUrl,
                    mimeType = "audio/flac",
                    bitDepth = bitDepth,
                    sampleRate = sampleRate,
                    fileExtension = "flac",
                    sourceName = sourceName,
                )
            } else null
        }.getOrNull()
    }

    private fun isStreamUrlPlayable(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "UMtify/1.0 (Android; Hi-Res Audio)")
            .header("Range", "bytes=0-1024")
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..206 || resp.code in 301..308 || resp.code == 416
            }
        }.getOrDefault(false)
    }

    suspend fun testEndpoint(endpointUrl: String? = null): Boolean = withContext(Dispatchers.IO) {
        val target = (endpointUrl?.trim()?.trimEnd('/') ?: AppSettings.customLosslessEndpoint.value.trim().trimEnd('/'))
            .ifBlank { BUILTIN_ENDPOINTS.first() }

        val testUrls = listOf(
            "$target/v1/health",
            "$target/health",
            "$target/search/?s=test",
            "$target/search?s=test",
            "$target/search/?q=test",
            "$target/search?q=test",
            "$target/",
        )
        for (url in testUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "UMtify/1.0 (Android; Hi-Res Audio)")
                .header("Accept", "application/json")
                .build()
            val ok = runCatching {
                httpClient.newCall(request).execute().use { resp ->
                    resp.isSuccessful || resp.code in 200..399
                }
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }
}
