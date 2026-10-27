package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The home-screen widget: clear crystal glass with the artwork tile beside it.
 *
 * Two providers, [MediaWidgetSquare] and [MediaWidgetWide], so the picker offers
 * a square one and a full-width one — but they are the same widget, and both can
 * be resized across the whole range. What they differ in is the size they arrive
 * at. Which of the two layouts a given instance draws is decided from its
 * *measured* width ([WIDE_LAYOUT_MIN_DP]), not from which provider it came from,
 * so a square dragged out to four cells sets its title beside the artwork and
 * picks up the artist, like and progress, and a wide one squeezed back to two
 * floats its dock over the cover instead — rather than either being stuck with a
 * layout its size doesn't suit.
 *
 * Three layers, bottom to top: the artwork tile (`widget_art`), the glass face
 * (`widget_glass`, drawn by [MediaWidgetArt]) and the controls, which the layout
 * positions. The split matters — a `RemoteViews` image is stretched to whatever
 * size the host gives the view, and the host's idea of that size is a range, so
 * anything positioned *inside* a bitmap drifts into whatever is beside it. The
 * artwork is a view of its own, sized by the layout, and the text column is
 * padded clear of it at exactly its width.
 */
abstract class MediaWidget : AppWidgetProvider() {

    /**
     * The width to assume when the host hasn't said. Launchers are supposed to
     * put a size in the options bundle before the first update and most do, but
     * not all, and a widget that guessed wrong renders its face at the wrong
     * aspect ratio until something moves it. This provider's own target size is
     * the best available guess.
     */
    protected abstract val fallbackWidthDp: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    /** Fires on every resize, which is a re-render: both bitmaps are size-specific. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, intArrayOf(id))
    }

    /** A widget that has been dragged off the home screen has no last face. */
    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            lastFace.remove(id)
            WidgetWave.stop(id)
        }
    }

    override fun onDisabled(context: Context) {
        MediaWidgetArt.clear()
        lastFace.clear()
    }

    /**
     * Renders off the broadcast thread, holding the broadcast open while it runs.
     *
     * [goAsync] is what makes that legal — artwork may have to come off disk or
     * out of the network, and returning from `onUpdate` first would let the
     * process be killed mid-render. The deadline is well inside the window a
     * broadcast gets; past it the widget keeps whatever it last drew, which is
     * a better outcome than an ANR.
     */
    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        val fallback = fallbackWidthDp
        scope.launch {
            try {
                render(app, ids, fallback, SystemClock.elapsedRealtime() + BROADCAST_BUDGET_MS)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        /**
         * The measured width at which the track is worth setting beside the
         * artwork rather than floated over it.
         *
         * The split layout spends 14dp on leading padding, at least 64dp on the
         * artwork tile, 16dp of hard gap, and 130dp of deck — 38dp previous, the
         * 54dp play pill and 38dp next with no room left for the spacers — so
         * below roughly 236dp the transport would not fit.
         *
         * Set just above a four-cell span (250dp nominal) rather than at the
         * midpoint between three cells and four: cells are not really 70dp on
         * every launcher, and this is the smallest width at which every button
         * keeps the size the design gives it. Below it the compact layout reads
         * better than a split with a shrunken deck.
         */
        const val WIDE_LAYOUT_MIN_DP = 236

        /**
         * Redraws every placed widget of any kind, the 4×1 included.
         *
         * Called by
         * [PlaybackService][com.music.bitchord.playback.PlaybackService] whenever
         * what the widget shows has changed — which is on every play, pause and
         * track change, so it does nothing on the calling thread beyond handing
         * off. Even asking the system which widgets exist is a binder round trip,
         * and that thread is the one ExoPlayer runs on.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            MediaWidgetPill.refresh(app)
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                for ((provider, fallbackWidthDp) in PROVIDERS) {
                    val ids = runCatching {
                        manager.getAppWidgetIds(ComponentName(app, provider))
                    }.getOrNull()
                    if (ids == null || ids.isEmpty()) continue
                    // No deadline: nothing is being held open here. This is the
                    // hot path — it runs on every track change, play and pause —
                    // so it is also the one where starving the second widget of
                    // its render would be noticed most.
                    render(app, ids, fallbackWidthDp, deadline = null)
                }
            }
        }

        /**
         * Redraws [ids], each within its own [RENDER_TIMEOUT_MS].
         *
         * Per widget rather than across all of them, because these renders are
         * mostly network waits and one slow cover used to spend the whole budget
         * — leaving every widget after it in the list unrendered. [deadline], for
         * the [goAsync] path only, caps the total: the broadcast has a window it
         * must finish inside, and a per-widget timeout can outrun it.
         */
        private suspend fun render(
            context: Context,
            ids: IntArray,
            fallbackWidthDp: Int,
            deadline: Long?,
        ) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            // Keyed on the artwork rather than the track: two tracks off one
            // album are one picture, so moving through an album redraws nothing.
            val key = snapshot.artworkUrl ?: KEY_NO_ARTWORK
            for (id in ids) {
                val budget = when (deadline) {
                    null -> RENDER_TIMEOUT_MS
                    else -> minOf(RENDER_TIMEOUT_MS, deadline - SystemClock.elapsedRealtime())
                }
                // Out of time. The widgets left keep what they are already
                // showing, which is the same thing they would keep if their
                // render had timed out.
                if (budget <= 0L) return
                withTimeoutOrNull(budget + RENDER_TIMEOUT_MS) {
                    render(context, manager, id, snapshot, key, fallbackWidthDp)
                }
            }
        }

        private suspend fun render(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            snapshot: MediaWidgetSnapshot,
            key: String,
            fallbackWidthDp: Int,
        ) {
            val size = measure(context, manager, id, fallbackWidthDp)
            var art = MediaWidgetArt.peekArt(size.art, key)
            var face = MediaWidgetArt.peekFace(size.face, key)
            if (art == null || face == null) {
                // Push the controls now so a tap is answered in a frame; the
                // pictures catch up below. The face is pushed from what this
                // widget last drew, not from nothing: a RemoteViews replaces the
                // whole tree, so leaving widget_glass unset clears it and the
                // fallback gradient shows through until the render finishes.
                manager.push(id, views(context, id, snapshot, size, art, face ?: lastFace[id]))
            }
            if (art == null) {
                art = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    MediaWidgetArt.renderArt(context, snapshot.artworkUrl, size.art, key)
                }
            }
            // The face never touches the network — it needs the tile only for
            // its colour and, on the square widget, for the dock's frost.
            if (face == null) {
                face = MediaWidgetArt.renderFace(
                    context = context,
                    artworkUrl = snapshot.artworkUrl,
                    spec = size.face,
                    tintSource = art,
                    key = key,
                )
            }
            lastFace[id] = face
            manager.push(id, views(context, id, snapshot, size, art, face))

            // The spectrum only exists on the split layout, and only while
            // something is actually playing: WidgetWave holds a ticker, so
            // every path that isn't "playing, wide" has to stop it.
            if (size.waveWidthPx > 0 && snapshot.isPlaying && snapshot.hasTrack) {
                WidgetWave.play(
                    context = context,
                    id = id,
                    layout = R.layout.widget_media_wide,
                    viewId = R.id.widget_wave,
                    widthPx = size.waveWidthPx,
                    heightPx = size.waveHeightPx,
                )
            } else {
                WidgetWave.stop(id)
            }
        }

        // ---- views ----

        private fun views(
            context: Context,
            id: Int,
            snapshot: MediaWidgetSnapshot,
            size: WidgetSize,
            art: Bitmap?,
            face: Bitmap?,
        ): RemoteViews {
            val layout =
                if (size.wide) R.layout.widget_media_wide else R.layout.widget_media_compact
            val views = RemoteViews(context.packageName, layout)
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            face?.let { views.setImageViewBitmap(R.id.widget_glass, it) }

            views.setTextViewText(
                R.id.widget_title,
                if (snapshot.hasTrack) {
                    snapshot.title
                } else {
                    context.getString(R.string.widget_nothing_played)
                },
            )

            // Both layouts carry the title; only the wide one has the room for
            // the artist, Like and the progress row.
            if (size.wide) {
                // The tile is a view of its own, not part of the face: it is
                // drawn at the size the bitmap was composited and only its
                // origin is set here, while the deck is padded past it. Both are
                // px insets from the same card edge, so they move together.
                views.setViewPadding(
                    R.id.widget_art,
                    size.artPad.left,
                    size.artPad.top,
                    size.artPad.right,
                    size.artPad.bottom,
                )
                views.setViewPadding(
                    R.id.widget_deck,
                    (size.deckPaddingDp * context.resources.displayMetrics.density).roundToInt(),
                    0,
                    0,
                    0,
                )
                views.setTextViewText(R.id.widget_artist, snapshot.artist)
                views.setViewVisibility(
                    R.id.widget_artist,
                    if (snapshot.artist.isBlank() || !size.roomy) View.GONE else View.VISIBLE,
                )
                bindLike(views, context, snapshot)
                bindProgress(views, snapshot, size.tall)
                // Zero, not just "never drawn": a paused widget must not leave
                // the last frame of the spectrum behind. While playing, the
                // frame the ticker is on rides along — a full update drops the
                // bitmap a partial one pushed, and the progress republish must
                // not blank the spectrum for a tick.
                val playing = snapshot.isPlaying && snapshot.hasTrack
                if (playing) {
                    WidgetWave.frameFor(id, size.waveWidthPx, size.waveHeightPx)
                        ?.let { views.setImageViewBitmap(R.id.widget_wave, it) }
                }
                views.setImageAlpha(
                    R.id.widget_wave,
                    if (playing) ALPHA_ENABLED else ALPHA_HIDDEN,
                )
            }

            views.setImageViewResource(
                R.id.widget_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(
                    if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play,
                ),
            )

            // On the root, not on widget_glass: the root is the last view offered
            // a touch, so this catches every pixel that isn't a button — including
            // the artwork tile and the deck's dead space, which are over the baked
            // face and should behave like it.
            val open = openPlayer(context)
            views.setOnClickPendingIntent(R.id.widget_root, open)

            if (snapshot.hasTrack) {
                views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
                // Dimmed at the ends of the queue, matching what the media
                // notification does with the same commands unavailable.
                views.setImageAlpha(
                    R.id.widget_previous,
                    if (snapshot.hasPrevious) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setImageAlpha(
                    R.id.widget_next,
                    if (snapshot.hasNext) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setOnClickPendingIntent(
                    R.id.widget_toggle,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_previous,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_PREVIOUS),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_next,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT),
                )
            } else {
                // Nothing has ever played, so there is nothing to control. Dim the
                // transport and send every button to the app: a control that opens
                // the library is more use than one that does nothing at all.
                for (button in TRANSPORT) {
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, open)
                }
                if (size.wide) views.setOnClickPendingIntent(R.id.widget_like, open)
            }
            return views
        }

        /** The Like disc: hidden where liking isn't offered, and a toggle otherwise. */
        private fun bindLike(
            views: RemoteViews,
            context: Context,
            snapshot: MediaWidgetSnapshot,
        ) {
            views.setViewVisibility(
                R.id.widget_like,
                if (snapshot.canLike) View.VISIBLE else View.INVISIBLE,
            )
            views.setImageViewResource(
                R.id.widget_like,
                if (snapshot.isLiked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart,
            )
            views.setContentDescription(
                R.id.widget_like,
                context.getString(
                    if (snapshot.isLiked) R.string.remove_from_liked else R.string.like,
                ),
            )
        }

        /**
         * The progress row: a live elapsed clock, a static length, and the bar.
         *
         * The clock is a [android.widget.Chronometer] rather than text so it
         * ticks between widget pushes — RemoteViews cannot animate anything, but
         * the host runs a Chronometer itself. The bar is only as fresh as the
         * last push; the service republishes every so often while playing (see
         * `PlaybackService.reportProgress`) and the clock covers the rest.
         *
         * [tall] is false on a card too short to hold all three rows. The deck
         * cannot shrink its type, so the row goes rather than the transport
         * being clipped.
         */
        private fun bindProgress(views: RemoteViews, snapshot: MediaWidgetSnapshot, tall: Boolean) {
            views.setViewVisibility(
                R.id.widget_progress_block,
                if (tall) View.VISIBLE else View.GONE,
            )
            if (!tall) return

            val duration = snapshot.durationMs
            val position = snapshot.positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
            val hasDuration = duration > 0L

            views.setTextViewText(
                R.id.widget_total,
                if (hasDuration) formatTime(duration) else null,
            )
            if (hasDuration) {
                val fraction = (position * PROGRESS_MAX / duration).toInt().coerceIn(0, PROGRESS_MAX)
                views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, fraction, false)
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_progress, View.INVISIBLE)
            }

            if (snapshot.hasTrack) {
                views.setChronometer(
                    R.id.widget_elapsed,
                    SystemClock.elapsedRealtime() - position,
                    null,
                    snapshot.isPlaying && hasDuration,
                )
                views.setViewVisibility(R.id.widget_elapsed, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_elapsed, View.GONE)
            }
        }

        private fun formatTime(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0L)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes >= 60) {
                String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

        /**
         * `setImageAlpha` has no dedicated `RemoteViews` helper, but `ImageView`
         * marks it remotable, so it can be reached by name.
         */
        private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) =
            setInt(viewId, "setImageAlpha", alpha)

        /**
         * Opens the app on the full player.
         *
         * No action and no launcher category on purpose. `MainActivity` is
         * `singleTask`, and an `ACTION_MAIN`/`CATEGORY_LAUNCHER` intent aimed at
         * the root of an existing task can be treated as a launcher tap and
         * satisfied by bringing the task forward — without `onNewIntent`, which
         * is the half of the relay that runs whenever the app was already alive.
         * An explicit component with no action always delivers.
         */
        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_PLAYER,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * A host that has gone away, or one that rejects the bitmap, is not worth
         * taking the process down for — and this runs from a broadcast, where an
         * escaping exception is a crash.
         */
        private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
            runCatching { updateAppWidget(id, views) }
        }

        // ---- measuring ----

        private class WidgetSize(
            val wide: Boolean,
            val face: FaceSpec,
            val art: ArtSpec,
            /** Where the deck starts, in dp from the row's own start. */
            val deckPaddingDp: Float,
            /**
             * Where the artwork tile is drawn from, in px: the insets that put
             * the full-card ImageView's 1:1 bitmap in the right place. Empty on
             * the compact layout, whose artwork is the card.
             */
            val artPad: Rect,
            /** Enough height for the title and its artist line. */
            val roomy: Boolean,
            /** Enough height for the progress row and the spectrum as well. */
            val tall: Boolean,
            /** The spectrum view's width in px; 0 where the layout has none. */
            val waveWidthPx: Int,
            /** The spectrum view's height in px; 0 where the layout has none. */
            val waveHeightPx: Int,
        )

        /**
         * The size to draw this instance at, in bitmap pixels, and the glass
         * spec that places everything in it.
         *
         * The options bundle reports a range, not a size: the smallest the host
         * will make the widget in one orientation and the largest in the other.
         * Portrait wants min-width and max-height, landscape the reverse. Read
         * for the orientation in force now, so what is drawn matches what is on
         * screen.
         *
         * The artwork tile's edge is the card's inner height — the artwork fills
         * the card vertically, a square, which is what the layout asks for with
         * `adjustViewBounds`. It is capped by the width the deck needs, so on a
         * narrow instance the tile gives way rather than the transport.
         */
        private fun measure(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            fallbackWidthDp: Int,
        ): WidgetSize {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthDp = options?.getInt(
                if (landscape) {
                    AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                } else {
                    AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                },
            )?.takeIf { it > 0 } ?: fallbackWidthDp
            val heightDp = options?.getInt(
                if (landscape) {
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                } else {
                    AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                },
            )?.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP

            val wide = widthDp >= WIDE_LAYOUT_MIN_DP
            val resources = context.resources
            val density = resources.displayMetrics.density
            val rawWidth = widthDp * density
            val rawHeight = heightDp * density
            // Every widget update parcels this bitmap to the host, which keeps it
            // for as long as the widget is on screen. Bounded so a tablet-sized
            // instance can't hand over something absurd; at phone sizes it never
            // binds.
            val longest = maxOf(rawWidth, rawHeight)
            val scale = if (longest > MAX_BITMAP_PX) MAX_BITMAP_PX / longest else 1f
            val widthPx = (rawWidth * scale).roundToInt().coerceAtLeast(1)
            val heightPx = (rawHeight * scale).roundToInt().coerceAtLeast(1)

            fun scaled(id: Int): Float = resources.getDimension(id) * scale
            fun unscaled(id: Int): Float = resources.getDimension(id)

            val radius = scaled(R.dimen.widget_corner_radius)
            val rim = scaled(R.dimen.widget_glass_rim)

            if (wide) {
                val paddingDp = unscaled(R.dimen.widget_wide_padding) / density
                val gapDp = unscaled(R.dimen.widget_wide_gap) / density
                val availableDp = (heightDp - 2f * paddingDp).coerceAtLeast(MIN_ART_DP)
                val widthCapDp = widthDp - 2f * paddingDp - gapDp - MIN_DECK_DP
                val artDp = minOf(availableDp, widthCapDp).coerceAtLeast(MIN_ART_DP)
                val artPx = (artDp * density).roundToInt().coerceAtLeast(1)
                // The rows are hidden rather than clipped, so the height the
                // deck needs grows with the user's font size, not just with dp.
                val textScale = resources.configuration.fontScale.coerceAtLeast(1f)

                // The tile is drawn at its own size, so only its origin matters;
                // centring it vertically in a card taller than the tile is what
                // the top inset is for. In real px, not bitmap px: this places a
                // view in the host, so a scaled-down bitmap on a very large card
                // must not shrink the inset with it.
                val padPx = (paddingDp * density).roundToInt()
                val artPadPx = (artDp * density).roundToInt()
                val gapPx = (gapDp * density).roundToInt()
                val artPad = Rect(
                    padPx,
                    ((heightDp * density - artPadPx) / 2f).roundToInt().coerceAtLeast(0),
                    0,
                    0,
                )

                return WidgetSize(
                    wide = true,
                    face = FaceSpec(
                        widthPx = widthPx,
                        heightPx = heightPx,
                        radiusPx = radius,
                        rimPx = rim,
                    ),
                    art = ArtSpec(
                        widthPx = artPx,
                        heightPx = artPx,
                        cornerPx = unscaled(R.dimen.widget_wide_art_corner),
                        rimPx = unscaled(R.dimen.widget_wide_art_rim),
                    ),
                    // Only the tile's own width: the 16dp gap is a layout margin
                    // on the deck now, so it is already outside this padding.
                    deckPaddingDp = artDp,
                    artPad = artPad,
                    roomy = availableDp >= ROOMY_DECK_DP * textScale,
                    tall = availableDp >= TALL_DECK_DP * textScale,
                    // The deck spans the card's inner width less the tile and
                    // the gap; the spectrum fills it edge to edge.
                    waveWidthPx = (widthDp * density).roundToInt()
                        .minus(2 * padPx)
                        .minus(artPadPx)
                        .minus(gapPx)
                        .coerceAtLeast(0),
                    waveHeightPx = resources.getDimensionPixelSize(R.dimen.widget_wave_height),
                )
            }

            val margin = scaled(R.dimen.widget_dock_margin)
            val dockHeight = scaled(R.dimen.widget_dock_height)
            val dockBottom = (heightPx - margin).coerceAtLeast(0f)
            return WidgetSize(
                wide = false,
                face = FaceSpec(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    radiusPx = radius,
                    rimPx = rim,
                    dock = Rect(
                        margin.roundToInt(),
                        (dockBottom - dockHeight).coerceAtLeast(0f).roundToInt(),
                        (widthPx - margin).roundToInt(),
                        dockBottom.roundToInt(),
                    ),
                    dockCornerPx = scaled(R.dimen.widget_dock_radius),
                    dockRimPx = scaled(R.dimen.widget_dock_rim),
                ),
                // Full bleed: the tile is the card, rounded to the card's own
                // radius so its edge sits under the rim rather than inside it.
                art = ArtSpec(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    cornerPx = unscaled(R.dimen.widget_corner_radius),
                    rimPx = 0f,
                ),
                deckPaddingDp = 0f,
                artPad = Rect(),
                roomy = true,
                tall = true,
                waveWidthPx = 0,
                waveHeightPx = 0,
            )
        }

        // ---- constants ----

        private val PROVIDERS = listOf(
            MediaWidgetSquare::class.java to SQUARE_WIDTH_DP,
            MediaWidgetWide::class.java to WIDE_WIDTH_DP,
        )

        private val TRANSPORT =
            intArrayOf(R.id.widget_previous, R.id.widget_toggle, R.id.widget_next)

        /** The smallest the artwork tile is allowed to get before the deck gives way. */
        private const val MIN_ART_DP = 64f

        /** Below this the deck's three transport buttons stop fitting. */
        private const val MIN_DECK_DP = 132f

        /** Below this the artist line is dropped rather than clipped. */
        private const val ROOMY_DECK_DP = 90f

        /** Below this the progress row and the spectrum are dropped rather than clipped. */
        private const val TALL_DECK_DP = 118f

        /**
         * Renders run on a process-wide scope rather than one tied to a receiver:
         * a provider instance lives only for the duration of one `onReceive`, and
         * [refresh] is not called from a receiver at all.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** How long any one widget's face may take before it is given up on. */
        private const val RENDER_TIMEOUT_MS = 8_000L

        /**
         * How long all of a broadcast's widgets together may take. Comfortably
         * inside the window a broadcast is allowed to stay open.
         */
        private const val BROADCAST_BUDGET_MS = 8_000L

        /**
         * The face each widget is currently showing.
         *
         * Kept so a render that fails has something truthful to fall back on —
         * see the push in [render]. Held strongly, which is the same thing the
         * launcher is already doing with these bitmaps for as long as the widget
         * is on screen, and dropped when the widget is.
         */
        private val lastFace = ConcurrentHashMap<Int, Bitmap>()

        private const val MAX_BITMAP_PX = 1_200f

        /** Total the progress bar is scaled to, so a fraction fits in an Int. */
        private const val PROGRESS_MAX = 1_000

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90

        /** The spectrum's alpha while nothing is playing — fully gone. */
        private const val ALPHA_HIDDEN = 0

        /** What the placeholder face is remembered under. */
        private const val KEY_NO_ARTWORK = "no-artwork"

        private const val REQUEST_OPEN_PLAYER = 0
    }
}

/** Grid spans, by the launcher's own arithmetic: a span of n cells is 70n − 30 dp. */
private const val SQUARE_WIDTH_DP = 110

private const val WIDE_WIDTH_DP = 250

private const val FALLBACK_HEIGHT_DP = 110

/** The 2×2 entry in the picker. See `res/xml/widget_media_square.xml`. */
class MediaWidgetSquare : MediaWidget() {
    override val fallbackWidthDp = SQUARE_WIDTH_DP
}

/** The 4×2 entry in the picker. See `res/xml/widget_media_wide.xml`. */
class MediaWidgetWide : MediaWidget() {
    override val fallbackWidthDp = WIDE_WIDTH_DP
}
