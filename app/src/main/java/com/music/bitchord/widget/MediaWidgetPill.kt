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
import android.view.Gravity
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
 * The 4×1 widget: the reference's horizontal crystal capsule.
 *
 * The capsule fills the cell — its glass is drawn at the host's own height,
 * inset 4dp top and bottom by the layout — so a one-row widget never leaves
 * transparent bands above and below that read as wasted grid space.
 *
 * Inside: the 54dp tile, the track column beside it with the twelve-bar wave
 * under the labels, and the three bubble keys on the right. Nothing else — the
 * timestamps, the seek bar and the corner keys are gone, which is what lets
 * previous, play/pause and next be full size inside a single row.
 *
 * At [COMPACT_WIDTH_DP] and below the capsule cannot hold the tile and three
 * large keys at once, so it drops the tile and the wave and centres the track
 * over the transport — the same widget, sized to what the host gives it.
 *
 * Its own provider rather than a second size of [MediaWidget]: a different shape
 * with a different picture. It reads the same [MediaWidgetSnapshot], shares the
 * same [MediaWidgetActions], and draws through [MediaWidgetArt] — the glass
 * capsule as one bitmap, the artwork tile as another, and the controls placed by
 * the layout. See [MediaWidget] for why the tile is a layer of its own.
 */
