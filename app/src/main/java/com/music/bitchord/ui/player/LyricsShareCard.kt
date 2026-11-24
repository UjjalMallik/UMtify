package com.music.bitchord.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.drawArtwork
import com.music.bitchord.ui.replay.drawBackdrop
import com.music.bitchord.ui.replay.ellipsised
import com.music.bitchord.ui.replay.loadBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 3 quick background styles for the Liquid Crystal Glass story card.
 */
enum class LyricsCardStyle(val label: String) {
    AMBIENT_BLUR("Ambient Blur"),
    DEEP_GLASS("Deep Glass"),
    MINIMAL_DARK("Minimal Dark"),
}

/**
 * Liquid Crystal Glass Story Image Generator:
 * Generates a 9:16 Story-ready (1080x1920) or 1:1 Square (1080x1080) card with:
 *  - Rich blurred / styled ambient backdrop
 *  - Centered translucent frosted-glass card (28.dp rounded corners, 1.5.dp border)
 *  - Top row: Album cover thumbnail + bold Song Title + Artist Name
 *  - Middle: Selected 1-4 lyric lines in large high-contrast typography
 *  - Bottom: Minimalist UMtify branding watermark
 */
internal suspend fun renderLyricsShareCard(
    context: Context,
    card: LyricsShareRequest,
    /** True for 9:16 Story (1080x1920), false for 1:1 Square (1080x1080) format. */
    story: Boolean = true,
    style: LyricsCardStyle = LyricsCardStyle.AMBIENT_BLUR,
): Bitmap = withContext(Dispatchers.Default) {
    val type = Fonts(context)
    val cover = card.artworkUrl?.let { loadBitmap(context, it) }

    val cardW = CARD_W
    val cardH = if (story) 1920 else 1080
    val bitmap = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // ── 1. Background style rendering ──────────────────────────────────────────
    when (style) {
        LyricsCardStyle.AMBIENT_BLUR -> {
            drawBackdrop(canvas, cover, 0f, cardH.toFloat())
            val darkOverlay = Paint().apply { color = Color.argb(90, 8, 10, 16) }
            canvas.drawRect(0f, 0f, cardW.toFloat(), cardH.toFloat(), darkOverlay)
        }
        LyricsCardStyle.DEEP_GLASS -> {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, cardH.toFloat(),
                    Color.rgb(14, 18, 30),
                    Color.rgb(6, 8, 14),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, cardW.toFloat(), cardH.toFloat(), bgPaint)
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cardW / 2f, cardH * 0.38f, cardW * 0.72f,
                    Color.argb(75, 45, 95, 190),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPaint(glowPaint)
        }
        LyricsCardStyle.MINIMAL_DARK -> {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, cardH.toFloat(),
                    Color.rgb(18, 18, 24),
                    Color.rgb(8, 8, 10),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, cardW.toFloat(), cardH.toFloat(), bgPaint)
            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cardW / 2f, cardH * 0.5f, cardW * 0.65f,
                    Color.argb(28, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPaint(accentPaint)
        }
    }

    // ── 2. Centered Translucent Frosted Glass Card ─────────────────────────────
    val marginX = 64f
    val glassW = cardW - 2 * marginX
    val glassH = if (story) 1360f else 860f
    val glassTop = (cardH - glassH) / 2f
    val glassBottom = glassTop + glassH
    val glassRect = RectF(marginX, glassTop, cardW - marginX, glassBottom)
    val glassRadius = 56f // 28.dp

    // Frosted body
    val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 18, 22, 32)
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(glassRect, glassRadius, glassRadius, glassPaint)

    // Glass rim border: 1.5.dp = 4.5f
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        shader = LinearGradient(
            glassRect.left, glassRect.top, glassRect.left, glassRect.bottom,
            Color.argb(150, 255, 255, 255),
            Color.argb(35, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(glassRect, glassRadius, glassRadius, borderPaint)

    // ── 3. Top Row: Crisp Thumbnail + Song Title + Artist Name ─────────────────
    val innerPad = 48f
    val topY = glassTop + innerPad
    val thumbSize = if (story) 128f else 104f
    val thumbX = glassRect.left + innerPad

    drawArtwork(canvas, cover, card.song.title, thumbX, topY, thumbSize, false)

    val textX = thumbX + thumbSize + 28f
    val textRoom = glassRect.right - innerPad - textX
    val titlePaint = type.heading(if (story) 46f else 38f, Color.WHITE, bold = true)
    val artistPaint = type.body(if (story) 36f else 30f, 0xCCFFFFFF.toInt())
    canvas.drawText(ellipsised(card.song.title, titlePaint, textRoom), textX, topY + thumbSize * 0.44f, titlePaint)
    canvas.drawText(ellipsised(card.song.artist, artistPaint, textRoom), textX, topY + thumbSize * 0.84f, artistPaint)

    // ── 4. Middle: Selected Lyric Lines ───────────────────────────────────────
    val lyricsTop = topY + thumbSize + (if (story) 48f else 32f)
    val lyricsBottom = glassBottom - (if (story) 100f else 76f)
    val availableHeight = lyricsBottom - lyricsTop
    val plan = fittestPlan(type, card.lines, availableHeight)
    val spareRoom = (availableHeight - plan.content).coerceAtLeast(0f)
    var y = lyricsTop + spareRoom / 2f

    for (row in plan.rows) {
        val baseline = y + row.gapBefore - row.paint.ascent() +
            if (row.isGap) row.paint.textSize * GAP_MARKER_OFFSET else 0f
        val x = if (row.centered) cardW / 2f else (glassRect.left + innerPad)
        canvas.drawText(row.text, x, baseline, row.paint)
        y += row.gapBefore + row.height
    }

    // ── 5. Bottom: Subtle Minimalist UMtify Branding Watermark ────────────────
    val watermarkPaint = type.heading(32f, 0x88FFFFFF.toInt(), bold = true).apply {
        letterSpacing = 0.08f
    }
    val wmText = "UMtify"
    val wmWidth = watermarkPaint.measureText(wmText)
    canvas.drawText(wmText, (cardW - wmWidth) / 2f, glassBottom - (if (story) 38f else 28f), watermarkPaint)

    bitmap
}

// ── Fitting ────────────────────────────────────────────────────────────────

private class Row(
    val text: String,
    val paint: Paint,
    val height: Float,
    val gapBefore: Float,
    /** Drawn centred rather than off the left edge — the gap marker. */
    val centered: Boolean = false,
    val isGap: Boolean = false,
)

/** The rows at one size, with everything needed to place them. */
private class Plan(val rows: List<Row>, val ascent: Float) {
    /** Distance from the top of the first line to the bottom of the last. */
    val content: Float
        get() = ascent + rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
}

/**
 * The largest size on [FIT_LADDER] whose rows still fit in [room], or the
 * bottom rung when none of them do.
 *
 * The bottom rung is a floor rather than a last resort: under it the type stops
 * being worth sharing, so the frame grows to hold the words instead of the
 * words shrinking to hold the frame. That the player budgets characters at the
 * pick is what keeps this branch from ever being reached in practice.
 */
private fun fittestPlan(
    type: Fonts,
    lines: List<LyricsShareLine>,
    room: Float,
): Plan {
    for (size in FIT_LADDER) {
        val plan = planAt(type, lines, size)
        if (plan.content <= room) return plan
    }
    return planAt(type, lines, FIT_LADDER.last())
}

/**
 * The rows at one [size], set in the face the lyrics panel draws with.
 *
 * SF Pro Display Heavy with the panel's tight tracking and its line advance,
 * carried over as the same ratios the panel's 34sp headline uses — so the card
 * reads as a still of that panel rather than as a different app quoting it.
 * Compose's tracking is absolute and `Paint`'s is a fraction of the size, hence
 * the divisions: -0.7 over 34 for the words, -0.7 over 20 for the smaller line
 * the panel hangs underneath them.
 */
private fun planAt(type: Fonts, lines: List<LyricsShareLine>, size: Float): Plan {
    val primary = type.heading(size, Color.WHITE).apply { letterSpacing = TRACKING }
    val subSize = (size * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
    val secondary = type.heading(subSize, SUB_COLOR).apply { letterSpacing = SUB_TRACKING }

    val rows = mutableListOf<Row>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            // The skipped lines, as "(...)" — the conventional editorial mark
            // for an omission. Hung off the left edge with the words, smaller
            // and a dim grey. Keep its row compact so the marker follows the
            // preceding lyric closely; the regular gap on the next lyric line
            // provides the separation below it.
            val marker = type.body(size * 0.65f, 0x66FFFFFF.toInt())
            val markerHeight = marker.descent() - marker.ascent()
            rows += Row("(...)", marker, markerHeight, 0f, isGap = true)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, paint: Paint, height: Float) {
            // One verse line is one paragraph: its first row carries the space
            // above it and its wraps sit tight underneath, so a phrase reads as
            // a block rather than as a list of separate sentences.
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.55f
                else -> 0f
            }
            rows += Row(text, paint, height, gap)
            firstOfLine = false
        }
        wrap(line.text, primary, CARD_CONTENT_W).forEach { add(it, primary, size * LEAD) }
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            wrap(sub, secondary, CARD_CONTENT_W).forEach { add(it, secondary, subSize * SUB_LEAD) }
        }
    }
    return Plan(rows, -primary.ascent())
}

