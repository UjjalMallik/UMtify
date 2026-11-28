package com.music.bitchord.data.sources

import com.music.bitchord.data.ExtensionRepoManager
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A [MusicSource] representing an installed audio/download extension synced from
 * [ExtensionRepoManager] into the draggable `SOURCES, TRIED IN THIS ORDER` list.
 */
class RepoExtensionSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.EXTENSION
    override val displayName: String get() = config.displayName

    private val extensionId: String
        get() = SourceRegistry.extensionIdFor(config.id) ?: config.id

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        val endpoints = resolveEndpoints()
        if (endpoints.isEmpty()) {
            return@withContext SourceHealth.Unreachable("No active endpoints")
        }
        SourceHealth.Ok("Extension Repo · Lossless FLAC")
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val endpoints = resolveEndpoints()
        if (endpoints.isEmpty()) return@withContext emptyList()
        val tracks = LosslessAudioProvider.searchTracksForExtension(
            query = query,
            endpointsOverride = endpoints,
            limit = limit,
        )
        tracks.map { track ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, track.encodedId),
                title = track.title,
                artist = track.artist,
                thumbnailUrl = null,
                durationText = track.durationSec.takeIf { it > 0 }?.let {
                    "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
                },
                sourceQuality = ModuleSource.LOSSLESS,
                sourceHiRes = track.quality.contains("HI_RES", ignoreCase = true),
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            val qualityTier = when (request) {
                is StreamRequest.Lossless -> "HI_RES_LOSSLESS"
                is StreamRequest.Best, is StreamRequest.Capped -> "LOSSLESS"
            }
            val result = LosslessAudioProvider.fetchStreamForExtension(
                encodedTrackId = trackId,
                fallbackEndpoints = resolveEndpoints(),
                qualityTier = qualityTier,
                sourceLabel = displayName,
            ) ?: return@withContext null

            SourceStream(
                url = result.streamUrl,
                format = StreamFormat(
                    codec = "flac",
                    bitDepth = result.bitDepth,
                    sampleRateHz = result.sampleRate,
                ),
                headers = mapOf(
                    "User-Agent" to LosslessAudioProvider.BROWSER_USER_AGENT,
                ),
                sourceConfigId = config.id,
                sourceName = displayName,
            )
        }

    private fun resolveEndpoints(): List<String> {
        val extracted = ExtensionRepoManager.getExtractedEndpointsFor(extensionId)
        val provider = LosslessAudioProvider.endpointsForExtension(extensionId)
        return (extracted + provider).distinct()
    }
}
