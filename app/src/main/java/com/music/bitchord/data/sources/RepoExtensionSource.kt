package com.music.bitchord.data.sources

import com.music.bitchord.data.ExtensionRepoManager
import com.music.bitchord.data.LosslessAudioProvider
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A [MusicSource] representing an installed/available audio/download extension synced
 * from [ExtensionRepoManager] into the single unified `SOURCES` list.
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
        if (config.metadataOnly) {
            return@withContext SourceHealth.Ok("Metadata & Lyrics only")
        }
        val endpoints = resolveEndpoints()
        if (endpoints.isEmpty()) {
            SourceHealthTracker.recordError(config.id, "No audio endpoint")
            return@withContext SourceHealth.Unreachable("No audio endpoint")
        }
        val probed = LosslessAudioProvider.probeEndpointHealth(endpoints)
        probed.fold(
            onSuccess = { latencyMs ->
                SourceHealthTracker.recordReady(config.id, latencyMs)
                SourceHealth.Ok("${latencyMs}ms · FLAC")
            },
            onFailure = { failure ->
                val reason = failure.message ?: "Unreachable"
                SourceHealthTracker.recordError(config.id, reason)
                SourceHealth.Unreachable(reason)
            },
        )
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (config.metadataOnly || query.isBlank()) return@withContext emptyList()
        val endpoints = resolveEndpoints()
        if (endpoints.isEmpty()) {
            SourceHealthTracker.recordError(config.id, "No audio endpoint")
            return@withContext emptyList()
        }
        val (tracks, errorReason) = LosslessAudioProvider.searchTracksForExtensionChecked(
            query = query,
            endpointsOverride = endpoints,
            limit = limit,
        )
        if (errorReason != null) {
            SourceHealthTracker.recordError(config.id, errorReason)
            return@withContext emptyList()
        }
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
            val startMs = System.currentTimeMillis()
            val qualityTier = when (request) {
                is StreamRequest.Lossless -> "HI_RES_LOSSLESS"
                is StreamRequest.Best, is StreamRequest.Capped -> "LOSSLESS"
            }
            val result = LosslessAudioProvider.fetchStreamForExtension(
                encodedTrackId = trackId,
                fallbackEndpoints = resolveEndpoints(),
                qualityTier = qualityTier,
                sourceLabel = displayName,
            )
            if (result == null) {
                SourceHealthTracker.recordError(config.id, "No FLAC stream")
                return@withContext null
            }
            val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            SourceHealthTracker.recordReady(config.id, latencyMs)

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
