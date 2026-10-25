package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.SongEditStore
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtworkStore

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the remote file and cached — else null.
 *
 * The extraction only ever runs for remote-library tracks with no thumbnail;
 * every other row resolves synchronously to what it already had, so this is
 * safe to call from shared components like [SongRow] that draw every library
 * in the app. Keyed on the track id, so a recycled row re-resolves instead
 * of flashing a stranger's cover.
 */
@Composable
fun rememberRemoteArtworkUrl(song: Song?, ignoreCustom: Boolean = false): String? {
    val context = LocalContext.current
    val key = song?.videoId
    // The listener's own cover outranks anything the track came with, here
    // rather than at each image: every row, the queue, the mini player and the
    // shared player all resolve artwork through this function or the host
    // wrapper around it, so one check puts a custom cover everywhere at once.
    // [ignoreCustom] is the editor's source-art preview.
    val edits by SongEditStore.edits.collectAsStateWithLifecycle()
    val custom = if (ignoreCustom) null else song?.let { edits[it.videoId]?.coverPath }?.let { "file://$it" }
    var resolved by remember(key) { mutableStateOf(song?.thumbnailUrl) }
    LaunchedEffect(key, custom) {
        if (custom == null && song != null && song.thumbnailUrl == null &&
            (song.videoId.startsWith("webdav:") || song.videoId.startsWith("smb:"))
        ) {
            resolved = RemoteArtworkStore.resolveArt(context, song)
        }
    }
    return custom ?: resolved
}
