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
import kotlin.math.sqrt

/**
 * Everything a widget's baked bitmap is made of, in pixels.
 *
 * The whole card is one bitmap rather than a stack of images because
 * [android.widget.RemoteViews] cannot blur, gradient, clip or tint anything at
 * runtime — there is no RenderEffect, no shader and no way to reach a view's
 * render node. So the frosted body, the specular rim, the inset artwork and (on
 * the square widget) the floating dock are all composed here on the CPU, once
 * per track and size, and pushed as a single ImageView.
 *
 * [art] and [dock] are in the bitmap's own coordinates, supplied by the provider
 * from the same dp values its layout uses, so the artwork always lands exactly
 * under the empty cell the RemoteViews leaves for it.
 */
internal class GlassSpec(
    val widthPx: Int,
    val heightPx: Int,
    val radiusPx: Float,
    val rimPx: Float,
    val art: Rect,
    val artCornerPx: Float,
    val artRimPx: Float,
    val artShadowPx: Float = 0f,
    val dock: Rect? = null,
    val dockCornerPx: Float = 0f,
    val dockRimPx: Float = 0f,
    val bodyAlpha: Int = 255,
) {
    /** Identifies the finished bitmap: every value that changes a pixel is in here. */
    fun cacheKey(key: String): String = buildString {
        append("glass|").append(key)
        append('|').append(widthPx).append('x').append(heightPx)
        append('|').append(radiusPx.roundToInt()).append('/').append(rimPx.roundToInt())
        append('|').append(art.left).append(',').append(art.top)
            .append(',').append(art.width()).append(',').append(art.height())
        append('|').append(artCornerPx.roundToInt()).append('/').append(artRimPx.roundToInt())
            .append('/').append(artShadowPx.roundToInt())
        dock?.let {
            append("|d").append(it.left).append(',').append(it.top)
                .append(',').append(it.width()).append(',').append(it.height())
                .append('/').append(dockCornerPx.roundToInt()).append('/').append(dockRimPx.roundToInt())
        }
        append('|').append(bodyAlpha)
    }
}

/**
 * Draws the optical liquid-glass face of all three home-screen widgets.
 *
 * The recipe, in the order it is painted:
 *
 *  - **Frosted mica base** — the cover reduced to a [AMBIENT_WORKING_WIDTH_PX]-wide
 *    working image and blurred until only its colours remain, sampled back up to
 *    fill the card, so the album's tones (and, through [GlassSpec.bodyAlpha], a
 *    little of the wallpaper) diffuse behind everything.
 *  - **Frosted tint** — `#8814181F` nudged a quarter of the way towards the
 *    cover's dominant accent, which is what keeps the glass reading as glass on a
 *    white sleeve and as the album on a saturated one.
 *  - **Wet-glass sheen** — a white-to-transparent vertical gradient across the
 *    top 40%, the highlight a curved pane catches.
 *  - **Specular rim** — a 1.5dp stroke whose gradient runs bright at the
 *    top-left, faint across the middle and back up at the bottom-right, plus a
 *    1dp inset glow: the signature edge of the reference glass card.
 *
 * Everything downstream of that ([GlassSpec.art], [GlassSpec.dock]) is optional
 * so the same renderer serves the 2×2 photocard, the 4×2 split card and the 4×1
 * capsule.
 */
internal object MediaWidgetArt {

