package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongEdit
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.album_label
import com.music.bitchord.sharedui.resources.artist_label
import com.music.bitchord.sharedui.resources.cancel
import com.music.bitchord.sharedui.resources.change_cover
import com.music.bitchord.sharedui.resources.cover_art
import com.music.bitchord.sharedui.resources.cover_save_failed
import com.music.bitchord.sharedui.resources.edit_song_info
import com.music.bitchord.sharedui.resources.edit_song_info_note
import com.music.bitchord.sharedui.resources.reset_cover
import com.music.bitchord.sharedui.resources.save
import com.music.bitchord.sharedui.resources.title_label
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Local title, artist, album and cover for a track that lives on this device.
 *
 * The dialog edits a working copy: nothing is written until Save, so Cancel
 * leaves the library exactly as it was and a picked image that was never saved
 * is not copied into the app at all. The current cover is the listener's own
 * one when there is one — [rememberRemoteArtworkUrl] already prefers it — and
 * "Reset to original" drops the override for the source's cover.
 *
 * Only offered for local and downloaded tracks, where the file is the track.
 * A streamed row's labels come from the catalogue and an edit would have
 * nothing to travel with when the same song is played elsewhere.
 */
@Composable
fun EditSongInfoDialog(
    song: Song,
    onDismiss: () -> Unit,
) {
    val edits by PlayerPlatform.host.songEdits.collectAsStateWithLifecycle()
    val existing = edits[song.videoId]
    var title by remember(song.videoId) { mutableStateOf(existing?.title ?: song.title) }
    var artist by remember(song.videoId) { mutableStateOf(existing?.artist ?: song.artist) }
    var album by remember(song.videoId) { mutableStateOf(existing?.album ?: song.albumName.orEmpty()) }
    var savedCover by remember(song.videoId) { mutableStateOf(existing?.coverPath) }
    var pickedCover by remember(song.videoId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val originalArt = PlayerPlatform.host.rememberOriginalArtworkUrl(song)
    val pickCover = PlayerPlatform.host.rememberCoverPicker { pickedCover = it }
    val coverModel = pickedCover ?: savedCover?.let { "file://$it" } ?: originalArt

    fun save() {
        if (saving) return
        scope.launch {
            saving = true
            saveFailed = false
            val newCover = if (pickedCover != null) {
                PlayerPlatform.host.saveSongCover(song.videoId, pickedCover!!)
            } else {
                savedCover
            }
            if (pickedCover != null && newCover == null) {
                saving = false
                saveFailed = true
                return@launch
            }
            val edit = SongEdit(
                title = title.trim().takeIf { it.isNotEmpty() && it != song.title },
                artist = artist.trim().takeIf { it.isNotEmpty() && it != song.artist },
                album = album.trim().takeIf { it.isNotEmpty() && it != song.albumName },
                coverPath = newCover,
            )
            if (edit.isEmpty) {
                PlayerPlatform.host.clearSongEdit(song.videoId)
            } else {
                PlayerPlatform.host.setSongEdit(song.videoId, edit)
            }
            saving = false
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(Res.string.edit_song_info),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.edit_song_info_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (coverModel != null) {
                            AsyncImage(
                                model = coverModel,
                                contentDescription = stringResource(Res.string.cover_art),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(112.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.cover_art),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = pickCover, enabled = !saving) {
                            Text(stringResource(Res.string.change_cover))
                        }
                        if (pickedCover != null || savedCover != null) {
                            TextButton(
                                onClick = {
                                    pickedCover = null
                                    savedCover = null
                                },
                                enabled = !saving,
                            ) {
                                Text(stringResource(Res.string.reset_cover))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(Res.string.title_label)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(Res.string.artist_label)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text(stringResource(Res.string.album_label)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (saveFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.cover_save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { save() }, enabled = !saving) {
                        if (saving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Text(stringResource(Res.string.save))
                        }
                    }
                }
            }
        }
    }
}
