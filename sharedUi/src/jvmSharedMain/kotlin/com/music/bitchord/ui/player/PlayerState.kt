package com.music.bitchord.ui.player

import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.music.bitchord.playback.PlaybackPosition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The looping clip some releases publish alongside their cover, for [song], or
 * null — a miss is the normal answer.
 */
@Composable
internal fun rememberCanvasArtwork(song: Song): CanvasArtwork? {
    val canvasEnabled by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by PlayerSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val prioritizeSpotifyCanvas by PlayerSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    val meteredConnection by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    // The switch turns the feature off outright; this is the narrower "not
    // over cellular" case — see [PlayerSettings.canvasOverCellular] for why a
    // clip's own loop makes that worth guarding separately from a still image.
    val canvasAllowedNow = canvasEnabled && (meteredConnection != true || canvasOverCellular)
    // Keyed on the song's identity rather than its videoId: a version switch
    // swaps the videoId for another cut of the *same* song, and a canvas is
    // searched for by title and artist — resetting here threw the clip out
    // mid-loop, blanked the player down to the still sleeve and restarted it,
    // which is the flicker. A different song keys differently and starts
    // clean; the same-titled-impostor case is guarded where the clip is
    // adopted, by [CanvasArtwork.matches].
    var canvas by remember(song.title, song.artist) { mutableStateOf<CanvasArtwork?>(null) }
    val latestAlbumName by rememberUpdatedState(song.albumName)
    LaunchedEffect(song.videoId, canvasAllowedNow, prioritizeSpotifyCanvas) {
        if (!canvasAllowedNow) {
            canvas = null
            return@LaunchedEffect
        }
        // Anything already settled for this track paints immediately: a
        // reopened player, or a track coming round again in the queue.
        //
        // Across a version switch the clip found for the last cut is carried
        // over rather than dropped — same title and artist, so the same
        // search, so the same clip — and only while it still *answers* to
        // what is playing: [CanvasArtwork.matches] is what stops a preserved
        // clip from being a different song's that happens to share a title.
        canvas = PlayerPlatform.host.cachedCanvas(song)
            ?: canvas?.takeIf { it.matches(song.title, song.artist, latestAlbumName) }

        // The album name is looked up separately and lands a moment after the
        // player opens, without cancelling/restarting this effect on backfill.
        if (canvas == null && latestAlbumName == null) delay(ALBUM_SETTLE_MS)
        // Keep what an earlier pass found if this one comes back empty, rather
        // than pulling a playing clip out from under itself.
        canvas = PlayerPlatform.host.canvasFor(song) ?: canvas
    }

    return canvas
}

/**
 * The player's cover: the one request every surface draws it from, whether it
 * has loaded, and the bounded retries after a failure.
 *
 * Everything here is keyed on the cover's [url] — see [rememberPlayerArtwork].
 */
@Stable
internal class PlayerArtwork(
    val url: String?,
    private val context: PlatformContext,
    /** The decode's longest side: the card's 800, or the banner's source size. */
    private val artPx: Int = ART_PX,
    val trackId: String? = null,
    private val previousMemoryCacheKey: String? = null,
    initiallyLoaded: Boolean = false,
) {
    /** Stable Coil memory cache key anchored to the artwork URL & target decode size. */
    val cacheKey: String? = url?.takeIf { it.isNotBlank() }?.let { "player_art:${it}_$artPx" }

    /**
     * Whether the cover is on screen. Seeded from [initiallyLoaded] when reusing
     * an already-warmed cover on app resume so Coil never blinks the frame out.
     */
    var loaded by mutableStateOf(initiallyLoaded)
        internal set

    var attempt by mutableIntStateOf(0)
        private set

    /** Latched on an error, and only upwards — cleared by the retry itself. */
    internal var failed by mutableStateOf(false)

    val request: ImageRequest by derivedStateOf {
        val attempt = attempt
        val memKey = if (attempt > 0 && cacheKey != null) "${cacheKey}_retry_$attempt" else cacheKey
        ImageRequest.Builder(context)
            .data(url)
            .size(artPx)
            .crossfade(false)
            .apply {
                if (memKey != null) {
                    memoryCacheKey(memKey)
                    placeholderMemoryCacheKey(previousMemoryCacheKey ?: memKey)
                } else if (previousMemoryCacheKey != null) {
                    placeholderMemoryCacheKey(previousMemoryCacheKey)
                }
                if (attempt > 0) memoryCacheKeyExtra("attempt", attempt.toString())
            }
            .build()
    }

    /** Fed from each painter drawing [request]. */
    fun onState(state: AsyncImagePainter.State) {
        val isLocal = url != null && (url.startsWith("file://") || url.startsWith("/"))
        if (isLocal) return
        when (state) {
            is AsyncImagePainter.State.Success -> {
                loaded = true
                failed = false
            }
            is AsyncImagePainter.State.Error -> {
                if (previousMemoryCacheKey == null) loaded = false
                failed = true
                url?.let { com.music.bitchord.ui.components.FailedArtworkCache.markFailed(it) }
            }
            is AsyncImagePainter.State.Loading -> {
                // Keep [loaded] intact when we already hold a warm placeholder in memory cache
                // (e.g., on ON_RESUME or same-track high-res swap) to prevent blank/blink frames.
                if (previousMemoryCacheKey == null && !loaded) {
                    loaded = false
                }
            }
            else -> Unit
        }
    }

    internal fun retry() {
        failed = false
        attempt++
    }
}

