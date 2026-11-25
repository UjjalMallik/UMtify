package com.music.bitchord.ui.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
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

    val rawCovers = (songs + artists + albums)
        .mapNotNull { it.artworkUrl }
        .distinct()
        .associateWith { url -> async(Dispatchers.IO) { loadBitmap(context, url) } }
        .mapValues { it.value.await() }
        .toMutableMap()

    // Live fallback lookup for local/downloaded tracks and albums with missing artwork
    val localSongs = LocalMediaRepository.allLocalSongsFlow.value
    val downloads = runCatching { Downloads.getDownloadedSongs(context) }.getOrDefault(emptyList())

    for (song in songs) {
        val artUrl = song.artworkUrl
        if (artUrl == null || rawCovers[artUrl] == null) {
            val metaFile = com.music.bitchord.ui.components.localThumbFileByTitleArtist(context, song.title, song.subtitle)
            if (metaFile != null && metaFile.length() > 0) {
                val bmp = com.music.bitchord.ui.components.decodeSampledBitmapFromFile(metaFile.absolutePath, 256)
                if (bmp != null) {
                    if (artUrl != null) rawCovers[artUrl] = bmp
                    rawCovers[song.key] = bmp
                    continue
                }
            }
            val match = localSongs.firstOrNull { it.videoId == song.key }
                ?: localSongs.firstOrNull { it.title.equals(song.title, ignoreCase = true) }
                ?: downloads.firstOrNull { it.videoId == song.key }
                ?: downloads.firstOrNull { it.title.equals(song.title, ignoreCase = true) }

            if (match != null) {
                val key = match.localUri ?: match.localPath ?: match.videoId
                val resolvedUrl = match.thumbnailUrl ?: LocalMediaRepository.getCachedLocalArtThumbUri(context, key)
                val bmp = (resolvedUrl?.let { loadBitmap(context, it) })
                    ?: com.music.bitchord.ui.components.decodeSampledBitmap(context, key, 256)
                    ?: com.music.bitchord.ui.components.localThumbFileByTitleArtist(context, match.title, match.artist)?.let {
                        com.music.bitchord.ui.components.decodeSampledBitmapFromFile(it.absolutePath, 256)
                    }
                if (bmp != null) {
                    if (artUrl != null) rawCovers[artUrl] = bmp
                    rawCovers[song.key] = bmp
                }
            }
        }
    }

    for (album in albums) {
        val artUrl = album.artworkUrl
        if (artUrl == null || rawCovers[artUrl] == null) {
            val metaFile = com.music.bitchord.ui.components.localThumbFileByTitleArtist(context, album.title, album.subtitle)
            if (metaFile != null && metaFile.length() > 0) {
                val bmp = com.music.bitchord.ui.components.decodeSampledBitmapFromFile(metaFile.absolutePath, 256)
                if (bmp != null) {
                    if (artUrl != null) rawCovers[artUrl] = bmp
                    rawCovers[album.key] = bmp
                    continue
                }
            }
            val match = localSongs.firstOrNull { it.albumName?.equals(album.title, ignoreCase = true) == true }
                ?: downloads.firstOrNull { it.albumName?.equals(album.title, ignoreCase = true) == true }
            if (match != null) {
                val key = match.localUri ?: match.localPath ?: match.videoId
                val resolvedUrl = match.thumbnailUrl ?: LocalMediaRepository.getCachedLocalArtThumbUri(context, key)
                val bmp = (resolvedUrl?.let { loadBitmap(context, it) })
                    ?: com.music.bitchord.ui.components.decodeSampledBitmap(context, key, 256)
                    ?: com.music.bitchord.ui.components.localThumbFileByTitleArtist(context, match.title, match.artist)?.let {
                        com.music.bitchord.ui.components.decodeSampledBitmapFromFile(it.absolutePath, 256)
                    }
                if (bmp != null) {
                    if (artUrl != null) rawCovers[artUrl] = bmp
                    rawCovers[album.key] = bmp
                }
            }
        }
    }
    val covers: Map<String, Bitmap?> = rawCovers

    withContext(Dispatchers.Default) {
        val lit = page?.let { summary.storyArtwork(it) }
            ?: songs.firstOrNull()?.artworkUrl
        val topArtistArt = artists.firstOrNull()?.artworkUrl

        val bitmap = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val type = Fonts(context)

        // Multi-tone glass backdrop: uses active theme ARGB colors when in AUTO mode
        val effectivePaletteColors = if (glassTheme == ReplayGlassTheme.AUTO) {
            themeColors ?: resolveDefaultThemeArgbColors(context)
        } else {
            glassTheme.argbColors
        }
        drawBackdrop(
            canvas = canvas,
            lead = covers[lit] ?: covers[songs.firstOrNull()?.key],
            hue = page?.let(::storyHue) ?: 0f,
            paletteColors = effectivePaletteColors,
            secondaryLead = covers[topArtistArt],
        )

        if (page == null) {
            val gap = 24f
            val headerH = 100f
            val heroH = 286f
            val rows = maxOf(songs.size, artists.size).coerceAtMost(POSTER_ROWS).coerceAtLeast(1)
            val rowH = 92f
            val columnsH = 68f + rows * rowH + 16f
            val hasAlbums = albums.isNotEmpty()
            val albumsH = if (hasAlbums) 380f else 0f
            val footerH = 96f

            val totalHeight = headerH + gap + heroH + gap + columnsH + (if (hasAlbums) gap + albumsH else 0f) + gap + footerH
            val startY = ((POSTER_H - totalHeight) / 2f).coerceAtLeast(64f)

            val leadCover = covers[lit] ?: covers[songs.firstOrNull()?.key]
            val palette = paletteOf(leadCover)

            // Resolve active theme primary and accent colors for dynamic, theme-adaptive tinting
            val themePrimary = if (effectivePaletteColors.size > 1) effectivePaletteColors[1] else effectivePaletteColors.firstOrNull() ?: 0xFF3B82F6.toInt()
            val themeAccent = if (effectivePaletteColors.size > 2) effectivePaletteColors[2] else themePrimary
            val accentColor = if (glassTheme == ReplayGlassTheme.AUTO && palette.isNotEmpty()) {
                palette.first()
            } else {
                themePrimary
            }
            val secondaryAccent = if (glassTheme == ReplayGlassTheme.AUTO && palette.size > 1) {
                palette[1]
            } else {
                themeAccent
            }

            var y = drawEditorialHeader(canvas, context, type, summary, holder, memberSince, startY, headerH)
            y += gap
            y = drawUnifiedHeroCard(canvas, context, type, summary, accentColor, secondaryAccent, y, heroH)
            y += gap
            y = drawColumns(canvas, context, type, songs, artists, covers, y, columnsH, rows, rowH)
            if (hasAlbums) {
                y += gap
                y = drawAlbums(canvas, context, type, albums, covers, y, albumsH)
            }
            y += gap
            drawSignatureFooterCard(canvas, context, type, y, footerH)
        } else {
            var y = drawEditorialHeader(canvas, context, type, summary, holder, memberSince, 120f, 100f)
            y = drawRuns(canvas, type, summary.storyHeadline(context, page), MARGIN, y, CONTENT_W)
            drawCard(canvas, context, type, summary, page, covers, y + 56f)
            drawSignatureFooterCard(canvas, context, type, POSTER_H - 140f, 96f)
        }
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
    secondaryLead: Bitmap? = null,
) {
    val songColors = paletteColors ?: paletteOf(lead).map { rotated(it, hue) }
    val artistColors = secondaryLead?.let { paletteOf(it).map { c -> rotated(c, hue) } }.orEmpty()
    val blendedColors = if (artistColors.isNotEmpty()) {
        listOf(
            songColors.first(),
            artistColors.first(),
            songColors.getOrElse(1) { songColors.first() },
            artistColors.getOrElse(1) { artistColors.first() },
        )
    } else {
        songColors
    }
    canvas.drawColor(dimmed(blendedColors.first()))

    val anchors = listOf(
        0.16f to 0.14f,
        0.86f to 0.24f,
        0.50f to 0.50f,
        0.14f to 0.72f,
        0.84f to 0.84f,
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    anchors.forEachIndexed { index, (fx, fy) ->
        val color = blendedColors[index % blendedColors.size]
        val cx = POSTER_W * fx
        val cy = height * fy
        val radius = POSTER_W * 0.92f
        paint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, 215),
                ColorUtils.setAlphaComponent(color, 75),
                ColorUtils.setAlphaComponent(color, 0),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
    paint.shader = null

    // Specular diagonal aurora sheen overlay for liquid glass depth
    paint.shader = android.graphics.LinearGradient(
        0f,
        0f,
        POSTER_W.toFloat(),
        height,
        intArrayOf(0x32FFFFFF, 0x00FFFFFF, 0x18FFFFFF),
        floatArrayOf(0f, 0.48f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, POSTER_W.toFloat(), height, paint)
    paint.shader = null

    // Rich radial dark glass vignette to pop Bento tiles
    val vignetteRadius = POSTER_W * 1.15f
    paint.shader = RadialGradient(
        POSTER_W / 2f,
        height * 0.48f,
        vignetteRadius,
        intArrayOf(
            0x2005080E,
            0x7005080E,
            0xDE04060A.toInt(),
        ),
        floatArrayOf(0f, 0.55f, 1f),
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
    fillAlpha: Int = 0x22,
    borderAlphaTop: Int = 61,
    borderAlphaBottom: Int = 15,
    accentGlow: Int? = null,
) {
    // 1. Subtle glow orb behind card
    val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            bounds.centerX(),
            bounds.centerY(),
            bounds.width() * 0.75f,
            if (accentGlow != null) {
                intArrayOf(ColorUtils.setAlphaComponent(accentGlow, 55), ColorUtils.setAlphaComponent(accentGlow, 15), 0x00FFFFFF)
            } else {
                intArrayOf(0x28FFFFFF, 0x0DFFFFFF, 0x00FFFFFF)
            },
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, orbPaint)

    // 2. Base semi-translucent glass fill (Liquid Crystal Glass surface)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (fillAlpha shl 24) or 0x00121624
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)

    // 3. Specular outer border: top-left to bottom-right gradient
    val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
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
            floatArrayOf(0f, 0.60f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, rimPaint)

    // 4. Inset inner specular highlight border
    val insetBounds = RectF(bounds.left + 2.5f, bounds.top + 2.5f, bounds.right - 2.5f, bounds.bottom - 2.5f)
    val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x22FFFFFF
    }
    canvas.drawRoundRect(insetBounds, cornerRadius - 2f, cornerRadius - 2f, innerRimPaint)
}

