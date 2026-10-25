package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withEdit

/**
 * [song] as the listener renamed it, or [song] itself where they changed
 * nothing.
 *
 * Display only. The returned track keeps every identity-bearing field — the
 * video id, the album and artist ids it was not asked to change, the local file
 * — so a renamed row still plays, opens and removes the track it names.
 */
@Composable
fun rememberEditedSong(song: Song): Song {
    val edits by PlayerPlatform.host.songEdits.collectAsStateWithLifecycle()
    return remember(song, edits) { song.withEdit(edits[song.videoId]) }
}

/**
 * [songs] wearing the listener's own titles, artists and albums.
 *
 * For a folder whose sorting, filtering and artist/album tabs are all derived
 * from the tracks themselves: mapping the list before those answers are asked
 * is what makes a renamed track sort, search and group under its new name
 * instead of the one it was downloaded with.
 */
@Composable
fun rememberEditedSongs(songs: List<Song>): List<Song> {
    val edits by PlayerPlatform.host.songEdits.collectAsStateWithLifecycle()
    return remember(songs, edits) {
        if (edits.isEmpty()) songs else songs.map { it.withEdit(edits[it.videoId]) }
    }
}
