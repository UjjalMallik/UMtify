package com.music.bitchord.data.sources.builtin

import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

object InternetArchiveProvider : BuiltInLosslessProvider {

    private const val TAG = "InternetArchiveProvider"
    override val name: String = "Archive.org"
    override val isEnabled: Boolean get() = AppSettings.enableInternetArchiveLossless.value

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun clean(term: String): String =
        term.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim().replace(Regex("""\s+"""), " ")

    override suspend fun resolveStream(
        title: String,
        artist: String,
        durationSec: Int?,
    ): SourceStream? = withContext(Dispatchers.IO) {
        if (!isEnabled || title.isBlank()) return@withContext null

        val cleanTitle = clean(title)
        val cleanArtist = clean(artist)
        val queryStr = if (cleanArtist.isNotBlank()) {
            "mediatype:(audio) AND (title:(\"$cleanTitle\") OR creator:(\"$cleanArtist\"))"
        } else {
            "mediatype:(audio) AND title:(\"$cleanTitle\")"
        }

        val encodedQuery = URLEncoder.encode(queryStr, "UTF-8")
        val searchUrl = "https://archive.org/advancedsearch.php?q=$encodedQuery&fl[]=identifier,title,creator&rows=5&output=json"

        val docs = runCatching {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["response"]?.jsonObject?.get("docs")?.jsonArray ?: emptyList()
            }
        }.getOrDefault(emptyList())

        if (docs.isEmpty()) return@withContext null

        for (doc in docs) {
            val identifier = doc.jsonObject["identifier"]?.jsonPrimitive?.content ?: continue
            val stream = findBestAudioFile(identifier, cleanTitle, durationSec)
            if (stream != null) return@withContext stream
        }

        null
    }

    private suspend fun findBestAudioFile(
        identifier: String,
        targetTitle: String,
        targetDurationSec: Int?,
    ): SourceStream? = withContext(Dispatchers.IO) {
        val metaUrl = "https://archive.org/metadata/$identifier/files"
        runCatching {
            val req = Request.Builder().url(metaUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val files = json.parseToJsonElement(body).jsonObject["result"]?.jsonArray ?: return@runCatching null

                // Filter candidates: prefer FLAC, fallback to WAV or high-bitrate MP3
                val flacFiles = mutableListOf<ArchiveFileInfo>()
                val fallbackFiles = mutableListOf<ArchiveFileInfo>()

                for (f in files) {
                    val obj = f.jsonObject
                    val fileName = obj["name"]?.jsonPrimitive?.content ?: continue
                    val format = obj["format"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT) ?: ""
                    val fileTitle = obj["title"]?.jsonPrimitive?.content ?: fileName
                    val lengthSec = obj["length"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()

                    val isFlac = format.contains("flac") || fileName.endsWith(".flac", ignoreCase = true)
                    val isWav = format.contains("wav") || fileName.endsWith(".wav", ignoreCase = true)
                    val isHighMp3 = format.contains("vbr") || format.contains("320") || fileName.endsWith(".mp3", ignoreCase = true)

                    val info = ArchiveFileInfo(
                        name = fileName,
                        format = if (isFlac) "flac" else if (isWav) "wav" else "mp3",
                        title = fileTitle,
                        durationSec = lengthSec,
                    )

                    if (isFlac) {
                        flacFiles.add(info)
                    } else if (isWav || isHighMp3) {
                        fallbackFiles.add(info)
                    }
                }

                val candidates = if (flacFiles.isNotEmpty()) flacFiles else fallbackFiles
                if (candidates.isEmpty()) return@runCatching null

                // Pick the candidate that matches targetTitle or targetDuration best
                val matched = candidates.firstOrNull {
                    it.title.contains(targetTitle, ignoreCase = true) ||
                        it.name.contains(targetTitle, ignoreCase = true)
                } ?: candidates.first()

                val encodedName = Uri.encode(matched.name, "/")
                val streamUrl = "https://archive.org/download/$identifier/$encodedName"
                val isLossless = matched.format in listOf("flac", "wav")

                SourceStream(
                    url = streamUrl,
                    format = StreamFormat(
                        codec = matched.format,
                        sampleRateHz = 44100,
                        bitDepth = if (isLossless) 16 else null,
                        kbps = if (isLossless) 1411 else 320,
                    ),
                    durationSec = matched.durationSec ?: targetDurationSec,
                    sourceName = "Archive.org",
                )
            }
        }.getOrNull()
    }

    override suspend fun searchTracks(query: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (!isEnabled || query.isBlank()) return@withContext emptyList<Song>()

        val cleanQuery = clean(query)
        val queryStr = "mediatype:(audio) AND (format:(Flac) OR format:(VBR MP3)) AND ($cleanQuery)"
        val encodedQuery = URLEncoder.encode(queryStr, "UTF-8")
        val searchUrl = "https://archive.org/advancedsearch.php?q=$encodedQuery&fl[]=identifier,title,creator,year&rows=$limit&output=json"

        val docs = runCatching {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "UMtify/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string().orEmpty()
                json.parseToJsonElement(body).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray?.toList() ?: emptyList()
            }
        }.getOrNull() ?: emptyList()

        if (docs.isEmpty()) return@withContext emptyList()

        // For each doc, fetch the files metadata in parallel to resolve the direct streamable audio URL
        coroutineScope {
            docs.map { doc ->
                async {
                    val obj = doc.jsonObject
                    val identifier = obj["identifier"]?.jsonPrimitive?.content ?: return@async null
                    val title = obj["title"]?.jsonPrimitive?.content?.ifBlank { null } ?: identifier
                    val creator = obj["creator"]?.jsonPrimitive?.content?.ifBlank { null } ?: "Archive.org"
                    val thumb = "https://archive.org/services/img/$identifier"

                    val stream = findBestAudioFile(identifier, cleanQuery, null)
                    val streamUrl = stream?.url ?: "https://archive.org/download/$identifier"

                    Song(
                        videoId = streamUrl,
                        title = title,
                        artist = creator,
                        thumbnailUrl = thumb,
                        durationText = stream?.durationSec?.let { formatDuration(it) },
                        sourceQuality = "LOSSLESS",
                        sourceHiRes = true,
                        downloadFormat = "FLAC",
                        playbackSource = "Archive.org",
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun formatDuration(sec: Int): String =
        "${sec / 60}:${"%02d".format(Locale.ROOT, sec % 60)}"

    private data class ArchiveFileInfo(
        val name: String,
        val format: String,
        val title: String,
        val durationSec: Int?,
    )
}
