package com.music.bitchord.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * The crystal seek bar: a glass trough, an accent-to-white fill and a glowing
 * thumb, drawn as one bitmap because [android.widget.RemoteViews] cannot give a
 * `ProgressBar` a thumb.
 *
 * Drawn fresh on every render rather than cached: the bitmap is a few hundred
 * pixels wide and twenty tall, and its whole content is the playhead.
 */
internal object WidgetSeek {

    /** The bar for [fraction] of the track, or a bare trough when nothing is known. */
    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        fraction: Float?,
        accent: Int,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val width = widthPx.coerceIn(1, MAX_WIDTH_PX)
        val height = heightPx.coerceIn(1, MAX_HEIGHT_PX)
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val middle = height / 2f
        val end = width.toFloat()

        val troughHeight = (1.8f * density).coerceAtMost(height * 0.5f)
        val trough = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TROUGH }
        canvas.drawRoundRect(
            RectF(0f, middle - troughHeight / 2f, end, middle + troughHeight / 2f),
            troughHeight / 2f,
            troughHeight / 2f,
            trough,
        )

        val fractionOrNull = fraction?.coerceIn(0f, 1f)
        if (fractionOrNull == null) return bitmap

        val fillHeight = (2.6f * density).coerceAtMost(height * 0.7f)
        val fillEnd = (end * fractionOrNull).coerceAtLeast(fillHeight)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                fillEnd,
                0f,
                withAlpha(accent, 0xD9),
                0xF2FFFFFF.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(
            RectF(0f, middle - fillHeight / 2f, fillEnd, middle + fillHeight / 2f),
            fillHeight / 2f,
            fillHeight / 2f,
            fill,
        )

        val thumbX = fillEnd.coerceIn(0f, end)
        val thumbRadius = (1.8f * density).coerceAtMost(height * 0.4f)
        val glowRadius = thumbRadius * 2.8f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                thumbX,
                middle,
                glowRadius,
                intArrayOf(withAlpha(accent, 0x8C), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(thumbX, middle, glowRadius, glow)
        canvas.drawCircle(
            thumbX,
            middle,
            thumbRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() },
        )
        return bitmap
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    /** White at 20%: the trough a bright wallpaper would otherwise swallow. */
    private const val TROUGH = 0x33FFFFFF

    private const val MAX_WIDTH_PX = 1_200
    private const val MAX_HEIGHT_PX = 48
}