    /**
     * Draws the widget face described by [spec].
     *
     * [key] identifies the track; it is what the finished bitmap is remembered
     * under. Pass null only when there is nothing worth remembering (no track),
     * so the placeholder is not cached under a shared name.
     */
    suspend fun render(
        context: Context,
        artworkUrl: String?,
        spec: GlassSpec,
        key: String?,
    ): Bitmap {
        peek(spec, key)?.let { return it }
        val cacheKey = key?.let { spec.cacheKey(it) }

        val cover = if (isCoolingOff(key)) null else loadArtwork(context, artworkUrl, maxOf(spec.widthPx, spec.heightPx))
        // A cover that was asked for and didn't arrive is a failure, not an
        // answer. The face is drawn either way — the caller has a widget to fill
        // — but it must not be remembered, or one dropped connection would pin
        // the placeholder to this track for as long as the process lives. See
        // the `put` at the end.
        val failed = cover == null && !artworkUrl.isNullOrBlank()
        if (failed && key != null) failures[key] = SystemClock.elapsedRealtime()

        val composed = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        val accent = canvas.drawAmbient(cover)
        if (spec.art.width() > 0 && spec.art.height() > 0) canvas.drawArtwork(cover, spec, accent)
        spec.dock?.let { canvas.drawDock(composed, it, spec, accent) }
        canvas.drawEdge(spec)

        val rounded = composed.withRoundedCorners(spec.radiusPx, spec.bodyAlpha)
        composed.recycle()
        if (!failed) cacheKey?.let { composites.put(it, rounded) }
        return rounded
    }

    /**
     * The face for these arguments if it has already been drawn, without drawing
     * it if it hasn't.
     *
     * Lets a provider find out, on the thread it was called on, whether it can
     * push a finished widget in one go. Without it every update would push the
     * controls first and the artwork second, and the gap between the two shows:
     * a play/pause tap — one glyph — would blink the whole card away and back.
     */
    fun peek(spec: GlassSpec, key: String?): Bitmap? =
        key?.let { composites[spec.cacheKey(it)] }?.takeIf { !it.isRecycled }

    /** Drops every remembered face — the last widget has just been removed. */
    fun clear() {
        composites.evictAll()
        failures.clear()
    }

