package com.music.bitchord.data.jiosaavn

import android.util.Base64
import com.music.bitchord.data.TrackLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }
}

@Serializable
data class RawArtistMapItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class RawArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList(),
)

object LenientArtistMapSerializer : KSerializer<RawArtistMap> {
    override val descriptor: SerialDescriptor = RawArtistMap.serializer().descriptor
    override fun serialize(encoder: Encoder, value: RawArtistMap) =
        RawArtistMap.serializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): RawArtistMap {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return RawArtistMap.serializer().deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) return RawArtistMap()
        return jsonDecoder.json.decodeFromJsonElement(RawArtistMap.serializer(), element)
    }
}

@Serializable
data class RawMoreInfo(
    @Serializable(with = LenientStringSerializer::class)
    val album_id: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    @Serializable(with = LenientStringSerializer::class)
    val duration: String = "",
    /**
     * Whether a 320kbps rendition exists, as `"true"`/`"false"` or boolean.
     */
    @SerialName("320kbps")
    @Serializable(with = LenientStringSerializer::class)
    val has320: String = "",
    @SerialName("primary_artists")
    val primaryArtistsStr: String = "",
    val singers: String = "",
    @Serializable(with = LenientArtistMapSerializer::class)
    @SerialName("artist_map")
    val artistMap: RawArtistMap = RawArtistMap(),
) {
    val supports320: Boolean
        get() = has320.equals("true", ignoreCase = true) || has320 == "1"
}

/** A decoded CDN URL and the bitrate it will really deliver. */
data class SaavnStream(val url: String, val kbps: Int?)

/**
 * Select the highest JioSaavn CDN rendition without losing URL parameters.
 *
 * The encrypted URL normally names the 96 kbps file and the catalogue states
 * whether the parallel 320 kbps object exists. CDN URLs are not guaranteed to
 * end at `.mp4`: cache-busting or access query parameters may follow it. The
 * old end-anchored match missed those URLs, left `_96.mp4` in place, and still
 * reported 320 upstream. Playback and Downloads consequently displayed 320
 * while both fetched the 96 kbps object.
 */
internal fun selectBestSaavnStream(decryptedUrl: String, supports320: Boolean): SaavnStream? {
    if (decryptedUrl.isBlank()) return null
    val rendition = Regex(
        "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
        RegexOption.IGNORE_CASE,
    ).find(decryptedUrl)
        // An unrecognised URL must not be labelled 320 merely because the
        // catalogue says that rendition exists; no rewrite was actually made.
        ?: return SaavnStream(decryptedUrl, null)

    val offered = rendition.groupValues[1].toInt()
    if (offered == 320) return SaavnStream(decryptedUrl, 320)
    if (!supports320) return SaavnStream(decryptedUrl, offered)

    val extension = rendition.groupValues[2]
    return SaavnStream(
        decryptedUrl.replaceRange(rendition.range, "_320.$extension"),
        320,
    )
}

