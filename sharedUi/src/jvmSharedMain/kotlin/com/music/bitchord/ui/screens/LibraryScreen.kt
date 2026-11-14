package com.music.bitchord.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.luminance
import com.music.bitchord.ui.components.liquidGlassCard
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import com.music.bitchord.ui.components.ShelfRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.components.LIBRARY_GRID_SPACING
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.libraryGrid
import com.music.bitchord.ui.components.librarySkeleton

/**
 * The signed-in library: the saved collections, as shelves of cards.
 *
 * Deliberately only the collections. This page used to end with two runs of
 * track rows — "Liked Music" and "Songs" — which are two overlapping answers
 * to the same question and read as one list that couldn't make up its mind: a
 * track that stopped being liked didn't leave the page, it moved down it, into
 * a section most people had taken for more of the same. Liked Music is a
 * playlist, and it is reached the way every other playlist here is, by opening
 * its card.
 *
 * The liked list is still fetched — it is what the rest of the app reads a
 * track's rating off (see MainViewModel's `likeStatuses`); it just isn't a
 * second place to browse it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    signedIn: Boolean,
    state: UiState<LibraryPage>,
    listState: LazyListState,
    onShelfItemClick: (ShelfItem) -> Unit,
    onShelfItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)? = null,
    /**
     * A shelf's "Show all" — every shelf's row here stops at five cards (see
     * [LibraryGridShelf]), so this is the only way to reach whatever didn't
     * fit.
     */
    onShowAll: (HomeShelf) -> Unit,
    /**
     * The way in to Replay at the head of the page — the row of Replay cards,
     * their placeholders while the history is read, or nothing at all.
     */
    replay: @Composable () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /**
     * The device's folders — downloads, local files, the remote libraries this
     * build supports — drawn as a list under the Replay cards. Built by the
     * app, since which of those exist is the platform's business.
     */
    links: List<LibraryLink>,
    /**
     * The "On device" shelf under that list: the releases kept on this device
     * whole. Left off the page entirely while there are none.
     */
    deviceItems: List<ShelfItem>,
    /** The big "Library" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    val pinnedPlaylists by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            if (showTitle) {
                item {
                    Text(
                        text = stringResource(Res.string.library),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    )
                }
            }
            item(key = "replay") { replay() }
            if (links.isNotEmpty()) {
                item(key = "links") { LibraryLinkList(links = links, onClick = onShelfItemClick) }
            }
            if (!signedIn) {
                item(key = "shelf:$PLAYLISTS") {
                    val emptyPlaylists = HomeShelf(PLAYLISTS, emptyList())
                    PlaylistShelf(
                        shelf = emptyPlaylists,
                        onItemClick = onShelfItemClick,
                        onItemLongPress = onShelfItemLongPress,
                        onNewPlaylist = onNewPlaylist,
                        onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                        onShowAll = { onShowAll(emptyPlaylists) },
                    )
                }
                item {
                    MessageState(
                        message = stringResource(Res.string.library_sign_in_description),
                        actionLabel = stringResource(Res.string.sign_in),
                        onAction = onSignIn,
                    )
                }
                return@LazyColumn
            }
            when (state) {
                is UiState.Loading -> librarySkeleton()
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> {
                    // A fresh account has no Playlists shelf at all, and that
                    // is exactly the account most in need of the button that
                    // makes one — so the row is drawn either way, empty but
                    // for the tile that creates the first playlist.
                    val shelves = state.data.shelves
                    if (shelves.none { it.title == PLAYLISTS }) {
                        item(key = "shelf:$PLAYLISTS") {
                            val emptyPlaylists = HomeShelf(PLAYLISTS, emptyList())
                            PlaylistShelf(
                                shelf = emptyPlaylists,
                                onItemClick = onShelfItemClick,
                                onItemLongPress = onShelfItemLongPress,
                                onNewPlaylist = onNewPlaylist,
                                onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                                onShowAll = { onShowAll(emptyPlaylists) },
                            )
                        }
                    }
                    shelves.forEach { shelf ->
                        item(key = "shelf:${shelf.title}") {
                            if (shelf.title == PLAYLISTS) {
                                val pinnedFirst = shelf.pinnedFirst(pinnedPlaylists)
                                PlaylistShelf(
                                    shelf = pinnedFirst,
                                    onItemClick = onShelfItemClick,
                                    onItemLongPress = onShelfItemLongPress,
                                    onNewPlaylist = onNewPlaylist,
                                    onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                                    onShowAll = { onShowAll(pinnedFirst) },
                                    pinnedPlaylists = pinnedPlaylists,
                                )
                            } else {
                                LibraryGridShelf(
                                    shelf = shelf,
                                    onItemClick = onShelfItemClick,
                                    onItemLongPress = onShelfItemLongPress,
                                    onShowAll = { onShowAll(shelf) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One of the Library's folder rows: the page [item] opens, behind [icon]. */