// ── Text ───────────────────────────────────────────────────────────────────

/**
 * Greedy wrap at [width], breaking wherever the line has to.
 *
 * Not a plain word-wrap: Japanese, Chinese and Korean lyrics carry no spaces at
 * all, and a wrapper that only split on spaces would hand the whole verse to
 * `drawText` and let it run off the card. `breakText` finds the character that
 * fits, and only then is the break walked back to a space — so scripts that keep
 * their words apart lose nothing, and scripts that don't still get several lines.
 */
private fun wrap(text: String, paint: Paint, width: Float): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return listOf("")

    val out = mutableListOf<String>()
    var rest = source
    while (rest.isNotEmpty()) {
        val fit = paint.breakText(rest, true, width, null)
        if (fit <= 0) {
            out += rest
            break
        }
        // Walk back to the last space inside the fit, but only when something
        // still has to follow it — otherwise the fit is the whole string and
        // cutting at the space would drop the final word.
        val space = rest.lastIndexOf(' ', fit - 1)
        val take = if (space > 0 && fit < rest.length) space else fit
        if (take <= 0) {
            out += rest
            break
        }
        out += rest.substring(0, take).trimEnd()
        rest = rest.substring(take).trimStart()
    }
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

// ── Card geometry ──────────────────────────────────────────────────────────