    /**
     * Whether this cover failed recently enough that it isn't worth asking for
     * again yet.
     *
     * The counterweight to not caching failures. A widget redraws on every play
     * and every pause as well as on every track change, so a cover that is not
     * merely slow but genuinely gone — a dead URL, a source that has moved its
     * images — would otherwise be re-fetched, twice over with the fallback, on
     * every tap of the play button. Long enough that a jammed play/pause finger
     * costs one attempt; short enough that walking back into Wi-Fi fixes the
     * widget on the next thing that happens rather than on the next restart.
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
     * Paints the frosted backdrop and returns the accent the rest of the face
     * tints against.
     */
    private fun Canvas.drawAmbient(cover: Bitmap?): Int {
        val accent = if (cover == null) DEFAULT_ACCENT else drawBlurredCover(cover)
        if (cover == null) fillPlaceholder(accent)
        drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply { color = glassTint(accent) },
        )
        val sheenBottom = height * SHEEN_SCALE
        drawRect(
            0f,
            0f,
            width.toFloat(),
            sheenBottom,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, sheenBottom,
                    SHEEN_ALPHA, Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
            },
        )
        return accent
    }

    /**
     * The cover as colour: centre-cropped, reduced to [AMBIENT_WORKING_WIDTH_PX]
     * and blurred far past a Gaussian, then sampled back up to the full canvas.
     *
     * Cropped at twice the working size and halved, so the reduction averages
     * each 2×2 block instead of sampling a sparse grid of it — aliasing that the
     * blur would turn into visible blocks on a moving queue of covers.
     */
    private fun Canvas.drawBlurredCover(cover: Bitmap): Int {
        val workW = AMBIENT_WORKING_WIDTH_PX
        val workH = (workW * height / width.coerceAtLeast(1)).coerceAtLeast(4)
        val crop = Bitmap.createBitmap(workW * 2, workH * 2, Bitmap.Config.ARGB_8888)
        Canvas(crop).fillCentreCropped(cover)
        val work = Bitmap.createScaledBitmap(crop, workW, workH, true)
        if (work !== crop) crop.recycle()

        val pixels = IntArray(workW * workH)
        work.getPixels(pixels, 0, workW, 0, 0, workW, workH)
        val accent = accentOf(pixels)
        blurInPlace(
            pixels,
            IntArray(pixels.size),
            workW,
            workH,
            (workW * AMBIENT_BLUR_FRACTION).roundToInt().coerceAtLeast(1),
        )
        work.setPixels(pixels, 0, workW, 0, 0, workW, workH)
        drawBitmap(
            work,
            Rect(0, 0, workW, workH),
            Rect(0, 0, width, height),
            Paint().apply { isFilterBitmap = true },
        )
        work.recycle()
        return accent
    }

    /** The inset album art card: rounded square, its own glass rim, optional shadow. */
    private fun Canvas.drawArtwork(cover: Bitmap?, spec: GlassSpec, accent: Int) {
        val rect = spec.art
        val artW = rect.width()
        val artH = rect.height()
        if (artW <= 0 || artH <= 0) return

        if (spec.artShadowPx > 0f) {
            // The fill under the art is almost transparent; only its shadow shows.
            drawRoundRect(
                RectF(rect),
                spec.artCornerPx,
                spec.artCornerPx,
                Paint().apply {
                    isAntiAlias = true
                    color = SHADOW_FILL
                    setShadowLayer(spec.artShadowPx, 0f, spec.artShadowPx * 0.45f, SHADOW_COLOR)
                },
            )
        }

        val art = Bitmap.createBitmap(artW, artH, Bitmap.Config.ARGB_8888)
        val artCanvas = Canvas(art)
        if (cover != null) artCanvas.fillCentreCropped(cover) else artCanvas.fillPlaceholder(accent)
        val rounded = art.withRoundedCorners(spec.artCornerPx)
        if (rounded !== art) art.recycle()
        drawBitmap(
            rounded,
            rect.left.toFloat(),
            rect.top.toFloat(),
            Paint().apply { isAntiAlias = true; isFilterBitmap = true },
        )
        rounded.recycle()

        strokeGlass(RectF(rect), spec.artCornerPx, spec.artRimPx, RIM_COLORS)
    }

    /**
     * The 2×2 widget's floating dock: a frost panel that samples what is beneath
     * it, so the artwork visibly blurs behind the glass rather than being
     * covered by a flat scrim.
     */
    private fun Canvas.drawDock(source: Bitmap, dock: Rect, spec: GlassSpec, accent: Int) {
        val dockW = dock.width()
        val dockH = dock.height()
        if (dockW <= 0 || dockH <= 0) return

        val region = runCatching { Bitmap.createBitmap(source, dock.left, dock.top, dockW, dockH) }.getOrNull()
        val layer = Bitmap.createBitmap(dockW, dockH, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        if (region != null) {
            val workW = DOCK_WORKING_WIDTH_PX
            val workH = (workW * dockH / dockW).coerceAtLeast(4)
            val work = Bitmap.createScaledBitmap(region, workW, workH, true)
            region.recycle()
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
        } else {
            layerCanvas.fillPlaceholder(accent)
        }
        layerCanvas.drawRect(
            0f, 0f, dockW.toFloat(), dockH.toFloat(),
            Paint().apply { color = DOCK_TINT },
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

        strokeGlass(RectF(dock), spec.dockCornerPx, spec.dockRimPx, RIM_COLORS)
    }

    /** The outer specular rim and the faint inner glow just inside it. */
    private fun Canvas.drawEdge(spec: GlassSpec) {
        val rect = RectF(0f, 0f, spec.widthPx.toFloat(), spec.heightPx.toFloat())
        strokeGlass(rect, spec.radiusPx, spec.rimPx, RIM_COLORS)
        val inset = spec.rimPx * GLOW_INSET_SCALE
        val glow = RectF(rect).apply { inset(inset, inset) }
        strokeGlass(
            glow,
            (spec.radiusPx - inset).coerceAtLeast(0f),
            spec.rimPx * GLOW_WIDTH_SCALE,
            GLOW_COLORS,
        )
    }

    /**
     * Strokes [rect] with a diagonal glass gradient: bright at the top-left,
     * nearly gone across the middle, back up at the bottom-right — the highlight
     * a real pane of glass throws along its edge.
     */
    private fun Canvas.strokeGlass(rect: RectF, cornerPx: Float, widthPx: Float, colors: IntArray) {
        if (widthPx <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        drawRoundRect(
            rect,
            cornerPx,
            cornerPx,
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
     *
     * [PLAYER_ART_PX] is on the ladder for exactly the same reason as the rest of
     * it, and it matters most at the top: a tablet-sized instance is wider than
     * every other rung, so without it the largest widgets were the ones certain
     * to fetch a size of their own. It is what the full player asks for, so a
     * widget that big now shares the player's copy instead.
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
     * The cover's dominant colour, averaged over pixels with enough saturation
     * and light to be a colour rather than a grey.
     *
     * Sampled on a stride so a phone-sized cover costs a few thousand reads at
     * most; falls back to [DEFAULT_ACCENT] for monochrome sleeves.
     */
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
    private fun Bitmap.withRoundedCorners(radiusPx: Float, alpha: Int = 255): Bitmap {
        if (radiusPx <= 0f && alpha == 255) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radiusPx,
            radiusPx,
            Paint().apply {
                isAntiAlias = true
                this.alpha = alpha
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

    /** The dark frosted layer, pulled a quarter of the way to the album's accent. */
    private fun glassTint(accent: Int): Int =
        (GLASS_TINT_ALPHA shl 24) or (mix(GLASS_TINT_RGB, accent, GLASS_ACCENT_MIX) and 0x00FFFFFF)

    // ---- tuning ----

    /** Width of the working image the mica backdrop is blurred on. */
    private const val AMBIENT_WORKING_WIDTH_PX = 64

    /**
     * The mica blur's box radius as a fraction of the working width. Three
     * passes at a fifth spread each colour across most of the card — the sleeve
     * reads as its tones, not as a picture.
     */
    private const val AMBIENT_BLUR_FRACTION = 0.2f

    /** How much of the card the wet-glass sheen covers, from the top. */
    private const val SHEEN_SCALE = 0.4f

    /** White at 22%. */
    private const val SHEEN_ALPHA = 0x38FFFFFF.toInt()

    /** `#14181F` at 53% — the frosted layer the artwork diffuses through. */
    private const val GLASS_TINT_RGB = 0xFF14181F.toInt()
    private const val GLASS_TINT_ALPHA = 0x88

    /** How far the frosted layer leans towards the album's own colour. */
    private const val GLASS_ACCENT_MIX = 0.25f

    /** Specular rim: white 80% → 15% → 65% down the diagonal. */
    private val RIM_COLORS = intArrayOf(0xCCFFFFFF.toInt(), 0x26FFFFFF, 0xA6FFFFFF.toInt())

    /** White 12%, three stops because a shader needs at least two. */
    private val GLOW_COLORS = intArrayOf(0x1FFFFFFF, 0x1FFFFFFF, 0x1FFFFFFF)

    private val RIM_STOPS = floatArrayOf(0f, 0.5f, 1f)

    /** The inner glow sits this many rim widths in… */
    private const val GLOW_INSET_SCALE = 1.5f

    /** …and is this much thinner than the rim. */
    private const val GLOW_WIDTH_SCALE = 0.66f

    /** Shadow under the 4×2 artwork card: nearly invisible fill, real shadow. */
    private const val SHADOW_FILL = 0x0A000000
    private const val SHADOW_COLOR = 0x55000000

    /** The 2×2 dock's frosted layer: `#14181F` at 30%. */
    private const val DOCK_TINT = 0x4D14181F

    /** White at 15%, over the top half of the dock. */
    private const val DOCK_SHEEN = 0x26FFFFFF
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
     * Finished faces, by track and size.
     *
     * Worth keeping because most widget updates do not change the picture at
     * all: a play/pause tap swaps one glyph, and re-deriving the face for it
     * would mean a cover decode and a stack of scaling passes to draw the
     * identical bitmap again. Sized for a handful of widgets' worth at phone
     * resolutions.
     */
    private val composites = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** When each cover last failed to load. @see isCoolingOff */
    private val failures = ConcurrentHashMap<String, Long>()

    /** @see isCoolingOff */
    private const val FAILURE_COOLDOWN_MS = 30_000L
}
