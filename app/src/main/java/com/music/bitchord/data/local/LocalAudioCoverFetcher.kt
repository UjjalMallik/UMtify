package com.music.bitchord.data.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
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
import java.io.ByteArrayOutputStream
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
        val cleanPath = when {
            rawData.startsWith(SCHEME_PREFIX) -> rawData.removePrefix(SCHEME_PREFIX)
            rawData.startsWith("file://") -> rawData.removePrefix("file://")
            else -> rawData
        }

        // 1. If direct image file exists on disk, load it immediately
        val directFile = File(cleanPath)
        if (directFile.exists() && directFile.length() > 0 &&
            (cleanPath.endsWith(".jpg", ignoreCase = true) || cleanPath.endsWith(".jpeg", ignoreCase = true) ||
             cleanPath.endsWith(".png", ignoreCase = true) || cleanPath.endsWith(".webp", ignoreCase = true))
        ) {
            val mime = when {
                cleanPath.endsWith(".png", ignoreCase = true) -> "image/png"
                cleanPath.endsWith(".webp", ignoreCase = true) -> "image/webp"
                else -> "image/jpeg"
            }
            return@withContext SourceFetchResult(
                source = ImageSource(directFile.source().buffer(), FileSystem.SYSTEM),
                mimeType = mime,
                dataSource = DataSource.DISK,
            )
        }

        // 2. Check if cached .jpg exists on disk
        val artFile = LocalMediaRepository.getLocalArtFile(context, cleanPath)
        if (artFile.exists() && artFile.length() > 0) {
            return@withContext SourceFetchResult(
                source = ImageSource(artFile.source().buffer(), FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        // 3. Extract and cache to disk
        LocalMediaRepository.extractAndCacheLocalArt(context, cleanPath, cleanPath)
        if (artFile.exists() && artFile.length() > 0) {
            return@withContext SourceFetchResult(
                source = ImageSource(artFile.source().buffer(), FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        // 4. Fallback direct extraction
        val bytes = extractEmbeddedPicture(context, cleanPath) ?: return@withContext null
        if (!artFile.exists() || artFile.length() == 0L) {
            runCatching {
                val tmp = File(artFile.parentFile, "${artFile.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(artFile)) {
                    runCatching { tmp.copyTo(artFile, overwrite = true); tmp.delete() }
                }
            }
        }

        val mime = detectMimeType(bytes) ?: "image/jpeg"
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
            if (data.startsWith("file://") || data.startsWith("/")) {
                val path = data.removePrefix("file://")
                val file = File(path)
                if (file.exists() && (path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) ||
                    path.endsWith(".png", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true))
                ) {
                    return true
                }
                val lower = path.lowercase(java.util.Locale.ROOT)
                return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                    lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".wav") ||
                    lower.endsWith(".aac")
            }
            if (data.startsWith("content://")) {
                return true
            }
            return false
        }

        fun extractEmbeddedPicture(context: Context, audioUriStr: String): ByteArray? {
            return runCatching {
                val uri = Uri.parse(audioUriStr)
                val directPath = if (audioUriStr.startsWith("/")) audioUriStr else LocalMediaRepository.safUriToStoragePath(uri)
                val localFile = directPath?.let { File(it) }?.takeIf { it.exists() }

                val pfd = if (localFile == null && !audioUriStr.startsWith("/")) {
                    runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                } else null

                val retriever = MediaMetadataRetriever()
                try {
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                    } else if (localFile != null) {
                        retriever.setDataSource(localFile.path)
                    } else if (audioUriStr.startsWith("/")) {
                        retriever.setDataSource(audioUriStr)
                    } else {
                        retriever.setDataSource(context, uri)
                    }

                    var pic = retriever.embeddedPicture
                    if ((pic == null || pic.isEmpty()) && pfd != null) {
                        runCatching {
                            val fb = MediaMetadataRetriever()
                            try {
                                fb.setDataSource(context, uri)
                                pic = fb.embeddedPicture
                            } finally {
                                fb.release()
                            }
                        }
                    }

                    // Tier 2: ContentResolver.loadThumbnail for Android Q+
                    if ((pic == null || pic.isEmpty()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            val bm = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                            val stream = ByteArrayOutputStream()
                            bm.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            val thumbBytes = stream.toByteArray()
                            if (thumbBytes.isNotEmpty()) {
                                pic = thumbBytes
                            }
                        }
                    }
                    pic
                } finally {
                    retriever.release()
                    pfd?.close()
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
            return if (isLocalAudioArt(str) || data.scheme == "file") LocalAudioCoverFetcher(context, str) else null
        }
    }
}