private fun drawEditorialHeader(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    top: Float = 100f,
    height: Float = 100f,
): Float {
    val locale = context.resources.configuration.locales[0]
    val label = summary.localizedLabel(context)
    val yearText = if (label.length == 4 && label.all { it.isDigit() }) label else "2026"

    val replayPaint = type.heading(44f, Color.WHITE)
    val replayW = replayPaint.measureText("REPLAY")
    canvas.drawText("REPLAY", MARGIN, top + 42f, replayPaint)

    // Glowing accent year pill next to REPLAY
    val pillFont = type.label(20f, 0xFFFFB300.toInt(), tracking = 0.10f).apply {
        textAlign = Paint.Align.CENTER
    }
    val pillTextW = pillFont.measureText(yearText)
    val pillW = pillTextW + 34f
    val pillH = 36f
    val pillX = MARGIN + replayW + 16f
    val pillBounds = RectF(pillX, top + 10f, pillX + pillW, top + 10f + pillH)

    val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFB300.toInt()
        style = Paint.Style.FILL
    }
    val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    canvas.drawRoundRect(pillBounds, 18f, 18f, pillFill)
    canvas.drawRoundRect(pillBounds, 18f, 18f, pillBorder)
    canvas.drawText(yearText, pillBounds.centerX(), pillBounds.centerY() + 7f, pillFont)

    // Subtitle below: "UJJAL MALLIK · SINCE 10/26"
    val credit = listOfNotNull(
        holder.ifBlank { "UJJAL MALLIK" },
        memberSince?.let { "SINCE $it" } ?: "MEMBER",
    ).joinToString(" · ")
    val creditPaint = type.label(20f, 0xAAFFFFFF.toInt(), tracking = 0.14f)
    canvas.drawText(credit.uppercase(locale), MARGIN, top + 84f, creditPaint)

    // Larger UMtify logo on the right, aligned cleanly with REPLAY 2026 headline
    drawWordmark(canvas, context, POSTER_W - MARGIN, top + 46f, height = 56f)

    return top + height
}

