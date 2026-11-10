package com.music.bitchord.data.sources.builtin

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
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

object JamendoProvider : BuiltInLosslessProvider {

    private const val TAG = "JamendoProvider"
    private const val DEFAULT_PUBLIC_CLIENT_ID = "b6747d04"

    override val name: String = "Jamendo"
    override val isEnabled: Boolean get() = AppSettings.enableJamendoLossless.value

    val effectiveClientId: String
        get() = AppSettings.jamendoClientId.value.ifBlank { DEFAULT_PUBLIC_CLIENT_ID }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    override suspend fun resolveStream(
        title: String,
        artist: String,
        durationSec: Int?,
    ): SourceStream? = withContext(Dispatchers.IO) {
        if (!isEnabled || title.isBlank()) return@withContext null

        val clientId = effectiveClientId
        val cleanTitle = title.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim()
        val cleanArtist = artist.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim()

        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
        val queryUrl = if (cleanArtist.isNotBlank()) {
            val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            "https://api.jamendo.com/v3.0/tracks/?client_id=$clientId&format=json&namesearch=$encodedTitle&artist_name=$encodedArtist&audioformat=flac&limit=5"
        } else {
            "https://api.jamendo.com/v3.0/tracks/?client_id=$clientId&format=json&namesearch=$encodedTitle&audioformat=flac&limit=5"
        }

        runCatching {
            val req = Request.Builder().url(queryUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                val results = parsed["results"]?.jsonArray ?: return@runCatching null
                if (results.isEmpty()) return@runCatching null

                // Pick best match by title & duration
                var bestTrack: JamendoTrack? = null
                var bestScore = -1

                for (item in results) {
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: continue
                    val name = obj["name"]?.jsonPrimitive?.content ?: ""
                    val artistName = obj["artist_name"]?.jsonPrimitive?.content ?: ""
                    val duration = obj["duration"]?.jsonPrimitive?.content?.toIntOrNull()
                    val audio = obj["audio"]?.jsonPrimitive?.content
                    val audiodl = obj["audiodl"]?.jsonPrimitive?.content

                    val streamUrl = audiodl?.ifBlank { null } ?: audio?.ifBlank { null } ?: continue

                    var score = 0
                    if (name.equals(cleanTitle, ignoreCase = true)) score += 40
                    else if (name.contains(cleanTitle, ignoreCase = true)) score += 20

                    if (cleanArtist.isNotBlank()) {
                        if (artistName.equals(cleanArtist, ignoreCase = true)) score += 30
                        else if (artistName.contains(cleanArtist, ignoreCase = true)) score += 15
                    }

                    if (durationSec != null && duration != null) {
                        val diff = kotlin.math.abs(durationSec - duration)
                        if (diff <= 3) score += 20
                        else if (diff <= 8) score += 10
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestTrack = JamendoTrack(id, name, artistName, duration, streamUrl)
                    }
                }

                val chosen = bestTrack ?: return@runCatching null

                SourceStream(
                    url = chosen.streamUrl,
                    format = StreamFormat(
                        codec = "flac",
                        bitDepth = 16,
                        sampleRateHz = 44100,
                        kbps = 1411,
                    ),
                    durationSec = chosen.duration ?: durationSec,
                    sourceName = "Jamendo",
                )
            }
        }.getOrNull()
    }

    override suspend fun searchTracks(query: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (!isEnabled || query.isBlank()) return@withContext emptyList()

        val clientId = effectiveClientId
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "https://api.jamendo.com/v3.0/tracks/?client_id=$clientId&format=json&namesearch=$encoded&audioformat=flac&limit=$limit"

        runCatching {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                val results = parsed["results"]?.jsonArray ?: return@runCatching emptyList()

                results.mapNotNull { item ->
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.content ?: id
                    val artistName = obj["artist_name"]?.jsonPrimitive?.content ?: "Jamendo Artist"
                    val albumName = obj["album_name"]?.jsonPrimitive?.content
                    val duration = obj["duration"]?.jsonPrimitive?.content?.toIntOrNull()
                    val audio = obj["audio"]?.jsonPrimitive?.content
                    val audiodl = obj["audiodl"]?.jsonPrimitive?.content
                    val image = obj["image"]?.jsonPrimitive?.content

                    val streamUrl = audiodl?.ifBlank { null } ?: audio?.ifBlank { null } ?: return@mapNotNull null

                    Song(
                        videoId = streamUrl,
                        title = name,
                        artist = artistName,
                        albumName = albumName,
                        thumbnailUrl = image,
                        durationText = duration?.let { formatDuration(it) },
                        sourceQuality = "LOSSLESS",
                        sourceHiRes = false,
                        downloadFormat = "FLAC",
                        playbackSource = "Jamendo",
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun formatDuration(sec: Int): String =
        "${sec / 60}:${"%02d".format(Locale.ROOT, sec % 60)}"

    private data class JamendoTrack(
        val id: String,
        val name: String,
        val artist: String,
        val duration: Int?,
        val streamUrl: String,
    )
}
