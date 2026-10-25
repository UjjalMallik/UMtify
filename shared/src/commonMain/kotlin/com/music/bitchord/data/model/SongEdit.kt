package com.music.bitchord.data.model

import kotlinx.serialization.Serializable

/**
 * The listener's own title, artist, album and cover for one track.
 *
 * Kept beside the track rather than written into it: a download's file and its
 * MediaStore row still carry what the source said, and playback, stream
 * resolution and the queue all go on addressing the track by id. Only what is
 * drawn passes through [withEdit]. A null field means "as the track came", so
 * clearing an override never has to store a copy of the original.
 */
@Serializable
data class SongEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Absolute path under the app's internal storage, not a URL. */
    val coverPath: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && coverPath == null
}

/**
 * This track wearing [edit]'s title, artist and album, for display.
 *
 * The credits are dropped when the artist was renamed: they are the old
 * track's links, and a byline the listener typed belongs to nobody in the
 * catalogue. Everything identity-bearing — [Song.videoId], the album id, the
 * local file, the queue entry — is untouched, so taps, playback and the menu
 * still act on the real track.
 */
fun Song.withEdit(edit: SongEdit?): Song {
    if (edit == null) return this
    val renamedArtist = edit.artist != null
    return copy(
        title = edit.title ?: title,
        artist = edit.artist ?: artist,
        albumName = edit.album ?: albumName,
        artists = if (renamedArtist) emptyList() else artists,
        artistId = if (renamedArtist) null else artistId,
    )
}