private const val CARD_W = 1080

/**
 * 9:16 — a ceiling on the height, not the height itself. The card is grown to
 * its passage and only ever comes out this tall when the passage demands it.
 */
private const val MAX_CARD_H = 1920f

private const val CARD_MARGIN = 72f
private const val CARD_CONTENT_W = CARD_W - CARD_MARGIN * 2

/** Baseline of the mark at the top right, and just under it where the gap starts. */
private const val HEADER_BASELINE = 132f
private const val HEADER_BOTTOM = 170f

/** Air kept between the fixed furniture and the passage, both ends alike. */
private const val GAP_ABOVE_BODY = 20f
private const val GAP_BELOW_BODY = 20f

/** The sleeve square in the footer, and the air kept under it. */
private const val FOOTER_THUMB = 160f
private const val FOOTER_PAD = 96f

/** Smallest the translation line is allowed to get before it stops being legible. */
private const val SUB_FLOOR = 24f

/** Small visual correction so the omission marker sits below the line above. */
private const val GAP_MARKER_OFFSET = 0.18f

/**
 * The sizes tried, largest first.
 *
 * A ladder rather than a search: a dozen candidates is nothing to lay out, and
 * stepping by a visible amount keeps two cards with adjacent line counts from
 * coming out nearly the same size and reading as a mistake.
 *
 * The last rung doubles as the floor — see [fittestPlan] for why the frame is
 * what gives way under it rather than the type.
 */
private val FIT_LADDER = floatArrayOf(96f, 88f, 80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)

// ── The panel's type, as ratios ────────────────────────────────────────────
// Every number here is one the lyrics panel uses, divided by the size it uses
// it at so it holds at whatever size the ladder lands on.

/** Tracking of the panel's headline: -0.7sp against its 34sp. */
private const val TRACKING = -0.7f / 34f

/** The same -0.7sp, against the 20sp it sets the line underneath in. */
private const val SUB_TRACKING = -0.7f / 20f

/** Line advance: 41sp over 34sp for the words, 25sp over 20sp for the sub. */
private const val LEAD = 41f / 34f
private const val SUB_LEAD = 25f / 20f

/** The sub-line against the words above it, and the white it is drawn in. */
private const val SUB_RATIO = 20f / 34f
private val SUB_COLOR = 0xD9FFFFFF.toInt()
