package com.music.bitchord.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.music.bitchord.R
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.ui.OfflineHome
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.liquidGlassCard
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import androidx.compose.ui.res.painterResource

/** How many rows a section shows before sending the reader to the folder for the rest. */
private const val ROWS_PER_SECTION = 6

/** ic_umtify_wordmark's viewport, 800 x 275 — the same wordmark the online feed leads with. */
private const val WORDMARK_ASPECT = 800f / 275f

/** The wordmark's height against the heading's font size, as the online feed sizes it. */
private const val LOGO_TO_FONT = 1.1f

/**
 * Home when there is nothing to stream.
 *
 * The online feed is a conversation with a server, and with no server it has
 * nothing to say but an error. This page says the same thing the Library does,
 * only nearer the top: what is on this device, already playable, right now.
 *
 * It never asks for the network. Every list is read off disk by
 * [com.music.bitchord.ui.MainViewModel.loadOfflineHome] and passed in already
 * loaded, so there is no spinner here and no failure to apologise for.
 */
@Composable
fun OfflineHomeScreen(
    data: OfflineHome?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    title: String,
    /** Whether the device can reach the internet, which decides if the chip can be dismissed. */
    networkAvailable: Boolean,
    /** Whether the listener switched Offline mode on themselves in Settings. */
    manualOffline: Boolean,
    onExitOfflineMode: () -> Unit,
    onOpenFolder: (browseId: String, title: String, subtitle: String) -> Unit,
    onOpenGroup: (LocalGroup) -> Unit,
    onPlay: (songs: List<Song>, index: Int, source: String) -> Unit,
    /** The card's shuffle glyph: the queue is built shuffled, so only the track that leads it is picked here. */
    onShuffleAll: (songs: List<Song>, source: String) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val downloadsLabel = stringResource(R.string.downloads)
    val localLabel = stringResource(R.string.local_music)
    val cachedLabel = stringResource(R.string.cached_songs)
    // Read here rather than where they are used: the list's own body is a
    // LazyListScope, which is not a composition, so a heading asked for there
    // would have no resources to ask.
    val quickAccessLabel = stringResource(R.string.offline_quick_access)
    val recentLabel = stringResource(R.string.offline_recently_played)
    val albumsLabel = stringResource(R.string.albums)
    val nothingLabel = stringResource(R.string.offline_nothing_yet)

    val isBn = remember { Locale.getDefault().language == "bn" }
    var activeFilter by remember { mutableStateOf("ALL") }
    val allOfflineSongs = remember(data) {
        val s = data ?: return@remember emptyList()
        (s.downloads + s.local + s.cached).distinctBy { it.videoId }
    }

    val offlineHomeBg = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(offlineHomeBg),
        contentPadding = contentPadding,
    ) {
        item(key = "offline-title", contentType = "header") { OfflineTitle(title, onSearchClick) }
        item(key = "offline-network-chip") {
            OfflineChip(
                networkAvailable = networkAvailable,
                manualOffline = manualOffline,
                onExit = onExitOfflineMode,
                modifier = Modifier
                    .padding(horizontal = PAGE_GUTTER)
                    .padding(bottom = 14.dp),
            )
        }
        val snapshot = data ?: return@LazyColumn

        // Floating Glass Segmented Filter Bar
        item(key = "offline-filter-chips") {
            val primary = MaterialTheme.colorScheme.primary
            val filters = remember(isBn) {
                listOf(
                    Triple("ALL", if (isBn) "সব অফলাইন" else "All Offline", Icons.Rounded.CloudOff),
                    Triple("LOCAL", if (isBn) "ফোনের গান" else "Local Tracks", Icons.Rounded.Folder),
                    Triple("DOWNLOADS", if (isBn) "ডাউনলোড" else "Downloads", Icons.Rounded.Download),
                    Triple("CACHED", if (isBn) "১০০% ক্যাশড" else "100% Cached", Icons.Rounded.Bolt),
                )
            }
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filters) { (key, filterLabel, icon) ->
                    val isSelected = activeFilter == key
                    val chipBg = if (isSelected) {
                        Brush.linearGradient(
                            listOf(
                                primary.copy(alpha = 0.38f),
                                primary.copy(alpha = 0.18f),
                            ),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.04f),
                            ),
                        )
                    }
                    val chipBorder = if (isSelected) {
                        Brush.linearGradient(
                            listOf(
                                primary.copy(alpha = 0.90f),
                                primary.copy(alpha = 0.50f),
                                Color.White.copy(alpha = 0.30f),
                            ),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.08f),
                            ),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(chipBg)
                            .border(
                                if (isSelected) 1.5.dp else 0.8.dp,
                                chipBorder,
                                CircleShape,
                            )
                            .clickable { activeFilter = key }
                            .padding(start = 10.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) primary else Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = filterLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) primary else Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }



        // "Crystal Vault" 2-Over-1 Asymmetric Bento Layout
        item(key = "offline-quick-access") {
            OfflineSectionHeader(quickAccessLabel)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val showDownloads = activeFilter == "ALL" || activeFilter == "DOWNLOADS"
                val showLocal = activeFilter == "ALL" || activeFilter == "LOCAL"
                if (showDownloads && showLocal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CompactBentoCard(
                            label = downloadsLabel,
                            count = snapshot.downloads.size,
                            browseId = "local:downloads",
                            songs = snapshot.downloads,
                            accentGlow = Color(0xFF3880FF),
                            onOpen = { onOpenFolder("local:downloads", downloadsLabel, "") },
                            modifier = Modifier.weight(1f),
                        )
                        CompactBentoCard(
                            label = localLabel,
                            count = snapshot.local.size,
                            browseId = "local:all",
                            songs = snapshot.local,
                            accentGlow = Color(0xFF10AC84),
                            onOpen = { onOpenFolder("local:all", localLabel, "") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (showDownloads) {
                    QuickAccessBentoCard(
                        label = downloadsLabel,
                        badgeText = "",
                        count = snapshot.downloads.size,
                        browseId = "local:downloads",
                        songs = snapshot.downloads,
                        accentGlow = Color(0xFF3880FF),
                        onOpen = { onOpenFolder("local:downloads", downloadsLabel, "") },
                    )
                } else if (showLocal) {
                    QuickAccessBentoCard(
                        label = localLabel,
                        badgeText = "",
                        count = snapshot.local.size,
                        browseId = "local:all",
                        songs = snapshot.local,
                        accentGlow = Color(0xFF10AC84),
                        onOpen = { onOpenFolder("local:all", localLabel, "") },
                    )
                }
                // Bottom Row: Cached songs full-width below
                if (activeFilter == "ALL" || activeFilter == "CACHED") {
                    QuickAccessBentoCard(
                        label = cachedLabel,
                        badgeText = "",
                        count = snapshot.cached.size,
                        browseId = CACHE_FOLDER_BROWSE_ID,
                        songs = snapshot.cached,
                        accentGlow = Color(0xFF9B59B6),
                        onOpen = { onOpenFolder(CACHE_FOLDER_BROWSE_ID, cachedLabel, "") },
                    )
                }
            }
        }

        if (snapshot.cached.isEmpty() && snapshot.downloads.isEmpty() && snapshot.local.isEmpty()) {
            item(key = "offline-empty") { MessageState(nothingLabel) }
            return@LazyColumn
        }

        // Filter-aware shelves and lists
        if (activeFilter == "ALL" || activeFilter == "CACHED") {
            shelf(
                title = recentLabel,
                songs = snapshot.cached.take(12),
                currentSong = currentSong,
                isPlaying = isPlaying,
                onSongClick = { song -> onPlay(snapshot.cached, snapshot.cached.indexOf(song), recentLabel) },
            )
        }
        if (activeFilter == "ALL" || activeFilter == "DOWNLOADS") {
            shelfCards(
                title = albumsLabel,
                groups = snapshot.downloadedAlbums,
                onOpenGroup = onOpenGroup,
            )
            songRows(
                title = downloadsLabel,
                songs = snapshot.downloads,
                limit = ROWS_PER_SECTION,
                showAll = { onOpenFolder("local:downloads", downloadsLabel, "") },
                currentSong = currentSong,
                isPlaying = isPlaying,
                onSongClick = { _, index -> onPlay(snapshot.downloads, index, downloadsLabel) },
            )
        }
        if (activeFilter == "ALL" || activeFilter == "CACHED") {
            songRows(
                title = cachedLabel,
                songs = snapshot.cached,
                limit = ROWS_PER_SECTION,
                showAll = { onOpenFolder(CACHE_FOLDER_BROWSE_ID, cachedLabel, "") },
                currentSong = currentSong,
                isPlaying = isPlaying,
                onSongClick = { _, index -> onPlay(snapshot.cached, index, cachedLabel) },
            )
        }
        if (activeFilter == "ALL" || activeFilter == "LOCAL") {
            shelfCards(
                title = localLabel,
                groups = snapshot.localAlbums,
                onOpenGroup = onOpenGroup,
            )
            songRows(
                title = localLabel,
                songs = snapshot.local,
                limit = ROWS_PER_SECTION,
                showAll = { onOpenFolder("local:all", localLabel, "") },
                currentSong = currentSong,
                isPlaying = isPlaying,
                onSongClick = { _, index -> onPlay(snapshot.local, index, localLabel) },
            )
        }
    }
}

