package com.music.bitchord.playback.smart

import android.content.Context
import android.os.SystemClock
import com.music.bitchord.data.LanguageTrackFilter
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.download.Downloads
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.playback.MAX_QUEUED_AUTOPLAY
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.youtubeSeedFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * Intelligent music recommendation and AutoPlay radio engine.
 *
 * Provides:
 *  - Multi-seed radio blending: seeds on the current track plus up to 2 preceding
 *    recent tracks and top artists for seamless musical progression.
 *  - Smart affinity scoring: ranks candidates by listening affinity, play count boost,
 *    and recency weighting (<7 days).
 *  - Skip penalty: tracks skipped quickly (<15s) receive a heavy penalty and are suppressed.
 *  - Deduplication: strict filtering against existing queue and identical audio/video cuts.
 *  - Personalized Home Feed shelf generation based on real listening stats.
 */
object RecommendationEngine {

    private const val SKIP_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours
    private val recentSkips = ConcurrentHashMap<String, Long>()

    /**
     * Records an early skipped track (< 15s play time) to penalize it in future recommendations.
     */
    fun recordSkip(videoId: String) {
        if (videoId.isNotBlank()) {
            recentSkips[videoId] = SystemClock.elapsedRealtime()
        }
    }

    /**
     * True if this track was recently skipped early by the listener.
     */
    fun isRecentlySkipped(videoId: String): Boolean {
        val at = recentSkips[videoId] ?: return false
        if (SystemClock.elapsedRealtime() - at > SKIP_EXPIRY_MS) {
            recentSkips.remove(videoId)
            return false
        }
        return true
    }

    /**
     * Loads, multi-seed blends, scores, and resolves the next AutoPlay queue batch.
     */
    suspend fun loadAutoplayTracks(
        existing: List<Song>,
        seedSong: Song,
        limit: Int = MAX_QUEUED_AUTOPLAY,
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val primarySeed = youtubeSeedFor(seedSong) ?: return@withContext Result.success(emptyList())

        // 1. Multi-seed blending: current seed + up to 2 other recent tracks from queue
        val recentSeeds = mutableListOf(seedSong)
        for (song in existing.asReversed()) {
            if (recentSeeds.size >= 3) break
            if (recentSeeds.none { QueueBuilder.isSameRecording(it, song) }) {
                recentSeeds.add(song)
            }
        }

        // Extract up to 2 distinct seed artists
        val seedArtists = recentSeeds.flatMap { QueueBuilder.artistSet(it.artist) }.take(2).toSet()

        // 2. Fetch primary radio recommendations
        val primaryCandidates = YtMusicRepository.radio(primarySeed).getOrElse {
            return@withContext Result.failure(it)
        }

        val allCandidates = mutableListOf<Song>()
        allCandidates.addAll(primaryCandidates)

        // If primary returned few candidates and a secondary seed is available, blend secondary radio
        if (allCandidates.size < 15 && recentSeeds.size > 1) {
            val secondarySeedId = youtubeSeedFor(recentSeeds[1])
            if (secondarySeedId != null && secondarySeedId != primarySeed) {
                YtMusicRepository.radio(secondarySeedId).onSuccess { secondaryList ->
                    for (candidate in secondaryList) {
                        if (allCandidates.none { QueueBuilder.isSameRecording(it, candidate) }) {
                            allCandidates.add(candidate)
                        }
                    }
                }
            }
        }

        // 2.5 Strict Language Filter for Explore categories or language seeds
        val category = seedSong.playbackSource.orEmpty()
        val isBengaliExplore = LanguageTrackFilter.isBengaliCategory(category) ||
            (seedSong.playbackSourceType == PlaybackSourceType.EXPLORE && LanguageTrackFilter.isBengali(seedSong.title, seedSong.artist))
        val isHindiExplore = LanguageTrackFilter.isHindiCategory(category) ||
            (seedSong.playbackSourceType == PlaybackSourceType.EXPLORE && LanguageTrackFilter.isHindi(seedSong.title, seedSong.artist))

        var languageFiltered = when {
            isBengaliExplore -> allCandidates.filter { LanguageTrackFilter.isBengali(it.title, it.artist, it.albumName) }
            isHindiExplore -> allCandidates.filter { LanguageTrackFilter.isHindi(it.title, it.artist, it.albumName) }
            else -> allCandidates
        }

        if (languageFiltered.size < limit && (isBengaliExplore || isHindiExplore)) {
            val query = if (isBengaliExplore) "Bengali songs ${seedSong.artist}" else "Hindi songs ${seedSong.artist}"
            val extraTracks = runCatching {
                YtMusicRepository.searchPage(query, SearchFilter.SONGS).getOrNull()?.rows.orEmpty()
            }.getOrDefault(emptyList()).mapNotNull { sr ->
                (sr as? SearchResult.Track)?.song ?: (sr as? SearchResult.TopTrack)?.song
            }
            val cleanExtra = if (isBengaliExplore) {
                extraTracks.filter { LanguageTrackFilter.isBengali(it.title, it.artist, it.albumName) }
            } else {
                extraTracks.filter { LanguageTrackFilter.isHindi(it.title, it.artist, it.albumName) }
            }
            languageFiltered = (languageFiltered + cleanExtra).distinctBy { it.videoId }
        }

        // 3. Affinity scoring
        val scoredCandidates = scoreAndRankCandidates(languageFiltered, seedArtists)

        // 4. Extend queue with artist caps & deduplication
        val extra = QueueBuilder.extend(existing, scoredCandidates, limit)
        if (extra.isEmpty()) return@withContext Result.success(emptyList())

        Result.success(extra.map {
            it.copy(
                radioName = seedSong.radioName,
                playbackSource = seedSong.playbackSource,
                playbackSourceType = seedSong.playbackSourceType,
                playbackSourceId = seedSong.playbackSourceId,
            ).asQueueEntry(QueueTier.AUTOPLAY)
        })
    }

