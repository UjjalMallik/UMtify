package com.music.bitchord.data.stats

import android.content.Context
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What the Replay knows about an artist beyond their name: their picture and
 * their page.
 *
 * ## Why any of this needs looking up
 *
 * Listening is counted per *name*, because a name is the only thing every track
 * carries — see [ListeningStats]. That is enough to rank artists and nothing
 * else. Two things the artist chart wants are simply not in the listening:
 *
 *  - **A picture of the artist.** Every track carries its own sleeve, and using
 *    that gave an artist chart illustrated with album covers — the same cover as
 *    the song chart above it, which reads as the page having drawn the wrong
 *    list rather than as a deliberate choice.
 *  - **A page to open.** A browse id only rides along when the row that queued
 *    the track happened to have one, which for a home-feed card or an AutoPlay
 *    suggestion it does not.
 *
 * Both come from a YouTube Music artist search, which this app is already
 * talking to constantly. Each artist is asked about once and the answer is kept
 * on this device for good, so the cost is a couple of requests the first time
 * somebody new turns up.
 */
object ArtistFacts {

    private lateinit var file: File

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** artist (lowercased) → what is known about them. */
    private val known = ConcurrentHashMap<String, StoredArtist>()

    /** Names already queued this session, so a track on repeat asks once. */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    private val requests = Channel<String>(Channel.UNLIMITED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var dirty = false

    /**
     * Bumped whenever a lookup lands.
     *
     * The Replay reads these facts once, while it is building its charts, so a
     * picture that arrives a second after the page opened would otherwise not
     * appear until the page was opened again — which for a page opened a few
     * times a year means never. Watching this lets it rebuild.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        scope.launch {
            load()
            worker()
        }
        // Writes are on a timer rather than one per answer. The cache holds
        // every artist ever played, so rewriting it after each lookup meant a
        // few hundred kilobytes per artist during a backfill — for a file that
        // is only read once, at launch.
        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                save()
            }
        }
    }

    private val ready: Boolean get() = this::file.isInitialized

    // ── Reading ─────────────────────────────────────────────────────────────
    //
    // None of these reach the network. The Replay is drawn from what is already
    // known, and an artist that isn't yet simply keeps the track's sleeve.

    /** A picture of the artist, or null to fall back to whatever the row has. */
    fun imageFor(artist: String): String? = known[key(artist)]?.image

    /** The artist's page, so a chart row opens it without searching first. */
    fun browseIdFor(artist: String): String? = known[key(artist)]?.browseId

    /**
     * An artist was played. Looks them up if anything about them is missing.
     *
     * Called from the recording path rather than from the Replay page, so the
     * answers accumulate quietly while music plays and the page has them in hand
     * when it opens.
     */
    fun noticed(artist: String) {
        if (!ready) return
        val key = key(artist)
        if (key.isEmpty() || key.length > MAX_NAME_LENGTH) return
        val entry = known[key]
        if (entry != null && !entry.wants()) return
        if (!queued.add(key)) return
        requests.trySend(artist.trim())
    }

    /** Whether the artist still needs its picture and page looked up. */
    private fun StoredArtist.wants(): Boolean =
        browseId == null &&
            System.currentTimeMillis() - cardAt > TimeUnit.DAYS.toMillis(RETRY_DAYS)

    // ── The lookups ─────────────────────────────────────────────────────────

    /**
     * One artist at a time, spaced out.
     *
     * Serial and slow on purpose: this is background enrichment for a page that
     * may not be opened for months, and it shares the app's HTTP client and the
     * Innertube session with playback. Nothing here is worth a millisecond of a
     * stream's latency.
     */
    private suspend fun worker() {
        for (name in requests) {
            val existing = known[key(name)]
            if (existing?.browseId == null) {
                runCatching { fetchCard(name) }
                    .onFailure { Log.w(TAG, "Artist lookup failed for $name", it) }
            }
            // Published straight away so an open Replay picks the answer up;
            // the disk copy follows on its own timer, above.
            _revision.value++
            delay(REQUEST_SPACING_MS)
        }
    }

    /**
     * The artist's picture and page, off a YouTube Music artist search.
     *
     * A search rather than a browse, because a browse needs the id and the id is
     * half of what this is for. The top artist hit for a name is the artist:
     * that is the same lookup the app already makes to find a video's catalogue
     * release, and the same one a person would make.
     */
    private suspend fun fetchCard(name: String) {
        val hit = YtMusicRepository.search(name, SearchFilter.ARTISTS).getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.firstOrNull()
            ?.item
        val entry = known[key(name)] ?: StoredArtist(key = key(name))
        known[key(name)] = entry.copy(
            // Only when the hit is actually this artist. A search for a name
            // nobody has heard of still returns *something*, and filing a
            // stranger's photograph under someone else's name is worse than
            // keeping the sleeve.
            image = hit?.thumbnailUrl?.takeIf { hit.title.equals(name, ignoreCase = true) }
                ?: entry.image,
            browseId = hit?.browseId?.takeIf { hit.title.equals(name, ignoreCase = true) }
                ?: entry.browseId,
            cardAt = System.currentTimeMillis(),
        )
        dirty = true
    }

    private fun key(artist: String): String = artist.trim().lowercase(Locale.ROOT)

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun load() {
        if (!ready || !file.exists()) return
        runCatching {
            json.decodeFromString(Stored.serializer(), file.readText())
        }.onSuccess { stored ->
            stored.artists.forEach { known[it.key] = it }
        }.onFailure {
            Log.w(TAG, "Discarding unreadable artist cache", it)
            file.delete()
        }
    }

    private fun save() {
        if (!ready || !dirty) return
        dirty = false
        runCatching {
            val stored = Stored(
                artists = known.values
                    .sortedByDescending { it.cardAt }
                    .take(MAX_ARTISTS),
            )
            file.writeText(json.encodeToString(Stored.serializer(), stored))
        }.onFailure { Log.w(TAG, "Could not write artist cache", it) }
    }

    @Serializable
    private data class Stored(val version: Int = 2, val artists: List<StoredArtist> = emptyList())

    @Serializable
    data class StoredArtist(
        val key: String,
        val image: String? = null,
        val browseId: String? = null,
        val cardAt: Long = 0L,
    )

    private const val TAG = "BitChordArtists"
    private const val FILE_NAME = "artist_facts.json"
    private const val MAX_NAME_LENGTH = 120
    private const val MAX_ARTISTS = 4_000
    private const val REQUEST_SPACING_MS = 1_500L
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val RETRY_DAYS = 14L
}