@Composable
internal fun rememberPlayerArtwork(
    remoteArt: String?,
    artPx: Int = ART_PX,
    trackId: String? = null,
): PlayerArtwork {
    val context = LocalPlatformContext.current
    val artUrl = remoteArt?.artworkAt(artPx)
    val trackHistory = remember(trackId) { arrayOfNulls<String>(2) } // [0]=lastUrl, [1]=lastCacheKey
    val currentKey = artUrl?.takeIf { it.isNotBlank() }?.let { "player_art:${it}_$artPx" }
    val sameTrackWarm = !trackId.isNullOrBlank() && trackHistory[0] != null && trackHistory[1] != null
    val art = remember(trackId, artUrl, artPx) {
        PlayerArtwork(
            url = artUrl,
            context = context,
            artPx = artPx,
            trackId = trackId,
            previousMemoryCacheKey = if (sameTrackWarm) trackHistory[1] else currentKey,
            initiallyLoaded = sameTrackWarm && (artUrl == trackHistory[0]),
        )
    }
    if (!artUrl.isNullOrBlank() && currentKey != null) {
        trackHistory[0] = artUrl
        trackHistory[1] = currentKey
    }
    LaunchedEffect(trackId, artUrl, art.failed) {
        // A track with no artwork at all fails immediately and would fail
        // identically three more times: there is no request to make, so there is
        // nothing a second go could do differently.
        val isLocal = artUrl != null && (artUrl.startsWith("file://") || artUrl.startsWith("local_art://") || artUrl.startsWith("content://") || artUrl.startsWith("/"))
        if (artUrl == null || isLocal || com.music.bitchord.ui.components.FailedArtworkCache.isFailed(artUrl) || !art.failed || art.attempt >= ART_RETRIES) return@LaunchedEffect
        delay(ART_RETRY_DELAY_MS)
        art.retry()
    }
    return art
}

/**
 * The seek bar's own state: a drag in progress, and where the handle was
 * dropped until the player's position catches up with it.
 */
@Stable
internal class PlayerScrub {
    var scrubbing by mutableStateOf(false)
        private set
    var value by mutableFloatStateOf(0f)
        private set

    /**
     * After releasing the scrubber the player needs to buffer before it
     * reports the new position. Kept showing where the user dropped it so the
     * handle doesn't snap back and then jump forward once loading finishes.
     */
    internal var pendingSeek by mutableStateOf<Float?>(null)

    /** Where the handle is drawn. */
    fun shown(positionMs: Long, durationMs: Long): Float {
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        return when {
            scrubbing -> value
            pendingSeek != null -> pendingSeek!!
            else -> fraction.coerceIn(0f, 1f)
        }
    }

    fun drag(to: Float) {
        scrubbing = true
        value = to
    }

    fun release(seekTo: (Float) -> Unit) {
        pendingSeek = value
        seekTo(value)
        scrubbing = false
    }
}

@Composable
internal fun rememberPlayerScrub(trackId: String, position: PlaybackPosition, durationMs: Long): PlayerScrub {
    val scrub = remember { PlayerScrub() }
    var pendingSeek by scrub::pendingSeek

    // Released as soon as the player's own position agrees with where the handle
    // was dropped — and unconditionally a few seconds later whether it agrees or
    // not.
    //
    // The agreement test alone is not enough, because it is the only thing that
    // ever cleared the override: if the position never passes close to the
    // target — a clamped or rejected seek, a rendition swapped underneath, a
    // progress sample that steps straight over the window — nothing releases it
    // and the handle sits frozen at the drop point for the rest of the track.
    // Audio and lyrics follow the real position perfectly throughout, so the
    // failure looks like a stuck seek bar on a track that is playing fine.
    //
    // Tolerance is absolute rather than a share of the duration: two percent is
    // a quarter-second on a jingle and twelve seconds on a long mix, and it is
    // the wall-clock gap that decides whether the handle appears to jump.
    //
    // Watched from inside the effect rather than keyed on the position, so the
    // screen that owns this does not have to read the playhead to drive it.
    LaunchedEffect(durationMs, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (durationMs <= 0) return@LaunchedEffect
        snapshotFlow { position.positionMs }.first { positionMs ->
            abs(positionMs - (target * durationMs).toLong()) < SEEK_SETTLE_TOLERANCE_MS
        }
        pendingSeek = null
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        pendingSeek = null
    }
    LaunchedEffect(trackId) { pendingSeek = null }

    return scrub
}

/**
 * The volume bar's state: the system output level, tweened to whatever the
 * system reports, and followed exactly under a finger.
 */
@Stable
internal class PlayerVolume(
    private val system: SystemVolume,
    private val scope: CoroutineScope,
) {
    /**
     * Animatable rather than plain state: a hardware volume step is a jump of
     * 1/15th of the bar, which reads as a stutter unless it's tweened.
     */
    val level = Animatable(system.level.value)
    var dragging by mutableStateOf(false)
        private set

    fun drag(to: Float) {
        dragging = true
        // Follow the finger exactly; only external changes tween.
        scope.launch { level.snapTo(to) }
        system.set(to)
    }

    fun release() {
        dragging = false
    }
}

@Composable
internal fun rememberPlayerVolume(): PlayerVolume {
    val system = PlayerPlatform.host.volume
    val scope = rememberCoroutineScope()
    val volume = remember(system) { PlayerVolume(system, scope) }
    // Hardware volume keys and the system panel change the level behind our
    // back; the host follows them, and so does the bar.
    val systemVolume by system.level.collectAsStateWithLifecycle()

    // Glide to the level the system reports, but never fight the finger — a
    // drag writes the level, which calls straight back through here.
    LaunchedEffect(systemVolume) {
        if (!volume.dragging) {
            volume.level.animateTo(systemVolume, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    return volume
}

