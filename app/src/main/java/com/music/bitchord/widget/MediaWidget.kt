package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The 2×2 widget: the square frosted card with the artwork tile, the track, the
 * equalizer and the three transport keys.
 *
 * Three layers, bottom to top: the artwork tile (`widget_art`), the glass face
 * (`widget_glass`, drawn by [MediaWidgetArt]) and the controls, which the layout
 * positions. The split matters — a `RemoteViews` image is stretched to whatever
 * size the host gives the view, and the host's idea of that size is a range, so
 * anything positioned *inside* a bitmap drifts into whatever is beside it. The
 * artwork is a view of its own, at the size the design gives it, the text column
 * is weighted beside it, and the spectrum is a row of its own.
 *
 * The card is meant to be square and the info file says so — two cells by two,
 * not resizable — so the face, the tile and the button row all land where the
 * design put them instead of being stretched into a tall rectangle.
 *
 * Every transport key is a 3D crystal bubble (`widget_bubble_*`) with the glyph
 * on top, so the shape is a vector rather than a bitmap and stays crisp at any
 * host density. The seek bar and the spectrum are baked bitmaps because
 * `RemoteViews` can give neither a thumb nor an animation.
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

    /** Fires on every resize, which is a re-render: every bitmap is size-specific. */
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
     * broadcast gets; past it the widget keeps what it last drew, which is a
     * better outcome than an ANR.
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
         * Redraws every placed square widget, the 4×1 and vinyl included.
         *
         * Called by
         * [PlaybackService][com.music.bitchord.playback.PlaybackService] whenever
         * what the widgets show has changed — which is on every play, pause and
         * track change, so it does nothing on the calling thread beyond handing
         * off. Even asking the system which widgets exist is a binder round trip,
         * and that thread is the one ExoPlayer runs on.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            MediaWidgetPill.refresh(app)
            CrystalVinylWidgetProvider.refresh(app)
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                for ((provider, fallbackWidthDp) in PROVIDERS) {
                    val ids = runCatching {
                        manager.getAppWidgetIds(ComponentName(app, provider))
                    }.getOrNull()
                    if (ids == null || ids.isEmpty()) continue
                    // No deadline: nothing is being held open here. This is the
                    // hot path — it runs on every track change, play and pause —
                    // so it is also the one where starving another widget of its
                    // render would be noticed most.
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
            // The face never touches the network — it only needs the tile for
            // the accent it washes the glass with.
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

            // The spectrum holds a ticker, so every path that isn't "playing,
            // with a track" has to stop it.
            if (snapshot.isPlaying && snapshot.hasTrack) {
                WidgetWave.play(
                    context = context,
                    id = id,
                    layout = size.layout,
                    viewId = R.id.widget_wave,
                    widthPx = size.waveWidthPx,
                    heightPx = size.waveHeightPx,
                    bars = size.waveBars,
                    accent = MediaWidgetArt.accentOf(art),
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
            val views = RemoteViews(context.packageName, size.layout)
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            face?.let { views.setImageViewBitmap(R.id.widget_glass, it) }

            val hasTrack = snapshot.hasTrack
            views.setTextViewText(
                R.id.widget_title,
                if (hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
            )
            views.setTextViewText(R.id.widget_artist, snapshot.artist)
            views.setViewVisibility(
                R.id.widget_artist,
                if (hasTrack && snapshot.artist.isNotBlank()) View.VISIBLE else View.GONE,
            )

            bindWave(views, id, snapshot, size)
            bindSeek(views, context, snapshot, size, art)

            views.setImageViewResource(
                R.id.widget_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
            )

            // On the root, not on widget_glass: the root is the last view offered
            // a touch, so this catches every pixel that isn't a button — including
            // the artwork tile and the dead space, which are over the baked face
            // and should behave like it.
            val open = openPlayer(context)
            views.setOnClickPendingIntent(R.id.widget_root, open)

            if (hasTrack) {
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
                for ((button, action) in ACTIONS) {
                    views.setOnClickPendingIntent(button, MediaWidgetActions.pendingIntent(context, action))
                }
            } else {
                // Nothing has ever played, so there is nothing to control. Dim the
                // transport and send every button to the app: a control that opens
                // the library is more use than one that does nothing at all.
                for ((button, _) in ACTIONS) {
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, open)
                }
            }
            return views
        }

        /**
         * The spectrum. Zero alpha when paused, not just "never drawn": a paused
         * widget must not leave the last frame behind. While playing, the frame
         * the ticker is on rides along — a full update drops the bitmap a
         * partial one pushed, and the progress republish must not blank the
         * spectrum for a tick.
         */
        private fun bindWave(
            views: RemoteViews,
            id: Int,
            snapshot: MediaWidgetSnapshot,
            size: WidgetSize,
        ) {
            views.setViewVisibility(R.id.widget_wave, View.VISIBLE)
            val playing = snapshot.isPlaying && snapshot.hasTrack
            if (playing) {
                WidgetWave.frameFor(id, size.waveWidthPx, size.waveHeightPx)
                    ?.let { views.setImageViewBitmap(R.id.widget_wave, it) }
            }
            views.setImageAlpha(R.id.widget_wave, if (playing) ALPHA_ENABLED else ALPHA_HIDDEN)
        }

        /** The crystal seek bar, drawn as a bitmap because it carries a thumb. */
        private fun bindSeek(
            views: RemoteViews,
            context: Context,
            snapshot: MediaWidgetSnapshot,
            size: WidgetSize,
            art: Bitmap?,
        ) {
            val show = snapshot.hasTrack
            views.setViewVisibility(R.id.widget_seek, if (show) View.VISIBLE else View.GONE)
            if (!show) return
            val duration = snapshot.durationMs
            val fraction = if (duration > 0L) {
                (snapshot.positionMs.coerceIn(0L, duration).toFloat() / duration)
            } else {
                null
            }
            WidgetSeek.render(
                context = context,
                widthPx = size.seekWidthPx,
                heightPx = size.seekHeightPx,
                fraction = fraction,
                accent = MediaWidgetArt.accentOf(art),
            )?.let { views.setImageViewBitmap(R.id.widget_seek, it) }
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
            val layout: Int,
            val face: FaceSpec,
            val art: ArtSpec,
            /** The spectrum view's width in px. */
            val waveWidthPx: Int,
            val waveHeightPx: Int,
            /** The design's bar count. */
            val waveBars: Int,
            /** The seek view's width in px. */
            val seekWidthPx: Int,
            val seekHeightPx: Int,
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

            val padPx = resources.getDimensionPixelSize(R.dimen.widget_card_padding)
            val artPx = resources.getDimensionPixelSize(R.dimen.widget_card_art)
            val innerPx = (rawWidth - 2 * padPx).roundToInt().coerceAtLeast(1)
            return WidgetSize(
                layout = R.layout.widget_media_compact,
                face = FaceSpec(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    radiusPx = minOf(
                        resources.getDimension(R.dimen.widget_card_radius) * scale,
                        minOf(widthPx, heightPx) / 2f,
                    ),
                    rimPx = resources.getDimension(R.dimen.widget_glass_rim) * scale,
                ),
                art = ArtSpec(
                    widthPx = artPx,
                    heightPx = artPx,
                    cornerPx = resources.getDimension(R.dimen.widget_card_art_corner),
                    rimPx = resources.getDimension(R.dimen.widget_card_art_rim),
                ),
                waveWidthPx = innerPx,
                waveHeightPx = resources.getDimensionPixelSize(R.dimen.widget_card_wave_height),
                waveBars = CARD_WAVE_BARS,
                seekWidthPx = innerPx,
                seekHeightPx = resources.getDimensionPixelSize(R.dimen.widget_card_seek_height),
            )
        }

        // ---- constants ----

        private val PROVIDERS = listOf(
            MediaWidgetSquare::class.java to SQUARE_WIDTH_DP,
        )

        private val ACTIONS = listOf(
            R.id.widget_previous to MediaWidgetActions.ACTION_PREVIOUS,
            R.id.widget_toggle to MediaWidgetActions.ACTION_TOGGLE,
            R.id.widget_next to MediaWidgetActions.ACTION_NEXT,
        )

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

        private const val CARD_WAVE_BARS = 24

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

private const val FALLBACK_HEIGHT_DP = 110

/** The 2×2 entry in the picker. See `res/xml/widget_media_square.xml`. */
class MediaWidgetSquare : MediaWidget() {
    override val fallbackWidthDp = SQUARE_WIDTH_DP
}