private fun drawUnifiedHeroCard(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    summary: ReplaySummary,
    primaryColor: Int,
    accentColor: Int,
    top: Float,
    heroH: Float = 286f,
): Float {
    val bounds = RectF(MARGIN, top, POSTER_W - MARGIN, top + heroH)
    drawLiquidGlassCard(
        canvas,
        bounds,
        cornerRadius = 56f, // RoundedCornerShape(28.dp)
        fillAlpha = 0x24,
        accentGlow = primaryColor,
    )

    val locale = context.resources.configuration.locales[0]

    // 1. "MINUTES LISTENED" pill
    val pillText = context.getString(R.string.minutes_listened).uppercase(locale)
    val pillFont = type.label(17f, Color.WHITE, tracking = 0.16f).apply {
        textAlign = Paint.Align.CENTER
    }
    val pillTextW = pillFont.measureText(pillText)
    val pillW = pillTextW + 36f
    val pillH = 32f
    val pillBounds = RectF(MARGIN + 28f, top + 22f, MARGIN + 28f + pillW, top + 22f + pillH)
    val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x48FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    canvas.drawRoundRect(pillBounds, 16f, 16f, pillFill)
    canvas.drawRoundRect(pillBounds, 16f, 16f, pillBorder)
    canvas.drawText(pillText, pillBounds.centerX(), pillBounds.centerY() + 6f, pillFont)

    // 2. Massive bold minutes listened with subtle soft glow & balanced vertical breathing room
    val numberText = formatMinutes(summary.totalMs)
    val numberPaint = type.heading(104f, Color.WHITE)
    val glowPaint = type.heading(104f, ColorUtils.setAlphaComponent(accentColor, 110))
    canvas.drawText(numberText, MARGIN + 28f + 1f, top + 164f, glowPaint)
    canvas.drawText(numberText, MARGIN + 28f, top + 162f, numberPaint)

    // 3. Compact row of 3 frosted stat pills underneath (Plays, Songs, Artists)
    val chips = listOf(
        context.replayCount(summary.totalPlays, R.plurals.replay_play_count),
        context.replayCount(summary.distinctSongs, R.plurals.replay_song_count),
        context.replayCount(summary.distinctArtists, R.plurals.replay_artist_count),
    )
    val chipFont = type.body(20f, 0xEEFFFFFF.toInt(), bold = true).apply {
        textAlign = Paint.Align.CENTER
    }
    val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    val chipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x36FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    val chipH = 38f
    val chipGap = 12f
    val maxChipsW = 540f
    val chipW = (maxChipsW - chipGap * (chips.size - 1)) / chips.size
    val chipY = top + 224f

    chips.forEachIndexed { i, labelText ->
        val cx = MARGIN + 28f + i * (chipW + chipGap)
        val cBounds = RectF(cx, chipY, cx + chipW, chipY + chipH)
        canvas.drawRoundRect(cBounds, 14f, 14f, chipFill)
        canvas.drawRoundRect(cBounds, 14f, 14f, chipBorder)
        canvas.drawText(ellipsised(labelText, chipFont, chipW - 14f), cBounds.centerX(), cBounds.centerY() + 7f, chipFont)
    }

    // 4. Right side: Decorative translucent Audio Waveform / Equalizer Bars graphic
    val waveW = 240f
    val waveRight = POSTER_W - MARGIN - 32f
    val waveLeft = waveRight - waveW
    val waveCenterY = top + heroH / 2f
    val waveH = 190f
    drawAudioWaveform(canvas, waveLeft, waveCenterY, waveW, waveH, primaryColor, accentColor)

    return top + heroH
}