    /**
     * Scores candidates using positional rank, artist affinity, play count boost, and skip penalty.
     */
    private suspend fun scoreAndRankCandidates(
        candidates: List<Song>,
        seedArtists: Set<String>,
    ): List<Song> {
        // Query recent listening summary for affinity weighting
        val summary = runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull()
        val playCountsBySong = summary?.songs?.associate { it.song.videoId to it.plays }.orEmpty()
        val playCountsByArtist = summary?.artists?.associate { it.title.lowercase(Locale.ROOT) to it.plays }.orEmpty()

        return candidates
            .mapIndexed { index, song ->
                var score = (100.0 - (index * 2.0)).coerceAtLeast(10.0)

                val songArtists = QueueBuilder.artistSet(song.artist)

                // Seed artist affinity boost (+30)
                if (songArtists.any { it in seedArtists }) {
                    score += 30.0
                }

                // Play count boost from user's history (up to +35)
                val trackPlays = playCountsBySong[song.videoId] ?: 0
                if (trackPlays > 0) {
                    score += min(trackPlays * 8.0, 25.0)
                }

                val artistPlays = songArtists.mapNotNull { playCountsByArtist[it] }.maxOrNull() ?: 0
                if (artistPlays > 0) {
                    score += min(artistPlays * 3.0, 20.0)
                }

                // Skip penalty: heavily downrank tracks skipped within the last 24h (-60)
                if (isRecentlySkipped(song.videoId)) {
                    score -= 60.0
                }

                // Video cut penalty: prefer album audio cuts over official music videos
                if (song.isVideo) {
                    score -= 20.0
                }

                song to score
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // ── Time-of-Day Smart Mix & Local-to-Online Smart Bridge ─────────────

    @Serializable
    data class TimeOfDayLogEntry(
        val trackId: String,
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        val hourOfDay: Int,
        val timestamp: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class LocalPlayRecord(
        val id: String,
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        val localUri: String? = null,
        var plays: Int = 0,
        var lastPlayed: Long = 0L,
    )

    @Serializable
    private data class BridgeCacheData(
        val timestamp: Long,
        val shelfTitle: String,
        val items: List<ShelfItem>,
    )

    enum class TimeSlot(
        val title: String,
        val subtitle: String,
        val startHour: Int,
        val endHour: Int,
    ) {
        MORNING("Morning Energy", "Start your day with high-vibe tunes", 5, 11),
        AFTERNOON("Afternoon Flow", "Keep your daytime momentum going", 12, 16),
        EVENING("Evening Chill", "Unwind and relax after a busy day", 17, 21),
        LATE_NIGHT("Your Late Night Mix", "Atmospheric sounds for the late hours", 22, 4);
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var baseDir: File? = null

    private val timeOfDayLogs = CopyOnWriteArrayList<TimeOfDayLogEntry>()
    private val localPlayMap = ConcurrentHashMap<String, LocalPlayRecord>()

    private var cachedBridgeShelf: HomeShelf? = null
    private var cachedBridgeTimestamp: Long = 0L
    private const val BRIDGE_CACHE_DURATION_MS = 6 * 60 * 60 * 1000L // 6 hours

    @Volatile
    private var currentTimeOfDayTracks: List<Song> = emptyList()

    @Volatile
    private var cachedTimeOfDayShelf: HomeShelf? = null

    @Volatile
    private var cachedTopArtistsShelf: HomeShelf? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "recommendations").apply { mkdirs() }
        baseDir = dir
        scope.launch {
            loadPersistedLogs(dir)
            refreshSmartShelves(context)
        }
    }

    private fun loadPersistedLogs(dir: File) {
        runCatching {
            val timeLogFile = File(dir, "time_of_day_log.json")
            if (timeLogFile.exists()) {
                val list = json.decodeFromString<List<TimeOfDayLogEntry>>(timeLogFile.readText())
                timeOfDayLogs.clear()
                timeOfDayLogs.addAll(list.takeLast(500))
            }

            val localPlayFile = File(dir, "local_plays.json")
            if (localPlayFile.exists()) {
                val list = json.decodeFromString<List<LocalPlayRecord>>(localPlayFile.readText())
                list.forEach { localPlayMap[it.id] = it }
            }

            val bridgeFile = File(dir, "bridge_cache.json")
            if (bridgeFile.exists()) {
                val cache = json.decodeFromString<BridgeCacheData>(bridgeFile.readText())
                if (System.currentTimeMillis() - cache.timestamp < BRIDGE_CACHE_DURATION_MS) {
                    cachedBridgeTimestamp = cache.timestamp
                    cachedBridgeShelf = HomeShelf(
                        title = cache.shelfTitle,
                        subtitle = "Local → Online",
                        items = cache.items,
                    )
                }
            }
        }
    }

    /**
     * Records a track played for > 30 seconds into the lightweight time-of-day listening log.
     */
    fun record30SecondPlay(song: Song) {
        if (song.videoId.isBlank()) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val entry = TimeOfDayLogEntry(
            trackId = song.videoId,
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            hourOfDay = hour,
        )
        timeOfDayLogs.add(entry)
        if (timeOfDayLogs.size > 500) {
            timeOfDayLogs.removeAt(0)
        }

        // Also track local track plays if it's local
        if (song.localUri != null || song.localPath != null || song.videoId.startsWith("local:")) {
            recordLocalPlay(song)
        }

        scope.launch {
            baseDir?.let { dir ->
                runCatching {
                    val file = File(dir, "time_of_day_log.json")
                    file.writeText(json.encodeToString(timeOfDayLogs.toList().takeLast(300)))
                }
            }
        }
    }

    /**
     * Tracks play counts for local/offline tracks in local storage.
     */
    fun recordLocalPlay(song: Song) {
        val key = song.localUri ?: song.localPath ?: song.videoId
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        val record = localPlayMap.getOrPut(key) {
            LocalPlayRecord(
                id = key,
                title = song.title,
                artist = song.artist,
                artworkUrl = song.thumbnailUrl,
                localUri = song.localUri ?: song.localPath,
            )
        }
        record.plays++
        record.lastPlayed = now

        scope.launch {
            baseDir?.let { dir ->
                runCatching {
                    val file = File(dir, "local_plays.json")
                    file.writeText(json.encodeToString(localPlayMap.values.toList()))
                }
            }
        }
    }

    fun getCurrentTimeSlot(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): TimeSlot = when (hour) {
        in 5..11 -> TimeSlot.MORNING
        in 12..16 -> TimeSlot.AFTERNOON
        in 17..21 -> TimeSlot.EVENING
        else -> TimeSlot.LATE_NIGHT
    }

    fun isTimeOfDayTitle(title: String): Boolean {
        val trimmed = title.trim()
        return TimeSlot.values().any { it.title.equals(trimmed, ignoreCase = true) }
    }

    fun getTimeOfDayTracks(): List<Song> = currentTimeOfDayTracks

    /**
     * Synchronously returns whatever smart shelves are ready in memory/cache
     * so that Home screen loads with ZERO delay.
     */
    fun getSmartShelves(context: Context): List<HomeShelf> {
        val result = mutableListOf<HomeShelf>()
        // 1. Time-of-Day Smart Mix
        cachedTimeOfDayShelf?.let { result.add(it) }
        // 2. Your Top Artists
        cachedTopArtistsShelf?.let { result.add(it) }
        // 3. Local-to-Online Smart Bridge (from memory/disk cache)
        if (cachedBridgeShelf != null && System.currentTimeMillis() - cachedBridgeTimestamp < BRIDGE_CACHE_DURATION_MS) {
            cachedBridgeShelf?.let { result.add(it) }
        }
        // 4. Native Apple Music Charts & Deezer/Spotify Artist Discovery shelves (cached in memory, zero shimmer lag)
        result.addAll(com.music.bitchord.data.MetadataDiscoveryProvider.getCachedHomeDiscoveryShelves())
        return result
    }

    suspend fun refreshSmartShelves(context: Context) = withContext(Dispatchers.IO) {
        cachedTimeOfDayShelf = generateTimeOfDayShelf(context)
        cachedTopArtistsShelf = generateTopArtistsShelf(context)
        val needsBridgeUpdate = cachedBridgeShelf == null ||
            (System.currentTimeMillis() - cachedBridgeTimestamp >= BRIDGE_CACHE_DURATION_MS)
        if (needsBridgeUpdate && !AppSettings.isOffline) {
            cachedBridgeShelf = generateLocalToOnlineBridgeShelf(context)
        }
        if (!AppSettings.isOffline) {
            runCatching {
                com.music.bitchord.data.MetadataDiscoveryProvider.refreshHomeDiscoveryShelves()
            }
        }
    }

    /**
     * Background refresh for Smart Shelves (e.g. queries YouTube for Local-to-Online bridge if expired).
     */
    fun refreshSmartShelvesAsync(context: Context, onNewShelves: (List<HomeShelf>) -> Unit) {
        scope.launch {
            refreshSmartShelves(context)
            val smart = getSmartShelves(context)
            withContext(Dispatchers.Main) {
                onNewShelves(smart)
            }
        }
    }

    /**
     * Generates active Time-of-Day mix shelf.
     */
    suspend fun generateTimeOfDayShelf(context: Context): HomeShelf? = withContext(Dispatchers.IO) {
        val slot = getCurrentTimeSlot()
        val inSlotEntries = timeOfDayLogs.filter { entry ->
            when (slot) {
                TimeSlot.MORNING -> entry.hourOfDay in 5..11
                TimeSlot.AFTERNOON -> entry.hourOfDay in 12..16
                TimeSlot.EVENING -> entry.hourOfDay in 17..21
                TimeSlot.LATE_NIGHT -> entry.hourOfDay >= 22 || entry.hourOfDay <= 4
            }
        }

        val trackCountMap = inSlotEntries.groupingBy { it.trackId }.eachCount()
        val sortedSlotEntries = inSlotEntries.distinctBy { it.trackId }
            .sortedByDescending { trackCountMap[it.trackId] ?: 0 }

        val mixSongs = mutableListOf<Song>()
        val seenTrackIds = mutableSetOf<String>()
        val seenSignatures = mutableSetOf<String>()

        fun tryAddSong(song: Song): Boolean {
            val trackId = song.videoId.trim()
            if (trackId.isBlank()) return false
            val normTitle = song.title.lowercase(Locale.ROOT).trim()
            val normArtist = song.artist.lowercase(Locale.ROOT).trim()
            val signature = "$normTitle|$normArtist"

            if (seenTrackIds.contains(trackId) || (normTitle.isNotBlank() && seenSignatures.contains(signature))) {
                return false
            }

            seenTrackIds.add(trackId)
            if (normTitle.isNotBlank()) {
                seenSignatures.add(signature)
            }
            mixSongs.add(song)
            return true
        }

        sortedSlotEntries.forEach { entry ->
            if (mixSongs.size < 15) {
                tryAddSong(
                    Song(
                        videoId = entry.trackId,
                        title = entry.title,
                        artist = entry.artist,
                        thumbnailUrl = entry.artworkUrl,
                    ),
                )
            }
        }

        // Fallback gracefully if history in this window is still small (< 5 tracks)
        if (mixSongs.size < 5) {
            val recentSongs = runCatching {
                ListeningStats.summary(ReplayPeriod.THIS_MONTH)?.songs?.map { it.song }.orEmpty()
            }.getOrDefault(emptyList())

            val extraCandidates = recentSongs + AudioCache.cachedSongs().map { it.song }
            for (candidate in extraCandidates) {
                if (mixSongs.size >= 15) break
                tryAddSong(candidate)
            }
        }

        if (mixSongs.isEmpty()) {
            val localSongs = runCatching {
                LocalMediaRepository.getLocalMusic(context).take(15)
            }.getOrDefault(emptyList())
            for (local in localSongs) {
                if (mixSongs.size >= 10) break
                tryAddSong(local)
            }
        }

        if (mixSongs.isEmpty()) return@withContext null

        currentTimeOfDayTracks = mixSongs

        val leadTrack = mixSongs.firstOrNull()
        val enrichment = if (leadTrack != null && !AppSettings.isOffline) {
            runCatching {
                com.music.bitchord.data.MetadataDiscoveryProvider.enrichTrack(leadTrack.title, leadTrack.artist)
            }.getOrNull()
        } else {
            null
        }
        val leadCover = mixSongs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            ?: enrichment?.artworkUrl
            ?: leadTrack?.thumbnailUrl
        val moodDetail = enrichment?.genre?.takeIf { it.isNotBlank() }?.let { "$it • ${slot.subtitle}" } ?: slot.subtitle

        HomeShelf(
            title = slot.title,
            subtitle = moodDetail,
            items = listOf(
                ShelfItem(
                    title = slot.title,
                    subtitle = "${mixSongs.size} songs • $moodDetail",
                    thumbnailUrl = leadCover,
                    videoId = mixSongs.firstOrNull()?.videoId,
                    browseId = null,
                ),
            ),
        )
    }

    /**
     * Generates "Your Top Artists" row from online and local listening history.
     */
    suspend fun generateTopArtistsShelf(context: Context): HomeShelf? = withContext(Dispatchers.IO) {
        val artistPlays = mutableMapOf<String, Int>()
        val artistArtwork = mutableMapOf<String, String?>()
        val artistBrowseIds = mutableMapOf<String, String?>()

        // 1. From ListeningStats
        runCatching {
            val summary = ListeningStats.summary(ReplayPeriod.ALL_TIME)
            summary?.artists?.forEach { entry ->
                val name = entry.title.trim()
                if (name.isNotBlank()) {
                    val key = name.lowercase(Locale.ROOT)
                    artistPlays[key] = (artistPlays[key] ?: 0) + entry.plays
                    if (artistArtwork[key].isNullOrBlank()) artistArtwork[key] = entry.artworkUrl
                    if (artistBrowseIds[key].isNullOrBlank()) artistBrowseIds[key] = entry.browseId
                }
            }
        }

        // 2. From Local play records & local music
        localPlayMap.values.forEach { record ->
            val name = record.artist.trim()
            if (name.isNotBlank() && !name.equals("Unknown Artist", ignoreCase = true)) {
                val key = name.lowercase(Locale.ROOT)
                artistPlays[key] = (artistPlays[key] ?: 0) + record.plays + 1
                if (artistArtwork[key].isNullOrBlank()) artistArtwork[key] = record.artworkUrl
            }
        }

        if (artistPlays.isEmpty()) {
            runCatching {
                val local = LocalMediaRepository.getLocalMusic(context)
                local.forEach { song ->
                    val name = song.artist.trim()
                    if (name.isNotBlank() && !name.equals("Unknown Artist", ignoreCase = true)) {
                        val key = name.lowercase(Locale.ROOT)
                        artistPlays[key] = (artistPlays[key] ?: 0) + 1
                        if (artistArtwork[key].isNullOrBlank()) artistArtwork[key] = song.thumbnailUrl
                    }
                }
            }
        }

        if (artistPlays.isEmpty()) return@withContext null

        val topKeys = artistPlays.entries
            .sortedByDescending { it.value }
            .take(15)
            .map { it.key }

        val shelfItems = topKeys.map { key ->
            val fallbackName = key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            val displayName = com.music.bitchord.data.ArtistAvatarCache.getDisplayName(key) ?: fallbackName
            val officialAvatar = com.music.bitchord.data.ArtistAvatarCache.get(key)
                ?: com.music.bitchord.data.stats.ArtistFacts.imageFor(key)
            if (officialAvatar == null) {
                com.music.bitchord.data.ArtistAvatarCache.resolveInBackground(key)
            }
            val bestAvatar = officialAvatar ?: artistArtwork[key]
            val browseId = artistBrowseIds[key]
                ?: com.music.bitchord.data.ArtistAvatarCache.getBrowseId(key)
                ?: com.music.bitchord.data.stats.ArtistFacts.browseIdFor(key)

            ShelfItem(
                title = displayName,
                subtitle = "Artist",
                thumbnailUrl = bestAvatar,
                videoId = null,
                browseId = browseId,
            )
        }

        HomeShelf(
            title = "Your Top Artists",
            subtitle = "Based on your listening history",
            items = shelfItems,
        )
    }

    /**
     * Local-to-Online Smart Bridge: "Because you play [Local Song] often"
     */
    suspend fun generateLocalToOnlineBridgeShelf(context: Context): HomeShelf? = withContext(Dispatchers.IO) {
        if (AppSettings.isOffline) return@withContext null

        if (cachedBridgeShelf != null && System.currentTimeMillis() - cachedBridgeTimestamp < BRIDGE_CACHE_DURATION_MS) {
            return@withContext cachedBridgeShelf
        }

        // Find top played local track
        val topLocal = localPlayMap.values.maxByOrNull { it.plays }
            ?: runCatching {
                val downloads = Downloads.getDownloadedSongs(context)
                downloads.firstOrNull { it.title.isNotBlank() }?.let {
                    LocalPlayRecord(
                        id = it.videoId,
                        title = it.title,
                        artist = it.artist,
                        artworkUrl = it.thumbnailUrl,
                        localUri = it.localUri,
                        plays = 3,
                    )
                }
            }.getOrNull()
            ?: runCatching {
                val local = LocalMediaRepository.getLocalMusic(context)
                local.firstOrNull { it.title.isNotBlank() }?.let {
                    LocalPlayRecord(
                        id = it.localUri ?: it.videoId,
                        title = it.title,
                        artist = it.artist,
                        artworkUrl = it.thumbnailUrl,
                        localUri = it.localUri,
                        plays = 1,
                    )
                }
            }.getOrNull()

        if (topLocal == null) return@withContext null

        val cleanTitle = cleanSearchTerm(topLocal.title)
        val cleanArtist = cleanSearchTerm(topLocal.artist)
        val metaEnrichment = runCatching {
            com.music.bitchord.data.MetadataDiscoveryProvider.enrichTrack(cleanTitle, cleanArtist)
        }.getOrNull()
        val query = listOfNotNull(
            metaEnrichment?.title ?: cleanTitle,
            metaEnrichment?.artist ?: cleanArtist,
            metaEnrichment?.genre?.takeIf { it.isNotBlank() },
        ).joinToString(" ").trim()
        if (query.isBlank()) return@withContext null

        val seenSignatures = HashSet<String>()
        val candidates = runCatching {
            YtMusicRepository.searchPage(query, SearchFilter.SONGS).getOrNull()?.rows.orEmpty()
        }.getOrDefault(emptyList()).mapNotNull { sr ->
            (sr as? SearchResult.Track)?.song ?: (sr as? SearchResult.TopTrack)?.song
        }.filter { song ->
            val sig = "${song.title.lowercase(Locale.ROOT).trim()}|${song.artist.lowercase(Locale.ROOT).trim()}"
            song.title.isNotBlank() && song.videoId != topLocal.id && seenSignatures.add(sig)
        }

        if (candidates.isEmpty()) return@withContext null

        val bridgeSubtitle = metaEnrichment?.genre?.takeIf { it.isNotBlank() }?.let { "Local → Online • $it" } ?: "Local → Online"
        val shelfItems = candidates.take(12).map { song ->
            ShelfItem(
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                videoId = song.videoId,
                browseId = null,
            )
        }

        val shelf = HomeShelf(
            title = "Because you play ${metaEnrichment?.title ?: topLocal.title}",
            subtitle = bridgeSubtitle,
            items = shelfItems,
        )

        cachedBridgeShelf = shelf
        cachedBridgeTimestamp = System.currentTimeMillis()

        baseDir?.let { dir ->
            runCatching {
                val file = File(dir, "bridge_cache.json")
                file.writeText(
                    json.encodeToString(
                        BridgeCacheData(
                            timestamp = cachedBridgeTimestamp,
                            shelfTitle = shelf.title,
                            items = shelfItems,
                        ),
                    ),
                )
            }
        }

        shelf
    }

    private fun cleanSearchTerm(text: String): String {
        return text
            .replace(Regex("(?i)\\.(mp3|flac|m4a|wav|aac|opus|ogg)$"), "")
            .replace(Regex("(?i)\\s*[\\[(](official video|official audio|lyrics|audio|video|hq|hd)[\\])]"), "")
            .trim()
    }
}
