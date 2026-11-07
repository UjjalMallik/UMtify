package com.music.bitchord.ui.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.stats.ReplaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The Replay as one picture, drawn to a bitmap you can send.
 *
 * ## Why this is drawn rather than screenshotted
 *
 * The obvious way to share a story card is to capture the one on screen. It is
 * also the wrong picture: a story card is one fact, and the thing people
 * actually want to send is the whole year — the minutes *and* the five songs
 * *and* the five artists. A screenshot would also carry the status bar, the
 * progress segments and whatever letterboxing that particular phone needed, so
 * the same Replay would come out looking different on every device.
 *
 * ## Why it isn't Compose
 *
 * Composing offscreen means laying out a composition that is not attached to a
 * window and then reading pixels back out of it, which on this app's minimum SDK
 * is a set of caveats about hardware bitmaps and layer support rather than an
 * API. A canvas is a canvas on every version, and the layout below is fixed —
 * nothing here reflows, so nothing here needs a layout system.
 *
 * The size is 1080 × 1920: exactly the 9:16 the story cards are held to (see
 * `StoryFrame`), so what gets shared is the shape of what was being looked at,
 * and it is the size every messaging app and story surface expects.
 */
suspend fun renderReplayPoster(
    context: Context,
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    /**
     * Which story card to draw, or null for the whole Replay.
     *
     * A card's share button sends the card. That is not the same picture as the
     * summary and should not be: somebody taps share on the genre card because
     * the genre surprised them, and receiving a table of every chart instead is
     * a different message. The Replay page's own button is where "all of it"
     * lives.
     */
    page: ReplayStoryPage? = null,
    glassTheme: ReplayGlassTheme = ReplayGlassTheme.AUTO,
    themeColors: List<Int>? = null,
): Bitmap = coroutineScope {
    // The artwork this needs is a dozen small fetches that are all in Coil's
    // disk cache already — the page they came from has been on screen. Started
    // together rather than in a loop so a cold cache costs one round trip
    // rather than twelve.
    val songs = summary.songRows(POSTER_ROWS)
    val artists = summary.artistRows(POSTER_ROWS)
    val albums = summary.albumRows(POSTER_ALBUMS)
    val covers = (songs + artists + albums)
        .mapNotNull { it.artworkUrl }
        .distinct()
        .associateWith { url -> async(Dispatchers.IO) { loadBitmap(context, url) } }
        .mapValues { it.value.await() }

    withContext(Dispatchers.Default) {
        val lit = page?.let { summary.storyArtwork(it) }
            ?: songs.firstOrNull()?.artworkUrl
        val bitmap = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val type = Fonts(context)

        // Multi-tone glass backdrop: uses active theme ARGB colors when in AUTO mode
        val effectivePaletteColors = if (glassTheme == ReplayGlassTheme.AUTO) {
            themeColors ?: resolveDefaultThemeArgbColors(context)
        } else {
            glassTheme.argbColors
        }
        drawBackdrop(canvas, covers[lit], page?.let(::storyHue) ?: 0f, paletteColors = effectivePaletteColors)
        var y = drawHeader(canvas, context, type, summary, holder, memberSince)
        if (page == null) {
            y = drawTotals(canvas, context, type, summary, y)
            y = drawColumns(canvas, context, type, songs, artists, covers, y)
            y = drawAlbums(canvas, context, type, albums, covers, y)
            drawGenres(canvas, context, type, summary, y)
        } else {
            y = drawRuns(canvas, type, summary.storyHeadline(context, page), MARGIN, y, CONTENT_W)
            drawCard(canvas, context, type, summary, page, covers, y + 56f)
        }
        drawFooter(canvas, context, type)
        bitmap
    }
}

private fun resolveDefaultThemeArgbColors(context: Context): List<Int> {
    val typedValue = android.util.TypedValue()
    val theme = context.theme
    val primary = if (theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)) {
        typedValue.data
    } else {
        0xFF3B82F6.toInt()
    }
    return listOf(0xFF0F172A.toInt(), primary, primary)
}

// ── One story card ──────────────────────────────────────────────────────────

