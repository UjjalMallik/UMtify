package com.music.bitchord.data

import com.music.bitchord.data.ArtistAvatarCache.ArtistEnrichment
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Active metadata & Home discovery engine backed by enabled Group 3 extensions
 * (Spotify Web, Apple Music, Bugs Music, Genie Music, Melon Music) as well as Deezer.
 *
 * Supplies:
 *  - High-resolution official circular Artist Portraits & canonical names for [ArtistAvatarCache]
 *    (Home "Your Top Artists", NowPlayingScreen, Replay).
 *  - Deduplicated track metadata & genre/mood tags for Smart Mix & Local->Online Smart Bridge.
 *  - Async cached Charts & Discovery shelf for HomeScreen with zero shimmer lag.
 */
object MetadataDiscoveryProvider {

    data class TrackEnrichment(
        val title: String,
        val artist: String,
        val albumName: String? = null,
        val genre: String? = null,
        val artworkUrl: String? = null,
        val source: String,
    )

    private const val CHART_CACHE_TTL_MS = 30 * 60 * 1000L

    private val trackCache = ConcurrentHashMap<String, TrackEnrichment>()
    private val resolvedVideoIds = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedChartShelf: HomeShelf? = null

    @Volatile
    private var cachedChartTimestamp: Long = 0L

    @Volatile
    private var cachedProviderSignature: String = ""

