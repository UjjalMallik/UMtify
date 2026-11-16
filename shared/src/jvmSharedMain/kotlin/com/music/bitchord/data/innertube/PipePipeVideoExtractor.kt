package com.music.bitchord.data.innertube

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResolvedVideoStream(
    val url: String,
    val isHls: Boolean = false,
    val userAgent: String = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    val videoId: String = "",
)

/**
 * PipePipe-style anonymous video engine:
 * 1. Resolves true YouTube video ID (NewPipe search + InnerTube VIDEOS fallback).
 * 2. 3-tier stream extraction (NewPipeExtractor -> InnerTube ANDROID_VR/IOS -> Piped API).
 */
object PipePipeVideoExtractor {
    private const val TAG = "PipePipeVideo"
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"

    private val streamCache = ConcurrentHashMap<String, ResolvedVideoStream>()

    suspend fun resolveAnonymousVideoStream(
        trackTitle: String,
        trackArtist: String,
        currentMediaId: String,
        durationSec: Int,
    ): ResolvedVideoStream? = withContext(Dispatchers.IO) {
        if (isValidYoutubeId(currentMediaId)) {
            streamCache[currentMediaId]?.let { return@withContext it }

            val directA = runCatching { extractFromNewPipe(currentMediaId) }.getOrNull()
            if (directA != null) {
                streamCache[currentMediaId] = directA
                TrackLog.d(TAG, "Direct Tier A (NewPipe) succeeded for $currentMediaId")
                return@withContext directA
            }

            val directPiped = runCatching { extractFromPipedApi(currentMediaId) }.getOrNull()
            if (directPiped != null) {
                streamCache[currentMediaId] = directPiped
                TrackLog.d(TAG, "Direct Tier B (Piped) succeeded for $currentMediaId")
                return@withContext directPiped
            }

            val directC = runCatching { extractFromInnerTube(currentMediaId) }.getOrNull()
            if (directC != null) {
                streamCache[currentMediaId] = directC
                TrackLog.d(TAG, "Direct Tier C (InnerTube) succeeded for $currentMediaId")
                return@withContext directC
            }
        }

        val targetVideoId = resolveTargetVideoId(trackTitle, trackArtist, currentMediaId, durationSec)
        if (targetVideoId == null) {
            TrackLog.w(TAG, "Could not resolve valid YouTube video ID for '$trackTitle' by '$trackArtist'")
            return@withContext null
        }

        streamCache[targetVideoId]?.let { return@withContext it }

        // Tier A: NewPipeExtractor
        val tierA = runCatching { extractFromNewPipe(targetVideoId) }.getOrNull()
        if (tierA != null) {
            streamCache[targetVideoId] = tierA
            TrackLog.d(TAG, "Tier A (NewPipe) succeeded for $targetVideoId")
            return@withContext tierA
        }

        // Tier B: Instant Piped API Fallback
        val pipedResult = runCatching { extractFromPipedApi(targetVideoId) }.getOrNull()
        if (pipedResult != null) {
            streamCache[targetVideoId] = pipedResult
            TrackLog.d(TAG, "Tier B (Piped API) succeeded for $targetVideoId")
            return@withContext pipedResult
        }

        // Tier C: InnerTube ANDROID_VR & IOS Anonymous Extraction
        val tierC = runCatching { extractFromInnerTube(targetVideoId) }.getOrNull()
        if (tierC != null) {
            streamCache[targetVideoId] = tierC
            TrackLog.d(TAG, "Tier C (InnerTube) succeeded for $targetVideoId")
            return@withContext tierC
        }

        TrackLog.w(TAG, "All 3 video extraction tiers failed for $targetVideoId ($trackTitle - $trackArtist)")
        null
    }

    private suspend fun resolveTargetVideoId(
        trackTitle: String,
        trackArtist: String,
        currentMediaId: String,
        durationSec: Int,
    ): String? {
        // Step 1: Podcasts / Long episodes (>8 min) with an existing 11-char YouTube ID
        if (durationSec > 480 && isValidYoutubeId(currentMediaId)) {
            return currentMediaId
        }

        val cleanTitle = trackTitle.replace(
            Regex("""(?i)\b(official audio|audio|lyrics|lyric|full album|topic|visualizer)\b"""),
            "",
        ).trim()
        val cleanArtist = trackArtist.trim()
        val searchQuery = "$cleanTitle $cleanArtist official video"

        // Step 1.1: NewPipeExtractor Search
        try {
            StreamResolver.ensureInit()
            val searchExtractor = ServiceList.YouTube.getSearchExtractor(
                searchQuery,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                null,
            )
            searchExtractor.fetchPage()
            val items = searchExtractor.initialPage?.items.orEmpty()
            for (item in items) {
                val title = item.name.orEmpty()
                if (isExcludedVideoTitle(title)) continue
                val vid = extractVideoId(item.url.orEmpty())
                if (vid != null && isValidYoutubeId(vid)) {
                    return vid
                }
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "NewPipe search for '$searchQuery' failed: ${e.message}")
        }

        // Step 1.2: InnerTube WEB Search Fallback (filter = VIDEOS)
        try {
            val candidates = YtMusicRepository.search("$cleanTitle $cleanArtist official video", SearchFilter.VIDEOS)
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
                .ifEmpty {
                    YtMusicRepository.search("$cleanTitle $cleanArtist", SearchFilter.VIDEOS)
                        .getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()
                }

            for (candidate in candidates) {
                if (isExcludedVideoTitle(candidate.title)) continue
                if (isValidYoutubeId(candidate.videoId)) {
                    return candidate.videoId
                }
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "InnerTube search fallback failed: ${e.message}")
        }

        // Step 1.3: Fallback to currentMediaId if valid 11-char ID
        if (isValidYoutubeId(currentMediaId)) {
            return currentMediaId
        }

        return null
    }