/**
 * The body of [page], under its headline.
 *
 * Deliberately not a pixel copy of the composable — a card on screen fills its
 * height with weighted spacers and this cannot, since it has no measurement pass
 * to spend. What it copies is the *content*: the same sentence, the same hero,
 * the same four runners-up, in the same order. Somebody who sends a card and
 * somebody who saw it are looking at the same facts.
 */
private fun drawCard(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    summary: ReplaySummary,
    page: ReplayStoryPage,
    covers: Map<String, Bitmap?>,
    top: Float,
) {
    when (page) {
        ReplayStoryPage.INTRO, ReplayStoryPage.MINUTES ->
            drawCollage(canvas, summary, covers, top)
        ReplayStoryPage.SONGS ->
            drawLeaderboard(canvas, context, type, summary.songRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.ARTISTS ->
            drawLeaderboard(canvas, context, type, summary.artistRows(CARD_ROWS), covers, top, true)
        ReplayStoryPage.ALBUMS ->
            drawLeaderboard(canvas, context, type, summary.albumRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.GENRES -> drawBigList(canvas, context, type, summary.genreRows(CARD_ROWS), top)
        ReplayStoryPage.HABITS -> drawHabits(canvas, context, type, summary, top)
        ReplayStoryPage.SUMMARY -> drawRecap(canvas, context, type, summary, top)
    }
}

/** The number one, large, with its runners-up under it. */
private fun drawLeaderboard(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    rows: List<ReplayRow>,
    covers: Map<String, Bitmap?>,
    top: Float,
    circular: Boolean,
) {
    val lead = rows.firstOrNull() ?: return
    val hero = 348f
    drawArtwork(canvas, lead.artworkUrl?.let { covers[it] }, lead.title, MARGIN, top, hero, circular)

    val textX = MARGIN + hero + 48f
    val textWidth = POSTER_W - MARGIN - textX
    val title = type.heading(78f, Color.WHITE)
    canvas.drawText(ellipsised(lead.title, title, textWidth), textX, top + 86f, title)
    var y = top + 86f
    lead.subtitle?.let {
        val sub = type.body(50f, 0xB3FFFFFF.toInt())
        y += 62f
        canvas.drawText(ellipsised(it, sub, textWidth), textX, y, sub)
    }
    val stats = type.body(44f, 0x8CFFFFFF.toInt())
    canvas.drawText(
        "${formatListening(context, lead.ms)} · ${context.replayCount(lead.plays, R.plurals.replay_play_count)}",
        textX,
        y + 62f,
        stats,
    )

    var rowY = top + hero + 90f
    rows.drop(1).forEach { row ->
        canvas.drawText(
            row.rank.toString(),
            MARGIN,
            rowY + 72f,
            type.heading(44f, 0x73FFFFFF),
        )
        val artX = MARGIN + 62f
        drawArtwork(canvas, row.artworkUrl?.let { covers[it] }, row.title, artX, rowY, 108f, circular)
        val name = type.body(48f, Color.WHITE, bold = true)
        val nameX = artX + 108f + 28f
        val stat = type.body(38f, 0x80FFFFFF.toInt())
        val statWidth = stat.measureText(formatListening(context, row.ms))
        canvas.drawText(
            ellipsised(row.title, name, POSTER_W - MARGIN - nameX - statWidth - 32f),
            nameX,
            rowY + 72f,
            name,
        )
        stat.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatListening(context, row.ms), POSTER_W - MARGIN, rowY + 72f, stat)
        rowY += 148f
    }
}

/** The genre card: one word, big, then the rest as a ranked list. */
private fun drawBigList(canvas: Canvas, context: Context, type: Fonts, rows: List<ReplayRow>, top: Float) {
    val lead = rows.firstOrNull() ?: return
    val word = type.heading(150f, Color.WHITE)
    canvas.drawText(ellipsised(lead.title, word, CONTENT_W), MARGIN, top + 120f, word)
    canvas.drawText(formatListening(context, lead.ms), MARGIN, top + 186f, type.body(46f, 0x99FFFFFF.toInt()))

    var y = top + 300f
    rows.drop(1).forEach { row ->
        canvas.drawText(row.rank.toString(), MARGIN, y, type.heading(48f, 0x73FFFFFF))
        val name = type.body(52f, Color.WHITE, bold = true)
        canvas.drawText(row.title, MARGIN + 80f, y, name)
        val stat = type.body(38f, 0x80FFFFFF.toInt()).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(formatListening(context, row.ms), POSTER_W - MARGIN, y, stat)
        y += 106f
    }
}

private fun drawHabits(canvas: Canvas, context: Context, type: Fonts, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun stat(value: String, label: String) {
        canvas.drawText(value, MARGIN, y, type.heading(84f, Color.WHITE))
        canvas.drawText(label, MARGIN, y + 56f, type.body(42f, 0x99FFFFFF.toInt()))
        y += 176f
    }
    if (summary.distinctAlbums > 0) {
        stat(grouped(summary.distinctAlbums.toLong()), context.getString(R.string.different_albums))
    }
    summary.busiestDay?.let {
        stat(
            formatDay(context, it),
            context.getString(R.string.your_biggest_day, formatListening(context, summary.busiestDayMs)),
        )
    }
    summary.peakHour?.let {
        stat(formatHour(context, it), context.getString(R.string.when_you_listen_the_most))
    }
}

private fun drawRecap(canvas: Canvas, context: Context, type: Fonts, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun line(label: String, value: String) {
        canvas.drawText(label, MARGIN, y, type.body(42f, 0x80FFFFFF.toInt()))
        val v = type.body(52f, Color.WHITE, bold = true)
        canvas.drawText(ellipsised(value, v, CONTENT_W - 320f), MARGIN + 320f, y, v)
        y += 96f
    }
    line(context.getString(R.string.minutes), formatMinutes(summary.totalMs))
    summary.songs.firstOrNull()?.let { line(context.getString(R.string.top_song), it.song.title) }
    summary.artists.firstOrNull()?.let { line(context.getString(R.string.top_artist), it.title) }
    summary.albums.firstOrNull()?.let { line(context.getString(R.string.top_album), it.title) }
    summary.genres.firstOrNull()?.let { line(context.getString(R.string.top_genre), it.title) }
}

/** The scatter of covers the opening cards are built around. */
private fun drawCollage(
    canvas: Canvas,
    summary: ReplaySummary,
    covers: Map<String, Bitmap?>,
    top: Float,
) {
    val squares = summary.songs.mapNotNull { it.song.thumbnailUrl }.distinct().take(3)
    val faces = summary.artists.mapNotNull { it.artworkUrl }.distinct()
        .filterNot { it in squares }.take(3)
    // Fractions of the content box, so the pile keeps its shape at any size.
    val squareAt = listOf(Triple(0.22f, 0.30f, 500f), Triple(0.02f, 0.06f, 260f), Triple(0.66f, 0.00f, 215f))
    val faceAt = listOf(Triple(0.00f, 0.62f, 185f), Triple(0.72f, 0.32f, 225f), Triple(0.46f, 0.76f, 200f))
    val height = 900f
    squares.forEachIndexed { index, url ->
        val (fx, fy, size) = squareAt[index]
        drawArtwork(canvas, covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, false)
    }
    faces.forEachIndexed { index, url ->
        val (fx, fy, size) = faceAt[index]
        drawArtwork(canvas, covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, true)
    }
}

// ── Background ──────────────────────────────────────────────────────────────

/**
 * The mesh, by hand.
 *
 * Four wide radial gradients off the leading sleeve's own colours, laid over a
 * dark base and then flattened under a vertical scrim — the same recipe the
 * player's backdrop uses, at a size where the radii can simply be written down
 * instead of derived from a layout.
 *
 * [height] is the canvas it is being drawn on: the poster passes the full
 * frame, a card that grows with its own contents passes whatever it turned out
 * to be. The anchors are fractions of it, so a short card gets the same
 * composition squeezed into its rows rather than the bottom half of a poster
 * left blank.
 */
internal fun drawBackdrop(
    canvas: Canvas,
    lead: Bitmap?,
    hue: Float,
    height: Float = POSTER_H.toFloat(),
    paletteColors: List<Int>? = null,
) {
    val colors = paletteColors ?: paletteOf(lead).map { rotated(it, hue) }
    canvas.drawColor(dimmed(colors.first()))

    val anchors = listOf(
        0.20f to 0.16f,
        0.84f to 0.22f,
        0.76f to 0.66f,
        0.18f to 0.78f,
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    colors.forEachIndexed { index, color ->
        val (fx, fy) = anchors[index % anchors.size]
        val cx = POSTER_W * fx
        val cy = height * fy
        val radius = POSTER_W * 0.95f
        paint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, 215),
                ColorUtils.setAlphaComponent(color, 0),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
    paint.shader = null

    // Specular diagonal aurora overlay for liquid glass depth
    paint.shader = android.graphics.LinearGradient(
        0f,
        0f,
        POSTER_W.toFloat(),
        height,
        intArrayOf(0x28FFFFFF, 0x00FFFFFF, 0x14FFFFFF),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, POSTER_W.toFloat(), height, paint)
    paint.shader = null

    // Ink, weighted to the foot where the smaller type is.
    paint.shader = android.graphics.LinearGradient(
        0f,
        0f,
        0f,
        height,
        intArrayOf(0x73000000.toInt(), 0x40000000, 0xB3000000.toInt()),
        floatArrayOf(0f, 0.42f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, POSTER_W.toFloat(), height, paint)
    paint.shader = null
}

/**
 * Four colours off the sleeve, saturated and held to a mid lightness so any
 * artwork yields a rich backdrop rather than a muddy or a blown-out one — the
 * same treatment `MeshGradient` gives its own palette, restated here because
 * this runs nowhere near a composition.
 */
internal fun paletteOf(bitmap: Bitmap?): List<Int> {
    val fallback = listOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFF2B5876.toInt(), 0xFFFFAF7B.toInt())
    val source = bitmap ?: return fallback
    val swatches = runCatching {
        Palette.from(source).maximumColorCount(24).generate().swatches
            .sortedByDescending { it.population }
            .map { it.rgb }
    }.getOrNull().orEmpty()
    if (swatches.isEmpty()) return fallback
    return (swatches + fallback).take(4).map(::tuned)
}

/** [color] turned [degrees] around the wheel, tone untouched — see [storyHue]. */
private fun rotated(color: Int, degrees: Float): Int {
    if (degrees == 0f) return color
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[0] = (hsl[0] + degrees) % 360f
    return ColorUtils.HSLToColor(hsl)
}

private fun tuned(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
    return ColorUtils.HSLToColor(hsl)
}

private fun dimmed(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[2] = 0.10f
    return ColorUtils.HSLToColor(hsl)
}

// ── Bands ───────────────────────────────────────────────────────────────────

private fun drawLiquidGlassCard(
    canvas: Canvas,
    bounds: RectF,
    cornerRadius: Float = 32f,
    fillAlpha: Int = 0x1E,
    borderAlphaTop: Int = 0x50,
    borderAlphaBottom: Int = 0x18,
) {
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (fillAlpha shl 24) or 0x00FFFFFF
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)

    val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        shader = android.graphics.LinearGradient(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            intArrayOf(
                (borderAlphaTop shl 24) or 0x00FFFFFF,
                (borderAlphaBottom shl 24) or 0x00FFFFFF,
                (borderAlphaTop / 2 shl 24) or 0x00FFFFFF,
            ),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, rimPaint)
}

private fun drawHeader(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
): Float {
    val label = summary.localizedLabel(context)
    val title = if (label.length == 4 && label.all { it.isDigit() }) {
        "Replay ${label.takeLast(2)}"
    } else {
        "Replay · $label"
    }
    canvas.drawText(title, MARGIN, 116f, type.heading(44f, 0xFFFFFFFF.toInt()))
    // The wordmark rather than the mark and the word beside it — the asset
    // spells the name, so drawing both would say it twice in the same corner.
    drawWordmark(canvas, context, POSTER_W - MARGIN, 116f)

    val credit = listOfNotNull(
        holder.ifBlank { context.getString(R.string.default_replay_holder) },
        memberSince?.let { context.getString(R.string.member_since_value, it) },
    ).joinToString(" · ")
    canvas.drawText(credit.uppercase(Locale.ROOT), MARGIN, 160f, type.label(22f, 0x8CFFFFFF.toInt()))
    return 204f
}

private fun drawTotals(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    summary: ReplaySummary,
    top: Float,
): Float {
    val cardHeight = 252f
    val cardBounds = RectF(MARGIN, top, POSTER_W - MARGIN, top + cardHeight)
    drawLiquidGlassCard(canvas, cardBounds, cornerRadius = 32f)

    // Glowing music/waveform accent badge in top-right
    val badgeW = 150f
    val badgeH = 38f
    val badgeBounds = RectF(cardBounds.right - badgeW - 24f, top + 24f, cardBounds.right - 24f, top + 24f + badgeH)
    val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x24FFFFFF
        style = Paint.Style.FILL
    }
    val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawRoundRect(badgeBounds, 19f, 19f, badgeFill)
    canvas.drawRoundRect(badgeBounds, 19f, 19f, badgeBorder)
    val badgeText = type.label(17f, 0xEEFFFFFF.toInt(), tracking = 0.12f)
    badgeText.textAlign = Paint.Align.CENTER
    canvas.drawText("♫ REPLAY", badgeBounds.centerX(), badgeBounds.centerY() + 6f, badgeText)

    // Section label
    val locale = context.resources.configuration.locales[0]
    canvas.drawText(
        context.getString(R.string.minutes_listened).uppercase(locale),
        MARGIN + 32f,
        top + 50f,
        type.label(22f, 0xB3FFFFFF.toInt(), tracking = 0.14f),
    )

    // Large number with generous vertical space above & below
    val numberText = formatMinutes(summary.totalMs)
    val numberPaint = type.heading(118f, Color.WHITE)
    canvas.drawText(
        numberText,
        MARGIN + 32f,
        top + 160f,
        numberPaint,
    )

    // Subtitle stats
    val subStats = listOfNotNull(
        context.replayCount(summary.totalPlays, R.plurals.replay_play_count),
        context.replayCount(summary.distinctSongs, R.plurals.replay_song_count),
        context.replayCount(summary.distinctArtists, R.plurals.replay_artist_count),
    ).joinToString(" · ")
    canvas.drawText(
        subStats,
        MARGIN + 32f,
        top + 214f,
        type.body(25f, 0x99FFFFFF.toInt()),
    )

    return top + cardHeight + 24f
}

