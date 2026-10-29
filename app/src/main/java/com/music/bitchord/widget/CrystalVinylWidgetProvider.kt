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
 * The 3×3 (and shorter 3×2) vinyl card: a circular cover inside concentric
 * crystal rings, then the track, the wave, the seek bar and the three large
 * transport bubbles.
 *
 * The disc is the one widget part that is not a rectangle. [MediaWidgetArt]
 * draws it as a circular crop of the cover, a 1.2dp crystal rim and two glowing
 * rings on a transparent square; the layout's `fitCenter` keeps it round and
 * centred at whatever the host gives the view, and the rings scale with it
 * because they are in the same bitmap.
 *
 * Its own provider, layout and info file: it shares the snapshot, the actions
 * and the glass renderer with the other three widgets, but nothing about its
 * composition is a resize of theirs.
 */
class CrystalVinylWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

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

        /** Redraws every placed vinyl widget. Called from [MediaWidget.refresh]. */
        internal fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, CrystalVinylWidgetProvider::class.java))
                }.getOrNull()
                if (ids == null || ids.isEmpty()) return@launch
                render(app, ids)
            }
        }

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
                        views(context, id, snapshot, specs, art, face ?: lastFace[id]),
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
                manager.push(id, views(context, id, snapshot, specs, art, face))

                if (specs.showWave && snapshot.isPlaying && snapshot.hasTrack) {
                    WidgetWave.play(
                        context = context,
                        id = id,
                        layout = R.layout.widget_media_vinyl,
                        viewId = R.id.widget_wave,
                        widthPx = specs.waveWidthPx,
                        heightPx = specs.waveHeightPx,
                        bars = WAVE_BARS,
                        accent = MediaWidgetArt.accentOf(art),
                    )
                } else {
                    WidgetWave.stop(id)
                }
            }
        }

        private class Specs(
            val face: FaceSpec,
            /** The disc: a square bitmap, cut to a circle with its rings. */
            val art: ArtSpec,
            val showArtist: Boolean,
            val showWave: Boolean,
            val showSeek: Boolean,
            /** Room for previous and next on either side of the toggle. */
            val showTransport: Boolean,
            val waveWidthPx: Int,
            val waveHeightPx: Int,
            val seekWidthPx: Int,
            val seekHeightPx: Int,
        )

        /**
         * The card's bitmaps: the glass at the card's own size, and the disc at
         * the widest square the card can hold.
         *
         * The disc is capped by the card's inner width and by [MAX_DISC_DP] —
         * beyond that the rings start to read as a plate rather than a disc
         * under the track. The layout, not this, is what leaves room for the
         * rows below it.
         */
        private fun specs(context: Context, manager: AppWidgetManager, id: Int): Specs {
            val resources = context.resources
            val density = resources.displayMetrics.density
            val (widthDp, heightDp) = sizeDp(context, manager, id)
            val rawWidth = widthDp * density
            val rawHeight = heightDp * density
            val widthPx = rawWidth.roundToInt().coerceIn(1, MAX_WIDTH_PX)
            val heightPx = rawHeight.roundToInt().coerceIn(1, MAX_HEIGHT_PX)

            val padding = resources.getDimensionPixelSize(R.dimen.widget_vinyl_padding)
            val waveHeight = resources.getDimensionPixelSize(R.dimen.widget_vinyl_wave_height)
            val seekHeight = resources.getDimensionPixelSize(R.dimen.widget_vinyl_seek_height)
            val inner = (widthPx - 2 * padding).coerceAtLeast(1)

            val showArtist = heightDp >= ROOMY_HEIGHT_DP && widthDp >= ROOMY_WIDTH_DP
            // The layout gives the disc the height the fixed rows leave, with
            // `fitCenter`; the bitmap is drawn at the widest it can be rather
            // than at that leftover, because the options bundle's height is the
            // 3-cell minimum, not the height the host actually gives the card.
            // A larger bitmap is scaled down by the view and stays sharp; one
            // sized from the minimum was scaled up and went soft.
            val disc = minOf(inner, (MAX_DISC_DP * density).roundToInt())
                .coerceAtLeast((MIN_DISC_DP * density).roundToInt())

            val gap = resources.getDimensionPixelSize(R.dimen.widget_vinyl_button_gap)
            val transportWidth = resources.getDimensionPixelSize(R.dimen.widget_vinyl_prev) * 2 +
                resources.getDimensionPixelSize(R.dimen.widget_vinyl_play) + 2 * gap

            return Specs(
                face = FaceSpec(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    radiusPx = minOf(
                        resources.getDimension(R.dimen.widget_card_radius),
                        minOf(widthPx, heightPx) / 2f,
                    ),
                    rimPx = resources.getDimension(R.dimen.widget_glass_rim),
                ),
                art = ArtSpec(
                    widthPx = disc,
                    heightPx = disc,
                    cornerPx = 0f,
                    rimPx = resources.getDimension(R.dimen.widget_vinyl_disc_rim),
                    disc = true,
                ),
                showArtist = showArtist,
                showWave = heightDp >= FULL_HEIGHT_DP,
                showSeek = heightDp >= SEEK_HEIGHT_DP,
                showTransport = inner >= transportWidth,
                waveWidthPx = inner,
                waveHeightPx = waveHeight,
                seekWidthPx = inner,
                seekHeightPx = seekHeight,
            )
        }

        /**
         * The size the host reports for the orientation in force, in dp.
         *
         * The options bundle reports a range: portrait wants the minimum width
         * and the maximum height, landscape the reverse.
         */
        private fun sizeDp(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
        ): Pair<Int, Int> {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val width = options?.getInt(
                if (landscape) {
                    AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                } else {
                    AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                },
            )?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
            val height = options?.getInt(
                if (landscape) {
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                } else {
                    AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                },
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
            val hasTrack = snapshot.hasTrack
            val views = RemoteViews(context.packageName, R.layout.widget_media_vinyl)
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            face?.let { views.setImageViewBitmap(R.id.widget_glass, it) }

            views.setTextViewText(
                R.id.widget_title,
                if (hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
            )
            views.setTextViewText(R.id.widget_artist, snapshot.artist)
            views.setViewVisibility(
                R.id.widget_artist,
                if (hasTrack && specs.showArtist && snapshot.artist.isNotBlank()) {
                    View.VISIBLE
                } else {
                    View.GONE
                },
            )

            val playing = specs.showWave && hasTrack && snapshot.isPlaying
            views.setViewVisibility(R.id.widget_wave, if (specs.showWave) View.VISIBLE else View.GONE)
            if (playing) {
                WidgetWave.frameFor(id, specs.waveWidthPx, specs.waveHeightPx)
                    ?.let { views.setImageViewBitmap(R.id.widget_wave, it) }
            }
            views.setImageAlpha(R.id.widget_wave, if (playing) ALPHA_ENABLED else ALPHA_HIDDEN)

            bindSeek(views, context, snapshot, specs, art)

            // Sits inside the card's 10dp padding at the 180dp three-cell
            // minimum: 44 + 8 + 54 + 8 + 44 is 158dp against 160dp of room.
            // Narrower than that the disc and the three keys cannot share the
            // card; previous and next go before the toggle, so the one control
            // that always makes sense is what is left.
            views.setViewVisibility(
                R.id.widget_previous,
                if (specs.showTransport) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.widget_next,
                if (specs.showTransport) View.VISIBLE else View.GONE,
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
            views.setOnClickPendingIntent(R.id.widget_root, open)

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
                for ((button, action) in TRANSPORT_CORE) {
                    views.setOnClickPendingIntent(button, MediaWidgetActions.pendingIntent(context, action))
                }
            } else {
                // Nothing to control yet; every button opens the app instead.
                for ((button, _) in TRANSPORT_CORE) {
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, open)
                }
            }
            return views
        }

        private fun bindSeek(
            views: RemoteViews,
            context: Context,
            snapshot: MediaWidgetSnapshot,
            specs: Specs,
            art: Bitmap?,
        ) {
            val show = specs.showSeek && snapshot.hasTrack
            views.setViewVisibility(R.id.widget_seek, if (show) View.VISIBLE else View.GONE)
            if (!show) return
            val duration = snapshot.durationMs
            val hasDuration = duration > 0L
            val position = snapshot.positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
            WidgetSeek.render(
                context = context,
                widthPx = specs.seekWidthPx,
                heightPx = specs.seekHeightPx,
                fraction = if (hasDuration) position.toFloat() / duration else null,
                accent = MediaWidgetArt.accentOf(art),
            )?.let { views.setImageViewBitmap(R.id.widget_seek, it) }
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

        private val TRANSPORT_CORE = listOf(
            R.id.widget_previous to MediaWidgetActions.ACTION_PREVIOUS,
            R.id.widget_toggle to MediaWidgetActions.ACTION_TOGGLE,
            R.id.widget_next to MediaWidgetActions.ACTION_NEXT,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The face each instance currently shows. @see render */
        private val lastFace = ConcurrentHashMap<Int, Bitmap>()

        /** Three cells by the launcher's 70n − 30 arithmetic, for a host that hasn't said. */
        private const val FALLBACK_WIDTH_DP = 180

        /** Three rows at the same arithmetic. */
        private const val FALLBACK_HEIGHT_DP = 180

        /** The card is a disc plus five rows; below three cells the artist is dropped. */
        private const val ROOMY_HEIGHT_DP = 180
        private const val ROOMY_WIDTH_DP = 180

        /**
         * The wave and the seek bar are part of the 3×3 design, so they ride with
         * the three rows rather than a taller threshold: the options bundle
         * reports three rows as 180dp (70n − 30), and a taller figure would have
         * hidden both on every phone.
         */
        private const val FULL_HEIGHT_DP = 180
        private const val SEEK_HEIGHT_DP = 180

        /** Beyond this the rings read as a plate rather than a disc. */
        private const val MAX_DISC_DP = 200f

        /** Below this the disc stops being a disc worth looking at. */
        private const val MIN_DISC_DP = 48f

        private const val WAVE_BARS = 20

        private const val MAX_WIDTH_PX = 1_200
        private const val MAX_HEIGHT_PX = 1_600

        private const val RENDER_TIMEOUT_MS = 8_000L

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90

        /** The spectrum's alpha while nothing is playing — fully gone. */
        private const val ALPHA_HIDDEN = 0

        private const val KEY_NO_ARTWORK = "no-artwork"

        private const val REQUEST_OPEN_PLAYER = 0
    }
}
