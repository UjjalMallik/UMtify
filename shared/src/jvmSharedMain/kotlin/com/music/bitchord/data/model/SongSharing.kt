package com.music.bitchord.data.model

/**
 * A YouTube video id is always exactly eleven URL-safe characters. Anything
 * else in [Song.videoId] is an identity that only means something on this
 * device: a `content://` or `file://` row from the media store, an `smb://`
 * path, or a module source's `src:` key.
 */
private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * Whether a share link can be built for this track.
 *
 * Only a real YouTube id addresses a watch page. A device file carries its own
 * URI where the id goes — see [Song.localUri] — so sharing it would send
 * `music.youtube.com/watch?v=content://…`, which opens nothing. A track
 * downloaded from YouTube keeps its eleven-character id and stays shareable
 * even while it plays from disk, which is the point of the distinction.
 */
val Song.hasShareableVideoId: Boolean
    get() = YOUTUBE_VIDEO_ID.matches(videoId)
