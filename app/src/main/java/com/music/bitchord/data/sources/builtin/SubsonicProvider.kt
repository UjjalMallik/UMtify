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
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object SubsonicProvider : BuiltInLosslessProvider {

    private const val TAG = "SubsonicProvider"
    override val name: String = "Subsonic"
    override val isEnabled: Boolean
        get() = AppSettings.enableSubsonicLossless.value &&
            AppSettings.subsonicServerUrl.value.isNotBlank() &&
            AppSettings.subsonicUsername.value.isNotBlank()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun buildAuthParams(username: String, pass: String): String {
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5(pass + salt)
        return "u=${URLEncoder.encode(username, "UTF-8")}&t=$token&s=$salt&v=1.16.1&c=UMtify&f=json"
    }

    /**
     * Verifies server reachability and credentials via `/rest/ping.view`.
     */
    suspend fun ping(serverUrl: String, username: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext Result.failure(IllegalArgumentException("Server URL is empty"))
        val auth = buildAuthParams(username.trim(), pass)
        val pingUrl = "$base/rest/ping.view?$auth"

        runCatching {
            val req = Request.Builder().url(pingUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching Result.failure<String>(Exception("HTTP error ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                val subResp = parsed["subsonic-response"]?.jsonObject
                    ?: return@runCatching Result.failure<String>(Exception("Invalid Subsonic response envelope"))

                val status = subResp["status"]?.jsonPrimitive?.content ?: "unknown"
                if (status == "ok") {
                    val serverType = subResp["type"]?.jsonPrimitive?.content ?: "Subsonic"
                    val version = subResp["version"]?.jsonPrimitive?.content ?: "1.16.1"
                    Result.success("Connected to $serverType (v$version)")
                } else {
                    val err = subResp["error"]?.jsonObject
                    val errMsg = err?.get("message")?.jsonPrimitive?.content ?: "Authentication or server error"
                    Result.failure<String>(Exception(errMsg))
                }
            }
        }.getOrElse { Result.failure(it) }
    }

    override suspend fun resolveStream(
        title: String,
        artist: String,
        durationSec: Int?,
    ): SourceStream? = withContext(Dispatchers.IO) {
        if (!isEnabled || title.isBlank()) return@withContext null

        val server = AppSettings.subsonicServerUrl.value.trim().trimEnd('/')
        val user = AppSettings.subsonicUsername.value.trim()
        val pass = AppSettings.subsonicPassword.value

        val auth = buildAuthParams(user, pass)
        val queryTerm = if (artist.isNotBlank()) "$title $artist" else title
        val encodedQuery = URLEncoder.encode(queryTerm, "UTF-8")
        val searchUrl = "$server/rest/search3.view?query=$encodedQuery&songCount=15&$auth"

        runCatching {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                val subResp = parsed["subsonic-response"]?.jsonObject ?: return@runCatching null
                val songs = subResp["searchResult3"]?.jsonObject?.get("song")?.jsonArray ?: return@runCatching null

                var bestMatch: SubsonicSong? = null
                var bestScore = -1

                for (s in songs) {
                    val songObj = s.jsonObject
                    val id = songObj["id"]?.jsonPrimitive?.content ?: continue
                    val sTitle = songObj["title"]?.jsonPrimitive?.content ?: ""
                    val sArtist = songObj["artist"]?.jsonPrimitive?.content ?: ""
                    val suffix = songObj["suffix"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT) ?: ""
                    val bitRate = songObj["bitRate"]?.jsonPrimitive?.content?.toIntOrNull()
                    val sDuration = songObj["duration"]?.jsonPrimitive?.content?.toIntOrNull()

                    val isLossless = suffix in listOf("flac", "alac", "wav", "aiff", "dsf", "dff")
                    val isAtmos = suffix in listOf("eac3", "ac4", "ec3")

                    var score = 0
                    if (sTitle.equals(title, ignoreCase = true)) score += 50
                    else if (sTitle.contains(title, ignoreCase = true)) score += 30

                    if (artist.isNotBlank()) {
                        if (sArtist.equals(artist, ignoreCase = true)) score += 40
                        else if (sArtist.contains(artist, ignoreCase = true)) score += 20
                    }

                    if (isLossless) score += 25
                    if (isAtmos) score += 30

                    if (durationSec != null && sDuration != null) {
                        val diff = kotlin.math.abs(durationSec - sDuration)
                        if (diff <= 3) score += 15
                        else if (diff <= 8) score += 5
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = SubsonicSong(
                            id = id,
                            title = sTitle,
                            artist = sArtist,
                            suffix = suffix,
                            bitRate = bitRate,
                            duration = sDuration,
                            isAtmos = isAtmos,
                            isLossless = isLossless,
                        )
                    }
                }

                val chosen = bestMatch ?: return@runCatching null
                // Build raw direct stream URL: format=raw preserves bit-exact audio without server transcoding
                val streamAuth = buildAuthParams(user, pass)
                val rawStreamUrl = "$server/rest/stream.view?id=${chosen.id}&format=raw&$streamAuth"

                val codec = when {
                    chosen.isAtmos -> "eac3-joc"
                    chosen.suffix.isNotBlank() -> chosen.suffix
                    else -> "flac"
                }

                SourceStream(
                    url = rawStreamUrl,
                    format = StreamFormat(
                        codec = codec,
                        bitDepth = if (chosen.isLossless) 16 else null,
                        sampleRateHz = 44100,
                        kbps = chosen.bitRate ?: if (chosen.isLossless) 1411 else 320,
                    ),
                    durationSec = chosen.duration ?: durationSec,
                    sourceName = "Subsonic",
                )
            }
        }.getOrNull()
    }

    override suspend fun searchTracks(query: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (!isEnabled || query.isBlank()) return@withContext emptyList()

        val server = AppSettings.subsonicServerUrl.value.trim().trimEnd('/')
        val user = AppSettings.subsonicUsername.value.trim()
        val pass = AppSettings.subsonicPassword.value

        val auth = buildAuthParams(user, pass)
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$server/rest/search3.view?query=$encodedQuery&songCount=$limit&$auth"

        runCatching {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                val subResp = parsed["subsonic-response"]?.jsonObject ?: return@runCatching emptyList()
                val songs = subResp["searchResult3"]?.jsonObject?.get("song")?.jsonArray ?: return@runCatching emptyList()

                songs.mapNotNull { s ->
                    val obj = s.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.content ?: id
                    val artist = obj["artist"]?.jsonPrimitive?.content ?: ""
                    val album = obj["album"]?.jsonPrimitive?.content
                    val suffix = obj["suffix"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT) ?: "flac"
                    val duration = obj["duration"]?.jsonPrimitive?.content?.toIntOrNull()
                    val coverArtId = obj["coverArt"]?.jsonPrimitive?.content

                    val streamAuth = buildAuthParams(user, pass)
                    val streamUrl = "$server/rest/stream.view?id=$id&format=raw&$streamAuth"
                    val thumbUrl = coverArtId?.let { "$server/rest/getCoverArt.view?id=$it&$streamAuth" }

                    val isLossless = suffix in listOf("flac", "alac", "wav", "aiff", "dsf")

                    Song(
                        videoId = streamUrl,
                        title = title,
                        artist = artist,
                        albumName = album,
                        thumbnailUrl = thumbUrl,
                        durationText = duration?.let { formatDuration(it) },
                        sourceQuality = if (isLossless) "LOSSLESS" else "HIGH",
                        sourceHiRes = isLossless,
                        downloadFormat = suffix.uppercase(Locale.ROOT),
                        playbackSource = "Subsonic",
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun formatDuration(sec: Int): String =
        "${sec / 60}:${"%02d".format(Locale.ROOT, sec % 60)}"

    private data class SubsonicSong(
        val id: String,
        val title: String,
        val artist: String,
        val suffix: String,
        val bitRate: Int?,
        val duration: Int?,
        val isAtmos: Boolean,
        val isLossless: Boolean,
    )
}
