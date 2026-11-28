package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    data class MetadataProviderStatus(
        val ready: Boolean = false,
        val latencyMs: Long = 0L,
        val enrichedCount: Int = 0,
        val error: String? = null,
    )

    private const val CHART_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val PREFS_NAME = "metadata_discovery_prefs"
    private const val KEY_APPLE_ENABLED = "apple_enabled"
    private const val KEY_SPOTIFY_DEEZER_ENABLED = "spotify_deezer_enabled"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _appleEnabled = MutableStateFlow(true)
    val appleEnabled: StateFlow<Boolean> = _appleEnabled.asStateFlow()

    private val _spotifyDeezerEnabled = MutableStateFlow(true)
    val spotifyDeezerEnabled: StateFlow<Boolean> = _spotifyDeezerEnabled.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _appleEnabled.value = p.getBoolean(KEY_APPLE_ENABLED, true)
        _spotifyDeezerEnabled.value = p.getBoolean(KEY_SPOTIFY_DEEZER_ENABLED, true)
    }

    fun setAppleEnabled(enabled: Boolean) {
        _appleEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_APPLE_ENABLED, enabled)?.apply()
    }

    fun setSpotifyDeezerEnabled(enabled: Boolean) {
        _spotifyDeezerEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SPOTIFY_DEEZER_ENABLED, enabled)?.apply()
    }

    private val trackCache = ConcurrentHashMap<String, TrackEnrichment>()
    private val resolvedVideoIds = ConcurrentHashMap<String, String>()
    private val resolvedArtistBrowseIds = ConcurrentHashMap<String, String>()

    private val _providerStates = MutableStateFlow<Map<String, MetadataProviderStatus>>(emptyMap())
    val providerStates: StateFlow<Map<String, MetadataProviderStatus>> = _providerStates.asStateFlow()

    @Volatile
    private var cachedChartShelf: HomeShelf? = null

    @Volatile
    private var cachedAppleChartsShelf: HomeShelf? = null

    @Volatile
    private var cachedDeezerArtistsShelf: HomeShelf? = null

    @Volatile
    private var cachedChartTimestamp: Long = 0L

    @Volatile
    private var cachedProviderSignature: String = ""

    @Volatile
    private var lastProbeTimestampMs: Long = 0L

    @Volatile
    private var lastProbeSignature: String = ""

    private const val HEALTH_CACHE_TTL_MS = 10 * 60 * 1000L

    private val httpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .callTimeout(5500, TimeUnit.MILLISECONDS)
            .build()
    }

    fun providerKeyFor(idOrLabel: String): String {
        val lower = idOrLabel.lowercase(Locale.ROOT)
        return when {
            "apple" in lower -> "apple"
            "spotify" in lower -> "spotify"
            "deezer" in lower -> "deezer"
            else -> lower
        }
    }

    fun statusForProvider(extensionId: String, label: String = ""): MetadataProviderStatus? {
        val states = _providerStates.value
        val key = providerKeyFor("$extensionId $label")
        return states[key]
    }

    private fun recordProviderReady(key: String, latencyMs: Long, itemsDelta: Int = 0) {
        _providerStates.update { current ->
            val prev = current[key]
            val nextCount = (prev?.enrichedCount ?: 0) + itemsDelta.coerceAtLeast(0)
            current + (
                key to MetadataProviderStatus(
                    ready = true,
                    latencyMs = latencyMs.coerceAtLeast(1L),
                    enrichedCount = nextCount,
                    error = null,
                )
            )
        }
    }

    private fun recordProviderError(key: String, reason: String) {
        _providerStates.update { current ->
            val prev = current[key]
            current + (
                key to MetadataProviderStatus(
                    ready = false,
                    latencyMs = prev?.latencyMs ?: 0L,
                    enrichedCount = prev?.enrichedCount ?: 0,
                    error = reason.trim().ifBlank { "Offline" }.take(40),
                )
            )
        }
    }

    /**
     * Probes enabled Group 3 Metadata & Home Discovery providers concurrently on [Dispatchers.IO]
     * using lightweight keyless public endpoints so `Sources` displays real-time status & counters.
     */
    suspend fun probeMetadataProviders(force: Boolean = false) = withContext(Dispatchers.IO) {
        val sig = providerSignature()
        val now = System.currentTimeMillis()
        if (!force && sig == lastProbeSignature && (now - lastProbeTimestampMs) < HEALTH_CACHE_TTL_MS && _providerStates.value.isNotEmpty()) {
            return@withContext
        }
        lastProbeSignature = sig
        lastProbeTimestampMs = now

        coroutineScope {
            val jobs = mutableListOf<Deferred<Unit>>()
            if (_appleEnabled.value) {
                jobs += async {
                    val start = System.currentTimeMillis()
                    val ok = runCatching {
                        val req = Request.Builder()
                            .url("https://itunes.apple.com/search?term=The+Weeknd&entity=musicArtist&limit=1")
                            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
                            .build()
                        httpClient.newCall(req).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                    if (ok) recordProviderReady("apple", System.currentTimeMillis() - start)
                    else recordProviderError("apple", "Timeout")
                }
            }
            if (_spotifyDeezerEnabled.value) {
                jobs += async {
                    val start = System.currentTimeMillis()
                    val ok = runCatching {
                        querySpotifyKeylessStatus() || queryDeezerArtist("The Weeknd")?.portraitUrl?.isNotBlank() == true
                    }.getOrDefault(false)
                    val latency = System.currentTimeMillis() - start
                    if (ok) {
                        recordProviderReady("spotify", latency)
                        recordProviderReady("deezer", latency)
                    } else {
                        recordProviderError("spotify", "Timeout")
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    private fun providerSignature(): String = buildString {
        if (_spotifyDeezerEnabled.value) append("sp,dz,")
        if (_appleEnabled.value) append("am,")
    }

    /**
     * Fetches official artist portrait & canonical artist name from enabled native metadata providers
     * (Spotify Web -> Apple Music -> Deezer) in parallel on [Dispatchers.IO].
     */
    suspend fun enrichArtist(artistName: String): ArtistEnrichment? = withContext(Dispatchers.IO) {
        val clean = artistName.trim()
        if (clean.isEmpty()) return@withContext null

        val useSpotify = _spotifyDeezerEnabled.value
        val useApple = _appleEnabled.value
        val useDeezer = _spotifyDeezerEnabled.value

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
     * Enriches track title/artist/album/genre/cover via enabled native metadata providers.
     */
    suspend fun enrichTrack(title: String, artist: String): TrackEnrichment? = withContext(Dispatchers.IO) {
        val key = "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"
        if (key == "|") return@withContext null
        trackCache[key]?.let { return@withContext it }

        val useApple = _appleEnabled.value
        val useDeezer = _spotifyDeezerEnabled.value
        if (!useApple && !useDeezer) return@withContext null

        val hit = withTimeoutOrNull(2500L) {
            coroutineScope {
                val apple: Deferred<TrackEnrichment?>? = if (useApple) async<TrackEnrichment?> { runCatching { queryAppleTrack(title, artist) }.getOrNull() } else null
                val deezer: Deferred<TrackEnrichment?>? = if (useDeezer) async<TrackEnrichment?> { runCatching { queryDeezerTrack(title, artist) }.getOrNull() } else null
                val lrcFallback = async<TrackEnrichment?> { runCatching { queryLrclibTrackMetadata(title, artist) }.getOrNull() }
                apple?.await() ?: deezer?.await() ?: lrcFallback.await()
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
        return cachedAppleChartsShelf ?: cachedChartShelf
    }

    /**
     * Returns all cached Home discovery shelves for enabled native providers:
     *  - "Top Charts • Apple Music" when Apple Music is enabled
     *  - "Trending Artists • Deezer" when Spotify & Deezer are enabled
     */
    fun getCachedHomeDiscoveryShelves(): List<HomeShelf> {
        val sig = providerSignature()
        if (sig.isEmpty() || sig != cachedProviderSignature) return emptyList()
        return buildList {
            if (_appleEnabled.value) {
                (cachedAppleChartsShelf ?: cachedChartShelf)?.takeIf { it.items.isNotEmpty() }?.let(::add)
            }
            if (_spotifyDeezerEnabled.value) {
                cachedDeezerArtistsShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)
            }
        }
    }

    suspend fun refreshHomeDiscoveryShelves(): List<HomeShelf> = withContext(Dispatchers.IO) {
        refreshChartDiscoveryShelf()
        getCachedHomeDiscoveryShelves()
    }

    /**
     * Refreshes the cached Apple Music Charts & Deezer Trending Artists shelves asynchronously
     * from enabled native metadata/chart providers.
     */
    suspend fun refreshChartDiscoveryShelf(): HomeShelf? = withContext(Dispatchers.IO) {
        val sig = providerSignature()
        if (sig.isEmpty()) {
            cachedChartShelf = null
            cachedAppleChartsShelf = null
            cachedDeezerArtistsShelf = null
            cachedProviderSignature = ""
            return@withContext null
        }
        val now = System.currentTimeMillis()
        if (sig == cachedProviderSignature && now - cachedChartTimestamp < CHART_CACHE_TTL_MS &&
            (cachedAppleChartsShelf != null || cachedDeezerArtistsShelf != null)
        ) {
            return@withContext cachedAppleChartsShelf ?: cachedChartShelf
        }

        val useApple = _appleEnabled.value
        val useDeezer = _spotifyDeezerEnabled.value

        val (appleCandidates, deezerTracks, deezerArtists) = coroutineScope {
            val appleTask = if (useApple) {
                async<List<ChartCandidate>> { runCatching { fetchAppleTopCharts() }.getOrDefault(emptyList()) }
            } else null
            val deezerTracksTask = if (useDeezer) {
                async<List<ChartCandidate>> { runCatching { fetchDeezerCharts() }.getOrDefault(emptyList()) }
            } else null
            val deezerArtistsTask = if (useDeezer) {
                async<List<ArtistChartCandidate>> { runCatching { fetchDeezerPopularArtists() }.getOrDefault(emptyList()) }
            } else null
            Triple(
                appleTask?.await().orEmpty(),
                deezerTracksTask?.await().orEmpty(),
                deezerArtistsTask?.await().orEmpty(),
            )
        }

        val appleShelf = if (useApple) {
            val topApple = deduplicateTracks(appleCandidates).take(15)
            if (topApple.isNotEmpty()) {
                val appleItems = resolvePlayableChartItems(topApple)
                if (appleItems.isNotEmpty()) {
                    HomeShelf(
                        title = "Top Charts • Apple Music",
                        subtitle = "Apple Music Most-Played",
                        items = appleItems,
                    )
                } else null
            } else null
        } else null

        val deezerArtistShelf = if (useDeezer) {
            val topArtists = deezerArtists.distinctBy { it.name.trim().lowercase(Locale.ROOT) }.take(15)
            if (topArtists.isNotEmpty()) {
                val artistItems = coroutineScope {
                    topArtists.map { artist ->
                        async {
                            val key = artist.name.trim().lowercase(Locale.ROOT)
                            val browseId = resolvedArtistBrowseIds[key]
                                ?: withTimeoutOrNull(1800L) {
                                    runCatching {
                                        YtMusicRepository.searchPage(artist.name, SearchFilter.ARTISTS)
                                            .getOrNull()?.rows
                                            ?.mapNotNull { (it as? SearchResult.Browse)?.item }
                                            ?.firstOrNull()
                                            ?.browseId
                                    }.getOrNull()
                                }?.also { resolvedArtistBrowseIds[key] = it }

                            ArtistAvatarCache.put(
                                artist = artist.name,
                                photoUrl = artist.portraitUrl,
                                browseId = browseId,
                            )

                            ShelfItem(
                                title = artist.name,
                                subtitle = "Artist • Popular on Deezer",
                                thumbnailUrl = artist.portraitUrl,
                                browseId = browseId,
                                videoId = null,
                            )
                        }
                    }.awaitAll()
                }.filter { !it.browseId.isNullOrBlank() || !it.thumbnailUrl.isNullOrBlank() }

                if (artistItems.isNotEmpty()) {
                    HomeShelf(
                        title = "Trending Artists • Deezer",
                        subtitle = "Popular on Deezer",
                        items = artistItems,
                    )
                } else null
            } else null
        } else null

        // Preserve fallback combined chart shelf if Apple yielded nothing while Deezer tracks succeeded.
        val combinedFallbackShelf = if (appleShelf == null && useDeezer) {
            val topCombined = deduplicateTracks(deezerTracks).take(15)
            if (topCombined.isNotEmpty()) {
                val items = resolvePlayableChartItems(topCombined)
                if (items.isNotEmpty()) {
                    HomeShelf(
                        title = "Popular on Deezer",
                        subtitle = "Spotify & Deezer Charts",
                        items = items,
                    )
                } else null
            } else null
        } else null

        if (appleShelf != null) {
            cachedAppleChartsShelf = appleShelf
            cachedChartShelf = appleShelf
        } else if (!useApple) {
            cachedAppleChartsShelf = null
            cachedChartShelf = combinedFallbackShelf
        }
        if (deezerArtistShelf != null) {
            cachedDeezerArtistsShelf = deezerArtistShelf
        } else if (!useDeezer) {
            cachedDeezerArtistsShelf = null
        }
        cachedChartTimestamp = System.currentTimeMillis()
        cachedProviderSignature = sig
        cachedAppleChartsShelf ?: cachedChartShelf
    }

    private suspend fun resolvePlayableChartItems(candidates: List<ChartCandidate>): List<ShelfItem> =
        coroutineScope {
            candidates.map { candidate ->
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
        }.filter { !it.videoId.isNullOrBlank() || !it.thumbnailUrl.isNullOrBlank() }

    private data class ArtistChartCandidate(
        val name: String,
        val portraitUrl: String?,
    )

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
        val start = System.currentTimeMillis()
        val token = runCatching { SpotifyToken.accessToken() }.getOrNull()
        if (!token.isNullOrBlank()) {
            val encoded = URLEncoder.encode(artistName, "UTF-8")
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/search?q=$encoded&type=artist&limit=3")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
                .build()
            val direct = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val items = JSONObject(body).optJSONObject("artists")?.optJSONArray("items") ?: return@use null
                    val best = findBestJsonArtist(items, artistName) ?: return@use null
                    val images = best.optJSONArray("images")
                    val portrait = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                    val name = best.optString("name").takeIf { it.isNotBlank() }
                    if (portrait == null && name == null) null else ArtistEnrichment(portrait, name)
                }
            }.getOrNull()
            if (direct != null) {
                recordProviderReady("spotify", System.currentTimeMillis() - start, itemsDelta = 1)
                return direct
            }
        }
        // Keyless fallback via Deezer portrait assist when Spotify Web session cookie isn't configured
        val assisted = runCatching { queryDeezerArtist(artistName, recordAsProvider = "spotify") }.getOrNull()
        return assisted
    }

    private fun querySpotifyKeylessStatus(): Boolean {
        val req = Request.Builder()
            .url("https://open.spotify.com/oembed?url=https://open.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        return httpClient.newCall(req).execute().use { it.isSuccessful }
    }

    private suspend fun queryAppleArtist(artistName: String): ArtistEnrichment? {
        val start = System.currentTimeMillis()
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
            recordProviderReady("apple", System.currentTimeMillis() - start, itemsDelta = 1)
            ArtistEnrichment(portraitUrl = hero, canonicalName = canonical)
        } else {
            null
        }
    }

    private fun queryDeezerArtist(artistName: String, recordAsProvider: String = "deezer"): ArtistEnrichment? {
        val start = System.currentTimeMillis()
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
            if (pic == null && name == null) {
                null
            } else {
                recordProviderReady(recordAsProvider, System.currentTimeMillis() - start, itemsDelta = 1)
                ArtistEnrichment(portraitUrl = pic?.replace("\\/", "/"), canonicalName = name)
            }
        }
    }

    private fun queryAppleTrack(title: String, artist: String): TrackEnrichment? {
        val start = System.currentTimeMillis()
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
            recordProviderReady("apple", System.currentTimeMillis() - start, itemsDelta = 1)
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
        val start = System.currentTimeMillis()
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
            recordProviderReady("deezer", System.currentTimeMillis() - start, itemsDelta = 1)
            TrackEnrichment(
                title = obj.optString("title_short").ifBlank { obj.optString("title").ifBlank { title } },
                artist = artistObj?.optString("name")?.ifBlank { artist } ?: artist,
                albumName = albumObj?.optString("title")?.takeIf { it.isNotBlank() },
                artworkUrl = art?.replace("\\/", "/"),
                source = "Deezer",
            )
        }
    }

    private fun queryLrclibTrackMetadata(title: String, artist: String): TrackEnrichment? {
        if (title.isBlank()) return null
        val tEnc = URLEncoder.encode(title.trim(), "UTF-8")
        val aEnc = URLEncoder.encode(artist.trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://lrclib.net/api/search?track_name=$tEnc&artist_name=$aEnc")
            .header("User-Agent", "UMtify/1.0")
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val arr = JSONArray(resp.body?.string().orEmpty())
            val obj = arr.optJSONObject(0) ?: return@use null
            val trackName = obj.optString("trackName").ifBlank { title }
            val artistName = obj.optString("artistName").ifBlank { artist }
            val albumName = obj.optString("albumName").takeIf { it.isNotBlank() }
            TrackEnrichment(
                title = trackName,
                artist = artistName,
                albumName = albumName,
                source = "LRCLIB",
            )
        }
    }

    private fun fetchAppleTopCharts(): List<ChartCandidate> {
        val start = System.currentTimeMillis()
        // Primary: Apple Music Marketing Tools Most-Played 25 RSS API
        val rssReq = Request.Builder()
            .url("https://rss.applemarketingtools.com/api/v2/us/music/most-played/25/songs.json")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        val rssResults = runCatching {
            httpClient.newCall(rssReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val results = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("feed")
                    ?.optJSONArray("results") ?: return@use emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val obj = results.optJSONObject(i) ?: continue
                        val title = obj.optString("name").trim()
                        val artist = obj.optString("artistName").trim()
                        val art = obj.optString("artworkUrl100")
                            .replace(Regex("""/\d+x\d+bb"""), "/600x600bb")
                            .takeIf { it.isNotBlank() }
                        val genre = obj.optJSONArray("genres")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
                        if (title.isNotBlank() && artist.isNotBlank()) {
                            add(ChartCandidate(title = title, artist = artist, artworkUrl = art, genre = genre))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())

        if (rssResults.isNotEmpty()) {
            recordProviderReady("apple", System.currentTimeMillis() - start, itemsDelta = rssResults.size)
            return rssResults
        }

        // Fallback: iTunes public RSS Top Songs
        val req = Request.Builder()
            .url("https://itunes.apple.com/us/rss/topsongs/limit=25/json")
            .header("User-Agent", LosslessAudioProvider.BROWSER_USER_AGENT)
            .build()
        val fallback = httpClient.newCall(req).execute().use { resp ->
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
        if (fallback.isNotEmpty()) {
            recordProviderReady("apple", System.currentTimeMillis() - start, itemsDelta = fallback.size)
        } else {
            recordProviderError("apple", "Timeout")
        }
        return fallback
    }

    private fun fetchDeezerCharts(): List<ChartCandidate> {
        val start = System.currentTimeMillis()
        val req = Request.Builder()
            .url("https://api.deezer.com/chart/0/tracks?limit=15")
            .header("User-Agent", "UMtify/1.0")
            .build()
        val list = httpClient.newCall(req).execute().use { resp ->
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
        if (list.isNotEmpty()) {
            recordProviderReady("deezer", System.currentTimeMillis() - start, itemsDelta = list.size)
            if (_spotifyDeezerEnabled.value) {
                recordProviderReady("spotify", System.currentTimeMillis() - start, itemsDelta = list.size)
            }
        }
        return list
    }

    private fun fetchDeezerPopularArtists(): List<ArtistChartCandidate> {
        val start = System.currentTimeMillis()
        val req = Request.Builder()
            .url("https://api.deezer.com/chart/0/artists?limit=15")
            .header("User-Agent", "UMtify/1.0")
            .build()
        val artists = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val portrait = obj.optString("picture_xl").takeIf { it.isNotBlank() }
                        ?: obj.optString("picture_big").takeIf { it.isNotBlank() }
                        ?: obj.optString("picture_medium").takeIf { it.isNotBlank() }
                    if (name.isNotBlank() && portrait != null) {
                        add(ArtistChartCandidate(name = name, portraitUrl = portrait.replace("\\/", "/")))
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            recordProviderReady("deezer", System.currentTimeMillis() - start, itemsDelta = artists.size)
            if (_spotifyDeezerEnabled.value) {
                recordProviderReady("spotify", System.currentTimeMillis() - start, itemsDelta = artists.size)
            }
        }
        return artists
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