/**
 * The two charts side by side as independent Liquid Glass Bento Cards.
 */
private fun drawColumns(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    songs: List<ReplayRow>,
    artists: List<ReplayRow>,
    covers: Map<String, Bitmap?>,
    top: Float,
): Float {
    val columnGap = 20f
    val colWidth = (POSTER_W - MARGIN * 2 - columnGap) / 2f
    val leftX = MARGIN
    val rightX = MARGIN + colWidth + columnGap

    val rows = maxOf(songs.size, artists.size).coerceAtMost(POSTER_ROWS)
    val rowH = 92f
    val cardH = 68f + rows * rowH + 20f

    val leftBounds = RectF(leftX, top, leftX + colWidth, top + cardH)
    val rightBounds = RectF(rightX, top, rightX + colWidth, top + cardH)

    drawLiquidGlassCard(canvas, leftBounds, cornerRadius = 28f)
    drawLiquidGlassCard(canvas, rightBounds, cornerRadius = 28f)

    val locale = context.resources.configuration.locales[0]
    canvas.drawText(
        context.getString(R.string.top_songs).uppercase(locale),
        leftX + 24f,
        top + 46f,
        type.label(22f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )
    canvas.drawText(
        context.getString(R.string.top_artists).uppercase(locale),
        rightX + 24f,
        top + 46f,
        type.label(22f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )

    var rowY = top + 76f
    repeat(rows) { index ->
        songs.getOrNull(index)?.let {
            drawRow(canvas, context, type, it, covers, leftX + 18f, rowY, colWidth - 36f, false)
        }
        artists.getOrNull(index)?.let {
            drawRow(canvas, context, type, it, covers, rightX + 18f, rowY, colWidth - 36f, true)
        }
        rowY += rowH
    }
    return top + cardH + 24f
}

private fun drawRow(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    row: ReplayRow,
    covers: Map<String, Bitmap?>,
    x: Float,
    y: Float,
    width: Float,
    circular: Boolean,
) {
    val artSize = 64f
    val badgeSize = 28f
    val badgeBounds = RectF(x, y + (artSize - badgeSize) / 2f, x + badgeSize, y + (artSize + badgeSize) / 2f)

    val isTopOne = row.rank == 1
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isTopOne) 0x3DFA2D48.toInt() else 0x22FFFFFF
        style = Paint.Style.FILL
    }
    val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isTopOne) 0x88FA2D48.toInt() else 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    canvas.drawRoundRect(badgeBounds, 14f, 14f, badgePaint)
    canvas.drawRoundRect(badgeBounds, 14f, 14f, badgeBorder)

    val rankText = type.heading(18f, if (isTopOne) 0xFFFA4D68.toInt() else 0xDDFFFFFF.toInt())
    rankText.textAlign = Paint.Align.CENTER
    canvas.drawText(
        row.rank.toString(),
        badgeBounds.centerX(),
        badgeBounds.centerY() + 6f,
        rankText,
    )

    val artX = x + badgeSize + 10f
    val art = row.artworkUrl?.let { covers[it] }
    drawArtwork(canvas, art, row.title, artX, y, artSize, circular)

    val textX = artX + artSize + 14f
    val textWidth = width - (textX - x)
    val title = type.body(24f, Color.WHITE, bold = true)
    canvas.drawText(ellipsised(row.title, title, textWidth), textX, y + 26f, title)
    val sub = type.body(19f, 0x99FFFFFF.toInt())
    val detail = listOfNotNull(row.subtitle, formatListening(context, row.ms)).joinToString(" · ")
    canvas.drawText(ellipsised(detail, sub, textWidth), textX, y + 50f, sub)
}

