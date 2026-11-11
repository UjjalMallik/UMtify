package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.builtin.SoundCloudProvider

class SoundCloudSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.SOUNDCLOUD
    override val displayName: String get() = config.label.ifBlank { SourceKind.SOUNDCLOUD.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val songs = SoundCloudProvider.searchTracks(query, limit)
        return songs.map { song ->
            song.copy(videoId = SourceRegistry.trackKey(config.id, song.videoId))
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (trackId.startsWith("http://") || trackId.startsWith("https://")) {
            val direct = if (trackId.contains("api-v2.soundcloud.com") || trackId.contains("transcodings")) {
                SoundCloudProvider.resolveTranscodingUrl(trackId) ?: trackId
            } else trackId
            return SourceStream(
                url = direct,
                format = StreamFormat(codec = "mp3", kbps = 256),
                sourceName = "SoundCloud",
            )
        }
        return SoundCloudProvider.resolveStream(trackId, "", null)
    }
}