class MediaWidgetPill : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    /** A resize changes the capsule's width and height; its face is drawn to size. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            lastFace.remove(id)
            WidgetWave.stop(id)
        }
    }

    override fun onDisabled(context: Context) {
        lastFace.clear()
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                render(app, ids)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        /** Redraws every placed 4×1 widget. Called from [MediaWidget.refresh]. */
        internal fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, MediaWidgetPill::class.java))
                }.getOrNull()
                if (ids == null || ids.isEmpty()) return@launch
                render(app, ids)
            }
        }

        /**
         * Pushes the controls at once when the pictures still have to be drawn,
         * for the same reason [MediaWidget] does: a tap should be answered in a
         * frame, not after a cover fetch.
         */
        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val key = snapshot.artworkUrl ?: KEY_NO_ARTWORK
            for (id in ids) {
                val specs = specs(context, manager, id)
                var art = if (specs.compact) null else MediaWidgetArt.peekArt(specs.art, key)
                var face = MediaWidgetArt.peekFace(specs.face, key)
                if (art == null || face == null) {
                    // With what this widget last drew, not with nothing: a
                    // RemoteViews replaces the whole tree. See MediaWidget.render.
                    manager.push(
                        id,
                        views(context, id, snapshot, specs, art, face ?: lastFace[id]),
                    )
                }
                // A compact capsule draws no tile at all, so there is nothing to
                // fetch for it either.
                if (art == null && !specs.compact) {
                    art = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                        MediaWidgetArt.renderArt(context, snapshot.artworkUrl, specs.art, key)
                    }
                }
                if (face == null) {
                    face = MediaWidgetArt.renderFace(
                        context = context,
                        artworkUrl = snapshot.artworkUrl,
                        spec = specs.face,
                        tintSource = art,
                        key = key,
                    )
                }
                lastFace[id] = face
                manager.push(id, views(context, id, snapshot, specs, art, face))

                if (specs.waveWidthPx > 0 && snapshot.isPlaying && snapshot.hasTrack) {
                    WidgetWave.play(
                        context = context,
                        id = id,
                        layout = R.layout.widget_media_pill,
                        viewId = R.id.widget_wave,
                        widthPx = specs.waveWidthPx,
                        heightPx = specs.waveHeightPx,
                        bars = PILL_WAVE_BARS,
                        accent = MediaWidgetArt.accentOf(art),
                    )
                } else {
                    WidgetWave.stop(id)
                }
            }
        }

        private class Specs(
            val face: FaceSpec,
            val art: ArtSpec,
            /** Too narrow for the tile and the wave. */
            val compact: Boolean,
            /** The spectrum's width in px; 0 where it is hidden. */
            val waveWidthPx: Int,
            val waveHeightPx: Int,
        )

        /**
         * The capsule's two bitmaps: its glass at the host's width and the height
         * the cell gives it, and the artwork tile at 54dp.
         *
         * The width also decides the layout: a capsule too narrow for the tile
         * and three large keys drops the tile and the wave (see [views]), and
         * the track moves to the middle of the freed width.
         */
        private fun specs(context: Context, manager: AppWidgetManager, id: Int): Specs {
            val resources = context.resources
            val density = resources.displayMetrics.density
            val (widthDp, heightDp) = sizeDp(context, manager, id)
            val widthPx = (widthDp * density).roundToInt().coerceIn(1, MAX_WIDTH_PX)
            val marginPx = resources.getDimensionPixelSize(R.dimen.widget_pill_margin)
            // The layout insets the capsule by a margin top and bottom; the glass
            // is the capsule, not the cell, or the baked corners would sit inside
            // the view by the margin and show a second edge.
            //
            // Never shorter than the capsule's own 56dp minimum: the options
            // bundle reports a one-row cell as 40dp (70n − 30) while the host
            // lays the capsule out at the row's real height, and a face drawn at
            // 40dp lands with a visibly thick rim once stretched up to it.
            val heightPx = (maxOf(heightDp, MIN_CAPSULE_HEIGHT_DP) * density)
                .roundToInt()
                .coerceIn(1, MAX_HEIGHT_PX)
                .let { (it - 2 * marginPx).coerceAtLeast(MIN_HEIGHT_PX) }
            val compact = widthDp < COMPACT_WIDTH_DP
            val artSize = resources.getDimensionPixelSize(R.dimen.widget_pill_art)
            return Specs(
                face = FaceSpec(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    radiusPx = minOf(
                        resources.getDimension(R.dimen.widget_pill_radius),
                        heightPx / 2f,
                    ),
                    rimPx = resources.getDimension(R.dimen.widget_glass_rim),
                ),
                art = ArtSpec(
                    widthPx = artSize,
                    heightPx = artSize,
                    cornerPx = resources.getDimension(R.dimen.widget_pill_art_corner),
                    rimPx = resources.getDimension(R.dimen.widget_pill_art_rim),
                ),
                compact = compact,
                // The wave is a fixed 64dp strip under the labels, so its bitmap
                // is exactly the size of its view at any card width — a width
                // computed from the options bundle would have been the 4-cell
                // minimum (250dp) minus the fixed columns, which is only a few
                // dp, and stretched across the host's wider card.
                waveWidthPx = if (compact) {
                    0
                } else {
                    resources.getDimensionPixelSize(R.dimen.widget_pill_wave_width)
                },
                waveHeightPx = resources.getDimensionPixelSize(R.dimen.widget_pill_wave_height),
            )
        }

        /**
         * The size the host reports for the orientation in force, in dp.
         *
         * The options bundle reports a range, not a size: portrait wants the
         * minimum width and the maximum height, landscape the reverse, which is
         * the same reading [MediaWidget] uses.
         */
        private fun sizeDp(context: Context, manager: AppWidgetManager, id: Int): Pair<Int, Int> {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val width = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            )?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
            val height = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            )?.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
            return width to height
        }

        private fun views(
            context: Context,
            id: Int,
            snapshot: MediaWidgetSnapshot,
            specs: Specs,
            art: Bitmap?,
            face: Bitmap?,
        ): RemoteViews {
            val compact = specs.compact
            val hasTrack = snapshot.hasTrack
            val views = RemoteViews(context.packageName, R.layout.widget_media_pill)
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            face?.let {
                views.setImageViewBitmap(R.id.widget_glass, it)
                // The fallback capsule would show through the body's own
                // transparency and double its haze.
                views.setInt(R.id.widget_pill, "setBackgroundResource", 0)
            }

            views.setTextViewText(
                R.id.widget_title,
                if (hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
            )
            views.setTextViewText(R.id.widget_artist, snapshot.artist)
            views.setViewVisibility(
                R.id.widget_artist,
                if (hasTrack && snapshot.artist.isNotBlank()) View.VISIBLE else View.GONE,
            )

            // A narrow capsule keeps only what fits three large keys wide: the
            // tile and the wave are given up and the track moves to the middle
            // of the freed width.
            views.setViewVisibility(R.id.widget_art, if (compact) View.GONE else View.VISIBLE)
            views.setInt(
                R.id.widget_title,
                "setGravity",
                if (compact) Gravity.CENTER_HORIZONTAL else Gravity.START,
            )
            views.setInt(
                R.id.widget_artist,
                "setGravity",
                if (compact) Gravity.CENTER_HORIZONTAL else Gravity.START,
            )
            // Fully hidden, not merely unpainted: pausing must leave no frame
            // of the spectrum behind. While playing, the ticker's current frame
            // rides along so a full update never blanks it.
            val playing = !compact && hasTrack && snapshot.isPlaying
            views.setViewVisibility(R.id.widget_wave, if (compact) View.GONE else View.VISIBLE)
            if (playing) {
                WidgetWave.frameFor(id, specs.waveWidthPx, specs.waveHeightPx)
                    ?.let { views.setImageViewBitmap(R.id.widget_wave, it) }
            }
            views.setImageAlpha(
                R.id.widget_wave,
                if (playing) ALPHA_ENABLED else ALPHA_HIDDEN,
            )

            views.setImageViewResource(
                R.id.widget_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
            )

            val open = openPlayer(context)
            views.setOnClickPendingIntent(R.id.widget_pill, open)

            if (hasTrack) {
                views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
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
                // Nothing to control yet; every button opens the app instead.
                // See MediaWidget.views.
                for ((button, _) in ACTIONS) {
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, open)
                }
            }
            return views
        }

        private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) =
            setInt(viewId, "setImageAlpha", alpha)

        /** Same intent as [MediaWidget]'s, and why it has no action is explained there. */
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

        private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
            runCatching { updateAppWidget(id, views) }
        }

        private val ACTIONS = listOf(
            R.id.widget_previous to MediaWidgetActions.ACTION_PREVIOUS,
            R.id.widget_toggle to MediaWidgetActions.ACTION_TOGGLE,
            R.id.widget_next to MediaWidgetActions.ACTION_NEXT,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The face each instance currently shows. @see render */
        private val lastFace = ConcurrentHashMap<Int, Bitmap>()

        /** Four cells by the launcher's 70n − 30 arithmetic, for a host that hasn't said. */
        private const val FALLBACK_WIDTH_DP = 250

        /** A one-row cell is about this tall when the host hasn't said. */
        private const val FALLBACK_HEIGHT_DP = 64

        /** The capsule's own minimum height, from `widget_media_pill.xml`. */
        private const val MIN_CAPSULE_HEIGHT_DP = 56

        /**
         * Below this the capsule can't hold the 54dp tile and three large keys:
         * the column and the transport would be squeezed into each other.
         * Narrower than this the tile and the wave go and the track spreads
         * across the whole capsule.
         */
        private const val COMPACT_WIDTH_DP = 180

        /** Bounds the bitmap parcelled to the host on a tablet-wide instance. */
        private const val MAX_WIDTH_PX = 1_600

        /** Bounds the face on a host that reports an absurd row height. */
        private const val MAX_HEIGHT_PX = 400

        /** Even at the smallest row the face keeps enough height to draw a rim. */
        private const val MIN_HEIGHT_PX = 24

        private const val PILL_WAVE_BARS = 12

        private const val RENDER_TIMEOUT_MS = 8_000L

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90

        /** The spectrum's alpha while nothing is playing — fully gone. */
        private const val ALPHA_HIDDEN = 0

        private const val KEY_NO_ARTWORK = "no-artwork"

        private const val REQUEST_OPEN_PLAYER = 0
    }
}