private fun drawAudioWaveform(
    canvas: Canvas,
    x: Float,
    centerY: Float,
    width: Float,
    height: Float,
    primaryColor: Int,
    accentColor: Int,
) {
    val barRatios = floatArrayOf(0.25f, 0.50f, 0.85f, 0.62f, 0.95f, 0.74f, 1.0f, 0.76f, 0.48f, 0.70f, 0.35f)
    val barCount = barRatios.size
    val barWidth = 11f
    val gap = (width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)

    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    barRatios.forEachIndexed { i, ratio ->
        val barH = (height * ratio).coerceAtLeast(14f)
        val bx = x + i * (barWidth + gap)
        val bTop = centerY - barH / 2f
        val bBottom = centerY + barH / 2f
        val rect = RectF(bx, bTop, bx + barWidth, bBottom)
        val corner = barWidth / 2f

        // Soft translucent glow around peaks using theme accent color
        if (ratio > 0.55f) {
            val glowRadius = 4f * ratio
            val glowRect = RectF(bx - glowRadius, bTop - glowRadius, bx + barWidth + glowRadius, bBottom + glowRadius)
            glowPaint.color = ColorUtils.setAlphaComponent(accentColor, (70 * ratio).toInt())
            canvas.drawRoundRect(glowRect, corner + glowRadius, corner + glowRadius, glowPaint)
        }

        // Dynamically tint the waveform bars using active card theme's primary/accent gradient colors
        barPaint.shader = LinearGradient(
            bx, bTop, bx, bBottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(primaryColor, 240),
                ColorUtils.setAlphaComponent(accentColor, 215),
                ColorUtils.setAlphaComponent(accentColor, 100),
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, corner, corner, barPaint)
        barPaint.shader = null
    }
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
    cardH: Float = 544f,
    rows: Int = 5,
    rowH: Float = 92f,
): Float {
    val columnGap = 18f
    val colWidth = (CONTENT_W - columnGap) / 2f
    val leftX = MARGIN
    val rightX = MARGIN + colWidth + columnGap

    val leftBounds = RectF(leftX, top, leftX + colWidth, top + cardH)
    val rightBounds = RectF(rightX, top, rightX + colWidth, top + cardH)

    drawLiquidGlassCard(canvas, leftBounds, cornerRadius = 32f, fillAlpha = 0x22)
    drawLiquidGlassCard(canvas, rightBounds, cornerRadius = 32f, fillAlpha = 0x22)

    val locale = context.resources.configuration.locales[0]
    canvas.drawText(
        context.getString(R.string.top_songs).uppercase(locale),
        leftX + 24f,
        top + 46f,
        type.label(21f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )
    canvas.drawText(
        context.getString(R.string.top_artists).uppercase(locale),
        rightX + 24f,
        top + 46f,
        type.label(21f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )

    var rowY = top + 68f
    repeat(rows) { index ->
        songs.getOrNull(index)?.let {
            drawRow(canvas, context, type, it, covers, leftX + 18f, rowY, colWidth - 36f, false)
        }
        artists.getOrNull(index)?.let {
            drawRow(canvas, context, type, it, covers, rightX + 18f, rowY, colWidth - 36f, true)
        }
        rowY += rowH
    }
    return top + cardH
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
        color = if (isTopOne) 0x44FA2D48.toInt() else 0x20FFFFFF
        style = Paint.Style.FILL
    }
    val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isTopOne) 0x99FA2D48.toInt() else 0x38FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    canvas.drawRoundRect(badgeBounds, 14f, 14f, badgePaint)
    canvas.drawRoundRect(badgeBounds, 14f, 14f, badgeBorder)

    val rankText = type.heading(18f, if (isTopOne) 0xFFFF7088.toInt() else 0xEEFFFFFF.toInt())
    rankText.textAlign = Paint.Align.CENTER
    canvas.drawText(
        row.rank.toString(),
        badgeBounds.centerX(),
        badgeBounds.centerY() + 6f,
        rankText,
    )

    val artX = x + badgeSize + 10f
    val art = row.artworkUrl?.let { covers[it] } ?: covers[row.key]
    drawArtwork(canvas, art, row.title, artX, y, artSize, circular)

    val textX = artX + artSize + 14f
    val textWidth = width - (textX - x)
    val title = type.body(23f, Color.WHITE, bold = true)
    canvas.drawText(ellipsised(row.title, title, textWidth), textX, y + 26f, title)
    val sub = type.body(18f, 0x99FFFFFF.toInt())
    val detail = listOfNotNull(row.subtitle, formatListening(context, row.ms)).joinToString(" · ")
    canvas.drawText(ellipsised(detail, sub, textWidth), textX, y + 51f, sub)
}

