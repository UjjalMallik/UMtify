package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import com.music.bitchord.ui.icons.BitChordIcons
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
    customOrder: List<String> = emptyList(),
    onReorder: ((List<String>) -> Unit)? = null,
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
            val allSections = (state as? UiState.Success)?.data ?: emptyList()
            val filteredSections = remember(allSections, selectedFilter) {
                when (selectedFilter) {
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
            }

            val allItems = remember(filteredSections, customOrder) {
                val items = filteredSections.flatMap(MoodGenreSection::items)
                    .distinctBy { it.browseId to it.params }
                if (customOrder.isEmpty()) {
                    items
                } else {
                    val orderMap = customOrder.withIndex().associate { it.value to it.index }
                    items.sortedBy { orderMap[it.title] ?: (10000 + items.indexOf(it)) }
                }
            }

            val isBn = remember { java.util.Locale.getDefault().language == "bn" }
            var liveItems by remember(allItems) { mutableStateOf(allItems) }
            var draggedItemTitle by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableStateOf(Offset.Zero) }
            var cellWidthPx by remember { mutableFloatStateOf(0f) }
            var cellHeightPx by remember { mutableFloatStateOf(0f) }

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

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = PAGE_GUTTER)
                            .padding(top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filterOptions.forEach { filter ->
                            val isSelected = filter == selectedFilter
                            val chipBg by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                animationSpec = spring(),
                                label = "chipBg",
                            )
                            val chipBorderColor by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                } else {
                                    Color.White.copy(alpha = 0.12f)
                                },
                                animationSpec = spring(),
                                label = "chipBorderColor",
                            )
                            val textColor by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
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
                                val chipLabel = when (filter) {
                                    "All Vibes" -> if (isBn) "সব ভাইব" else "All Vibes"
                                    "Moods & Moments" -> if (isBn) "মুড ও মুহূর্ত" else "Moods & Moments"
                                    "Genres" -> if (isBn) "জনরা" else "Genres"
                                    "Charts & Trending" -> if (isBn) "চার্ট ও ট্রেন্ডিং" else "Charts & Trending"
                                    else -> filter
                                }
                                Text(
                                    text = chipLabel,
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
                        val rows = liveItems.chunked(columns)
                        items(rows, key = { row -> row.joinToString("|") { it.title } }) { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                                modifier = Modifier
                                    .padding(horizontal = PAGE_GUTTER)
                                    .padding(bottom = MOOD_SPACING),
                            ) {
                                row.forEach { item ->
                                    val isDragging = item.title == draggedItemTitle
                                    MoodGenreCard(
                                        item = item,
                                        onClick = { onCategoryClick(item) },
                                        isDragging = isDragging,
                                        dragOffset = if (isDragging) dragOffset else Offset.Zero,
                                        onDragStart = {
                                            draggedItemTitle = item.title
                                            dragOffset = Offset.Zero
                                        },
                                        onDrag = { delta ->
                                            dragOffset += delta
                                            val currentIndex = liveItems.indexOfFirst { it.title == item.title }
                                            if (currentIndex >= 0 && cellWidthPx > 0 && cellHeightPx > 0) {
                                                val colShift = when {
                                                    dragOffset.x > cellWidthPx * 0.5f -> 1
                                                    dragOffset.x < -cellWidthPx * 0.5f -> -1
                                                    else -> 0
                                                }
                                                val rowShift = when {
                                                    dragOffset.y > cellHeightPx * 0.5f -> 1
                                                    dragOffset.y < -cellHeightPx * 0.5f -> -1
                                                    else -> 0
                                                }
                                                if (colShift != 0 || rowShift != 0) {
                                                    val targetIndex = (currentIndex + colShift + rowShift * columns).coerceIn(0, liveItems.lastIndex)
                                                    if (targetIndex != currentIndex) {
                                                        liveItems = liveItems.toMutableList().apply {
                                                            add(targetIndex, removeAt(currentIndex))
                                                        }
                                                        dragOffset = Offset(
                                                            dragOffset.x - colShift * cellWidthPx,
                                                            dragOffset.y - rowShift * cellHeightPx,
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedItemTitle = null
                                            dragOffset = Offset.Zero
                                            onReorder?.invoke(liveItems.map { it.title })
                                        },
                                        onDragCancel = {
                                            draggedItemTitle = null
                                            dragOffset = Offset.Zero
                                            liveItems = allItems
                                        },
                                        onSize = { width, height ->
                                            cellWidthPx = width
                                            cellHeightPx = height
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
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
    isDragging: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onSize: (Float, Float) -> Unit = { _, _ -> },
) {
    val cardShape = RoundedCornerShape(22.dp)
    val scale = if (isDragging) 1.06f else 1.0f
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = dragOffset.x
                translationY = dragOffset.y
                shadowElevation = if (isDragging) 28f else 0f
            }
            .then(
                if (isDragging) {
                    Modifier.border(
                        width = 2.dp,
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.95f),
                                Color(0xFF64B5F6),
                                Color.White.copy(alpha = 0.95f),
                            ),
                        ),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .onSizeChanged { onSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(item.title) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDragStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            },
    ) {
        MoodGenreSleeve(
            item = item,
            onDragHandleStart = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDragStart()
            },
            onDragHandleMove = { delta -> onDrag(delta) },
            onDragHandleEnd = { onDragEnd() },
            onDragHandleCancel = { onDragCancel() },
            modifier = Modifier
                .aspectRatio(MOOD_CARD_ASPECT)
                .liquidGlassCard(shape = cardShape)
                .clip(cardShape)
                .clickable(onClick = onClick),
        )
    }
}

/**
 * Unique Luxury Frosted Glass Bento Card for Explore moods and genres.
 *
 * Distinct from low-res stretched thumbnails: features a rich curated mesh gradient,
 * crisp left-aligned category typography + curated subtitle, a glowing frosted glass play orb,
 * and a floating framed tilted artwork collage tile in the bottom-right corner.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    onDragHandleStart: () -> Unit = {},
    onDragHandleMove: (Offset) -> Unit = {},
    onDragHandleEnd: () -> Unit = {},
    onDragHandleCancel: () -> Unit = {},
) {
    val isBn = remember { java.util.Locale.getDefault().language == "bn" }
    val tone = remember(item.stripeColor, item.title) { moodTone(item) }
    val subtitle = remember(item.title, isBn) { categorySubtitle(item.title, isBn) }
    val displayTitle = remember(item.title, isBn) { localizeMoodTitle(item.title, isBn) }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        tone.stripe.copy(alpha = 0.72f),
                        tone.placeholder.copy(alpha = 0.40f),
                        Color(0xFF0D121B).copy(alpha = 0.94f),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            ),
    ) {
        // 1. Crystal highlight line across top edge
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 2. Subtle ambient soundwave watermark lines in background
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val barHeights = remember { listOf(6.dp, 12.dp, 18.dp, 10.dp, 22.dp, 14.dp, 8.dp, 16.dp, 11.dp, 20.dp, 7.dp) }
            barHeights.forEach { height ->
                Box(
                    modifier = Modifier
                        .size(width = 2.dp, height = height)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                )
            }
        }

        // 3. Floating Framed Artwork Tile (tilted in bottom-right corner)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 6.dp, y = 6.dp)
                .rotate(-6f)
                .size(66.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            tone.stripe.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = BitChordIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.88f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // 3.5. Dedicated Drag Grip Handle in top-right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .border(0.8.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .pointerInput(item.title) {
                    detectDragGestures(
                        onDragStart = { onDragHandleStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragHandleMove(dragAmount)
                        },
                        onDragEnd = { onDragHandleEnd() },
                        onDragCancel = { onDragHandleCancel() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Reorder",
                tint = Color.White.copy(alpha = 0.88f),
                modifier = Modifier.size(16.dp),
            )
        }

        // 4. Content Column on Left: Title + Subtitle + Glowing Play Orb
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.68f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.95f)),
                    )
                    Text(
                        text = displayTitle,
                        style = titleStyle.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.65f),
                                offset = Offset(0f, 1f),
                                blurRadius = 3f,
                            ),
                        ),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp,
                )
            }

            // Glowing Frosted Glass Play Orb
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f))
                    .border(
                        width = 0.8.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.65f),
                                Color.White.copy(alpha = 0.20f),
                            ),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** Localized titles for Explore moods and genres. */
fun localizeMoodTitle(title: String, isBn: Boolean = java.util.Locale.getDefault().language == "bn"): String = if (!isBn) title else when {
    title.equals("Gaming", ignoreCase = true) -> "গেমিং"
    title.equals("Chill", ignoreCase = true) -> "চিল"
    title.equals("Commute", ignoreCase = true) -> "কম্যুট"
    title.equals("Energize", ignoreCase = true) || title.equals("Energy", ignoreCase = true) -> "এনার্জাইজ"
    title.equals("Feel good", ignoreCase = true) -> "ফিল গুড"
    title.equals("Focus", ignoreCase = true) -> "ফোকাস"
    title.equals("Party", ignoreCase = true) -> "পার্টি"
    title.equals("Romance", ignoreCase = true) -> "রোমান্স"
    title.equals("Sad", ignoreCase = true) -> "স্যাড"
    title.equals("Sleep", ignoreCase = true) -> "স্লিপ"
    title.equals("Workout", ignoreCase = true) -> "ওয়ার্কআউট"
    title.equals("African", ignoreCase = true) -> "আফ্রিকান"
    title.equals("Arabic", ignoreCase = true) -> "আরবি"
    title.equals("Bengali", ignoreCase = true) -> "বাংলা"
    title.equals("Bollywood", ignoreCase = true) -> "বলিউড"
    title.equals("Classical", ignoreCase = true) -> "ক্লাসিক্যাল"
    title.equals("Country", ignoreCase = true) -> "কান্ট্রি"
    title.equals("Dance", ignoreCase = true) -> "ড্যান্স"
    title.equals("Electronic", ignoreCase = true) -> "ইলেকট্রনিক"
    title.equals("Folk", ignoreCase = true) -> "ফোক"
    title.equals("Hip-Hop", ignoreCase = true) || title.equals("Rap", ignoreCase = true) -> "হিপ-হপ"
    title.equals("Indie", ignoreCase = true) -> "ইন্ডি"
    title.equals("Jazz", ignoreCase = true) -> "জ্যাজ"
    title.equals("K-Pop", ignoreCase = true) -> "কে-পপ"
    title.equals("Latin", ignoreCase = true) -> "ল্যাটিন"
    title.equals("Metal", ignoreCase = true) -> "মেটাল"
    title.equals("Pop", ignoreCase = true) -> "পপ"
    title.equals("R&B", ignoreCase = true) -> "আরঅ্যান্ডবি"
    title.equals("Rock", ignoreCase = true) -> "রক"
    title.equals("Charts", ignoreCase = true) -> "চার্টস"
    title.equals("Trending", ignoreCase = true) -> "ট্রেন্ডিং"
    else -> title
}

/** Rich curated subtitle descriptors for Explore moods and genres. */
fun categorySubtitle(title: String, isBn: Boolean = java.util.Locale.getDefault().language == "bn"): String = when {
    title.contains("Bengali", ignoreCase = true) -> if (isBn) "সেরা হিট, রবীন্দ্র ও ক্ল্যাসিক গান" else "Top Hits, Rabindra & Classics"
    title.contains("Carnatic", ignoreCase = true) -> if (isBn) "দক্ষিণী শাস্ত্রীয় ও পবিত্র রাগ" else "South Classical & Sacred Ragas"
    title.contains("Hindustani", ignoreCase = true) -> if (isBn) "উত্তর ভারতীয় শাস্ত্রীয় ও সেতার" else "North Classical, Khayal & Sitar"
    title.contains("Hindi", ignoreCase = true) || title.contains("Bollywood", ignoreCase = true) -> if (isBn) "ব্লকবাস্টার হিট ও মেলোডি" else "Blockbusters & Melodies"
    title.contains("Chill", ignoreCase = true) -> if (isBn) "লো-ফাই, অ্যাকোস্টিক ও রিল্যাক্স সুর" else "Lo-Fi, Acoustic & Relax"
    title.contains("Commute", ignoreCase = true) -> if (isBn) "যাত্রাপথের গান ও রোডট্রিপ" else "On The Move & Roadtrips"
    title.contains("Energy", ignoreCase = true) || title.contains("Energize", ignoreCase = true) -> if (isBn) "হাই ভোল্টেজ ও পাওয়ার বিটস" else "High Voltage & Power Beats"
    title.contains("Focus", ignoreCase = true) -> if (isBn) "মনোযোগ সহকারে পড়াশোনা ও কাজ" else "Deep Study & Concentration"
    title.contains("Gaming", ignoreCase = true) -> if (isBn) "গেমিং বিটস, হাইলাইটস ও মিক্স" else "Curated Mixes & Gaming Highlights"
    title.contains("Party", ignoreCase = true) -> if (isBn) "ক্লাব অ্যান্থেম ও ড্যান্সফ্লোর হিট" else "Club Anthems & Dancefloor"
    title.contains("Romance", ignoreCase = true) -> if (isBn) "রোমান্টিক গান ও ভালোবাসার সুর" else "Love Ballads & Duets"
    title.contains("Sleep", ignoreCase = true) -> if (isBn) "শান্ত সুর ও আরামদায়ক ঘুম" else "Ambient, Drone & Calm"
    title.contains("Workout", ignoreCase = true) -> if (isBn) "জিম ও কার্ডিও পাম্প বিটস" else "Pump & Cardio Beats"
    title.contains("Feel good", ignoreCase = true) -> if (isBn) "আনন্দময় ও উজ্জ্বল অনুভূতি" else "Sunny Day Vibes & Uplift"
    title.contains("Sad", ignoreCase = true) -> if (isBn) "হৃদয়ভাঙা সুর ও মনখারাপ" else "Heartbreak & Melancholy"
    title.contains("Pop", ignoreCase = true) -> if (isBn) "টপ চার্ট ও ভাইরাল গানসমূহ" else "Chart Toppers & Viral"
    title.contains("Hip-Hop", ignoreCase = true) || title.contains("Rap", ignoreCase = true) -> if (isBn) "ট্র্যাপ, র‍্যাপ ও স্ট্রিট বিটস" else "Trap, Bars & Street"
    title.contains("Rock", ignoreCase = true) -> if (isBn) "ক্লাসিক রিফ ও অল্টারনেটিভ রক" else "Classic Riffs & Alt Anthems"
    title.contains("Electronic", ignoreCase = true) || title.contains("Dance", ignoreCase = true) -> if (isBn) "ইডিএম, সিন্থওয়েভ ও ক্লাব" else "EDM, Synthwave & Club"
    title.contains("Indie", ignoreCase = true) -> if (isBn) "অল্টারনেটিভ ও ইন্ডি ফোক" else "Alternative & Indie Folk"
    title.contains("Classical", ignoreCase = true) -> if (isBn) "সিম্ফনি ও বিশ্বখ্যাত মাস্টারপিস" else "Symphonies & Masterpieces"
    title.contains("K-Pop", ignoreCase = true) -> if (isBn) "গ্লোবাল হিট ও জনপ্রিয় কে-পপ" else "Global Hits & Idol Anthems"
    title.contains("Jazz", ignoreCase = true) -> if (isBn) "স্মুথ স্যাক্সোফোন ও জ্যাজ" else "Smooth Sax & Standards"
    else -> if (isBn) "বাছাইকৃত মিক্স ও হাইলাইটস" else "Curated Mixes & Highlights"
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
        // Hero Header with Glass Card & "Play All" Action
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.06f),
                                Color.Black.copy(alpha = 0.40f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(22.dp),
                    )
                    .padding(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = categorySubtitle(title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    if (state is UiState.Success && state.data.isNotEmpty()) {
                        val firstItem = state.data.firstOrNull()?.items?.firstOrNull()
                        if (firstItem != null) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .clickable { onItemClick(firstItem) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Play All",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
