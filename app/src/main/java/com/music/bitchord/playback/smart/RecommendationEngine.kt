package com.music.bitchord.playback.smart

import android.os.SystemClock
import com.music.bitchord.data.LanguageTrackFilter
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.playback.MAX_QUEUED_AUTOPLAY
import com.music.bitchord.playback.QueueBuilder
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.youtubeSeedFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
}
