package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.components.liquidGlassCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    var selectedFilter by remember { mutableStateOf("All Vibes") }
    val filterOptions = remember {
        listOf("All Vibes", "Moods & Moments", "Genres", "Charts & Trending")
    }

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                }

                // Top "Vibe & Moods" Frosted Glass Filter Bar
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = PAGE_GUTTER)
                            .padding(bottom = 16.dp),
                    ) {
                        filterOptions.forEach { filter ->
                            val isSelected = filter == selectedFilter
                            val primary = MaterialTheme.colorScheme.primary
                            val chipBg by animateColorAsState(
                                targetValue = if (isSelected) primary.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                                animationSpec = spring(),
                                label = "chipBg",
                            )
                            val chipBorderColor by animateColorAsState(
                                targetValue = if (isSelected) primary.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.18f),
                                animationSpec = spring(),
                                label = "chipBorder",
                            )
                            val textColor by animateColorAsState(
                                targetValue = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                animationSpec = spring(),
                                label = "textColor",
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(chipBg)
                                    .border(
                                        width = if (isSelected) 1.2.dp else 0.8.dp,
                                        color = chipBorderColor,
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    .clickable { selectedFilter = filter }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = filter,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = textColor,
                                )
                            }
                        }
                    }
                }

                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        val allSections = state.data
                        val filteredSections = when (selectedFilter) {
                            "Moods & Moments" -> {
                                val moodSections = allSections.filter { sec ->
                                    sec.title.contains("mood", ignoreCase = true) ||
                                        sec.title.contains("moment", ignoreCase = true)
                                }
                                if (moodSections.isNotEmpty()) {
                                    moodSections
                                } else {
                                    val moodKeywords = setOf("chill", "commute", "energy", "focus", "party", "romance", "sleep", "workout", "feel good", "relax", "sad")
                                    val items = allSections.flatMap { it.items }.filter { item ->
                                        moodKeywords.any { kw -> item.title.contains(kw, ignoreCase = true) }
                                    }
                                    if (items.isNotEmpty()) listOf(MoodGenreSection("Moods & Moments", items)) else allSections
                                }
                            }
                            "Genres" -> {
                                val genreSections = allSections.filter { sec ->
                                    sec.title.contains("genre", ignoreCase = true)
                                }
                                if (genreSections.isNotEmpty()) {
                                    genreSections
                                } else {
                                    val genreKeywords = setOf("pop", "rock", "hip-hop", "rap", "r&b", "electronic", "dance", "indie", "metal", "classical", "jazz", "country", "folk", "latin", "k-pop", "j-pop")
                                    val items = allSections.flatMap { it.items }.filter { item ->
                                        genreKeywords.any { kw -> item.title.contains(kw, ignoreCase = true) }
                                    }
                                    if (items.isNotEmpty()) listOf(MoodGenreSection("Genres", items)) else allSections
                                }
                            }
                            "Charts & Trending" -> {
                                val chartSections = allSections.filter { sec ->
                                    sec.title.contains("chart", ignoreCase = true) ||
                                        sec.title.contains("trend", ignoreCase = true) ||
                                        sec.title.contains("top", ignoreCase = true)
                                }
                                if (chartSections.isNotEmpty()) {
                                    chartSections
                                } else {
                                    val chartKeywords = setOf("chart", "top", "trending", "hits", "hot", "viral")
                                    val items = allSections.flatMap { it.items }.filter { item ->
                                        chartKeywords.any { kw -> item.title.contains(kw, ignoreCase = true) }
                                    }
                                    if (items.isNotEmpty()) listOf(MoodGenreSection("Charts & Trending", items)) else allSections
                                }
                            }
                            else -> allSections
                        }

                        val rows = filteredSections.flatMap(MoodGenreSection::items)
                            .distinctBy { it.browseId to it.params }
                            .chunked(columns)
                        items(rows, key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                            MoodGenreRow(row = row, columns = columns, onCategoryClick = onCategoryClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEach { item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                modifier = Modifier.weight(1f),
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(22.dp)
    MoodGenreSleeve(
        item = item,
        modifier = modifier
            .aspectRatio(MOOD_CARD_ASPECT)
            .liquidGlassCard(shape = cardShape)
            .clip(cardShape)
            .clickable(onClick = onClick),
    )
}

/**
 * Unique full-bleed Liquid Glass Bento Card for Explore moods and genres.
 *
 * Full-bleed artwork with high-gloss diagonal multi-stop scrim,
 * frosted glass top-left pill badge with category glowing dot,
 * and a subtle bottom-right specular glass play orb.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val tone = remember(item.stripeColor, item.title) { moodTone(item) }

    Box(modifier = modifier.background(tone.placeholder)) {
        // 1. Full-bleed category cover artwork
        item.thumbnailUrl?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2. Rich Multi-Stop Liquid Glass Scrim (Top-Right sheen -> Center accent -> Bottom-Left moody dark)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.12f),
                            tone.stripe.copy(alpha = 0.38f),
                            Color.Black.copy(alpha = 0.82f),
                        ),
                        start = Offset(Float.POSITIVE_INFINITY, 0f),
                        end = Offset(0f, Float.POSITIVE_INFINITY),
                    ),
                ),
        )

        // Top edge crystal highlight line for glass depth
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 3. Top-Left Frosted Glass Pill Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Category glowing dot
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(tone.stripe),
                )
                Text(
                    text = item.title,
                    style = titleStyle.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(0f, 1f),
                            blurRadius = 4f,
                        ),
                    ),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 4. Bottom-Right Subtle Glass Play Orb
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .border(
                    width = 0.8.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.15f),
                        ),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private class MoodTone(val stripe: Color, val placeholder: Color)

private fun moodTone(item: MoodGenre): MoodTone {
    val base = item.stripeColor?.let { Color(it.toInt()) } ?: fallbackMoodColor(item.title)
    val stripe = lerp(base, Color.Black, .08f)
    val shadow = lerp(base, Color.Black, .68f)
    val highlight = lerp(base, Color.White, .32f)
    return MoodTone(
        stripe = stripe,
        placeholder = lerp(shadow, highlight, .45f),
    )
}

/** For a button that came without YouTube's colour: muted, never neon. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(22.dp)

/** Width over height; a two-column phone row lands at about 100dp tall. */
const val MOOD_CARD_ASPECT = 1.68f

/**
 * The narrowest a mood card is let get before the row drops a column.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