private fun drawAlbums(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    albums: List<ReplayRow>,
    covers: Map<String, Bitmap?>,
    top: Float,
    cardHeight: Float = 380f,
): Float {
    if (albums.isEmpty()) return top
    val cardBounds = RectF(MARGIN, top, POSTER_W - MARGIN, top + cardHeight)
    drawLiquidGlassCard(canvas, cardBounds, cornerRadius = 32f, fillAlpha = 0x22)

    val locale = context.resources.configuration.locales[0]
    canvas.drawText(
        context.getString(R.string.top_albums).uppercase(locale),
        MARGIN + 24f,
        top + 42f,
        type.label(21f, 0xEEFFFFFF.toInt(), tracking = 0.14f),
    )

    val items = albums.take(POSTER_ALBUMS)
    val innerMargin = 20f
    val availableW = CONTENT_W - innerMargin * 2
    val tileSpacing = 18f
    val tileW = (availableW - tileSpacing * (items.size - 1)) / items.size.coerceAtLeast(1)
    val tileH = tileW // aspectRatio(1f) filling its tile width
    val artY = top + 64f

    val tileRadius = 24f

    items.forEachIndexed { index, album ->
        val x = MARGIN + innerMargin + index * (tileW + tileSpacing)
        val tileBounds = RectF(x, artY, x + tileW, artY + tileH)

        // Drop shadow behind showcase tile
        val shadowBounds = RectF(x + 2f, artY + 6f, x + tileW + 2f, artY + tileH + 6f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        canvas.drawRoundRect(shadowBounds, tileRadius, tileRadius, shadowPaint)

        // Clip strictly to tileBounds with tileRadius for cover art & bottom gradient
        val clipPath = Path().apply {
            addRoundRect(tileBounds, tileRadius, tileRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        val art = album.artworkUrl?.let { covers[it] } ?: covers[album.key]
        if (art != null) {
            val scale = tileW / minOf(art.width, art.height).toFloat()
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    x - (art.width * scale - tileW) / 2f,
                    artY - (art.height * scale - tileH) / 2f,
                )
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(matrix)
                }
            }
            canvas.drawRect(tileBounds, paint)
        } else {
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 255, 255, 255)
                style = Paint.Style.FILL
            }
            canvas.drawRect(tileBounds, tilePaint)
            drawMusicNoteGlyph(canvas, tileBounds.centerX(), tileBounds.centerY(), tileW * 0.44f)
        }

        // Sleek frosted dark bottom gradient inside the tile holding album title & artist
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                x, artY + tileH * 0.40f, x, artY + tileH,
                intArrayOf(0x00000000, 0x990A0D14.toInt(), 0xF20A0D14.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(RectF(x, artY + tileH * 0.40f, x + tileW, artY + tileH), gradientPaint)
        canvas.restore()

        // Subtle rank pill badge (#1, #2, #3) in the top-left corner
        val rankText = "#${index + 1}"
        val rankPaint = type.label(16f, Color.WHITE, tracking = 0.06f).apply {
            textAlign = Paint.Align.CENTER
        }
        val rankPillW = rankPaint.measureText(rankText) + 20f
        val rankPillH = 26f
        val rankBounds = RectF(x + 12f, artY + 12f, x + 12f + rankPillW, artY + 12f + rankPillH)
        val rankFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
        val rankBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(rankBounds, 13f, 13f, rankFill)
        canvas.drawRoundRect(rankBounds, 13f, 13f, rankBorder)
        canvas.drawText(rankText, rankBounds.centerX(), rankBounds.centerY() + 5.5f, rankPaint)

        // Sleek glass border around the showcase tile with exact same radius
        val tileBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3EFFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        canvas.drawRoundRect(tileBounds, tileRadius, tileRadius, tileBorder)

        // Bold album title and artist name inside bottom frosted area
        val name = type.body(20f, Color.WHITE, bold = true)
        canvas.drawText(ellipsised(album.title, name, tileW - 24f), x + 12f, artY + tileH - 34f, name)
        val sub = type.body(16f, 0xCCFFFFFF.toInt())
        val subText = album.subtitle ?: formatListening(context, album.ms)
        canvas.drawText(
            ellipsised(subText, sub, tileW - 24f),
            x + 12f,
            artY + tileH - 14f,
            sub,
        )
    }
    return top + cardHeight
}