private fun drawAlbums(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    albums: List<ReplayRow>,
    covers: Map<String, Bitmap?>,
    top: Float,
): Float {
    if (albums.isEmpty()) return top
    val cardHeight = 310f
    val cardBounds = RectF(MARGIN, top, POSTER_W - MARGIN, top + cardHeight)
    drawLiquidGlassCard(canvas, cardBounds, cornerRadius = 32f)

    val locale = context.resources.configuration.locales[0]
    canvas.drawText(
        context.getString(R.string.top_albums).uppercase(locale),
        MARGIN + 28f,
        top + 46f,
        type.label(22f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )

    val items = albums.take(POSTER_ALBUMS)
    val innerMargin = 28f
    val availableW = POSTER_W - MARGIN * 2 - innerMargin * 2
    val artSize = 158f
    val gap = if (items.size > 1) (availableW - artSize * items.size) / (items.size - 1) else 0f
    val artY = top + 68f

    items.forEachIndexed { index, album ->
        val x = MARGIN + innerMargin + index * (artSize + gap)
        drawArtwork(canvas, album.artworkUrl?.let { covers[it] }, album.title, x, artY, artSize, false)

        val artBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(RectF(x, artY, x + artSize, artY + artSize), 16f, 16f, artBorder)

        val name = type.body(22f, Color.WHITE, bold = true)
        canvas.drawText(ellipsised(album.title, name, artSize), x, artY + artSize + 30f, name)
        val sub = type.body(19f, 0x8CFFFFFF.toInt())
        canvas.drawText(
            ellipsised(album.subtitle ?: formatListening(context, album.ms), sub, artSize),
            x,
            artY + artSize + 54f,
            sub,
        )
    }
    return top + cardHeight + 24f
}

private fun drawGenres(canvas: Canvas, context: Context, type: Fonts, summary: ReplaySummary, top: Float) {
    val genres = summary.genreRows(POSTER_GENRES)
    if (genres.isEmpty()) return
    val locale = context.resources.configuration.locales[0]
    canvas.drawText(
        context.getString(R.string.top_genres).uppercase(locale),
        MARGIN,
        top + 28f,
        type.label(22f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )
    val paint = type.body(24f, Color.WHITE, bold = true)
    val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    val chipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    var x = MARGIN
    val y = top + 46f
    genres.forEach { genre ->
        val width = paint.measureText(genre.title) + 40f
        if (x + width > POSTER_W - MARGIN) return
        val pillRect = RectF(x, y, x + width, y + 46f)
        canvas.drawRoundRect(pillRect, 23f, 23f, chipFill)
        canvas.drawRoundRect(pillRect, 23f, 23f, chipBorder)
        canvas.drawText(genre.title, x + 20f, y + 31f, paint)
        x += width + 14f
    }
}

private fun drawFooter(canvas: Canvas, context: Context, type: Fonts) {
    val text = context.getString(R.string.counted_on_device_with_bitchord)
    val font = type.label(21f, 0xEEFFFFFF.toInt(), tracking = 0.08f)
    font.textAlign = Paint.Align.CENTER
    val textWidth = font.measureText(text)
    val pillW = textWidth + 56f
    val pillH = 46f
    val centerX = POSTER_W / 2f
    val pillBounds = RectF(centerX - pillW / 2f, POSTER_H - 100f, centerX + pillW / 2f, POSTER_H - 100f + pillH)

    val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1EFFFFFF
        style = Paint.Style.FILL
    }
    val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3DFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawRoundRect(pillBounds, 23f, 23f, pillFill)
    canvas.drawRoundRect(pillBounds, 23f, 23f, pillBorder)
    canvas.drawText(text, centerX, pillBounds.centerY() + 7f, font)
}

// ── Pieces ──────────────────────────────────────────────────────────────────

/**
 * A cover, cropped square and rounded — or, where one never loaded, the same
 * lettered stand-in the lists use, so a missing sleeve is a deliberate-looking
 * tile rather than a hole.
 */
internal fun drawArtwork(
    canvas: Canvas,
    bitmap: Bitmap?,
    fallback: String,
    x: Float,
    y: Float,
    size: Float,
    circular: Boolean,
) {
    val bounds = RectF(x, y, x + size, y + size)
    val radius = if (circular) size / 2f else size * 0.12f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (bitmap != null) {
        val scale = size / minOf(bitmap.width, bitmap.height).toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                x - (bitmap.width * scale - size) / 2f,
                y - (bitmap.height * scale - size) / 2f,
            )
        }
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(matrix) }
        canvas.drawRoundRect(bounds, radius, radius, paint)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x33FFFFFF
        }
        canvas.drawRoundRect(bounds, radius, radius, rimPaint)
        paint.shader = null
    } else {
        val hue = (fallback.hashCode().toFloat() % 360f + 360f) % 360f
        paint.color = ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.45f))
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }
}

