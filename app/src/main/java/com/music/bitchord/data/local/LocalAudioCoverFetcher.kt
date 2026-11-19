package com.music.bitchord.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.music.bitchord.data.LocalMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source
import java.io.File

/**
 * Lazy, cached Coil fetcher for real embedded cover art from local audio files (MP3, FLAC, M4A, etc.).
 *
 * Avoids reading heavy embedded picture bytes during library/folder scanning, reading metadata only
 * when Coil requests the image for display. Coil automatically caches the decoded bitmap in MemoryCache
 * and DiskCache.
 */
class LocalAudioCoverFetcher(
    private val context: Context,
    private val rawData: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val audioUriStr = when {
            rawData.startsWith(SCHEME_PREFIX) -> rawData.removePrefix(SCHEME_PREFIX)
            else -> rawData
        }
        val bytes = extractEmbeddedPicture(context, audioUriStr) ?: return@withContext null

        val mime = detectMimeType(bytes)
        SourceFetchResult(
            source = ImageSource(bytes.inputStream().source().buffer(), FileSystem.SYSTEM),
            mimeType = mime,
            dataSource = DataSource.DISK,
        )
    }

    private fun detectMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    companion object {
        const val SCHEME_PREFIX = "local_art://"

        fun isLocalAudioArt(data: String): Boolean {
            if (data.startsWith(SCHEME_PREFIX)) return true
            if (data.startsWith("content://") && (data.contains("document") || data.contains("tree") || data.contains("audio"))) {
                val lower = data.lowercase()
                return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                    lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".wav") ||
                    lower.endsWith(".aac") || data.contains("audio")
            }
            if (data.startsWith("/") || data.startsWith("file://")) {
                val lower = data.lowercase()
                return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                    lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".wav") ||
                    lower.endsWith(".aac")
            }
            return false
        }

        fun extractEmbeddedPicture(context: Context, audioUriStr: String): ByteArray? {
            return runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    val uri = Uri.parse(audioUriStr)
                    val directPath = if (audioUriStr.startsWith("/")) audioUriStr else LocalMediaRepository.safUriToStoragePath(uri)
                    val localFile = directPath?.let { File(it) }?.takeIf { it.exists() }
                    if (localFile != null) {
                        retriever.setDataSource(localFile.path)
                    } else if (audioUriStr.startsWith("/")) {
                        retriever.setDataSource(audioUriStr)
                    } else {
                        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                        if (pfd != null) {
                            pfd.use {
                                retriever.setDataSource(it.fileDescriptor)
                            }
                        } else {
                            retriever.setDataSource(context, uri)
                        }
                    }
                    retriever.embeddedPicture
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (isLocalAudioArt(data)) LocalAudioCoverFetcher(context, data) else null
    }

    class UriFactory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val str = data.toString()
            return if (isLocalAudioArt(str)) LocalAudioCoverFetcher(context, str) else null
        }
    }
}