    private fun isExcludedVideoTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("audio") ||
            lower.contains("lyric") ||
            lower.contains("topic") ||
            lower.contains("full album")
    }

    private fun isValidYoutubeId(id: String): Boolean =
        id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun extractVideoId(url: String): String? {
        if (url.length == 11 && isValidYoutubeId(url)) return url
        val vParam = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1)
        if (vParam != null) return vParam
        val beMatch = Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1)
        if (beMatch != null) return beMatch
        val embedMatch = Regex("""embed/([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1)
        if (embedMatch != null) return embedMatch
        val shortsMatch = Regex("""shorts/([a-zA-Z0-9_-]{11})""").find(url)?.groupValues?.get(1)
        if (shortsMatch != null) return shortsMatch
        val lastSegment = url.substringAfterLast("/").substringBefore("?").substringBefore("&")
        if (lastSegment.length == 11 && isValidYoutubeId(lastSegment)) return lastSegment
        return null
    }

    // Tier A: NewPipeExtractor
    fun extractFromNewPipe(targetVideoId: String): ResolvedVideoStream? {
        StreamResolver.ensureInit()
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$targetVideoId")
        extractor.fetchPage()

        // 1. Prioritize Muxed Streams for Speed & Reliability: itag 18 (360p MP4) or itag 22 (720p MP4)
        val muxedStreams = extractor.videoStreams?.filter { s ->
            !s.content.isNullOrBlank() &&
                (s.format == MediaFormat.MPEG_4 || (s.format?.name ?: "").contains("MP4", ignoreCase = true))
        }.orEmpty()

        val itagMatch = muxedStreams.firstOrNull { it.itag == 18 }
            ?: muxedStreams.firstOrNull { it.itag == 22 }
            ?: muxedStreams.firstOrNull { it.height in 360..720 }
            ?: muxedStreams.firstOrNull()

        if (itagMatch != null && !itagMatch.content.isNullOrBlank()) {
            return ResolvedVideoStream(
                url = itagMatch.content,
                isHls = false,
                userAgent = CHROME_UA,
                videoId = targetVideoId,
            )
        }

        // 2. Fallback to videoOnlyStreams (filtering for avc1 / video/mp4 at 360p or 480p)
        val videoOnlyStreams = extractor.videoOnlyStreams?.filter { s ->
            !s.content.isNullOrBlank() &&
                (s.codec?.contains("avc1", ignoreCase = true) == true ||
                    s.format == MediaFormat.MPEG_4 ||
                    (s.format?.name ?: "").contains("MP4", ignoreCase = true))
        }.orEmpty()

        val pickedVideoOnly = videoOnlyStreams.firstOrNull { it.height == 360 }
            ?: videoOnlyStreams.firstOrNull { it.height == 480 }
            ?: videoOnlyStreams.firstOrNull { it.height in 360..720 }
            ?: videoOnlyStreams.firstOrNull()

        if (pickedVideoOnly != null && !pickedVideoOnly.content.isNullOrBlank()) {
            return ResolvedVideoStream(
                url = pickedVideoOnly.content,
                isHls = false,
                userAgent = CHROME_UA,
                videoId = targetVideoId,
            )
        }

        // 3. Fallback to hlsUrl
        val hls = extractor.hlsUrl
        if (!hls.isNullOrBlank()) {
            return ResolvedVideoStream(
                url = hls,
                isHls = true,
                userAgent = CHROME_UA,
                videoId = targetVideoId,
            )
        }

        return null
    }

    // Tier B: InnerTube ANDROID_VR & IOS Anonymous Client Extraction
    fun extractFromInnerTube(targetVideoId: String): ResolvedVideoStream? {
        val vrResult = queryInnerTubePlayer(
            videoId = targetVideoId,
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            clientNumber = "28",
            userAgent = ANDROID_VR_UA,
        )
        if (vrResult != null) return vrResult

        val iosResult = queryInnerTubePlayer(
            videoId = targetVideoId,
            clientName = "IOS",
            clientVersion = "19.45.4",
            clientNumber = "5",
            userAgent = IOS_UA,
        )
        return iosResult
    }

    private fun queryInnerTubePlayer(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientNumber: String,
        userAgent: String,
    ): ResolvedVideoStream? {
        return try {
            val payload = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    }
                }
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", clientNumber)
                .header("X-YouTube-Client-Version", clientVersion)
                .post(payload)
                .build()

            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
                val streamingData = root["streamingData"]?.jsonObject ?: return null

                // 1. HLS manifest
                val hlsManifestUrl = streamingData["hlsManifestUrl"]?.jsonPrimitive?.content
                if (!hlsManifestUrl.isNullOrBlank()) {
                    return ResolvedVideoStream(
                        url = hlsManifestUrl,
                        isHls = true,
                        userAgent = userAgent,
                        videoId = videoId,
                    )
                }

                // 2. Muxed progressive formats
                val formats = streamingData["formats"]?.jsonArray?.mapNotNull { it.jsonObject }
                val muxedMp4 = formats?.filter {
                    val url = it["url"]?.jsonPrimitive?.content
                    val mime = it["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    !url.isNullOrBlank() && mime.contains("video/mp4")
                }?.maxByOrNull {
                    it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                }
                val muxedUrl = muxedMp4?.get("url")?.jsonPrimitive?.content
                if (!muxedUrl.isNullOrBlank()) {
                    return ResolvedVideoStream(
                        url = muxedUrl,
                        isHls = false,
                        userAgent = userAgent,
                        videoId = videoId,
                    )
                }

                // 3. Adaptive formats (prefer 360p / 480p / 720p avc1)
                val adaptive = streamingData["adaptiveFormats"]?.jsonArray?.mapNotNull { it.jsonObject }
                val videoAdaptive = adaptive?.filter {
                    val url = it["url"]?.jsonPrimitive?.content
                    val mime = it["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    !url.isNullOrBlank() && (mime.contains("video/mp4") || mime.contains("video/webm"))
                }

                val avc1Matches = videoAdaptive?.filter {
                    val mime = it["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    mime.contains("avc1", ignoreCase = true)
                }?.filter {
                    val height = it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    height in 360..720
                }?.maxByOrNull {
                    it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                }

                val pickedAdaptive = avc1Matches
                    ?: videoAdaptive?.filter {
                        val height = it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        height in 360..720
                    }?.maxByOrNull {
                        it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    }
                    ?: videoAdaptive?.firstOrNull()

                val vidUrl = pickedAdaptive?.get("url")?.jsonPrimitive?.content
                if (!vidUrl.isNullOrBlank()) {
                    return ResolvedVideoStream(
                        url = vidUrl,
                        isHls = false,
                        userAgent = userAgent,
                        videoId = videoId,
                    )
                }
                null
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "InnerTube extraction failed for $clientName on $videoId: ${e.message}")
            null
        }
    }

    // Tier B: Instant Piped API Fallback
    fun extractFromPipedApi(targetVideoId: String): ResolvedVideoStream? {
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
        )

        val client = Http.client.newBuilder()
            .callTimeout(4000, TimeUnit.MILLISECONDS)
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .build()

        for (instance in pipedInstances) {
            try {
                val pipedUrl = "$instance/streams/$targetVideoId"
                val request = okhttp3.Request.Builder()
                    .url(pipedUrl)
                    .header("User-Agent", CHROME_UA)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
                            val hlsUrl = root["hls"]?.jsonPrimitive?.content
                            if (!hlsUrl.isNullOrBlank()) {
                                return ResolvedVideoStream(
                                    url = hlsUrl,
                                    isHls = true,
                                    userAgent = CHROME_UA,
                                    videoId = targetVideoId,
                                )
                            }

                            val videoStreams = root["videoStreams"]?.jsonArray?.mapNotNull { it.jsonObject }
                            val mp4Stream = videoStreams?.firstOrNull {
                                val u = it["url"]?.jsonPrimitive?.content
                                val mime = it["mimeType"]?.jsonPrimitive?.content.orEmpty()
                                val format = it["format"]?.jsonPrimitive?.content.orEmpty()
                                !u.isNullOrBlank() && (mime.contains("video/mp4") || format.contains("mp4", ignoreCase = true))
                            } ?: videoStreams?.firstOrNull {
                                val u = it["url"]?.jsonPrimitive?.content
                                !u.isNullOrBlank()
                            }

                            val streamUrl = mp4Stream?.get("url")?.jsonPrimitive?.content
                            if (!streamUrl.isNullOrBlank()) {
                                return ResolvedVideoStream(
                                    url = streamUrl,
                                    isHls = false,
                                    userAgent = CHROME_UA,
                                    videoId = targetVideoId,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                TrackLog.w(TAG, "Piped instance $instance failed for $targetVideoId: ${e.message}")
            }
        }
        return null
    }
}
