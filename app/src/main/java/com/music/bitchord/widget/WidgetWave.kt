package com.music.bitchord.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The crystal spectrum in all three widgets — the 2×2's equalizer row, the 4×1's
 * compact twelve bars and the vinyl card's wave.
 *
 * A widget cannot animate: a `RemoteViews` is a snapshot, and the host never
 * runs one. What it can do is take a fresh one. So the frames are drawn here,
 * ahead of time — twelve of them, a whole cycle of a few drifting sine waves
 * rendered as rounded crystal bars — and a single ticker pushes the next frame
 * into just the wave view through [AppWidgetManager.partiallyUpdateAppWidget].
 * The rest of the widget is untouched by those pushes, so nothing else on the
 * card is re-inflated ten times a second.
 *
 * The wave is a view of its own in the layout, so stopping is exact: its alpha
 * is set to 0 and no frame is ever pushed again, which leaves no track, no
 * baseline and no last frame showing. Only instances the provider has told to
 * play animate; the ticker exits when none are left.
 *
 * Frames are drawn at a bounded working width and stretched by the host, since
 * each one is parcelled to the launcher ten times a second and a soft bar has
 * nothing to gain from full resolution.
 */
internal object WidgetWave {

    /**
     * One widget that is currently animating — or was, until [stop] was called.
     * The geometry is kept so a resize can be told from a re-render: the frames
     * are drawn at the wave view's pixel size, so a different size is a
     * different set of frames.
     */
    private class Instance(
        val context: Context,
        val id: Int,
        val layout: Int,
        val viewId: Int,
        val widthPx: Int,
        val heightPx: Int,
        val bars: Int,
        val accent: Int,
        val frames: List<Bitmap>,
    ) {
        var index = 0
    }

    private val instances = ConcurrentHashMap<Int, Instance>()
    private val lock = Any()
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts, or resumes, the wave on [id].
     *
     * [layout] and [viewId] are the wave's own layout and view — re-applying the
     * whole widget layout in a partial update would reset the views the ticker
     * isn't touching. [bars] is the bar count the design calls for, or 0 to
     * derive one from the width. [accent] tints the foot of every bar with the
     * album's colour; 0 leaves the spectrum white.
     */
    fun play(
        context: Context,
        id: Int,
        layout: Int,
        viewId: Int,
        widthPx: Int,
        heightPx: Int,
        bars: Int = 0,
        accent: Int = 0,
    ) {
        if (widthPx <= 0 || heightPx <= 0) {
            stop(id)
            return
        }
        val app = context.applicationContext
        val existing = instances[id]
        if (existing != null &&
            existing.layout == layout &&
            existing.viewId == viewId &&
            existing.widthPx == widthPx &&
            existing.heightPx == heightPx &&
            existing.bars == bars &&
            existing.accent == accent
        ) {
            ensureTicker()
            return
        }
        instances[id] = Instance(
            context = app,
            id = id,
            layout = layout,
            viewId = viewId,
            widthPx = widthPx,
            heightPx = heightPx,
            bars = bars,
            accent = accent,
            frames = frames(app, widthPx, heightPx, bars, accent),
        )
        ensureTicker()
    }

    /**
     * The frame the ticker would push next, for a full widget render to carry.
     *
     * A full update re-inflates the whole tree, which drops the bitmap the
     * ticker last pushed; without this the spectrum would blank for one tick
     * every time the service republishes the widget's progress. Null when the
     * size has changed, in which case [play] will draw fresh frames anyway.
     */
    fun frameFor(id: Int, widthPx: Int, heightPx: Int): Bitmap? {
        val instance = instances[id] ?: return null
        if (instance.widthPx != widthPx || instance.heightPx != heightPx) return null
        return instance.frames[instance.index]
    }

    /**
     * Stops the wave on [id] and hides what it last drew.
     *
     * Paused, stopped, deleted and resized-down all arrive here. The last frame
     * is left in the view at alpha 0 rather than being cleared with it: a
     * `RemoteViews` bitmap action cannot be unset, but an invisible one is
     * indistinguishable from no wave at all, which is what pausing should look
     * like.
     */
    fun stop(id: Int) {
        val instance = instances.remove(id) ?: return
        push(instance, alpha = ALPHA_HIDDEN, bitmap = null)
    }

