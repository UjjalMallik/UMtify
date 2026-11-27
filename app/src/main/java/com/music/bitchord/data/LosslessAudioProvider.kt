package com.music.bitchord.data

import android.util.Base64
import android.util.LruCache
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        "https://dab.yeet.su",
        "https://squid.wtf",
        "https://wolf.qqdl.site",
        "https://maus.qqdl.site",
        "https://katze.qqdl.site",
        "https://hund.qqdl.site",
        "https://vogel.qqdl.site",
        "https://hifi-api.vercel.app",
        "https://tidal.kinoko.eu.org",
        "https://api.monochrome.tf",
        "https://hifi.monochrome.tf",
        "https://trident.monochrome.tf",
    )

    @Volatile
    private var lastHealthyEndpoint: String? = null

    private val mirrorLatencies = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val mirrorFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile
    private var benchmarkedTopMirrors: List<String> = emptyList()
    @Volatile
    private var lastBenchmarkAtMs: Long = 0L
    private val isBenchmarking = AtomicBoolean(false)

    fun recordMirrorSuccess(endpoint: String, latencyMs: Long) {
        mirrorLatencies[endpoint] = latencyMs
        mirrorFailures[endpoint] = 0
    }

    fun recordMirrorFailure(endpoint: String) {
        val fails = (mirrorFailures[endpoint] ?: 0) + 1
        mirrorFailures[endpoint] = fails
        if (fails >= 2) {
            mirrorLatencies[endpoint] = 99999L
        }
    }

    /** Returns the top [limit] fastest servers chosen by smart connection benchmarking (ignoring list position). */
    fun getTopBenchmarkedServers(limit: Int = 3): List<String> {
        val benchmarked = benchmarkedTopMirrors
        if (benchmarked.isNotEmpty()) return benchmarked.take(limit)
        return getCandidateEndpoints().take(limit)
    }

    /**
     * Benchmarks all enabled lossless mirrors & custom addons in parallel (2s cap) on Dispatchers.IO,
     * ranking the top 2–3 fastest healthy servers for the current Wi-Fi / Mobile Data connection.
     */
    suspend fun benchmarkTopServers(reason: String = "manual"): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (reason != "network-change" && now - lastBenchmarkAtMs < 3_000L && benchmarkedTopMirrors.isNotEmpty()) {
            return@withContext benchmarkedTopMirrors
        }
        if (!isBenchmarking.compareAndSet(false, true)) {
            return@withContext benchmarkedTopMirrors
        }
        try {
            val candidates = getCandidateEndpoints()
            val probeClient = httpClient.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .callTimeout(1800, TimeUnit.MILLISECONDS)
                .build()

            val results = withTimeoutOrNull(2000L) {
                coroutineScope {
                    // Warm custom UMtify & enabled AddonSource health in parallel
                    SourceRegistry.activeAddonSources().forEach { addon ->
                        launch { runCatching { addon.health() } }
                    }

                    candidates.map { endpoint ->
                        async {
                            val base = endpoint.trimEnd('/')
                            val urls = listOf(
                                "$base/v1/health",
                                "$base/health",
                                "$base/v1/search?q=test",
                                "$base/search/?s=test",
                            )
                            val start = System.currentTimeMillis()
                            var latency: Long? = null
                            for (u in urls) {
                                val req = Request.Builder()
                                    .url(u)
                                    .header("User-Agent", "UMtify/1.0 (Android; SmartBenchmark)")
                                    .header("Accept", "application/json")
                                    .build()
                                val ok = runCatching {
                                    probeClient.newCall(req).execute().use { resp ->
                                        if (!resp.isSuccessful) false
                                        else {
                                            val text = resp.body?.string()?.trim().orEmpty()
                                            text.startsWith("{") || text.startsWith("[") || text == "ok"
                                        }
                                    }
                                }.getOrDefault(false)
                                if (ok) {
                                    latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                                    break
                                }
                            }
                            if (latency != null) {
                                recordMirrorSuccess(endpoint, latency)
                                endpoint to latency
                            } else {
                                recordMirrorFailure(endpoint)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }.orEmpty()

            val rankedTop = results.sortedBy { it.second }.take(3).map { it.first }
            if (rankedTop.isNotEmpty()) {
                benchmarkedTopMirrors = rankedTop
                lastHealthyEndpoint = rankedTop.first()
                lastBenchmarkAtMs = System.currentTimeMillis()
                Log.i(TAG, "Smart benchmark ($reason) selected top servers: $rankedTop")
            }
            benchmarkedTopMirrors
        } finally {
            isBenchmarking.set(false)
        }
    }

    private data class CacheEntry(val result: LosslessStreamResult, val timestamp: Long)
    private val memoryCache = object : LruCache<String, CacheEntry>(150) {}
    private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes

    private val httpClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .callTimeout(4500, TimeUnit.MILLISECONDS)
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
        ExtensionRepoManager.getExtractedEndpoints().forEach {
            if (it !in endpoints) endpoints.add(it)
        }
        BUILTIN_ENDPOINTS.forEach {
            if (it !in endpoints) endpoints.add(it)
        }
        return endpoints.sortedBy { mirrorLatencies[it] ?: 400L }
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
        val topBenchmarked = getTopBenchmarkedServers(3)

        // 1. Fast Parallel Racing over the Top 3 benchmarked fastest servers (plus cached winner)
        val probeEndpoints = (listOfNotNull(lastHealthyEndpoint) + topBenchmarked + endpoints)
            .distinct()
            .take(4)
        val resultDeferred = CompletableDeferred<LosslessStreamResult?>()
        val atomicFinished = AtomicBoolean(false)

        try {
            coroutineScope {
                probeEndpoints.forEach { ep ->
                    launch {
                        try {
                            val res = probeSingleMirror(ep, encodedQuery, normTitle, normArtist, durationSec, qualityTier)
                            if (res != null && atomicFinished.compareAndSet(false, true)) {
                                lastHealthyEndpoint = ep
                                resultDeferred.complete(res)
                                coroutineContext.cancelChildren() // Cancel all slower in-flight mirror requests immediately
                            }
                        } catch (e: Exception) {
                            // mirror probe failed
                        }
                    }
                }

                // Race timeout of 3500ms
                withTimeoutOrNull(3500L) {
                    resultDeferred.await()
                }?.let { winningResult ->
                    synchronized(memoryCache) {
                        memoryCache.put(cacheKey, CacheEntry(winningResult, System.currentTimeMillis()))
                    }
                    Log.d(TAG, "Race won by $lastHealthyEndpoint: ${winningResult.streamUrl}")
                    return@coroutineScope winningResult
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parallel mirror race timed out or failed: ${e.message}")
        }

        if (resultDeferred.isCompleted) {
            val res = resultDeferred.getCompleted()
            if (res != null) {
                synchronized(memoryCache) {
                    memoryCache.put(cacheKey, CacheEntry(res, System.currentTimeMillis()))
                }
                return@withContext res
            }
        }

        null
    }

    private fun probeSingleMirror(
        endpoint: String,
        encodedQuery: String,
        normTitle: String,
        normArtist: String,
        durationSec: Int?,
        qualityTier: String,
    ): LosslessStreamResult? {
        val start = System.currentTimeMillis()
        val candidate = searchCandidates(endpoint, encodedQuery, normTitle, normArtist, durationSec)
        if (candidate == null) {
            recordMirrorFailure(endpoint)
            return null
        }
        val streamResult = fetchStream(endpoint, candidate.id, qualityTier)
        if (streamResult == null) {
            recordMirrorFailure(endpoint)
            return null
        }
        if (isStreamUrlPlayable(streamResult.streamUrl)) {
            recordMirrorSuccess(endpoint, System.currentTimeMillis() - start)
            return streamResult
        } else {
            recordMirrorFailure(endpoint)
            return null
        }
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
            "$base/api/search?q=$encodedQuery",
        )

        for (url in searchUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .build()

            val response = runCatching { httpClient.newCall(request).execute() }.getOrNull() ?: continue
            val body = response.use { if (it.isSuccessful) it.body?.string() else null } ?: continue
            val candidates = parseSearchResults(body, normTitle, normArtist, targetDurationSec)
            if (candidates.isNotEmpty()) {
                return candidates.maxByOrNull { it.score }
            }
        }

        // Fast Deezer public search fallback to obtain metadata & ID
        val deezerUrl = "https://api.deezer.com/search?q=$encodedQuery"
        val deezerRequest = Request.Builder()
            .url(deezerUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()
        val deezerResponse = runCatching { httpClient.newCall(deezerRequest).execute() }.getOrNull()
        val deezerBody = deezerResponse?.use { if (it.isSuccessful) it.body?.string() else null }
        if (deezerBody != null) {
            val deezerCandidates = parseSearchResults(deezerBody, normTitle, normArtist, targetDurationSec)
            if (deezerCandidates.isNotEmpty()) {
                return deezerCandidates.maxByOrNull { it.score }
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
                val duration = item.optInt("duration", 0).takeIf { it > 0 }
                    ?: (item.optInt("duration_ms", 0) / 1000).takeIf { it > 0 }
                    ?: 0
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

                if (quality.contains("HI_RES", ignoreCase = true) || item.optInt("maximum_bit_depth", 0) >= 24) {
                    score += 15
                } else if (quality.contains("LOSSLESS", ignoreCase = true) || item.optInt("maximum_bit_depth", 0) >= 16) {
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
            "$base/download-url/$trackId?quality=$qualityTier",
            "$base/api/download-url/$trackId?quality=$qualityTier",
            "$base/dl/dzr?id=$trackId",
            "$base/dl/tid?id=$trackId",
        )

        for (url in trackUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

            // Check common direct stream & download URL keys (Tidal, Qobuz, DAB, Deezer)
            var streamUrl = dataObj.optString("url").takeIf { it.isNotBlank() }
                ?: dataObj.optString("OriginalTrackUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("flacUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("streamUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("stream_url").takeIf { it.isNotBlank() }
                ?: dataObj.optString("downloadUrl").takeIf { it.isNotBlank() }
                ?: dataObj.optString("download_url").takeIf { it.isNotBlank() }
                ?: dataObj.optString("directURL").takeIf { it.isNotBlank() }
                ?: dataObj.optString("playbackUrl").takeIf { it.isNotBlank() }
                ?: root.optString("url").takeIf { it.isNotBlank() }
                ?: root.optString("OriginalTrackUrl").takeIf { it.isNotBlank() }
                ?: root.optString("flacUrl").takeIf { it.isNotBlank() }
                ?: root.optString("streamUrl").takeIf { it.isNotBlank() }
                ?: root.optString("stream_url").takeIf { it.isNotBlank() }
                ?: root.optString("downloadUrl").takeIf { it.isNotBlank() }
                ?: root.optString("download_url").takeIf { it.isNotBlank() }
                ?: root.optString("directURL").takeIf { it.isNotBlank() }
                ?: root.optString("playbackUrl").takeIf { it.isNotBlank() }

            val manifestStr = dataObj.optString("manifest").takeIf { it.isNotBlank() }
                ?: root.optString("manifest").takeIf { it.isNotBlank() }
                ?: dataObj.optJSONObject("track")?.optString("manifest")?.takeIf { it.isNotBlank() }

            var bitDepth = if (qualityTier == "HI_RES_LOSSLESS") 24 else 16
            var sampleRate = if (qualityTier == "HI_RES_LOSSLESS") 96000 else 44100

            if (dataObj.has("bitDepth")) bitDepth = dataObj.optInt("bitDepth", bitDepth)
            else if (dataObj.has("bit_depth")) bitDepth = dataObj.optInt("bit_depth", bitDepth)
            else if (dataObj.has("maximum_bit_depth")) bitDepth = dataObj.optInt("maximum_bit_depth", bitDepth)

            if (dataObj.has("sampleRate")) sampleRate = dataObj.optInt("sampleRate", sampleRate)
            else if (dataObj.has("sample_rate")) {
                val sr = dataObj.optDouble("sample_rate", sampleRate.toDouble())
                sampleRate = if (sr <= 192.0) (sr * 1000).toInt() else sr.toInt()
            } else if (dataObj.has("maximum_sampling_rate")) {
                val sr = dataObj.optDouble("maximum_sampling_rate", sampleRate.toDouble())
                sampleRate = if (sr <= 192.0) (sr * 1000).toInt() else sr.toInt()
            }

            // Tidal Base64 manifest decoding
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

            val statedQuality = dataObj.optString("audioQuality")
                .ifBlank { dataObj.optString("quality") }
                .uppercase(Locale.ROOT)
            val statedCodec = dataObj.optString("codec")
                .ifBlank { dataObj.optString("mimeType") }
                .lowercase(Locale.ROOT)

            if (streamUrl != null && streamUrl.startsWith("http")) {
                val pathLower = streamUrl.substringBefore('?').lowercase(Locale.ROOT)
                val explicitlyLossyExt = pathLower.endsWith(".mp3") ||
                    pathLower.endsWith(".aac") ||
                    pathLower.endsWith(".opus") ||
                    pathLower.endsWith(".ogg") ||
                    pathLower.endsWith(".webm")
                val explicitlyLossyCodec = statedCodec.contains("mp3") ||
                    statedCodec.contains("mpeg") ||
                    statedCodec.contains("aac") ||
                    statedCodec.contains("opus") ||
                    statedCodec.contains("vorbis")
                val explicitlyLossyQuality = statedQuality in setOf("LOW", "MEDIUM", "NORMAL", "HIGH", "320", "128", "96") &&
                    !pathLower.endsWith(".flac")
                if (explicitlyLossyExt || explicitlyLossyCodec || explicitlyLossyQuality) {
                    return@runCatching null
                }

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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Range", "bytes=0-1024")
            .build()
        val probeClient = httpClient.newBuilder()
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            probeClient.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful || resp.code in 200..206 || resp.code in 301..308 || resp.code == 416
                if (!ok) return@use false
                val ct = resp.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                val rejectedLossyType = ct.startsWith("audio/mpeg") ||
                    ct.startsWith("audio/mp3") ||
                    ct.startsWith("audio/aac") ||
                    ct.startsWith("audio/opus") ||
                    ct.startsWith("audio/ogg") ||
                    ct.startsWith("audio/webm") ||
                    ct.startsWith("text/html") ||
                    ct.startsWith("application/json")
                !rejectedLossyType
            }
        }.getOrDefault(false)
    }

    suspend fun testEndpoint(endpointUrl: String? = null): Boolean = withContext(Dispatchers.IO) {
        val target = (endpointUrl?.trim()?.trimEnd('/') ?: AppSettings.customLosslessEndpoint.value.trim().trimEnd('/'))
            .ifBlank { BUILTIN_ENDPOINTS.first() }

        val testUrls = listOf(
            "$target/v1/health",
            "$target/health",
            "$target/api/health",
            "$target/search/?s=test",
            "$target/search?s=test",
            "$target/search/?q=test",
            "$target/search?q=test",
            "$target/",
        )
        for (url in testUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
