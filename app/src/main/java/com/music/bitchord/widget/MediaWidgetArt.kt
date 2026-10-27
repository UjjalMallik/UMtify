package com.music.bitchord.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The album tile of a widget, in pixels: a rounded square, its crystal rim, and
 * nothing else.
 *
 * The tile is its own bitmap, drawn into its own `ImageView`, because it is the
 * one part of a widget that must not be stretched. A `RemoteViews` image is
 * scaled to whatever the host decides the view is, and the host decides from
 * the widget's cell span — which is a range, not a size. Baking the artwork into
 * the card meant the tile slid inward and grew into the title whenever that
 * guess was a little off. Positioned by the layout instead, it is square at any
 * size and the text can never run into it.
 */
internal class ArtSpec(
    val widthPx: Int,
    val heightPx: Int,
    val cornerPx: Float,
    val rimPx: Float,
) {
    /** Identifies the finished bitmap: every value that changes a pixel is in here. */
    fun cacheKey(key: String): String =
        "art|$key|${widthPx}x$heightPx|${cornerPx.roundToInt()}/${rimPx.roundToInt()}"
}

/**
 * The glass of a widget, in pixels: the clear body, its wet sheen, the specular
 * rim and — on the square widget — the floating dock.
 *
 * No artwork is composed into this any more. The body is a nearly transparent
 * pane, so whatever the launcher has on the home screen shows through it; the
 * artwork is a layer of its own, above this one. The dock is the exception: it
 * frosts what is *under* it, which is only possible by sampling the artwork
 * bitmap, passed to [MediaWidgetArt.renderFace].
 */
internal class FaceSpec(
    val widthPx: Int,
    val heightPx: Int,
    val radiusPx: Float,
    val rimPx: Float,
    val dock: Rect? = null,
    val dockCornerPx: Float = 0f,
    val dockRimPx: Float = 0f,
) {
    fun cacheKey(key: String): String = buildString {
        append("face|").append(key)
        append('|').append(widthPx).append('x').append(heightPx)
        append('|').append(radiusPx.roundToInt()).append('/').append(rimPx.roundToInt())
        dock?.let {
            append("|d").append(it.left).append(',').append(it.top)
                .append(',').append(it.width()).append(',').append(it.height())
                .append('/').append(dockCornerPx.roundToInt()).append('/').append(dockRimPx.roundToInt())
        }
    }
}

/**
 * Draws the crystal glass of all three home-screen widgets.
 *
 * The recipe, in the order it is painted:
 *
 *  - **Clear body** — white at 12% over a 10% dark scrim, with up to 8% of the
 *    cover's dominant colour: enough to keep white type legible on a bright
 *    wallpaper, far too little to hide the wallpaper itself.
 *  - **Wet-glass sheen** — white 28% fading out across the top 35%, the
 *    highlight a curved pane catches, and a 14% bounce along the bottom 15%
 *    where the pane curves away.
 *  - **Specular rim** — a 1.5dp stroke running white 85% at the top-left, 25%
 *    across the middle and 70% at the bottom-right.
 *
 * Nothing here is blurred or tinted at runtime — [android.widget.RemoteViews]
 * cannot do any of that — so all of it is composed on the CPU, once per track
 * and size, and cached under [ArtSpec.cacheKey] / [FaceSpec.cacheKey].
 */
internal object MediaWidgetArt {

    /**
     * Draws the artwork tile described by [spec].
     *
     * [key] identifies the track; it is what the finished bitmap is remembered
     * under. Pass null only when there is nothing worth remembering (no track).
     */
    suspend fun renderArt(
        context: Context,
        artworkUrl: String?,
        spec: ArtSpec,
        key: String?,
    ): Bitmap {
        peekArt(spec, key)?.let { return it }
        val cacheKey = key?.let { spec.cacheKey(it) }

        val cover = if (isCoolingOff(key)) {
            null
        } else {
            loadArtwork(context, artworkUrl, maxOf(spec.widthPx, spec.heightPx))
        }
        // A cover that was asked for and didn't arrive is a failure, not an
        // answer: the tile is drawn either way, but it must not be remembered,
        // or one dropped connection would pin the placeholder to this track for
        // as long as the process lives.
        val failed = cover == null && !artworkUrl.isNullOrBlank()
        if (failed && key != null) failures[key] = SystemClock.elapsedRealtime()

        val accent = cover?.let { accentOfBitmap(it) } ?: DEFAULT_ACCENT
        val base = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base)
        if (cover != null) canvas.fillCentreCropped(cover) else canvas.fillPlaceholder(accent)