    private fun ensureTicker() {
        synchronized(lock) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    if (instances.isEmpty()) break
                    for (instance in instances.values) {
                        val frame = instance.frames[instance.index]
                        instance.index = (instance.index + 1) % instance.frames.size
                        push(instance, alpha = ALPHA_SHOWN, bitmap = frame)
                    }
                    delay(FRAME_INTERVAL_MS)
                }
            }
        }
    }

    private fun push(instance: Instance, alpha: Int, bitmap: Bitmap?) {
        val views = RemoteViews(instance.context.packageName, instance.layout)
        bitmap?.let { views.setImageViewBitmap(instance.viewId, it) }
        // ImageView.setImageAlpha is remotable; RemoteViews just has no helper.
        views.setInt(instance.viewId, "setImageAlpha", alpha)
        runCatching {
            AppWidgetManager.getInstance(instance.context)
                .partiallyUpdateAppWidget(instance.id, views)
        }
    }

    /**
     * The whole animation, as twelve bitmaps.
     *
     * Each bar's height is two out-of-step sines plus an envelope that tapers
     * the ends, so the cycle reads as a spectrum rather than as a marching
     * pattern, and neighbouring frames differ by a twelfth of a turn — small
     * enough that the one-second loop has no visible seams.
     */
    private fun frames(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        bars: Int,
        accent: Int,
    ): List<Bitmap> {
        val width = widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
        val height = heightPx.coerceIn(MIN_HEIGHT_PX, MAX_HEIGHT_PX)
        val density = context.resources.displayMetrics.density
        val count = (if (bars > 0) bars else (width / (8f * density)).roundToInt())
            .coerceIn(MIN_BARS, MAX_BARS)
        val slot = width.toFloat() / count
        val bar = (slot * 0.56f)
            .coerceAtLeast(1.2f * density)
            .coerceAtMost((slot - 0.8f).coerceAtLeast(1f))
        val radius = bar / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(0xA6FFFFFF.toInt(), 0x73FFFFFF, 0x40FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val tint = if (accent != 0) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    height.toFloat(),
                    intArrayOf(
                        accent and 0x00FFFFFF,
                        (0x59 shl 24) or (accent and 0x00FFFFFF),
                    ),
                    floatArrayOf(0.2f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        } else {
            null
        }
        val rect = RectF()
        return List(FRAME_COUNT) { frame ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val phase = 2.0 * PI * frame / FRAME_COUNT
            for (i in 0 until count) {
                val unit = i.toFloat()
                val across = if (count == 1) 0.5f else unit / (count - 1f)
                val fraction = fraction(unit, across, phase)
                val left = unit * slot + (slot - bar) / 2f
                rect.set(left, height - height * fraction, left + bar, height.toFloat())
                canvas.drawRoundRect(rect, radius, radius, paint)
                tint?.let { canvas.drawRoundRect(rect, radius, radius, it) }
            }
            bitmap
        }
    }

    /** How tall the bar at [unit] stands, as a fraction of the wave view. */
    private fun fraction(unit: Float, across: Float, phase: Double): Float {
        val taper = 0.62f + 0.38f * sin(PI * across).toFloat()
        val slow = sin(phase + unit * 0.62 + 0.9 * sin(unit * 0.35)).toFloat()
        val fast = sin(phase * 1.7 + unit * 0.27 + 1.7).toFloat()
        val level = 0.5f + 0.5f * (0.65f * slow + 0.35f * fast)
        return (0.14f + 0.86f * taper * level).coerceIn(0.10f, 1f)
    }

    /** Twelve frames make a one-second loop at this cadence. */
    private const val FRAME_COUNT = 12

    private const val FRAME_INTERVAL_MS = 90L

    private const val MIN_BARS = 6
    private const val MAX_BARS = 48

    private const val MIN_WIDTH_PX = 16
    private const val MAX_WIDTH_PX = 640

    private const val MIN_HEIGHT_PX = 3
    private const val MAX_HEIGHT_PX = 96

    private const val ALPHA_SHOWN = 255
    private const val ALPHA_HIDDEN = 0
}