private fun drawSignatureFooterCard(
    canvas: Canvas,
    context: Context,
    type: Fonts,
    top: Float,
    stripH: Float = 96f,
): Float {
    val bounds = RectF(MARGIN, top, POSTER_W - MARGIN, top + stripH)
    drawLiquidGlassCard(canvas, bounds, cornerRadius = 48f, fillAlpha = 0x22)

    val logoLeft = MARGIN + 28f
    val centerY = top + stripH / 2f
    val logoBaseline = centerY + 10f

    // UMtify logo on the left
    val mark = runCatching {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_umtify_wordmark, null)
    }.getOrNull()

    var textStartX = logoLeft
    if (mark != null) {
        val wordmarkW = 32f * 800f / 275f // ~93f
        val wordmarkH = 32f
        val wmTop = centerY - wordmarkH / 2f
        mark.setTint(Color.WHITE)
        mark.setBounds(logoLeft.toInt(), wmTop.toInt(), (logoLeft + wordmarkW).toInt(), (wmTop + wordmarkH).toInt())
        mark.draw(canvas)
        textStartX = logoLeft + wordmarkW + 20f
    } else {
        val brandPaint = type.heading(28f, Color.WHITE)
        canvas.drawText("UMtify", textStartX, logoBaseline, brandPaint)
        textStartX += brandPaint.measureText("UMtify") + 16f
    }

    // Divider dot
    val dotPaint = type.body(20f, 0x66FFFFFF)
    canvas.drawText("·", textStartX, centerY + 6f, dotPaint)
    val afterDotX = textStartX + dotPaint.measureText("·") + 14f

    // Tagline: "Music. Style. Experience."
    val taglinePaint = type.label(18f, 0xCCFFFFFF.toInt(), tracking = 0.12f)
    canvas.drawText("Music. Style. Experience.", afterDotX, centerY + 6f, taglinePaint)

    // Right side: Subtle frosted pill "Liquid Glass · 2026"
    val pillText = "Liquid Glass · 2026"
    val pillFont = type.label(17f, 0xEEFFFFFF.toInt(), tracking = 0.10f).apply {
        textAlign = Paint.Align.CENTER
    }
    val pillW = pillFont.measureText(pillText) + 38f
    val pillH = 42f
    val pillRight = POSTER_W - MARGIN - 24f
    val pillLeft = pillRight - pillW
    val pillTop = top + (stripH - pillH) / 2f
    val pillBounds = RectF(pillLeft, pillTop, pillRight, pillTop + pillH)

    val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x48FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    canvas.drawRoundRect(pillBounds, 21f, 21f, pillFill)
    canvas.drawRoundRect(pillBounds, 21f, 21f, pillBorder)
    canvas.drawText(pillText, pillBounds.centerX(), pillBounds.centerY() + 6f, pillFont)

    return top + stripH
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
    val radius = if (circular) size / 2f else size * 0.18f
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
            strokeWidth = 1.5f
            color = 0x33FFFFFF
        }
        canvas.drawRoundRect(bounds, radius, radius, rimPaint)
        paint.shader = null
    } else {
        // CLEAN MINIMALIST MUSIC NOTE ON DARK FROSTED TILE (NEVER FLAT PINK/GREEN SQUARES!)
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, radius, radius, tilePaint)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.argb(60, 255, 255, 255)
        }
        canvas.drawRoundRect(bounds, radius, radius, rimPaint)
        drawMusicNoteGlyph(canvas, bounds.centerX(), bounds.centerY(), size * 0.44f)
    }
}