/**
 * A card's sentence, wrapped, with the emphasised runs in the heavier face.
 *
 * Laid out by hand because a canvas has no notion of a paragraph made of two
 * typefaces: the runs are broken into words, each word keeps the weight of the
 * run it came from, and they are placed greedily until the next one would not
 * fit. Measuring per word rather than per run is what lets a line break in the
 * middle of the bold part — which every one of these sentences does.
 */
private fun drawRuns(
    canvas: Canvas,
    type: Fonts,
    runs: List<HeadlineRun>,
    x: Float,
    top: Float,
    maxWidth: Float,
): Float {
    val bold = type.heading(HEADLINE_SIZE, Color.WHITE)
    val plain = type.body(HEADLINE_SIZE, 0x9EFFFFFF.toInt(), bold = true)

    // Flattened first, with a weight remembered per character, and only then
    // split on whitespace. Splitting the runs individually looked equivalent and
    // was not: a run ending mid-word — "4 songs" bold followed by ", one was…"
    // plain — became two tokens with a space invented between them, and the
    // sentence read "4 songs , one was your anthem". A word that straddles a
    // weight boundary takes the weight of its first letter, which is why the
    // comma there comes out bold; that is the right way to be wrong, since the
    // alternative is a comma floating a space away from what it punctuates.
    val text = StringBuilder()
    val weights = ArrayList<Boolean>()
    runs.forEach { run ->
        text.append(run.text)
        repeat(run.text.length) { weights += run.bold }
    }

    var lineX = x
    var y = top + HEADLINE_SIZE
    var index = 0
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            index++
            continue
        }
        var end = index
        while (end < text.length && !text[end].isWhitespace()) end++
        val word = text.substring(index, end)
        val paint = if (weights[index]) bold else plain
        val width = paint.measureText("$word ")
        if (lineX + width - x > maxWidth && lineX > x) {
            lineX = x
            y += HEADLINE_LEADING
        }
        canvas.drawText(word, lineX, y, paint)
        lineX += width
        index = end
    }
    return y + 24f
}

