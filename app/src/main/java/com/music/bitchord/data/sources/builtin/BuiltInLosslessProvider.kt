package com.music.bitchord.data.sources.builtin

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceStream

/**
 * Unified contract for built-in lossless / Hi-Res audio providers.
 */
interface BuiltInLosslessProvider {
    /** Human-readable provider label (e.g. "Archive.org", "Jamendo"). */
    val name: String

    /** Whether this provider is enabled by user preference and ready for queries. */
    val isEnabled: Boolean

    /**
     * Resolves an active track to a lossless/high-quality playable stream.
     */
    suspend fun resolveStream(title: String, artist: String, durationSec: Int?): SourceStream?

    /**
     * Performs a direct catalog search across this provider's lossless library.
     */
    suspend fun searchTracks(query: String, limit: Int = 20): List<Song>
}