/** [logo], when set, is drawn in place of [icon] — a service's own mark. */
data class LibraryLink(val item: ShelfItem, val icon: ImageVector, val logo: DrawableResource? = null)

/**
 * Bento Layout for Library Collections:
 * - Top Row: Side-by-side Downloads (left) and Local Music (right)
 * - Below: Cached songs (compact card with dedicated Cache Music Symbol) and My Spotify (compact sleek card)
 * - Any other configured storage connections (WebDAV, SMB, etc.)
 */
@Composable
private fun LibraryLinkList(links: List<LibraryLink>, onClick: (ShelfItem) -> Unit) {
    val downloadsLink = links.find { it.item.browseId == "local:downloads" }
    val localLink = links.find { it.item.browseId == "local:all" }
    val cachedLink = links.find { it.item.browseId == "local:cache" }
    val spotifyLink = links.find { it.item.browseId == "app:spotify" || it.logo != null }
    val otherLinks = links.filter { it != downloadsLink && it != localLink && it != cachedLink && it != spotifyLink }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top Row: Downloads + Local Music side-by-side
        if (downloadsLink != null && localLink != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LibraryBentoCard(
                    link = downloadsLink,
                    accent = Color(0xFF3880FF),
                    onClick = { onClick(downloadsLink.item) },
                    modifier = Modifier.weight(1f),
                )
                LibraryBentoCard(
                    link = localLink,
                    accent = Color(0xFF10AC84),
                    onClick = { onClick(localLink.item) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            downloadsLink?.let { dl ->
                LibraryBentoCard(
                    link = dl,
                    accent = Color(0xFF3880FF),
                    onClick = { onClick(dl.item) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            localLink?.let { ll ->
                LibraryBentoCard(
                    link = ll,
                    accent = Color(0xFF10AC84),
                    onClick = { onClick(ll.item) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Below Them: Cached songs compact card
        if (cachedLink != null) {
            LibraryCachedCard(
                link = cachedLink,
                onClick = { onClick(cachedLink.item) },
            )
        }

        // Below Them: My Spotify compact sleek card
        if (spotifyLink != null) {
            LibrarySpotifyCard(
                link = spotifyLink,
                onClick = { onClick(spotifyLink.item) },
            )
        }

        // Any other server links (WebDAV, SMB, etc.)
        otherLinks.forEach { link ->
            LibraryGenericLinkCard(
                link = link,
                onClick = { onClick(link.item) },
            )
        }
    }
}

@Composable
private fun LibraryBentoCard(
    link: LibraryLink,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(22.dp)
    val cardBg = Brush.linearGradient(
        listOf(
            Color(0xFF161C26).copy(alpha = 0.95f),
            Color(0xFF0C1018).copy(alpha = 0.98f),
        ),
    )
    val specularBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.35f),
            accent.copy(alpha = 0.50f),
            Color.White.copy(alpha = 0.10f),
        ),
    )

    Box(
        modifier = modifier
            .heightIn(min = 116.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(1.2.dp, specularBorder, cardShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f))
                    .border(0.8.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = link.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = link.item.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = link.item.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryCachedCard(
    link: LibraryLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF9B59B6)
    val cardShape = RoundedCornerShape(20.dp)
    val cardBg = Brush.linearGradient(
        listOf(
            Color(0xFF161C26).copy(alpha = 0.95f),
            Color(0xFF0C1018).copy(alpha = 0.98f),
        ),
    )
    val specularBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.35f),
            accent.copy(alpha = 0.45f),
            Color.White.copy(alpha = 0.10f),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(1.2.dp, specularBorder, cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = link.item.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = link.item.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 52.dp dedicated Cache Music Symbol
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.20f))
                    .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = BitChordIcons.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySpotifyCard(
    link: LibraryLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF1DB954)
    val cardShape = RoundedCornerShape(20.dp)
    val cardBg = Brush.linearGradient(
        listOf(
            Color(0xFF161C26).copy(alpha = 0.95f),
            Color(0xFF0C1018).copy(alpha = 0.98f),
        ),
    )
    val specularBorder = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.35f),
            accent.copy(alpha = 0.45f),
            Color.White.copy(alpha = 0.10f),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(1.2.dp, specularBorder, cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f))
                    .border(0.8.dp, accent.copy(alpha = 0.50f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (link.logo != null) {
                    Icon(
                        painter = painterResource(link.logo),
                        contentDescription = null,
                        tint = Color(0xFF1ED760),
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = link.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "My Spotify",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = link.item.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LibraryGenericLinkCard(
    link: LibraryLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(18.dp)
    val cardBg = Brush.linearGradient(
        listOf(
            Color(0xFF161C26).copy(alpha = 0.94f),
            Color(0xFF0E121A).copy(alpha = 0.98f),
        ),
    )
    val secondary = MaterialTheme.colorScheme.secondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(0.8.dp, Color.White.copy(alpha = 0.15f), cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(secondary.copy(alpha = 0.18f))
                .border(0.8.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = link.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = link.item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private val LINK_ICON_SIZE = 24.dp
private val LINK_ICON_GAP = 16.dp

/**
 * The one shelf on this page that can be written to: it leads with the tile
 * that creates a playlist, and holding a card gets rename and delete on top of
 * the queue actions every other shelf's menu offers.
 */
@Composable
private fun PlaylistShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)? = null,
    onShowAll: () -> Unit,
    pinnedPlaylists: List<String> = emptyList(),
) {
    LibraryGridShelf(
        shelf = shelf,
        onItemClick = onItemClick,
        onItemLongPress = onItemLongPress,
        onShowAll = onShowAll,
        pinnedPlaylists = pinnedPlaylists,
        leadingCard = {
            Row(horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING)) {
                NewShelfCard(
                    icon = BitChordIcons.Plus,
                    label = stringResource(Res.string.new_playlist),
                    subtitle = stringResource(Res.string.saved_to_youtube_music),
                    onClick = onNewPlaylist,
                )
                if (onImportSpotifyPlaylist != null) {
                    NewShelfCard(
                        icon = BitChordIcons.Download,
                        label = stringResource(Res.string.import_spotify),
                        subtitle = stringResource(Res.string.import_spotify_subtitle),
                        onClick = onImportSpotifyPlaylist,
                        logo = Res.drawable.spotify_logo,
                    )
                }
            }
        },
    )
}

/** A Library shelf's preview row never swipes past this many cards. */
private const val LIBRARY_ROW_MAX_ITEMS = 5

/**
 * A Library shelf: a sideways-scrolling row of [SHELF_CARD_WIDTH] cards, the
 * same as every other shelf, but stopped at [LIBRARY_ROW_MAX_ITEMS] rather
 * than left to run the shelf's whole length — with a "Show all" beside the
 * title whenever there's more than that, opening the rest as a
 * vertically-scrolling grid instead. See [LibraryGridPage].
 *
 * [leadingCard], if given, occupies the first slot and counts against that
 * cap — see [PlaylistShelf].
 */
@Composable
internal fun LibraryGridShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onShowAll: () -> Unit,
    leadingCard: (@Composable () -> Unit)? = null,
    pinnedPlaylists: List<String> = emptyList(),
) {
    val leadingCount = if (leadingCard != null) 1 else 0
    val visibleItems = shelf.items.take((LIBRARY_ROW_MAX_ITEMS - leadingCount).coerceAtLeast(0))
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(
            title = shelf.title,
            subtitle = shelf.subtitle,
            onShowAll = onShowAll.takeIf { shelf.items.size + leadingCount > LIBRARY_ROW_MAX_ITEMS },
        )
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            items(
                items = visibleItems,
                key = { it.browseId ?: (it.title + it.subtitle.orEmpty()) },
                contentType = { "shelf_card" },
            ) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = { onItemLongPress(item) },
                    isPinned = item.browseId != null && item.browseId in pinnedPlaylists,
                )
            }
        }
    }
}

/**
 * Everything a Library shelf's "Show all" opens onto — the same cards, at the
 * same [libraryGrid] width, run down the screen instead of stopping at one row.
 */
@Composable
fun LibraryGridPage(
    shelf: HomeShelf,
    gridState: LazyGridState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onNewPlaylist: (() -> Unit)? = null,
) {
    // Re-read live rather than trusting [shelf] to already be sorted: this page
    // is opened from a snapshot (see `libraryShowAll` in MainActivity), and a
    // pin toggled from this page's own long-press menu must move the card
    // immediately rather than waiting for the row underneath to be revisited.
    val pinnedPlaylists by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    val librarySort by AppUi.host.librarySort.collectAsStateWithLifecycle()
    // Pinning wins over the default order, but an explicit sort is a stronger,
    // more deliberate signal than a pin and is left to reorder the whole grid,
    // pinned cards included.
    val sortedShelf = shelf.pinnedFirst(pinnedPlaylists).sortedForLibrary(librarySort)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            if (onNewPlaylist != null) {
                item(key = "leading") {
                    NewShelfCard(
                        icon = BitChordIcons.Plus,
                        label = stringResource(Res.string.new_playlist),
                        subtitle = stringResource(Res.string.saved_to_youtube_music),
                        onClick = onNewPlaylist,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(
                items = sortedShelf.items,
                key = { it.browseId ?: (it.title + it.subtitle.orEmpty()) },
                contentType = { "shelf_card" },
            ) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = { onItemLongPress(item) },
                    modifier = Modifier.fillMaxWidth(),
                    isPinned = item.browseId != null && item.browseId in pinnedPlaylists,
                )
            }
        }
    }
}

/**
 * Moves whichever of this shelf's cards are in [pinned] to the front, in the
 * order they were pinned, leaving everything else in its existing order behind
 * them.
 *
 * A no-op on any shelf that isn't Playlists: [pinned] only ever holds playlist
 * browse ids, so an album or artist shelf never has a card that matches.
 */
private fun HomeShelf.pinnedFirst(pinned: List<String>): HomeShelf {
    if (pinned.isEmpty()) return this
    val byId = items.filter { it.browseId != null }.associateBy { it.browseId }
    val pinnedItems = pinned.mapNotNull { byId[it] }
    if (pinnedItems.isEmpty()) return this
    val pinnedSet = pinnedItems.toSet()
    return copy(items = pinnedItems + items.filter { it !in pinnedSet })
}

/**
 * A card's title is all a Library shelf carries, so [LibrarySort.DEFAULT] is
 * the only option that isn't alphabetical — everything else sorts on it.
 */
private fun HomeShelf.sortedForLibrary(sort: LibrarySort): HomeShelf = when (sort) {
    LibrarySort.DEFAULT -> this
    LibrarySort.TITLE_ASC -> copy(items = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }))
    LibrarySort.TITLE_DESC -> copy(
        items = items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }),
    )
}

/** The library feed whose cards are the account's own — see [PlaylistShelf]. */
private const val PLAYLISTS = YtMusicRepository.PLAYLISTS_SHELF
