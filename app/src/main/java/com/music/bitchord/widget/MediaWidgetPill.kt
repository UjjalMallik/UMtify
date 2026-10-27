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
 * The 4×1 widget: a clear crystal capsule with the artwork tile inset at its
 * left, the track and its spectrum above the transport beside it.
 *
 * At [COMPACT_WIDTH_DP] and below the capsule cannot hold the tile and five
 * keys at once, so the layout drops the tile, Like and Shuffle and centres the
 * title over previous / play / next — the same widget, sized to what the host
 * gives it, rather than five cramped keys and a cover that overlaps them.
 *
 * Its own provider rather than a third size of [MediaWidget]: a different shape
 * with a different picture. It reads the same [MediaWidgetSnapshot], shares the
 * same [MediaWidgetActions], and draws through [MediaWidgetArt] — the glass
 * capsule as one bitmap, the artwork tile as another, and the controls placed by
 * the layout. See [MediaWidget] for why the tile is a layer of its own.
 */
class MediaWidgetPill : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    /** A resize changes the capsule's width, and its face is drawn to size. */
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
                var art = MediaWidgetArt.peekArt(specs.art, key)
                var face = MediaWidgetArt.peekFace(specs.face, key)
                if (art == null || face == null) {
                    // With what this widget last drew, not with nothing: a
                    // RemoteViews replaces the whole tree. See MediaWidget.render.
                    manager.push(
                        id,
                        views(context, snapshot, id, specs, art, face ?: lastFace[id]),
                    )
                }
                if (art == null) {
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
                manager.push(id, views(context, snapshot, id, specs, art, face))

                if (specs.waveWidthPx > 0 && snapshot.isPlaying && snapshot.hasTrack) {
                    WidgetWave.play(
                        context = context,
                        id = id,
                        layout = R.layout.widget_media_pill,
                        viewId = R.id.widget_wave,
                        widthPx = specs.waveWidthPx,
                        heightPx = specs.waveHeightPx,
                    )
                } else {
                    WidgetWave.stop(id)
                }
            }
        }

        private class Specs(
            val face: FaceSpec,
            val art: ArtSpec,
            /** Too narrow for the tile and five keys: the tile goes, so do Like and Shuffle. */
            val compact: Boolean,
            /** The spectrum's width in px, matching the track column. */
            val waveWidthPx: Int,
            val waveHeightPx: Int,
        )

        /**
         * The capsule's two bitmaps: its glass, the widget's width for the
         * orientation in force, and a fixed-height capsule; and the artwork tile
         * at the size its layout cell gives it.
         *
         * The width also decides the layout: a capsule too narrow for the tile
         * and five keys drops the tile (see [views]), and the spectrum then
         * spans the full inner width rather than starting past a tile.
         */
        private fun specs(context: Context, manager: AppWidgetManager, id: Int): Specs {
            val resources = context.resources
            val density = resources.displayMetrics.density
            val widthDp = widthDp(context, manager, id)
            val widthPx = (widthDp * density).roundToInt().coerceIn(1, MAX_WIDTH_PX)
            val compact = widthDp < COMPACT_WIDTH_DP
            val heightPx = resources.getDimensionPixelSize(R.dimen.widget_pill_height)
            val artSize = resources.getDimensionPixelSize(R.dimen.widget_pill_art)
            val padding = resources.getDimensionPixelSize(R.dimen.widget_pill_padding_h)
            val trackInset =
                if (compact) 0 else artSize + resources.getDimensionPixelSize(R.dimen.widget_pill_gap)
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
                waveWidthPx = (widthPx - 2 * padding - trackInset).coerceAtLeast(0),
                waveHeightPx = resources.getDimensionPixelSize(R.dimen.widget_wave_pill_height),
            )
        }

        /**
         * The width the host reports for the orientation in force, in dp.
         *
         * The options bundle reports a range, not a size: portrait wants the
         * minimum width and landscape the maximum, which is the same reading
         * [MediaWidget] uses.
         */
        private fun widthDp(context: Context, manager: AppWidgetManager, id: Int): Int {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            return options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            )?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
        }

        private fun views(
            context: Context,
            snapshot: MediaWidgetSnapshot,
            id: Int,
            specs: Specs,
            art: Bitmap?,
            face: Bitmap?,
        ): RemoteViews {
            val compact = specs.compact
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
                when {
                    !snapshot.hasTrack -> context.getString(R.string.widget_nothing_played)
                    snapshot.artist.isBlank() -> snapshot.title
                    else -> "${snapshot.title} · ${snapshot.artist}"
                },
            )

            // Narrow capsules keep only what fits three keys wide: the tile,
            // Like and Shuffle are given up so previous / play / next have room
            // to spread, and the title moves to the middle of the freed width.
            views.setViewVisibility(R.id.widget_art, if (compact) View.GONE else View.VISIBLE)
            views.setInt(
                R.id.widget_title,
                "setGravity",
                if (compact) Gravity.CENTER_HORIZONTAL else Gravity.START,
            )
            // Fully hidden, not merely unpainted: pausing must leave no frame
            // of the spectrum behind. While playing, the ticker's current frame
            // rides along so a full update never blanks it.
            val playing = snapshot.isPlaying && snapshot.hasTrack
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
            // Hidden rather than greyed, like the player's: the buttons either
            // side share the room out evenly without it.
            views.setViewVisibility(
                R.id.widget_like,
                if (!compact && snapshot.canLike) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_shuffle,
                if (compact) View.GONE else View.VISIBLE,
            )
            views.setImageViewResource(
                R.id.widget_like,
                if (snapshot.isLiked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart,
            )
            views.setContentDescription(
                R.id.widget_like,
                context.getString(if (snapshot.isLiked) R.string.remove_from_liked else R.string.like),
            )
            views.setImageViewResource(
                R.id.widget_shuffle,
                if (snapshot.shuffleEnabled) R.drawable.ic_widget_shuffle_on else R.drawable.ic_widget_shuffle,
            )
            views.setContentDescription(
                R.id.widget_shuffle,
                context.getString(if (snapshot.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off),
            )

            val open = openPlayer(context)
            views.setOnClickPendingIntent(R.id.widget_pill, open)

            if (snapshot.hasTrack) {
                views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
                views.setImageAlpha(R.id.widget_like, ALPHA_ENABLED)
                views.setImageAlpha(R.id.widget_shuffle, ALPHA_ENABLED)
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
            R.id.widget_like to MediaWidgetActions.ACTION_LIKE,
            R.id.widget_previous to MediaWidgetActions.ACTION_PREVIOUS,
            R.id.widget_toggle to MediaWidgetActions.ACTION_TOGGLE,
            R.id.widget_next to MediaWidgetActions.ACTION_NEXT,
            R.id.widget_shuffle to MediaWidgetActions.ACTION_SHUFFLE,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The face each instance currently shows. @see render */
        private val lastFace = ConcurrentHashMap<Int, Bitmap>()

        /** Four cells by the launcher's 70n − 30 arithmetic, for a host that hasn't said. */
        private const val FALLBACK_WIDTH_DP = 250

        /**
         * Below this the capsule can't hold the tile and all five keys: the deck
         * would be under ~150dp, which is the five keys' own width with no gaps
         * left between them. Narrower than this the tile, Like and Shuffle go
         * and the remaining three spread across the whole capsule.
         */
        private const val COMPACT_WIDTH_DP = 232

        /** Bounds the bitmap parcelled to the host on a tablet-wide instance. */
        private const val MAX_WIDTH_PX = 1_600

        private const val RENDER_TIMEOUT_MS = 8_000L

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90

        /** The spectrum's alpha while nothing is playing — fully gone. */
        private const val ALPHA_HIDDEN = 0

        private const val KEY_NO_ARTWORK = "no-artwork"

        private const val REQUEST_OPEN_PLAYER = 0
    }
}