@Serializable
data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    /** JioSaavn sends this as the string `"1"` or `"0"` or number. */
    @SerialName("explicit_content")
    @Serializable(with = LenientStringSerializer::class)
    val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo()
) {
    val isExplicit: Boolean
        get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
data class RawSearchResponse(
    val results: List<RawSongItem> = emptyList()
)

@Serializable
data class RawSongsResponse(
    val songs: List<RawSongItem> = emptyList()
)

/**
 * Put uncensored catalogue rows ahead of their clean duplicates.
 *
 * Top-level so the pure ordering rule remains unit-testable without
 * initialising Android's Base64-backed service singleton on the JVM.
 */
internal fun prioritizeExplicit(songs: List<RawSongItem>): List<RawSongItem> =
    songs.sortedByDescending { it.isExplicit }

object JioSaavnService {
    private const val TAG = "BitChord"
    
    // https://www.jiosaavn.com/api.php
    private val BASE_URL = String(Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=", Base64.DEFAULT), Charsets.UTF_8)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Note: BitChord uses OkHttp engine for ktor
    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 6_000
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 6_000
            }
            defaultRequest {
                url(BASE_URL)
                headers.append(HttpHeaders.Accept, "application/json, text/plain, */*")
                headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                headers.append("X-Forwarded-For", "49.36.0.1")
                headers.append("X-Real-IP", "49.36.0.1")
                headers.append("Accept-Language", "en-IN,en;q=0.9,bn;q=0.8,hi;q=0.7")
                headers.append(HttpHeaders.Cookie, "explicit_content=1")
            }
            expectSuccess = false
        }
    }

    private fun decryptUrl(encryptedUrl: String): String {
        val trimmed = encryptedUrl.trim()
        if (trimmed.isBlank()) return ""
        return try {
            val key = "38346591" // DES 8-byte key
            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decodedBytes = Base64.decode(trimmed, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            TrackLog.e(TAG, "JioSaavn URL decryption failed", e)
            ""
        }
    }

    /**
     * The best CDN URL this track really has, and the bitrate it will deliver.
     *
     * The rewrite to `_320` is conditional on [RawMoreInfo.supports320] rather
     * than applied to everything. Rewriting unconditionally is not an upgrade —
     * the CDN has no 320 rendition to serve for a track that hasn't got one —
     * and the old code did it anyway *and* then reported a flat 320 upstream.
     * A 96kbps stream advertised as 320 clears
     * [SourceResolver.worthSwapping][com.music.bitchord.data.sources.SourceResolver]
     * against YouTube's 160kbps Opus, so the listener was swapped down to a
     * third of the bitrate on a line that claimed twice YouTube's.
     */
    private fun bestStream(encryptedUrl: String, supports320: Boolean): SaavnStream? {
        val decryptedUrl = decryptUrl(encryptedUrl)
        return selectBestSaavnStream(decryptedUrl, supports320)
    }

    suspend fun searchSongs(query: String): List<RawSongItem> = runCatching {
        val response = client.get("") {
            parameter("__call", "search.getResults")
            parameter("_format", "json")
            parameter("_marker", "0")
            parameter("api_version", "4")
            parameter("ctx", "web6dot0")
            parameter("q", query)
            parameter("p", "1")
            parameter("n", "10")
        }

        if (response.status != HttpStatusCode.OK) {
            TrackLog.w(TAG, "Saavn search failed: HTTP ${response.status.value}")
            return@runCatching emptyList()
        }

        val rawText = response.bodyAsText().trim()
        val jsonText = if (rawText.startsWith("{") || rawText.startsWith("[")) {
            rawText
        } else {
            val start = rawText.indexOf('{')
            if (start != -1) rawText.substring(start) else rawText
        }
        val body = json.decodeFromString<RawSearchResponse>(jsonText)
        body.results
    }.getOrElse {
        TrackLog.w(TAG, "Saavn search error: ${it.message}")
        emptyList()
    }

    suspend fun getStreamUrl(saavnSongId: String): SaavnStream? {
        val result = runCatching {
            val response = client.get("") {
                parameter("__call", "song.getDetails")
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("api_version", "4")
                parameter("ctx", "web6dot0")
                parameter("pids", saavnSongId)
            }

            if (response.status != HttpStatusCode.OK) {
                TrackLog.w(TAG, "Saavn getDetails failed: HTTP ${response.status.value}")
                return@runCatching null
            }

            // `song.getDetails` does not answer with the `{"songs":[…]}`
            // envelope `search.getResults` uses. It answers with a map keyed by
            // the id that was asked for — `{"5xceLLNe":{…}}` — so decoding it
            // as [RawSongsResponse] found no `songs` key, produced an empty
            // list under `ignoreUnknownKeys`, and returned null without an
            // error or an exception to explain itself. Both shapes are read
            // here so neither endpoint changing its mind breaks the other.
            val rawText = response.bodyAsText().trim()
            val jsonText = if (rawText.startsWith("{") || rawText.startsWith("[")) {
                rawText
            } else {
                val start = rawText.indexOf('{')
                if (start != -1) rawText.substring(start) else rawText
            }
            val root = json.parseToJsonElement(jsonText) as? JsonObject
                ?: return@runCatching null
            val songElement = (root["songs"] as? JsonArray)?.firstOrNull()
                ?: root[saavnSongId]
                ?: root.values.firstOrNull { it is JsonObject }
                ?: run {
                    TrackLog.w(TAG, "Saavn getDetails held no song for $saavnSongId")
                    return@runCatching null
                }
            val rawSong = json.decodeFromJsonElement(RawSongItem.serializer(), songElement)

            bestStream(rawSong.moreInfo.encryptedMediaUrl, rawSong.moreInfo.supports320)
        }
        return result.onFailure { TrackLog.w(TAG, "Saavn getDetails error: ${it.message}") }.getOrNull()
    }
}