/** A horizontal shelf of artwork cards, with the row that leads the player marked. */
private fun LazyListScope.shelf(
    title: String,
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
) {
    if (songs.isEmpty()) return
    item(key = "$title-header") { OfflineSectionHeader(title) }
    item(key = "$title-shelf") {
        val isCurrent = playingRowMatcher(songs, currentSong)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            state = rememberLazyListState(),
        ) {
            itemsIndexed(songs, key = { position, song -> "$position-${song.videoId}" }) { _, song ->
                SongCard(
                    song = song,
                    isCurrent = isCurrent(song),
                    isPlaying = isCurrent(song) && isPlaying,
                    onClick = { onSongClick(song) },
                )
            }
        }
    }
}

/** The releases a folder's files group into, as the same cards the Library deals them. */
private fun LazyListScope.shelfCards(
    title: String,
    groups: List<LocalGroup>,
    onOpenGroup: (LocalGroup) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "$title-header") { OfflineSectionHeader(title) }
    item(key = "$title-shelf") {
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            state = rememberLazyListState(),
        ) {
            itemsIndexed(groups, key = { position, group -> "$position-${group.browseId}" }) { _, group ->
                val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
                val cardShape = RoundedCornerShape(18.dp)
                val cardBorder = if (isLight) {
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ),
                    )
                }
                val cardSheen = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f)),
                )
                val cardBase = if (isLight) {
                    Color.White.copy(alpha = 0.80f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.50f)
                }
                Column(
                    modifier = Modifier
                        .width(SHELF_CARD_WIDTH)
                        .liquidGlassCard(shape = cardShape, isLight = isLight)
                        .clickable { onOpenGroup(group) }
                        .padding(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        val isBrokenOrBlank = group.thumbnailUrl.isNullOrBlank() || com.music.bitchord.ui.components.FailedArtworkCache.isFailed(group.thumbnailUrl)
                        if (isBrokenOrBlank) {
                            com.music.bitchord.ui.components.DefaultCoverTile(
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            val context = coil3.compose.LocalPlatformContext.current
                            val imageRequest = remember(group.thumbnailUrl) {
                                coil3.request.ImageRequest.Builder(context)
                                    .data(group.thumbnailUrl.artworkAt(CARD_ART_PX))
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onState = { state ->
                                    if (state is coil3.compose.AsyncImagePainter.State.Error) {
                                        com.music.bitchord.ui.components.FailedArtworkCache.markFailed(group.thumbnailUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = group.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A folder's first [limit] tracks as rows, with the heading's tail sending you to the folder. */
private fun LazyListScope.songRows(
    title: String,
    songs: List<Song>,
    limit: Int,
    showAll: () -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Song, Int) -> Unit,
) {
    if (songs.isEmpty()) return
    val shown = songs.take(limit)
    item(key = title) {
        OfflineSectionHeader(title, onShowAll = if (songs.size > shown.size) showAll else null)
    }
    // No keys on the rows: two files can share a video id, and a device folder
    // is full of the duplicates a key would have to be unique over.
    items(shown) { song ->
        val isCurrent = playingRowMatcher(shown, currentSong)(song)
        SongRow(
            song = song,
            onClick = { onSongClick(song, songs.indexOf(song)) },
            isCurrent = isCurrent,
            isPlaying = isCurrent && isPlaying,
        )
    }
    item(key = "$title-spacer") { Spacer(Modifier.height(14.dp)) }
}

/** The page heading: the wordmark and the tab's name, as the online feed has them. */
@Composable
private fun OfflineTitle(title: String, onSearchClick: (() -> Unit)? = null) {
    val style = MaterialTheme.typography.displayLarge
    val pillShape = RoundedCornerShape(percent = 50)
    Column(Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 2.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .padding(bottom = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
            Image(
                painter = painterResource(R.drawable.ic_umtify_wordmark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (isLight) Color(0xFF121212) else Color.White),
                contentScale = ContentScale.FillHeight,
                modifier = Modifier
                    .height(36.dp)
                    .aspectRatio(WORDMARK_ASPECT),
            )
            if (onSearchClick != null) {
                Spacer(Modifier.width(12.dp))
                val searchBorder = remember(isLight) {
                    if (isLight) {
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.18f),
                                Color.Black.copy(alpha = 0.10f),
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .liquidGlassCard(shape = pillShape, isLight = isLight)
                        .clickable(onClick = onSearchClick)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = if (isLight) Color(0xFF1E1E1E) else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Search songs, artists...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (isLight) Color(0xFF1E1E1E) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(text = title, style = style, color = MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * Striking, prominent Liquid Glass Status Bar / Pill Card across the top of Listen Now.
 */
@Composable
private fun OfflineChip(
    networkAvailable: Boolean,
    manualOffline: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val barShape = RoundedCornerShape(22.dp)

    val infiniteTransition = rememberInfiniteTransition(label = "offlinePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val backgroundGradient = remember(primary, surfaceHigh, surfaceContainer, isLight) {
        if (isLight) {
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    primary.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.85f),
                ),
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        } else {
            Brush.linearGradient(
                listOf(
                    surfaceHigh.copy(alpha = 0.82f),
                    primary.copy(alpha = 0.20f),
                    surfaceContainer.copy(alpha = 0.60f),
                ),
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        }
    }

    val borderBrush = remember(primary, isLight) {
        if (isLight) {
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.70f),
                    primary.copy(alpha = 0.35f),
                    Color.Black.copy(alpha = 0.12f),
                ),
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        } else {
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.45f),
                    primary.copy(alpha = 0.40f),
                    Color.White.copy(alpha = 0.12f),
                ),
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        }
    }

    val isBn = remember { Locale.getDefault().language == "bn" }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(backgroundGradient)
            .drawWithContent {
                drawContent()
                if (!isLight) {
                    val sheenHeight = size.height * 0.40f
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                            startY = 0f,
                            endY = sheenHeight,
                        ),
                        size = Size(size.width, sheenHeight),
                    )
                }
            }
            .border(1.2.dp, borderBrush, barShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pulsing offline badge icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.18f * pulseAlpha))
                .border(1.dp, primary.copy(alpha = 0.38f * pulseAlpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = if (isBn) "অফলাইন মোড · চালু" else "Offline Mode · ON",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (!networkAvailable && !manualOffline) {
                    stringResource(R.string.offline_mode_no_internet)
                } else {
                    if (isBn) "অন-ডিভাইস স্টোরেজ থেকে চলছে" else "Playing from on-device storage"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Tactile glass toggle switch pill "Turn Off"
        if (manualOffline || networkAvailable) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                primary.copy(alpha = 0.45f),
                                primary.copy(alpha = 0.28f),
                            ),
                        ),
                    )
                    .border(
                        1.5.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.70f),
                                primary.copy(alpha = 0.85f),
                                Color.White.copy(alpha = 0.30f),
                            ),
                        ),
                        CircleShape,
                    )
                    .clickable(onClick = onExit)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2ECC71)),
                    )
                    Text(
                        text = if (isBn) "বন্ধ করুন" else stringResource(R.string.offline_mode_turn_off),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        ),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineSectionHeader(title: String, onShowAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(top = 26.dp, bottom = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onShowAll != null) {
            Text(
                text = stringResource(R.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * One of the three ways in. The tile is the folder — tap it and you are in the
 * listing, the whole of it — while the two glyphs under the count start that
 * same list playing, without asking first or showing it on the way.
 */
@Composable
private fun QuickAccessBentoCard(
    label: String,
    badgeText: String,
    count: Int,
    browseId: String,
    songs: List<Song>,
    accentGlow: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBn = remember { Locale.getDefault().language == "bn" }
    val isCachedCard = browseId == CACHE_FOLDER_BROWSE_ID
    val cardShape = RoundedCornerShape(22.dp)

    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val cardBackground = Brush.linearGradient(
        listOf(
            surfaceHigh.copy(alpha = 0.72f),
            accentGlow.copy(alpha = 0.14f),
            surfaceContainer.copy(alpha = 0.52f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val specularBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.24f),
            accentGlow.copy(alpha = 0.35f),
            outlineVariant.copy(alpha = 0.12f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    val liveTrackPreview = remember(songs, isBn) {
        if (songs.isEmpty()) {
            if (isBn) "কোনো গান নেই" else "No songs yet"
        } else {
            val topTitles = songs.take(2).map { it.title.trim() }.filter { it.isNotBlank() }
            if (topTitles.isEmpty()) {
                if (isBn) "অফলাইন ট্র্যাকসমূহ" else "Offline tracks"
            } else if (songs.size > 2) {
                "${topTitles.joinToString(", ")} & more"
            } else {
                topTitles.joinToString(", ")
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (isCachedCard) 74.dp else 112.dp)
            .clip(cardShape)
            .background(cardBackground)
            .border(1.2.dp, specularBorder, cardShape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = if (isCachedCard) 8.dp else 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left Side: Glowing icon, pills, bold large title, live track preview
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCachedCard) 3.dp else 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isCachedCard) 32.dp else 40.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(accentGlow.copy(alpha = 0.22f))
                            .border(0.8.dp, accentGlow.copy(alpha = 0.55f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = deviceFolderIcon(browseId) ?: BitChordIcons.Download,
                            contentDescription = null,
                            tint = accentGlow,
                            modifier = Modifier.size(if (isCachedCard) 18.dp else 22.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accentGlow.copy(alpha = 0.14f))
                            .border(0.6.dp, accentGlow.copy(alpha = 0.35f), CircleShape)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "$count ${if (isBn) "টি গান" else if (isCachedCard) "cached" else "tracks"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (!isCachedCard && badgeText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentGlow.copy(alpha = 0.15f))
                                .border(0.6.dp, accentGlow.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = accentGlow,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = if (isCachedCard) 17.sp else 21.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = liveTrackPreview,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (isCachedCard) 12.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Right Side: Dedicated 52.dp Cache Music Symbol or featured artwork
            if (isCachedCard) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accentGlow.copy(alpha = 0.20f))
                        .border(1.dp, accentGlow.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentGlow.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                val previewSongs = remember(songs) { songs.take(4) }
                Box(
                    modifier = Modifier.size(86.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewSongs.isNotEmpty() && previewSongs.size < 4) {
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .offset(y = (-3).dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(accentGlow.copy(alpha = 0.25f)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (previewSongs.size >= 4) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(modifier = Modifier.weight(1f)) {
                                    MosaicTile(previewSongs[0], Modifier.weight(1f))
                                    MosaicTile(previewSongs[1], Modifier.weight(1f))
                                }
                                Row(modifier = Modifier.weight(1f)) {
                                    MosaicTile(previewSongs[2], Modifier.weight(1f))
                                    MosaicTile(previewSongs[3], Modifier.weight(1f))
                                }
                            }
                        } else if (previewSongs.isNotEmpty()) {
                            val firstArt = previewSongs.first().thumbnailUrl ?: previewSongs.first().artworkAt(CARD_ART_PX)
                            if (!firstArt.isNullOrBlank()) {
                                AsyncImage(
                                    model = firstArt,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = accentGlow.copy(alpha = 0.75f),
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact square-ish card for the top-row side-by-side Bento layout.
 * Taller than wide to fill a 50% column comfortably.
 */
@Composable
private fun CompactBentoCard(
    label: String,
    count: Int,
    browseId: String,
    songs: List<Song>,
    accentGlow: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBn = remember { Locale.getDefault().language == "bn" }
    val cardShape = RoundedCornerShape(22.dp)

    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val cardBg = Brush.linearGradient(
        listOf(
            surfaceHigh.copy(alpha = 0.72f),
            accentGlow.copy(alpha = 0.14f),
            surfaceContainer.copy(alpha = 0.52f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val specularBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.24f),
            accentGlow.copy(alpha = 0.35f),
            outlineVariant.copy(alpha = 0.12f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val previewSongs = remember(songs) { songs.take(4) }
    val trackPreview = remember(songs, isBn) {
        if (songs.isEmpty()) {
            if (isBn) "কোনো গান নেই" else "No songs yet"
        } else {
            val tops = songs.take(2).map { it.title.trim() }.filter { it.isNotBlank() }
            when {
                tops.isEmpty() -> if (isBn) "অফলাইন ট্র্যাক" else "Offline tracks"
                songs.size > 2 -> "${tops.first()} & more"
                else -> tops.joinToString(", ")
            }
        }
    }

    Box(
        modifier = modifier
            .heightIn(min = 150.dp)
            .clip(cardShape)
            .background(cardBg)
            .drawWithContent {
                drawContent()
                // Top specular sheen
                val sheenH = size.height * 0.35f
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
                        startY = 0f,
                        endY = sheenH,
                    ),
                    size = Size(size.width, sheenH),
                )
            }
            .border(1.2.dp, specularBorder, cardShape)
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top: artwork mosaic + count pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentGlow.copy(alpha = 0.12f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
            ) {
                if (previewSongs.size >= 4) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f)) {
                            MosaicTile(previewSongs[0], Modifier.weight(1f))
                            MosaicTile(previewSongs[1], Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.weight(1f)) {
                            MosaicTile(previewSongs[2], Modifier.weight(1f))
                            MosaicTile(previewSongs[3], Modifier.weight(1f))
                        }
                    }
                } else if (previewSongs.isNotEmpty()) {
                    val art = previewSongs.first().thumbnailUrl ?: previewSongs.first().artworkAt(CARD_ART_PX)
                    if (!art.isNullOrBlank()) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = accentGlow.copy(alpha = 0.65f),
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                        )
                    }
                } else {
                    Icon(
                        imageVector = deviceFolderIcon(browseId) ?: BitChordIcons.Download,
                        contentDescription = null,
                        tint = accentGlow.copy(alpha = 0.55f),
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center),
                    )
                }
                // Count pill overlay bottom-left
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(accentGlow.copy(alpha = 0.20f))
                        .border(0.6.dp, accentGlow.copy(alpha = 0.45f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Label + badge text
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = trackPreview,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MosaicTile(song: Song, modifier: Modifier = Modifier) {
    val art = song.thumbnailUrl ?: song.artworkAt(CARD_ART_PX)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.04f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

@Composable
private fun MiniAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    }
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** A track as a card: sleeve over title and credit, the same shape every shelf uses. */
@Composable
private fun SongCard(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val cardShape = RoundedCornerShape(18.dp)
    val cardBorder = if (isLight) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                Color.Black.copy(alpha = 0.10f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.22f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            ),
        )
    }
    val cardSheen = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f)),
    )
    val cardBase = if (isLight) {
        Color.White.copy(alpha = 0.80f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.50f)
    }

    Column(
        modifier = modifier
            .liquidGlassCard(shape = cardShape, isLight = isLight)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val isBrokenOrBlank = song.thumbnailUrl.isNullOrBlank() || com.music.bitchord.ui.components.FailedArtworkCache.isFailed(song.thumbnailUrl)
            if (isBrokenOrBlank) {
                com.music.bitchord.ui.components.DefaultCoverTile(
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val context = coil3.compose.LocalPlatformContext.current
                val imageRequest = remember(song.thumbnailUrl) {
                    coil3.request.ImageRequest.Builder(context)
                        .data(song.thumbnailUrl.artworkAt(CARD_ART_PX))
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is coil3.compose.AsyncImagePainter.State.Error) {
                            com.music.bitchord.ui.components.FailedArtworkCache.markFailed(song.thumbnailUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
