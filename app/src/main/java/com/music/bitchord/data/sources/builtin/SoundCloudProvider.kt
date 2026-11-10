package com.music.bitchord.data.sources.builtin

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

object SoundCloudProvider {

    private const val TAG = "SoundCloudProvider"
    private const val FALLBACK_CLIENT_ID = "4k5qtSNfeV1Kfd9EI3yUimifZyTE95E3"

    @Volatile
    private var cachedClientId: String = FALLBACK_CLIENT_ID

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    val isEnabled: Boolean
        get() = SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.SOUNDCLOUD }?.enabled ?: true

    private suspend fun getClientId(): String {
        return cachedClientId
    }

    suspend fun refreshClientId(): String = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://soundcloud.com")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                )
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching cachedClientId
                val html = resp.body?.string().orEmpty()
                val scriptMatcher = Pattern.compile("""https?://[a-zA-Z0-9.-]+\.sndcdn\.com/[^\"]+?\.js""").matcher(html)
                val scripts = mutableListOf<String>()
                while (scriptMatcher.find()) {
                    scripts.add(scriptMatcher.group())
                }
                for (s in scripts.reversed()) {
                    val sReq = Request.Builder().url(s).header("User-Agent", "Mozilla/5.0").build()
                    Http.client.newCall(sReq).execute().use { sResp ->
                        if (sResp.isSuccessful) {
                            val js = sResp.body?.string().orEmpty()
                            val idMatcher = Pattern.compile("""client_id[:=]["']?([a-zA-Z0-9]{32})["']?""").matcher(js)
                            if (idMatcher.find()) {
                                val found = idMatcher.group(1)
                                if (!found.isNullOrBlank()) {
                                    cachedClientId = found
                                    TrackLog.d(TAG, "Refreshed SoundCloud Client ID: $found")
                                    return@withContext found
                                }
                            }
                        }
                    }
                }
                cachedClientId
            }
        }.getOrDefault(cachedClientId)
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        durationSec: Int?,
    ): SourceStream? = withContext(Dispatchers.IO) {
        if (!isEnabled || title.isBlank()) return@withContext null

        val cleanTitle = title.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim()
        val cleanArtist = artist.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim()
        val query = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle

        val tracks = searchInternal(query, limit = 5)
        if (tracks.isEmpty()) return@withContext null

        var bestTrack: SoundCloudTrack? = null
        var bestScore = -1

        for (track in tracks) {
            var score = 0
            if (track.title.equals(cleanTitle, ignoreCase = true)) score += 40
            else if (track.title.contains(cleanTitle, ignoreCase = true)) score += 20

            if (cleanArtist.isNotBlank()) {
                if (track.artist.equals(cleanArtist, ignoreCase = true)) score += 30
                else if (track.artist.contains(cleanArtist, ignoreCase = true)) score += 15
            }

            if (durationSec != null && track.durationSec != null) {
                val diff = kotlin.math.abs(durationSec - track.durationSec)
                if (diff <= 3) score += 20
                else if (diff <= 8) score += 10
            }

            if (score > bestScore) {
                bestScore = score
                bestTrack = track
            }
        }

        val chosen = bestTrack ?: tracks.first()
        val streamUrl = resolveDirectStreamUrl(chosen.transcodings) ?: return@withContext null

        SourceStream(
            url = streamUrl,
            format = StreamFormat(
                codec = "mp3",
                kbps = 256,
                sampleRateHz = 44100,
            ),
            durationSec = chosen.durationSec ?: durationSec,
            sourceName = "SoundCloud",
        )
    }

    suspend fun searchTracks(query: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        if (!isEnabled || query.isBlank()) return@withContext emptyList()
        val tracks = searchInternal(query.trim(), limit = limit)
        tracks.mapNotNull { track ->
            val streamUrl = resolveDirectStreamUrl(track.transcodings) ?: return@mapNotNull null
            Song(
                videoId = streamUrl,
                title = track.title,
                artist = track.artist,
                thumbnailUrl = track.artworkUrl,
                durationText = track.durationSec?.let { formatDuration(it) },
                sourceQuality = "HIGH",
                downloadFormat = "MP3",
                playbackSource = "SoundCloud",
            )
        }
    }

    private suspend fun searchInternal(query: String, limit: Int): List<SoundCloudTrack> {
        val cid = getClientId()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$cid&limit=$limit"

        var tracks = executeSearchRequest(searchUrl)
        if (tracks == null) {
            val newCid = refreshClientId()
            val retryUrl = "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$newCid&limit=$limit"
            tracks = executeSearchRequest(retryUrl)
        }
        return tracks ?: emptyList()
    }

    private fun executeSearchRequest(url: String): List<SoundCloudTrack>? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (resp.code == 401) return null
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                val collection = parsed["collection"]?.jsonArray ?: return emptyList()

                collection.mapNotNull { item ->
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.content ?: id
                    val user = obj["user"]?.jsonObject
                    val artist = user?.get("username")?.jsonPrimitive?.content ?: "SoundCloud"
                    val durationMs = obj["duration"]?.jsonPrimitive?.content?.toIntOrNull()
                    val artwork = obj["artwork_url"]?.jsonPrimitive?.content
                    val media = obj["media"]?.jsonObject
                    val transcodingsArr = media?.get("transcodings")?.jsonArray ?: return@mapNotNull null

                    val transcodings = transcodingsArr.mapNotNull { t ->
                        val tObj = t.jsonObject
                        val tUrl = tObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val formatObj = tObj["format"]?.jsonObject
                        val protocol = formatObj?.get("protocol")?.jsonPrimitive?.content ?: ""
                        val mimeType = formatObj?.get("mime_type")?.jsonPrimitive?.content ?: ""
                        SoundCloudTranscoding(tUrl, protocol, mimeType)
                    }

                    SoundCloudTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        durationSec = durationMs?.div(1000),
                        artworkUrl = artwork,
                        transcodings = transcodings,
                    )
                }
            }
        }.getOrNull()
    }

    private fun resolveDirectStreamUrl(transcodings: List<SoundCloudTranscoding>): String? {
        if (transcodings.isEmpty()) return null
        val chosen = transcodings.firstOrNull { it.protocol == "progressive" }
            ?: transcodings.firstOrNull { it.protocol == "hls" }
            ?: transcodings.first()

        val cid = cachedClientId
        val targetUrl = "${chosen.url}?client_id=$cid"

        return runCatching {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["url"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    private fun formatDuration(sec: Int): String =
        "${sec / 60}:${"%02d".format(Locale.ROOT, sec % 60)}"

    data class SoundCloudTranscoding(
        val url: String,
        val protocol: String,
        val mimeType: String,
    )

    data class SoundCloudTrack(
        val id: String,
        val title: String,
        val artist: String,
        val durationSec: Int?,
        val artworkUrl: String?,
        val transcodings: List<SoundCloudTranscoding>,
    )
}