        val rounded = if (spec.cornerPx > 0f) base.withRoundedCorners(spec.cornerPx) else base
        if (rounded !== base) base.recycle()
        Canvas(rounded).strokeGlass(
            RectF(0f, 0f, spec.widthPx.toFloat(), spec.heightPx.toFloat()),
            spec.cornerPx,
            spec.rimPx,
            ART_RIM_COLORS,
            inset = true,
        )
        if (!failed) cacheKey?.let { arts.put(it, rounded) }
        return rounded
    }

    /**
     * Draws the glass face described by [spec].
     *
     * [tintSource] is the artwork the face is going over: the dock on the square
     * widget frosts it, and its dominant colour is what the 8% accent wash is
     * made of. Null means there is no cover — the face is still glass, just
     * untinted, and it is not cached so a cover that arrives later can tint it.
     */
    suspend fun renderFace(
        context: Context,
        artworkUrl: String?,
        spec: FaceSpec,
        tintSource: Bitmap?,
        key: String?,
    ): Bitmap {
        peekFace(spec, key)?.let { return it }
        val cacheKey = key?.let { spec.cacheKey(it) }

        val accent = tintSource?.let { accentOfBitmap(it) } ?: DEFAULT_ACCENT
        val composed = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        canvas.drawClearGlass(accent)
        spec.dock?.let { canvas.drawDock(tintSource, it, spec) }
        canvas.drawEdge(spec)

        val rounded = composed.withRoundedCorners(spec.radiusPx)
        composed.recycle()
        // A face tinted from nothing would keep the default accent for the life
        // of the process; let the next update have another go instead.
        if (tintSource != null || artworkUrl.isNullOrBlank()) {
            cacheKey?.let { faces.put(it, rounded) }
        }
        return rounded
    }

    /** The artwork tile for these arguments if it has already been drawn. */
    fun peekArt(spec: ArtSpec, key: String?): Bitmap? =
        key?.let { arts[spec.cacheKey(it)] }?.takeIf { !it.isRecycled }

    /** The glass face for these arguments if it has already been drawn. */
    fun peekFace(spec: FaceSpec, key: String?): Bitmap? =
        key?.let { faces[spec.cacheKey(it)] }?.takeIf { !it.isRecycled }

    /** Drops every remembered face — the last widget has just been removed. */
    fun clear() {
        arts.evictAll()
        faces.evictAll()
        failures.clear()
    }

    /**
     * Whether this cover failed recently enough that it isn't worth asking for
     * again yet.
     *
     * The counterweight to not caching failures. A widget redraws on every play
     * and every pause as well as on every track change, so a cover that is not
     * merely slow but genuinely gone — a dead URL, a source that has moved its
     * images — would otherwise be re-fetched on every tap of the play button.
     * Long enough that a jammed play/pause finger costs one attempt; short
     * enough that walking back into Wi-Fi fixes the widget on the next thing
     * that happens rather than on the next restart.
     */
    private fun isCoolingOff(key: String?): Boolean {
        val failedAt = key?.let { failures[it] } ?: return false
        val since = SystemClock.elapsedRealtime() - failedAt
        // Also the way back out: a clock that has gone backwards (elapsedRealtime
        // does not, but a stale entry from before a process restart would read
        // that way) must not read as "failed in the future" and cool off forever.
        if (since in 0 until FAILURE_COOLDOWN_MS) return true
        failures.remove(key)
        return false
    }

    // ---- glass ----

    /**
     * The clear pane: a white haze, a whisper of the album's colour, a dark
     * scrim just heavy enough to hold white type over a bright wallpaper, the
     * top sheen and the bounce along the foot.
     */
    private fun Canvas.drawClearGlass(accent: Int) {
        val w = width.toFloat()
        val h = height.toFloat()

        drawRect(0f, 0f, w, h, Paint().apply { color = GLASS_WHITE })
        drawRect(0f, 0f, w, h, Paint().apply { color = withAlpha(accent, GLASS_ACCENT_ALPHA) })
        drawRect(0f, 0f, w, h, Paint().apply { color = GLASS_BLACK })

        val sheenBottom = h * SHEEN_SCALE
        drawRect(
            0f, 0f, w, sheenBottom,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, sheenBottom,
                    SHEEN_ALPHA, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            },
        )

        val bounceTop = h * REFLECTION_START
        drawRect(
            0f, bounceTop, w, h,
            Paint().apply {
                shader = LinearGradient(
                    0f, bounceTop, 0f, h,
                    Color.TRANSPARENT, REFLECTION_ALPHA, Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /**
     * The square widget's floating dock: a pane that frosts whatever is under
     * it, so the artwork visibly blurs behind the glass rather than being
     * covered by a flat scrim.
     */
    private fun Canvas.drawDock(source: Bitmap?, dock: Rect, spec: FaceSpec) {
        val dockW = dock.width()
        val dockH = dock.height()
        if (dockW <= 0 || dockH <= 0) return

        val layer = Bitmap.createBitmap(dockW, dockH, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        if (source != null && !source.isRecycled) {
            // The artwork is the card's own size, bar a rounding either way, so
            // the dock's place in it is proportional rather than exact.
            val sx = source.width.toFloat() / spec.widthPx.coerceAtLeast(1)
            val sy = source.height.toFloat() / spec.heightPx.coerceAtLeast(1)
            val left = (dock.left * sx).roundToInt().coerceIn(0, source.width - 1)
            val top = (dock.top * sy).roundToInt().coerceIn(0, source.height - 1)
            val right = (dock.right * sx).roundToInt().coerceIn(left + 1, source.width)
            val bottom = (dock.bottom * sy).roundToInt().coerceIn(top + 1, source.height)
            val region = runCatching {
                Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            }.getOrNull()
            if (region != null) {
                val workW = DOCK_WORKING_WIDTH_PX
                val workH = (workW * dockH / dockW).coerceAtLeast(4)
                val work = Bitmap.createScaledBitmap(region, workW, workH, true)
                if (work !== region) region.recycle()
                val pixels = IntArray(workW * workH)
                work.getPixels(pixels, 0, workW, 0, 0, workW, workH)
                blurInPlace(
                    pixels,
                    IntArray(pixels.size),
                    workW,
                    workH,
                    (workW * DOCK_BLUR_FRACTION).roundToInt().coerceAtLeast(1),
                )
                work.setPixels(pixels, 0, workW, 0, 0, workW, workH)
                layerCanvas.drawBitmap(
                    work,
                    Rect(0, 0, workW, workH),
                    Rect(0, 0, dockW, dockH),
                    Paint().apply { isFilterBitmap = true },
                )
                work.recycle()
            }
        }

        layerCanvas.drawRect(
            0f, 0f, dockW.toFloat(), dockH.toFloat(),
            Paint().apply { color = DOCK_WHITE },
        )
        val sheenBottom = dockH * DOCK_SHEEN_SCALE
        layerCanvas.drawRect(
            0f, 0f, dockW.toFloat(), sheenBottom,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, sheenBottom,
                    DOCK_SHEEN, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            },
        )

        val rounded = layer.withRoundedCorners(spec.dockCornerPx)
        if (rounded !== layer) layer.recycle()
        drawBitmap(
            rounded,
            dock.left.toFloat(),
            dock.top.toFloat(),
            Paint().apply { isAntiAlias = true; isFilterBitmap = true },
        )
        rounded.recycle()

        strokeGlass(
            RectF(dock),
            spec.dockCornerPx,
            spec.dockRimPx,
            DOCK_RIM_COLORS,
            inset = true,
        )
    }

    /** The outer specular rim — the lit edge that makes the pane read as glass. */
    private fun Canvas.drawEdge(spec: FaceSpec) {
        strokeGlass(
            RectF(0f, 0f, spec.widthPx.toFloat(), spec.heightPx.toFloat()),
            spec.radiusPx,
            spec.rimPx,
            RIM_COLORS,
            inset = true,
        )
    }

    /**
     * Strokes [rect] with a diagonal glass gradient: bright at the top-left,
     * nearly gone across the middle, back up at the bottom-right — the highlight
     * a real pane of glass throws along its edge.
     *
     * [inset] pulls the stroke half its width inside the rect, so a rim drawn on
     * a bitmap's own edge is not half clipped away.
     */
    private fun Canvas.strokeGlass(
        rect: RectF,
        cornerPx: Float,
        widthPx: Float,
        colors: IntArray,
        inset: Boolean,
    ) {
        if (widthPx <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        val bounds = RectF(rect)
        if (inset) {
            val half = widthPx / 2f
            bounds.inset(half, half)
        }
        val radius = (cornerPx - if (inset) widthPx / 2f else 0f).coerceAtLeast(0f)
        drawRoundRect(
            bounds,
            radius,
            radius,
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = widthPx
                shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    colors, RIM_STOPS, Shader.TileMode.CLAMP,
                )
            },
        )
    }

    // ---- artwork ----

    private suspend fun loadArtwork(context: Context, url: String?, longestSidePx: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val px = artPxFor(longestSidePx)
        // Second choice, and only when the first misses: the size the media
        // session itself asked for. A widget wider than [NOTIFICATION_ART_PX]
        // lands on a rung above it, which is the right size to *draw* but not
        // one this track is guaranteed to already have on disk — so with no
        // network the correct size fails and the widget falls back to the
        // gradient while the notification, the player and the lock screen are
        // all showing the cover from cache. Asked for second rather than
        // first because on a phone it is an upscale, and one worth avoiding
        // whenever the better copy can actually be had.
        val fallbackPx = NOTIFICATION_ART_PX.takeIf { it != px }
        return load(context, url, px) ?: fallbackPx?.let { load(context, url, it) }
    }

    private suspend fun load(context: Context, url: String, px: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            // Through the app's own size ladder, so this shares a disk-cache
            // entry with the rows, cards, headers and player already drawing the
            // same cover instead of pulling a widget-sized copy of its own over
            // the wire. Local artwork (content://…/albumart/…) carries no size
            // hint and passes through untouched.
            .data(url.artworkAt(px) ?: url)
            .size(px)
            .allowHardware(false) // the blur below reads pixels
            .build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    /**
     * The smallest size in the app's existing artwork ladder that still covers a
     * widget this big.
     *
     * Deliberately not the widget's own pixel width. A size nothing else in the
     * app asks for is a cache entry nothing else in the app fills, so the widget
     * would fetch its own copy of every cover over the network; landing on one of
     * these means the artwork is usually already on disk — and for the playing
     * track, [NOTIFICATION_ART_PX] is the size the media session itself
     * requested, so it is certainly there. A 720px cover in an 860px-wide widget
     * is a 1.2× upscale that no one can see.
     */
    private fun artPxFor(longestSidePx: Int): Int = when {
        longestSidePx <= ROW_ART_PX -> ROW_ART_PX
        longestSidePx <= CARD_ART_PX -> CARD_ART_PX
        longestSidePx <= NOTIFICATION_ART_PX -> NOTIFICATION_ART_PX
        longestSidePx <= HEADER_ART_PX -> HEADER_ART_PX
        else -> PLAYER_ART_PX
    }

    /** Fills the canvas with [src], cropped from its centre rather than squashed. */
    private fun Canvas.fillCentreCropped(src: Bitmap) {
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val sampleW = (width / scale).coerceAtMost(src.width.toFloat())
        val sampleH = (height / scale).coerceAtMost(src.height.toFloat())
        val left = (src.width - sampleW) / 2f
        val top = (src.height - sampleH) / 2f
        drawBitmap(
            src,
            Rect(
                left.toInt(),
                top.toInt(),
                (left + sampleW).toInt(),
                (top + sampleH).toInt(),
            ),
            Rect(0, 0, width, height),
            Paint().apply { isFilterBitmap = true },
        )
    }

    /**
     * What stands in for a cover there isn't one of: a track with no artwork, a
     * fetch that failed, or nothing ever played.
     *
     * Tinted towards [accent] rather than a fixed slate so the no-artwork case
     * still looks like part of the same glass family.
     */
    private fun Canvas.fillPlaceholder(accent: Int) {
        drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        mix(PLACEHOLDER_TOP, accent, PLACEHOLDER_ACCENT_MIX),
                        PLACEHOLDER_MID,
                        PLACEHOLDER_BOTTOM,
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /**
     * The dominant colour of [source], sampled from a 32px reduction.
     *
     * Averaged over pixels with enough saturation and light to be a colour
     * rather than a grey; falls back to [DEFAULT_ACCENT] for monochrome sleeves.
     */
    private fun accentOfBitmap(source: Bitmap): Int {
        if (source.isRecycled) return DEFAULT_ACCENT
        val size = ACCENT_WORKING_PX
        val small = Bitmap.createScaledBitmap(source, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        if (small !== source) small.recycle()
        return accentOf(pixels)
    }

    private fun accentOf(pixels: IntArray): Int {
        val hsv = FloatArray(3)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val stride = (pixels.size / ACCENT_SAMPLES).coerceAtLeast(1)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            Color.colorToHSV(c, hsv)
            if (hsv[1] >= ACCENT_MIN_SATURATION && hsv[2] in ACCENT_VALUE_RANGE) {
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            i += stride
        }
        if (n == 0) return DEFAULT_ACCENT
        return (0xFF shl 24) or
            ((r / n).toInt() shl 16) or
            ((g / n).toInt() shl 8) or
            (b / n).toInt()
    }

    // ---- the blur ----

    /**
     * Blurs [pixels] in place, using [scratch] as the intermediate.
     *
     * A separable box filter run [BLUR_PASSES] times, which is the standard
     * cheap stand-in for a Gaussian — three passes are within a percent of one,
     * and this costs a handful of integer adds per pixel with no kernel to walk,
     * because each output reuses the previous window's sum.
     *
     * The source is opaque (it was cut out of the fully painted cover), so only
     * the three colour channels are carried through and alpha is written back
     * solid.
     */
    private fun blurInPlace(pixels: IntArray, scratch: IntArray, w: Int, h: Int, radius: Int) {
        val r = radius.coerceAtMost(maxOf(1, minOf(w, h) - 1))
        repeat(BLUR_PASSES) {
            boxPass(pixels, scratch, lines = h, lineStride = w, span = w, step = 1, radius = r)
            boxPass(scratch, pixels, lines = w, lineStride = 1, span = h, step = w, radius = r)
        }
    }

    /**
     * One box-filter pass from [src] to [dst], along whichever axis the strides
     * describe: rows are `lineStride = w, step = 1`, columns the reverse.
     */
    private fun boxPass(
        src: IntArray,
        dst: IntArray,
        lines: Int,
        lineStride: Int,
        span: Int,
        step: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = src[base + i.coerceIn(0, span - 1) * step]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (i in 0 until span) {
                dst[base + i * step] =
                    OPAQUE or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                // Slide the window on by one: drop what leaves the near end,
                // take what enters the far one. Both ends clamp, so the edges
                // hold their own colour instead of averaging in nothing and
                // darkening.
                val gone = src[base + (i - radius).coerceIn(0, span - 1) * step]
                val come = src[base + (i + radius + 1).coerceIn(0, span - 1) * step]
                r += ((come shr 16) and 0xFF) - ((gone shr 16) and 0xFF)
                g += ((come shr 8) and 0xFF) - ((gone shr 8) and 0xFF)
                b += (come and 0xFF) - (gone and 0xFF)
            }
        }
    }

    // ---- corners and colour ----

    /**
     * The same bitmap with its corners rounded off.
     *
     * A second bitmap rather than a mask applied in place: clearing the corners
     * of the original means either an un-antialiased `clipPath` or a
     * difference-of-paths draw in CLEAR mode, and both leave a visibly ragged
     * arc. Drawn through a shader instead, the round rect's own antialiasing
     * does the work.
     */
    private fun Bitmap.withRoundedCorners(radiusPx: Float): Bitmap {
        if (radiusPx <= 0f) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radiusPx,
            radiusPx,
            Paint().apply {
                isAntiAlias = true
                shader = BitmapShader(this@withRoundedCorners, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            },
        )
        return out
    }

    /** [base] moved [t] of the way towards [accent], alpha untouched (opaque out). */
    private fun mix(base: Int, accent: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val a = (accent shr shift) and 0xFF
            return (b + (a - b) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** [color]'s three channels at [alpha], as a premultiplied-free ARGB int. */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    // ---- tuning ----

    /** White at 12%: the body's haze. */
    private const val GLASS_WHITE = 0x1FFFFFFF

    /** Black at 10%, and only so white type holds on a bright wallpaper. */
    private const val GLASS_BLACK = 0x1A000000

    /** The album's colour, at 8%. */
    private const val GLASS_ACCENT_ALPHA = 0x14

    /** The wet sheen: white 28% across the top 35%. */
    private const val SHEEN_ALPHA = 0x47FFFFFF
    private const val SHEEN_SCALE = 0.35f

    /** The foot of the pane bouncing white 14% back up over its last 15%. */
    private const val REFLECTION_ALPHA = 0x24FFFFFF
    private const val REFLECTION_START = 0.85f

    /** Specular rim: white 85% → 25% → 70% down the diagonal. */
    private val RIM_COLORS = intArrayOf(0xD9FFFFFF.toInt(), 0x40FFFFFF, 0xB3FFFFFF.toInt())

    /** The artwork tile's own rim: the same gradient, softer. */
    private val ART_RIM_COLORS = intArrayOf(0xB3FFFFFF.toInt(), 0x33FFFFFF, 0x8CFFFFFF.toInt())

    /** The floating dock's rim: white 60% → 20% → 50%. */
    private val DOCK_RIM_COLORS = intArrayOf(0x99FFFFFF.toInt(), 0x33FFFFFF, 0x80FFFFFF.toInt())

    private val RIM_STOPS = floatArrayOf(0f, 0.5f, 1f)

    /** The dock's frosted pane: white 12%, plus 18% across its top half. */
    private const val DOCK_WHITE = 0x1FFFFFFF
    private const val DOCK_SHEEN = 0x2EFFFFFF
    private const val DOCK_SHEEN_SCALE = 0.5f

    /** Width of the working image the dock's frost is blurred on. */
    private const val DOCK_WORKING_WIDTH_PX = 48
    private const val DOCK_BLUR_FRACTION = 0.12f

    /** Placeholder gradient, tinted a little towards the accent. */
    private const val PLACEHOLDER_TOP = 0xFF2E3446.toInt()
    private const val PLACEHOLDER_MID = 0xFF1B2130.toInt()
    private const val PLACEHOLDER_BOTTOM = 0xFF07090E.toInt()
    private const val PLACEHOLDER_ACCENT_MIX = 0.35f

    /** What a monochrome sleeve falls back to. */
    private const val DEFAULT_ACCENT = 0xFF5B6BD6.toInt()

    private const val ACCENT_WORKING_PX = 32
    private const val ACCENT_MIN_SATURATION = 0.45f
    private val ACCENT_VALUE_RANGE = 0.55f..0.92f
    private const val ACCENT_SAMPLES = 4096

    /**
     * Box-filter passes per blur. Three is where a box stops being
     * distinguishable from a Gaussian; two leaves faint straight-edged
     * shoulders around anything bright, which on album art means around every
     * highlight.
     */
    private const val BLUR_PASSES = 3

    /** Alpha channel for the opaque pixels [boxPass] writes. */
    private const val OPAQUE = 0xFF shl 24

    /**
     * Finished tiles and faces, by track and size.
     *
     * Worth keeping because most widget updates do not change the picture at
     * all: a play/pause tap swaps one glyph, and re-deriving the face for it
     * would mean a cover decode and a stack of scaling passes to draw the
     * identical bitmap again.
     */
    private val arts = object : LruCache<String, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val faces = object : LruCache<String, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** When each cover last failed to load. @see isCoolingOff */
    private val failures = ConcurrentHashMap<String, Long>()

    /** @see isCoolingOff */
    private const val FAILURE_COOLDOWN_MS = 30_000L
}