/**
 * The wordmark, its foot on [baseline] and its right edge on [right].
 *
 * The exported card is the one place the brand arrives as pixels somebody else
 * will zoom into, so it carries the same wordmark the header on screen does,
 * at the size the 46 px heading beside it implies.
 */
internal fun drawWordmark(canvas: Canvas, context: Context, right: Float, baseline: Float) {
    val mark = runCatching {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_umtify_wordmark, null)
    }.getOrNull() ?: return
    val left = right - WORDMARK_W
    val top = baseline - WORDMARK_H
    mark.setTint(0xE6FFFFFF.toInt())
    mark.setBounds(left.toInt(), top.toInt(), right.toInt(), baseline.toInt())
    mark.draw(canvas)
}

/** Trims [text] to [width], with an ellipsis, the way a single-line row would. */
internal fun ellipsised(text: String, paint: Paint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    var end = text.length
    while (end > 1 && paint.measureText(text.take(end) + "…") > width) end--
    return text.take(end).trimEnd() + "…"
}

/**
 * The app's own face, on a canvas.
 *
 * The poster is the one thing from this app that ends up somewhere else, so it
 * has more reason than any screen to be set in the type the app is set in.
 * Falls back to the platform sans if a weight can't be loaded, which keeps a
 * missing font resource a slightly plainer picture rather than a crash on the
 * share button.
 */