private fun drawMusicNoteGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float) {
    val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 255, 255, 255)
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = (size * 0.11f).coerceAtLeast(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val w = size * 0.76f
    val h = size * 0.86f
    val left = cx - w / 2f
    val top = cy - h / 2f
    val headR = size * 0.16f

    // Note heads
    canvas.drawCircle(left + headR, top + h - headR, headR, notePaint)
    canvas.drawCircle(left + w - headR, top + h - headR * 1.3f, headR, notePaint)

    // Stems & Beam
    canvas.drawLine(left + headR * 1.7f, top + h - headR, left + headR * 1.7f, top + headR, strokePaint)
    canvas.drawLine(left + w, top + h - headR * 1.3f, left + w, top, strokePaint)
    canvas.drawLine(left + headR * 1.7f, top + headR, left + w, top, strokePaint)
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
internal fun drawWordmark(
    canvas: Canvas,
    context: Context,
    right: Float,
    baseline: Float,
    height: Float = WORDMARK_H,
) {
    val mark = runCatching {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_umtify_wordmark, null)
    }.getOrNull() ?: return
    val width = height * 800f / 275f
    val left = right - width
    val top = baseline - height
    mark.setTint(0xF0FFFFFF.toInt())
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

internal suspend fun loadBitmap(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val cleanPath = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("local_art://") -> url.removePrefix("local_art://")
            url.startsWith("/") -> url
            else -> null
        }
        if (cleanPath != null) {
            val file = File(cleanPath)
            if (file.exists() && file.length() > 0) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) return@withContext bmp
            }
            val hash = cleanPath.hashCode().toUInt().toString()
            val thumb = File(File(context.filesDir, "local_art_thumb"), "$hash.jpg")
            if (thumb.exists() && thumb.length() > 0) {
                val bmp = BitmapFactory.decodeFile(thumb.absolutePath)
                if (bmp != null) return@withContext bmp
            }
            val full = File(File(context.filesDir, "local_art_full"), "$hash.jpg")
            if (full.exists() && full.length() > 0) {
                val bmp = BitmapFactory.decodeFile(full.absolutePath)
                if (bmp != null) return@withContext bmp
            }
            val cached = LocalMediaRepository.extractAndCacheLocalArt(context, cleanPath, cleanPath)
            if (cached != null) {
                val f = File(cached.removePrefix("file://"))
                if (f.exists() && f.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) return@withContext bmp
                }
            }
        }

        val request = ImageRequest.Builder(context)
            .data(url.artworkAt(400))
            // Palette needs pixel access, and a hardware bitmap cannot be drawn
            // into a software canvas at all — which is the whole of this file.
            .allowHardware(false)
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}

/** 9:16, the shape the story cards are held to — see `StoryFrame`. */
private const val POSTER_W = 1080
private const val POSTER_H = 1920

private const val MARGIN = 64f

/** The wordmark's drawn size: 800×275 in the asset, enlarged to match REPLAY headline height. */
private const val WORDMARK_H = 56f
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
