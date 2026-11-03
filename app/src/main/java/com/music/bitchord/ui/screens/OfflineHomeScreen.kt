package com.music.bitchord.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "offline-title") { OfflineTitle(title) }
        item(key = "offline-network-chip") {
            OfflineChip(
                networkAvailable = networkAvailable,
                manualOffline = manualOffline,
                onExit = onExitOfflineMode,
                modifier = Modifier
                    .padding(horizontal = PAGE_GUTTER)
                    .padding(bottom = 18.dp),
            )
        }
        val snapshot = data ?: return@LazyColumn
        item(key = "offline-quick-access") {
            OfflineSectionHeader(quickAccessLabel)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickAccessCard(
                    label = downloadsLabel,
                    count = snapshot.downloads.size,
                    browseId = "local:downloads",
                    colors = listOf(Color(0xFF1E3C72), Color(0xFF2A5298)),
                    modifier = Modifier.weight(1f),
                    onOpen = { onOpenFolder("local:downloads", downloadsLabel, "") },
                    onPlay = { onPlay(snapshot.downloads, 0, downloadsLabel) },
                    onShuffle = { onShuffleAll(snapshot.downloads, downloadsLabel) },
                )
                QuickAccessCard(
                    label = localLabel,
                    count = snapshot.local.size,
                    browseId = "local:all",
                    colors = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
                    modifier = Modifier.weight(1f),
                    onOpen = { onOpenFolder("local:all", localLabel, "") },
                    onPlay = { onPlay(snapshot.local, 0, localLabel) },
                    onShuffle = { onShuffleAll(snapshot.local, localLabel) },
                )
                QuickAccessCard(
                    label = cachedLabel,
                    count = snapshot.cached.size,
                    browseId = CACHE_FOLDER_BROWSE_ID,
                    colors = listOf(Color(0xFF42275A), Color(0xFF734B6D)),
                    modifier = Modifier.weight(1f),
                    onOpen = { onOpenFolder(CACHE_FOLDER_BROWSE_ID, cachedLabel, "") },
                    onPlay = { onPlay(snapshot.cached, 0, cachedLabel) },
                    onShuffle = { onShuffleAll(snapshot.cached, cachedLabel) },
                )
            }
        }
        if (snapshot.cached.isEmpty() && snapshot.downloads.isEmpty() && snapshot.local.isEmpty()) {
            item(key = "offline-empty") { MessageState(nothingLabel) }
            return@LazyColumn
        }
        // The cache is kept in the order playback last touched it, which is as
        // close to a history of offline listening as anything on this device is.
        shelf(
            title = recentLabel,
            songs = snapshot.cached.take(12),
            currentSong = currentSong,
            isPlaying = isPlaying,
            onSongClick = { song -> onPlay(snapshot.cached, snapshot.cached.indexOf(song), recentLabel) },
        )
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
        songRows(
            title = cachedLabel,
            songs = snapshot.cached,
            limit = ROWS_PER_SECTION,
            showAll = { onOpenFolder(CACHE_FOLDER_BROWSE_ID, cachedLabel, "") },
            currentSong = currentSong,
            isPlaying = isPlaying,
            onSongClick = { _, index -> onPlay(snapshot.cached, index, cachedLabel) },
        )
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
                Column(
                    modifier = Modifier
                        .width(SHELF_CARD_WIDTH)
                        .clickable { onOpenGroup(group) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .thumbnailBorder(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = group.thumbnailUrl.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = group.subtitle,
                        style = MaterialTheme.typography.bodySmall,
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
private fun OfflineTitle(title: String) {
    val style = MaterialTheme.typography.displayLarge
    val logoHeight = with(LocalDensity.current) { (style.fontSize * LOGO_TO_FONT).toDp() }
    Column(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
        Image(
            painter = painterResource(R.drawable.ic_umtify_wordmark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            contentScale = ContentScale.FillHeight,
            modifier = Modifier
                .padding(bottom = 14.dp)
                .height(logoHeight)
                .aspectRatio(WORDMARK_ASPECT),
        )
        Text(text = title, style = style, color = MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * The pill that explains the page.
 *
 * It is a status, not a banner: nothing here is broken, the feed simply moved
 * onto the device. Its one control — switching Offline mode back off — appears
 * only when that would actually change something, so a phone in a tunnel is not
 * offered a button that does nothing.
 */
@Composable
private fun OfflineChip(
    networkAvailable: Boolean,
    manualOffline: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(start = 14.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.offline_mode_on),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!networkAvailable) {
            Text(
                text = "  ·  ${stringResource(R.string.offline_mode_no_internet)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (manualOffline && networkAvailable) {
            Text(
                text = stringResource(R.string.offline_mode_turn_off),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onExit)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
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
private fun QuickAccessCard(
    label: String,
    count: Int,
    browseId: String,
    colors: List<Color>,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = remember(colors) { MeshPalette(colors) }
    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            MeshGradientBackground(
                palette = palette,
                trackKey = browseId,
                blurRadius = 22.dp,
            )
            Icon(
                imageVector = deviceFolderIcon(browseId) ?: BitChordIcons.Download,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.song_count_plural, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            MiniAction(
                icon = BitChordIcons.Play,
                description = stringResource(R.string.offline_play_all),
                onClick = onPlay,
                enabled = count > 0,
            )
            MiniAction(
                icon = BitChordIcons.Shuffle,
                description = stringResource(R.string.offline_shuffle_all),
                onClick = onShuffle,
                enabled = count > 0,
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
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .thumbnailBorder(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = song.thumbnailUrl.artworkAt(CARD_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