internal class Fonts(context: Context) {
    private val heavy = font(context, R.font.sf_pro_display_heavy) ?: Typeface.DEFAULT_BOLD
    private val semibold = font(context, R.font.sf_pro_display_semibold) ?: Typeface.DEFAULT_BOLD
    private val regular = font(context, R.font.sf_pro_display_regular) ?: Typeface.DEFAULT

    fun heading(size: Float, color: Int) = paint(heavy, size, color)

    fun body(size: Float, color: Int, bold: Boolean = false) =
        paint(if (bold) semibold else regular, size, color)

    fun label(size: Float, color: Int, tracking: Float = 0.10f) =
        paint(semibold, size, color).apply { letterSpacing = tracking }

    private fun paint(face: Typeface, size: Float, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
        }

    private fun font(context: Context, id: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
}

internal suspend fun loadBitmap(context: Context, url: String): Bitmap? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(url.artworkAt(400))
        // Palette needs pixel access, and a hardware bitmap cannot be drawn
        // into a software canvas at all — which is the whole of this file.
        .allowHardware(false)
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
}.getOrNull()

/** 9:16, the shape the story cards are held to — see `StoryFrame`. */
private const val POSTER_W = 1080
private const val POSTER_H = 1920

private const val MARGIN = 72f

/** The wordmark's drawn size: 800×275 in the asset, set to the 46 px heading. */
private const val WORDMARK_H = 48f
private const val WORDMARK_W = WORDMARK_H * 800f / 275f

/** The width type and artwork are laid out in. */
private const val CONTENT_W = POSTER_W - MARGIN * 2

/** Matches the 30sp the card on screen sets its sentence at, at 3x. */
private const val HEADLINE_SIZE = 88f
private const val HEADLINE_LEADING = 108f

/** How far down a chart a shared card goes — the same five the card shows. */
private const val CARD_ROWS = 5
private const val COLUMN_GAP = 36f
private const val ROW_HEIGHT = 116f
private const val ART = 84f
private const val ACCENT = 0xFFFA2D48.toInt()

private const val POSTER_ROWS = 5
private const val POSTER_ALBUMS = 3
private const val POSTER_GENRES = 4