    private val httpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .callTimeout(5500, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun providerSignature(): String = buildString {
        if (ExtensionRepoManager.isMetadataProviderEnabled("spotify")) append("sp,")
        if (ExtensionRepoManager.isMetadataProviderEnabled("apple")) append("am,")
        if (ExtensionRepoManager.isMetadataProviderEnabled("deezer")) append("dz,")
        if (ExtensionRepoManager.isMetadataProviderEnabled("bugs")) append("bg,")
        if (ExtensionRepoManager.isMetadataProviderEnabled("genie")) append("gn,")
        if (ExtensionRepoManager.isMetadataProviderEnabled("melon")) append("ml,")
    }

    /**
     * Fetches official artist portrait & canonical artist name from enabled metadata providers
     * (Spotify Web -> Apple Music -> Deezer) in parallel on [Dispatchers.IO].
     */
    suspend fun enrichArtist(artistName: String): ArtistEnrichment? = withContext(Dispatchers.IO) {
        val clean = artistName.trim()
        if (clean.isEmpty()) return@withContext null

        val useSpotify = ExtensionRepoManager.isMetadataProviderEnabled("spotify")
        val useApple = ExtensionRepoManager.isMetadataProviderEnabled("apple")
        val useDeezer = ExtensionRepoManager.isMetadataProviderEnabled("deezer")

        if (!useSpotify && !useApple && !useDeezer) return@withContext null

        withTimeoutOrNull(3000L) {
            coroutineScope {
                val spotifyJob: Deferred<ArtistEnrichment?>? = if (useSpotify) async<ArtistEnrichment?> { runCatching { querySpotifyArtist(clean) }.getOrNull() } else null
                val appleJob: Deferred<ArtistEnrichment?>? = if (useApple) async<ArtistEnrichment?> { runCatching { queryAppleArtist(clean) }.getOrNull() } else null
                val deezerJob: Deferred<ArtistEnrichment?>? = if (useDeezer) async<ArtistEnrichment?> { runCatching { queryDeezerArtist(clean) }.getOrNull() } else null

                spotifyJob?.await()?.takeIf { !it.portraitUrl.isNullOrBlank() }
                    ?: appleJob?.await()?.takeIf { !it.portraitUrl.isNullOrBlank() }
                    ?: deezerJob?.await()?.takeIf { !it.portraitUrl.isNullOrBlank() }
                    ?: appleJob?.await()
                    ?: deezerJob?.await()
            }
        }
    }

    /**
     * Enriches track title/artist/album/genre/cover via enabled metadata providers.
     */
    suspend fun enrichTrack(title: String, artist: String): TrackEnrichment? = withContext(Dispatchers.IO) {
        val key = "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"
        if (key == "|") return@withContext null
        trackCache[key]?.let { return@withContext it }

        val useApple = ExtensionRepoManager.isMetadataProviderEnabled("apple")
        val useDeezer = ExtensionRepoManager.isMetadataProviderEnabled("deezer")
        if (!useApple && !useDeezer) return@withContext null

        val hit = withTimeoutOrNull(2500L) {
            coroutineScope {
                val apple: Deferred<TrackEnrichment?>? = if (useApple) async<TrackEnrichment?> { runCatching { queryAppleTrack(title, artist) }.getOrNull() } else null
                val deezer: Deferred<TrackEnrichment?>? = if (useDeezer) async<TrackEnrichment?> { runCatching { queryDeezerTrack(title, artist) }.getOrNull() } else null
                apple?.await() ?: deezer?.await()
            }
        }
        if (hit != null) {
            trackCache[key] = hit
        }
        hit
    }

    fun getCachedChartShelf(): HomeShelf? {
        val sig = providerSignature()
        if (sig.isEmpty() || sig != cachedProviderSignature) return null
        return cachedChartShelf
    }

    /**
     * Refreshes the cached Charts & Discovery shelf asynchronously from enabled Group 3
     * metadata/chart providers (Apple Music, Deezer, Spotify Web, Melon, Bugs, Genie).
     */
    suspend fun refreshChartDiscoveryShelf(): HomeShelf? = withContext(Dispatchers.IO) {
        val sig = providerSignature()
        if (sig.isEmpty()) {
            cachedChartShelf = null
            cachedProviderSignature = ""
            return@withContext null
        }
        val now = System.currentTimeMillis()
        val existing = cachedChartShelf
        if (existing != null && sig == cachedProviderSignature && now - cachedChartTimestamp < CHART_CACHE_TTL_MS) {
            return@withContext existing
        }

        val useApple = ExtensionRepoManager.isMetadataProviderEnabled("apple")
        val useDeezer = ExtensionRepoManager.isMetadataProviderEnabled("deezer")
        val useKoreanCharts = ExtensionRepoManager.isMetadataProviderEnabled("melon") ||
            ExtensionRepoManager.isMetadataProviderEnabled("bugs") ||
            ExtensionRepoManager.isMetadataProviderEnabled("genie")

        val rawTracks: List<ChartCandidate> = coroutineScope {
            val tasks = mutableListOf<Deferred<List<ChartCandidate>>>()
            if (useApple) {
                tasks += async<List<ChartCandidate>> { runCatching { fetchAppleTopCharts() }.getOrDefault(emptyList()) }
            }
            if (useDeezer) {
                tasks += async<List<ChartCandidate>> { runCatching { fetchDeezerCharts() }.getOrDefault(emptyList()) }
            }
            if (useKoreanCharts) {
                tasks += async<List<ChartCandidate>> { runCatching { fetchKoreanChartSuggestions() }.getOrDefault(emptyList()) }
            }
            tasks.awaitAll().flatten()
        }

        val deduped = deduplicateTracks(rawTracks).take(15)
        if (deduped.isEmpty()) return@withContext cachedChartShelf

        // Resolve playable YouTube videoIds for top chart tracks in parallel (short timeout)
        val shelfItems = coroutineScope {
            deduped.map { candidate ->
                async {
                    val cacheKey = "${candidate.title.lowercase(Locale.ROOT)}|${candidate.artist.lowercase(Locale.ROOT)}"
                    val videoId = candidate.videoId
                        ?: resolvedVideoIds[cacheKey]
                        ?: withTimeoutOrNull(1800L) {
                            runCatching {
                                YtMusicRepository.searchPage("${candidate.title} ${candidate.artist}", SearchFilter.SONGS)
                                    .getOrNull()?.rows
                                    ?.mapNotNull { sr -> (sr as? SearchResult.Track)?.song ?: (sr as? SearchResult.TopTrack)?.song }
                                    ?.firstOrNull()
                                    ?.videoId
                            }.getOrNull()
                        }?.also { resolvedVideoIds[cacheKey] = it }

                    val genreSuffix = candidate.genre?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    ShelfItem(
                        title = candidate.title,
                        subtitle = "${candidate.artist}$genreSuffix",
                        thumbnailUrl = candidate.artworkUrl,
                        videoId = videoId,
                        browseId = null,
                    )
                }
            }.awaitAll()
        }

        val sourcesUsed = buildList {
            if (useApple) add("Apple Music")
            if (useDeezer) add("Deezer")
            if (ExtensionRepoManager.isMetadataProviderEnabled("spotify")) add("Spotify Web")
            if (ExtensionRepoManager.isMetadataProviderEnabled("melon")) add("Melon")
            if (ExtensionRepoManager.isMetadataProviderEnabled("bugs")) add("Bugs")
            if (ExtensionRepoManager.isMetadataProviderEnabled("genie")) add("Genie")
        }

        val subtitle = if (sourcesUsed.isNotEmpty()) {
            "Charts & Discovery • ${sourcesUsed.take(3).joinToString(" · ")}"
        } else {
            "Trending Charts & Discovery"
        }

        val shelf = HomeShelf(
            title = "Charts & Discovery",
            subtitle = subtitle,
            items = shelfItems,
        )
        cachedChartShelf = shelf
        cachedChartTimestamp = System.currentTimeMillis()
        cachedProviderSignature = sig
        shelf
    }

    private data class ChartCandidate(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val genre: String? = null,
        val videoId: String? = null,
    )

    private fun deduplicateTracks(candidates: List<ChartCandidate>): List<ChartCandidate> {
        val seen = HashSet<String>()
        val result = ArrayList<ChartCandidate>(candidates.size)
        for (item in candidates) {
            val cleanTitle = cleanComparisonKey(item.title)
            val cleanArtist = cleanComparisonKey(item.artist.substringBefore(',').substringBefore('&'))
            if (cleanTitle.isBlank()) continue
            val sig = "$cleanTitle|$cleanArtist"
            if (seen.add(sig)) {
                result.add(item)
            }
        }
        return result
    }

    private fun cleanComparisonKey(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), "")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private suspend fun querySpotifyArtist(artistName: String): ArtistEnrichment? {
        val token = runCatching { SpotifyToken.accessToken() }.getOrNull() ?: return null
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$encoded&type=artist&limit=3")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string().orEmpty()
            val items = JSONObject(body).optJSONObject("artists")?.optJSONArray("items") ?: return@use null
            val best = findBestJsonArtist(items, artistName) ?: return@use null
            val images = best.optJSONArray("images")
            val portrait = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            val name = best.optString("name").takeIf { it.isNotBlank() }
            if (portrait == null && name == null) null else ArtistEnrichment(portrait, name)
        }
    }

    private suspend fun queryAppleArtist(artistName: String): ArtistEnrichment? {
        val appleArt = runCatching { AppleArtistArtRepository.artFor(artistName) }.getOrNull()
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val req = Request.Builder()
            .url("https://itunes.apple.com/search?term=$encoded&entity=musicArtist&limit=3")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        val canonical = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val results = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return@use null
                findBestItunesArtist(results, artistName)?.optString("artistName")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        val hero = appleArt?.heroUrl?.takeIf { it.isNotBlank() }
        return if (hero != null || canonical != null) {
            ArtistEnrichment(portraitUrl = hero, canonicalName = canonical)
        } else {
            null
        }
    }

    private fun queryDeezerArtist(artistName: String): ArtistEnrichment? {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val req = Request.Builder()
            .url("https://api.deezer.com/search/artist?q=$encoded&limit=3")
            .header("User-Agent", "UMtify/1.0")
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string().orEmpty()
            val data = JSONObject(body).optJSONArray("data") ?: return@use null
            val best = findBestJsonArtist(data, artistName) ?: return@use null
            val pic = best.optString("picture_xl").takeIf { it.isNotBlank() }
                ?: best.optString("picture_big").takeIf { it.isNotBlank() }
                ?: best.optString("picture_medium").takeIf { it.isNotBlank() }
            val name = best.optString("name").takeIf { it.isNotBlank() }
            if (pic == null && name == null) null else ArtistEnrichment(portraitUrl = pic?.replace("\\/", "/"), canonicalName = name)
        }
    }

    private fun queryAppleTrack(title: String, artist: String): TrackEnrichment? {
        val encoded = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://itunes.apple.com/search?term=$encoded&entity=song&limit=3")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val results = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return@use null
            if (results.length() == 0) return@use null
            val obj = results.optJSONObject(0) ?: return@use null
            val art = obj.optString("artworkUrl100")
                .takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
            TrackEnrichment(
                title = obj.optString("trackName").ifBlank { title },
                artist = obj.optString("artistName").ifBlank { artist },
                albumName = obj.optString("collectionName").takeIf { it.isNotBlank() },
                genre = obj.optString("primaryGenreName").takeIf { it.isNotBlank() },
                artworkUrl = art,
                source = "Apple Music",
            )
        }
    }

    private fun queryDeezerTrack(title: String, artist: String): TrackEnrichment? {
        val encoded = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://api.deezer.com/search?q=$encoded&limit=3")
            .header("User-Agent", "UMtify/1.0")
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return@use null
            if (data.length() == 0) return@use null
            val obj = data.optJSONObject(0) ?: return@use null
            val albumObj = obj.optJSONObject("album")
            val artistObj = obj.optJSONObject("artist")
            val art = albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
            TrackEnrichment(
                title = obj.optString("title_short").ifBlank { obj.optString("title").ifBlank { title } },
                artist = artistObj?.optString("name")?.ifBlank { artist } ?: artist,
                albumName = albumObj?.optString("title")?.takeIf { it.isNotBlank() },
                artworkUrl = art?.replace("\\/", "/"),
                source = "Deezer",
            )
        }
    }

    private fun fetchAppleTopCharts(): List<ChartCandidate> {
        val req = Request.Builder()
            .url("https://itunes.apple.com/us/rss/topsongs/limit=12/json")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val feed = JSONObject(resp.body?.string().orEmpty()).optJSONObject("feed") ?: return@use emptyList()
            val entries = feed.optJSONArray("entry") ?: return@use emptyList()
            buildList {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val title = entry.optJSONObject("im:name")?.optString("label").orEmpty().trim()
                    val artist = entry.optJSONObject("im:artist")?.optString("label").orEmpty().trim()
                    val images = entry.optJSONArray("im:image")
                    val rawArt = images?.optJSONObject((images.length() - 1).coerceAtLeast(0))?.optString("label").orEmpty()
                    val art = rawArt.replace(Regex("""/\d+x\d+bb"""), "/600x600bb").takeIf { it.isNotBlank() }
                    val genre = entry.optJSONObject("category")?.optJSONObject("attributes")?.optString("label")?.takeIf { it.isNotBlank() }
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        add(ChartCandidate(title = title, artist = artist, artworkUrl = art, genre = genre))
                    }
                }
            }
        }
    }

    private fun fetchDeezerCharts(): List<ChartCandidate> {
        val req = Request.Builder()
            .url("https://api.deezer.com/chart/0/tracks?limit=12")
            .header("User-Agent", "UMtify/1.0")
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val title = obj.optString("title_short").ifBlank { obj.optString("title") }.trim()
                    val artist = obj.optJSONObject("artist")?.optString("name").orEmpty().trim()
                    val album = obj.optJSONObject("album")
                    val art = album?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("cover_big")?.takeIf { it.isNotBlank() }
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        add(ChartCandidate(title = title, artist = artist, artworkUrl = art?.replace("\\/", "/")))
                    }
                }
            }
        }
    }

    private suspend fun fetchKoreanChartSuggestions(): List<ChartCandidate> {
        val rows = runCatching {
            YtMusicRepository.searchPage("K-Pop Top 100 Melon Bugs Genie", SearchFilter.SONGS).getOrNull()?.rows.orEmpty()
        }.getOrDefault(emptyList())
        return rows.mapNotNull { sr ->
            val song = (sr as? SearchResult.Track)?.song ?: (sr as? SearchResult.TopTrack)?.song ?: return@mapNotNull null
            ChartCandidate(
                title = song.title,
                artist = song.artist,
                artworkUrl = song.thumbnailUrl,
                genre = "K-Pop Charts",
                videoId = song.videoId,
            )
        }.take(8)
    }

    private fun findBestJsonArtist(items: JSONArray, wanted: String): JSONObject? {
        val normWanted = wanted.trim().lowercase(Locale.ROOT)
        var fallback: JSONObject? = null
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim().lowercase(Locale.ROOT)
            if (name == normWanted) return obj
            if (fallback == null && (name.contains(normWanted) || normWanted.contains(name))) {
                fallback = obj
            }
        }
        return fallback ?: items.optJSONObject(0)
    }

    private fun findBestItunesArtist(items: JSONArray, wanted: String): JSONObject? {
        val normWanted = wanted.trim().lowercase(Locale.ROOT)
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            if (obj.optString("artistName").trim().lowercase(Locale.ROOT) == normWanted) return obj
        }
        return items.optJSONObject(0)
    }
}
