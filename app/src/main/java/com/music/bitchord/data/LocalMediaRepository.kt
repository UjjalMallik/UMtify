package com.music.bitchord.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.local.LocalAudioCoverFetcher
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.LocalArtworkCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "BitChord"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    // `mp4` only ever reaches this list as an audio row — MediaStore files a
    // video .mp4 under Video, not Audio — so it is the audio-only MP4s Dolby
    // and Apple music downloads come as (AC-4, E-AC-3, ALAC). `dsf`/`dff` are
    // DSD, which DsdExtractor plays.
    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "webm", "dsf", "dff",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/BitChord` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%", "%BitChord%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                val musicDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC,
                )
                listOf(File(musicDir, DownloadStore.FOLDER), File(musicDir, "BitChord")).forEach { folder ->
                    if (folder.exists() && folder.isDirectory) {
                        folder.listFiles()?.forEach { file ->
                            if (file.isFile && isAudioFileName(file.name)) {
                                val uriStr = Uri.fromFile(file).toString()
                                if (uriStr !in knownUris) {
                                    val modified = file.lastModified().takeIf { it > 0 }?.div(1_000)
                                    extraSongs.add(
                                        buildSongFromUri(
                                            context,
                                            uriStr,
                                            file.name,
                                            ScannedTags(null, null, modified, modified),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/${DownloadStore.FOLDER} directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
            )
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * A way for a build to look beyond MediaStore — the Android TV app reads USB
     * drives MediaStore does not index. Handed what MediaStore found (nothing,
     * without the storage permission) and returns the list to use. Null on the
     * phone, where MediaStore is the whole story.
     */
    @Volatile
    var extraLocalMusic: (suspend (Context, List<Song>) -> List<Song>)? = null

    data class DeviceFolder(
        val name: String,
        val path: String,
        val songCount: Int,
        val isCustom: Boolean = false,
    )

    data class ExtractedMeta(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
        val trackNumber: Int? = null,
        val artworkUri: String? = null,
    )

    val detectedAudioFolders = MutableStateFlow<List<DeviceFolder>>(emptyList())
    val allLocalSongsFlow = MutableStateFlow<List<Song>>(emptyList())

    private const val LOCAL_TRACKS_INDEX_FILE = "local_tracks_index.json"

    fun getIndexFile(context: Context): File = File(context.filesDir, LOCAL_TRACKS_INDEX_FILE)

    fun loadCachedIndex(context: Context): List<Song> {
        return runCatching {
            val file = getIndexFile(context)
            if (!file.exists() || file.length() == 0L) return emptyList()
            val text = file.readText()
            val jsonArray = org.json.JSONArray(text)
            val list = ArrayList<Song>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val videoId = obj.optString("id").ifBlank { obj.optString("videoId") }
                if (videoId.isBlank()) continue
                list.add(
                    Song(
                        videoId = videoId,
                        title = obj.optString("title", "Unknown Title"),
                        artist = obj.optString("artist", "Unknown Artist"),
                        thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                        durationText = obj.optString("durationText").takeIf { it.isNotBlank() },
                        albumName = obj.optString("albumName").takeIf { it.isNotBlank() },
                        localUri = obj.optString("localUri").takeIf { it.isNotBlank() } ?: videoId,
                        localPath = obj.optString("localPath").takeIf { it.isNotBlank() },
                        localDateAddedSeconds = if (obj.has("dateAdded")) obj.optLong("dateAdded") else null,
                        localDateModifiedSeconds = if (obj.has("dateModified")) obj.optLong("dateModified") else null,
                        trackNumber = if (obj.has("trackNumber")) obj.optInt("trackNumber") else null,
                        folderUri = obj.optString("folderUri").takeIf { it.isNotBlank() },
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun saveCachedIndex(context: Context, songs: List<Song>) {
        runCatching {
            val file = getIndexFile(context)
            val jsonArray = org.json.JSONArray()
            for (song in songs) {
                val obj = org.json.JSONObject()
                obj.put("id", song.videoId)
                obj.put("title", song.title)
                obj.put("artist", song.artist)
                song.thumbnailUrl?.let { obj.put("thumbnailUrl", it) }
                song.durationText?.let { obj.put("durationText", it) }
                song.albumName?.let { obj.put("albumName", it) }
                song.localUri?.let { obj.put("localUri", it) }
                song.localPath?.let { obj.put("localPath", it) }
                song.localDateAddedSeconds?.let { obj.put("dateAdded", it) }
                song.localDateModifiedSeconds?.let { obj.put("dateModified", it) }
                song.trackNumber?.let { obj.put("trackNumber", it) }
                song.folderUri?.let { obj.put("folderUri", it) }
                jsonArray.put(obj)
            }
            val tmp = File(context.filesDir, "$LOCAL_TRACKS_INDEX_FILE.tmp")
            tmp.writeText(jsonArray.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Failed saving local tracks index: ${it.message}") }
    }

    fun init(context: Context) {
        if (allLocalSongsFlow.value.isEmpty()) {
            val cached = loadCachedIndex(context)
            if (cached.isNotEmpty()) {
                allLocalSongsFlow.value = cached
            }
        }
    }

    private val scanMutex = Mutex()
    private val repositoryScope = CoroutineScope(Dispatchers.IO)
    private var enrichmentJob: Job? = null

    private const val LOCAL_ART_DIR = "local_art"
    private const val LOCAL_ART_THUMB_DIR = "local_art_thumb"

    fun getLocalArtFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, LOCAL_ART_DIR).apply { if (!exists()) mkdirs() }
        val hash = key.hashCode().toUInt().toString()
        return File(dir, "$hash.jpg")
    }

    fun getLocalArtThumbFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, LOCAL_ART_THUMB_DIR).apply { if (!exists()) mkdirs() }
        val hash = key.hashCode().toUInt().toString()
        return File(dir, "$hash.jpg")
    }

    private fun calculateThumbSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var inSampleSize = 1
        if (width > maxSize || height > maxSize) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= maxSize && (halfWidth / inSampleSize) >= maxSize) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun getCachedLocalArtUri(context: Context, key: String): String? {
        val file = getLocalArtFile(context, key)
        return if (file.exists() && file.length() > 0) {
            "file://${file.absolutePath}"
        } else {
            null
        }
    }

    fun extractAndCacheLocalArt(context: Context, audioUriStr: String, key: String): String? {
        val cached = getCachedLocalArtUri(context, key)
        if (cached != null) return cached

        val parsedUri = Uri.parse(audioUriStr)
        val meta = extractMetadataAndArt(context, parsedUri, key)
        return meta?.artworkUri ?: getCachedLocalArtUri(context, key)
    }

    fun extractMetadataAndArt(context: Context, fileUri: Uri, trackKey: String): ExtractedMeta? {
        val uriStr = fileUri.toString()
        val directPath = if (uriStr.startsWith("/")) uriStr else safUriToStoragePath(fileUri)
        val localFile = directPath?.let { File(it) }?.takeIf { it.exists() }

        var metaTitle: String? = null
        var metaArtist: String? = null
        var metaAlbum: String? = null
        var metaDur: Long? = null
        var metaTrack: String? = null
        var picBytes: ByteArray? = null

        // If localFile exists, try direct path first
        if (localFile != null) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(localFile.path)
                    metaTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    metaArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    metaAlbum = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    metaDur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    metaTrack = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    picBytes = r.embeddedPicture
                } finally {
                    r.release()
                }
            }
        }

        // 1. Try context.contentResolver.openFileDescriptor(audioUri, "r")?.use { pfd -> retriever.setDataSource(pfd.fileDescriptor) } -> retriever.embeddedPicture
        if (picBytes == null && !uriStr.startsWith("/")) {
            runCatching {
                context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(pfd.fileDescriptor)
                        if (metaTitle == null) metaTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        if (metaArtist == null) metaArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        if (metaAlbum == null) metaAlbum = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        if (metaDur == null) metaDur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        if (metaTrack == null) metaTrack = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        picBytes = r.embeddedPicture
                    } finally {
                        r.release()
                    }
                }
            }
        }

        // 2. If null, try retriever.setDataSource(context, audioUri) -> retriever.embeddedPicture
        if (picBytes == null && !uriStr.startsWith("/")) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, fileUri)
                    if (metaTitle == null) metaTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    if (metaArtist == null) metaArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    if (metaAlbum == null) metaAlbum = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    if (metaDur == null) metaDur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (metaTrack == null) metaTrack = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    picBytes = r.embeddedPicture
                } finally {
                    r.release()
                }
            }
        }

        // 3. If still null and Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, try context.contentResolver.loadThumbnail(audioUri, Size(512, 512), null)
        if ((picBytes == null || picBytes.isEmpty()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !uriStr.startsWith("/")) {
            runCatching {
                val bm = context.contentResolver.loadThumbnail(fileUri, Size(512, 512), null)
                val stream = ByteArrayOutputStream()
                bm.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val bytes = stream.toByteArray()
                if (bytes.isNotEmpty()) {
                    picBytes = bytes
                }
            }
        }

        // 4. Write bytes atomically to cacheDir/local_art/<hash>.jpg
        var artUri: String? = null
        val artFile = getLocalArtFile(context, trackKey)
        val thumbFile = getLocalArtThumbFile(context, trackKey)
        if (artFile.exists() && artFile.length() > 0) {
            artUri = "file://${artFile.absolutePath}"
        } else if (picBytes != null && picBytes.isNotEmpty()) {
            runCatching {
                val tmp = File(artFile.parentFile, "${artFile.name}.tmp")
                tmp.writeBytes(picBytes)
                if (tmp.renameTo(artFile) || runCatching { tmp.copyTo(artFile, overwrite = true); tmp.delete() }.isSuccess) {
                    artUri = "file://${artFile.absolutePath}"
                }
            }
        }

        // Generate tiny 128x128 disk thumbnail (~5KB)
        if (!thumbFile.exists() || thumbFile.length() == 0L) {
            runCatching {
                val rawBytes = picBytes ?: if (artFile.exists()) artFile.readBytes() else null
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                    val sampleSize = calculateThumbSampleSize(bounds.outWidth, bounds.outHeight, 128)
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                    if (bmp != null) {
                        val scaled = if (bmp.width > 128 || bmp.height > 128) {
                            Bitmap.createScaledBitmap(bmp, 128, 128, true)
                        } else {
                            bmp
                        }
                        val tmpThumb = File(thumbFile.parentFile, "${thumbFile.name}.tmp")
                        FileOutputStream(tmpThumb).use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        if (scaled != bmp) bmp.recycle()
                        scaled.recycle()
                        if (!tmpThumb.renameTo(thumbFile)) {
                            tmpThumb.copyTo(thumbFile, overwrite = true)
                            tmpThumb.delete()
                        }
                    }
                }
            }
        }

        if (artUri != null) {
            runCatching {
                LocalArtworkCache.prewarm(artUri, 160, context)
            }
        }

        return ExtractedMeta(
            title = metaTitle?.cleanTag(),
            artist = metaArtist?.cleanTag(),
            album = metaAlbum?.cleanTag(),
            durationMs = metaDur,
            trackNumber = trackNumberFromTag(metaTrack),
            artworkUri = artUri,
        )
    }

    private fun triggerEnrichmentPass(context: Context, songs: List<Song>) {
        val appContext = context.applicationContext
        enrichmentJob?.cancel()
        enrichmentJob = repositoryScope.launch {
            val toEnrich = songs.filter { song ->
                val uriStr = song.localUri ?: song.videoId
                val cachedArt = getCachedLocalArtUri(appContext, uriStr)
                cachedArt == null || song.durationText == null || song.albumName == null ||
                    song.thumbnailUrl?.startsWith("file://") != true
            }
            if (toEnrich.isEmpty()) return@launch

            val dispatcher = Dispatchers.IO.limitedParallelism(4)

            // Batch in chunks of 8 so the UI receives fast progressive updates
            for (chunk in toEnrich.chunked(8)) {
                if (!isActive) break

                val batchResults = coroutineScope {
                    chunk.map { song ->
                        async(dispatcher) {
                            if (!isActive) return@async null
                            val uriStr = song.localUri ?: song.videoId
                            val parsedUri = Uri.parse(uriStr)
                            val meta = extractMetadataAndArt(appContext, parsedUri, uriStr)
                            if (meta != null) {
                                val newTitle = meta.title ?: song.title
                                val newArtist = meta.artist ?: song.artist
                                val newAlbum = meta.album ?: song.albumName
                                val newDuration = meta.durationMs?.takeIf { it > 0 }?.let(::formatDuration) ?: song.durationText
                                val newArt = meta.artworkUri ?: song.thumbnailUrl
                                val newTrackNum = meta.trackNumber ?: song.trackNumber

                                val enriched = song.copy(
                                    title = newTitle,
                                    artist = newArtist,
                                    albumName = newAlbum,
                                    durationText = newDuration,
                                    thumbnailUrl = newArt,
                                    trackNumber = newTrackNum,
                                )
                                if (enriched != song) song.videoId to enriched else null
                            } else null
                        }
                    }.awaitAll().filterNotNull().toMap()
                }

                if (batchResults.isNotEmpty() && isActive) {
                    val current = allLocalSongsFlow.value
                    val hasChanges = current.any { batchResults.containsKey(it.videoId) && batchResults[it.videoId] != it }
                    if (hasChanges) {
                        val updated = current.map { batchResults[it.videoId] ?: it }
                        allLocalSongsFlow.value = updated
                        saveCachedIndex(appContext, updated)
                    }
                }
            }
        }
    }

    fun hasCustomFolders(): Boolean =
        AppSettings.localMusicCustomFolders.value.isNotEmpty() ||
        AppSettings.localMusicSelectedSongs.value.isNotEmpty()

    fun getCustomFolderUris(): Set<String> =
        AppSettings.localMusicCustomFolders.value

    fun getFolderDisplayName(context: Context, uriOrPath: String): String {
        if (uriOrPath.startsWith("content://")) {
            val treeUri = Uri.parse(uriOrPath)
            val docName = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
            if (!docName.isNullOrBlank()) return docName

            val docId = runCatching {
                if (uriOrPath.contains("/document/")) DocumentsContract.getDocumentId(treeUri)
                else DocumentsContract.getTreeDocumentId(treeUri)
            }.getOrNull()
            if (!docId.isNullOrBlank()) {
                val segment = docId.substringAfterLast(':').substringAfterLast('/')
                if (segment.isNotBlank()) return Uri.decode(segment)
            }
            val lastPath = treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            if (!lastPath.isNullOrBlank()) return Uri.decode(lastPath)
            return "Custom Folder"
        }
        return uriOrPath.substringAfterLast('/').ifBlank { "Storage" }
    }

    internal fun safUriToStoragePath(uri: Uri): String? = runCatching {
        val uriStr = uri.toString()
        val docId = if (uriStr.contains("/document/")) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }
        if (docId.startsWith("raw:")) {
            return@runCatching docId.removePrefix("raw:")
        }
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/")
    }.getOrNull()

    private val safAudioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "alac", "wma",
    )
    private val safVideoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "3gp", "ts", "flv", "webm",
    )

    fun scanSafFolderFast(context: Context, treeUriString: String): List<Song> {
        val songs = mutableListOf<Song>()
        val seenInFolder = mutableSetOf<String>()
        val visitedDirs = mutableSetOf<String>()

        runCatching {
            val treeUri = Uri.parse(treeUriString)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val queue = ArrayDeque<String>()
            queue.add(parentDocId)
            visitedDirs.add(parentDocId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )

            while (queue.isNotEmpty()) {
                val curDocId = queue.removeFirst()
                val curChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, curDocId)
                context.contentResolver.query(curChildrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(idCol) ?: continue
                        val mime = cursor.getString(mimeCol).orEmpty()
                        val name = cursor.getString(nameCol).orEmpty()
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (visitedDirs.add(docId)) {
                                queue.add(docId)
                            }
                        } else {
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (ext in safVideoExtensions || mime.startsWith("video/")) continue
                            val isAudio = mime.startsWith("audio/") || ext in safAudioExtensions
                            if (isAudio) {
                                val normName = name.lowercase().trim()
                                val dedupKey = if (size > 0) "$normName:$size" else "$docId:$normName"
                                if (!seenInFolder.add(docId) || !seenInFolder.add(dedupKey)) continue

                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                val modTime = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) / 1000 else null
                                songs.add(buildSongFast(context, fileUri, docId, name, modTime, folderUri = treeUriString))
                            }
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Fast SAF query failed for $treeUriString: ${it.message}")
            if (songs.isEmpty()) {
                songs.addAll(scanSafTreeFallback(context, treeUriString))
            }
        }

        return songs
    }

    fun scanSafTree(context: Context, treeUriString: String): List<Song> {
        return scanSafFolderFast(context, treeUriString)
    }

    private fun scanSafTreeFallback(context: Context, treeUriString: String): List<Song> {
        val uri = Uri.parse(treeUriString)
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return emptyList()
        val songs = mutableListOf<Song>()
        val seenInFolder = mutableSetOf<String>()
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (file.isDirectory) {
                    queue.add(file)
                } else if (file.isFile) {
                    val name = file.name.orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val mime = file.type?.lowercase(Locale.ROOT)

                    if (ext in safVideoExtensions || mime?.startsWith("video/") == true) continue
                    val isAudio = (mime?.startsWith("audio/") == true) || (ext in safAudioExtensions)
                    if (!isAudio) continue

                    val size = file.length()
                    val normName = name.lowercase().trim()
                    val dedupKey = if (size > 0) "$normName:$size" else "${file.uri}:$normName"
                    if (!seenInFolder.add(file.uri.toString()) || !seenInFolder.add(dedupKey)) continue

                    val modified = file.lastModified().takeIf { it > 0 }?.div(1000)
                    songs.add(buildSongFast(context, file.uri, file.uri.toString(), name, modified, folderUri = treeUriString))
                }
            }
        }
        return songs
    }

    private fun buildSongFast(
        context: Context,
        fileUri: Uri,
        docId: String,
        fileName: String,
        modifiedSeconds: Long?,
        folderUri: String? = null,
    ): Song {
        val uriStr = fileUri.toString()
        val nameWithoutExt = fileName.substringBeforeLast('.').ifBlank { fileName }

        var title = nameWithoutExt
        var artist = "Unknown Artist"
        if (nameWithoutExt.contains(" - ")) {
            val parts = nameWithoutExt.split(" - ", limit = 2)
            if (parts[0].isNotBlank()) artist = parts[0].trim()
            if (parts[1].isNotBlank()) title = parts[1].trim()
        }

        val directPath = safUriToStoragePath(fileUri)
        val cachedArt = getCachedLocalArtUri(context, uriStr)
        val artworkUrl = cachedArt ?: "local_art://$uriStr"

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = artworkUrl,
            durationText = null,
            albumName = null,
            localUri = uriStr,
            localPath = directPath ?: uriStr,
            localDateAddedSeconds = modifiedSeconds,
            localDateModifiedSeconds = modifiedSeconds,
            trackNumber = null,
            folderUri = folderUri,
        )
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    suspend fun refreshFoldersAndSongs(context: Context): List<Song> =
        refreshLocalMusic(context)

    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val current = allLocalSongsFlow.value
        if (current.isNotEmpty()) return@withContext current
        val cached = loadCachedIndex(context)
        if (cached.isNotEmpty()) {
            allLocalSongsFlow.value = cached
            return@withContext cached
        }
        refreshLocalMusic(context)
    }

    suspend fun refreshLocalMusic(context: Context): List<Song> = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val hasPerm = hasStoragePermission(context)
            val customUris = getCustomFolderUris()
            val scanAll = AppSettings.scanAllAudioFolders.value
            val selectedIndividualSongs = AppSettings.localMusicSelectedSongs.value

            if (!scanAll && customUris.isEmpty() && selectedIndividualSongs.isEmpty()) {
                detectedAudioFolders.value = emptyList()
                allLocalSongsFlow.value = emptyList()
                saveCachedIndex(context, emptyList())
                return@withContext emptyList()
            }
            if (!hasPerm && customUris.isEmpty() && selectedIndividualSongs.isEmpty()) {
                val extra = extraLocalMusic?.invoke(context, emptyList()) ?: emptyList()
                allLocalSongsFlow.value = extra
                saveCachedIndex(context, extra)
                return@withContext extra
            }

            val collectedSongs = mutableListOf<Song>()
            val foldersList = mutableListOf<DeviceFolder>()
            val seenKeys = mutableSetOf<String>()
            val excludedSongs = AppSettings.localMusicExcludedSongs.value
            val excludedFolders = AppSettings.localMusicExcludedFolders.value.map { it.normalizedPath() }.toSet()
            val filterNonMusic = AppSettings.filterNonMusicAudio.value

            // 1. Scan custom SAF folders
            for (uriStr in customUris) {
                if (uriStr in excludedFolders || uriStr.normalizedPath() in excludedFolders) continue
                val safSongs = scanSafFolderFast(context, uriStr)
                val displayName = getFolderDisplayName(context, uriStr)
                foldersList.add(DeviceFolder(name = displayName, path = uriStr, songCount = safSongs.size, isCustom = true))

                for (safSong in safSongs) {
                    val songIdentifier = safSong.localPath ?: safSong.localUri ?: safSong.videoId
                    val normPath = safSong.localPath?.normalizedPath()

                    if (normPath != null && normPath in seenKeys) continue
                    if (safSong.localUri != null && safSong.localUri in seenKeys) continue
                    if (safSong.videoId in seenKeys) continue

                    if (excludedSongs.isNotEmpty() && (songIdentifier in excludedSongs || safSong.localUri in excludedSongs || safSong.videoId in excludedSongs)) {
                        continue
                    }

                    collectedSongs.add(safSong)
                    normPath?.let { seenKeys.add(it) }
                    safSong.localUri?.let { seenKeys.add(it) }
                    seenKeys.add(safSong.videoId)
                }
            }

            // 2. Scan individually selected songs (if any)
            for (songUriStr in selectedIndividualSongs) {
                val normPath = songUriStr.normalizedPath()
                if (normPath in seenKeys || songUriStr in seenKeys) continue
                if (excludedSongs.isNotEmpty() && (songUriStr in excludedSongs || normPath in excludedSongs)) continue

                runCatching {
                    val uri = Uri.parse(songUriStr)
                    val fileName = getFileNameFromUri(context, uri) ?: "Selected Track"
                    val song = buildSongFast(context, uri, songUriStr, fileName, null, folderUri = null)
                    collectedSongs.add(song)
                    seenKeys.add(normPath)
                    seenKeys.add(songUriStr)
                    seenKeys.add(song.videoId)
                }
            }

            // 3. Scan MediaStore if scanAllAudioFolders is true and permission is granted
            if (scanAll && hasPerm) {
                val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.TRACK,
                )

                val selection = if (filterNonMusic) {
                    buildString {
                        append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                        append(
                            " AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                                " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                                " OR ${MediaStore.Audio.Media.DURATION} IS NULL)",
                        )
                        append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
                        append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                        append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                        }
                    }
                } else {
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                }
                val selectionArgs = if (filterNonMusic) {
                    arrayOf(MIN_LOCAL_MUSIC_DURATION_MS.toString())
                } else {
                    null
                }
                val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
                val mediaStoreFolderCounts = mutableMapOf<String, Int>()

                runCatching {
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder,
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                        val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                        val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

                        val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val displayName = cursor.getString(displayNameCol).orEmpty()
                            val rawTitle = cursor.getString(titleCol)
                            val rawArtist = cursor.getString(artistCol)
                            val rawAlbum = cursor.getString(albumCol)
                            val albumId = cursor.getLong(albumIdCol)
                            val durationMs = cursor.getLong(durationCol)
                            val path = cursor.getString(dataCol)

                            if (filterNonMusic && !isEligibleLocalMusic(durationMs, displayName, path)) continue

                            val folderPath = path?.let { File(it).parent }
                            if (folderPath != null) {
                                if (folderPath in excludedFolders || folderPath.normalizedPath() in excludedFolders) continue
                                mediaStoreFolderCounts[folderPath] = (mediaStoreFolderCounts[folderPath] ?: 0) + 1
                            }

                            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                            val normPath = path?.normalizedPath()

                            if (normPath != null && normPath in seenKeys) continue
                            if (contentUri in seenKeys) continue

                            val videoId = videoIdByUri[contentUri] ?: contentUri
                            val songIdentifier = path ?: contentUri

                            if (excludedSongs.isNotEmpty() && (songIdentifier in excludedSongs || contentUri in excludedSongs || videoId in excludedSongs)) {
                                continue
                            }

                            val title = rawTitle.cleanTag()?.removeAudioExtension()
                                ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                                ?: "Track $id"
                            val artist = rawArtist.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown Artist"
                            val albumName = rawAlbum.takeUnless { it.isNullOrBlank() || it == "<unknown>" }
                            val cachedArt = getCachedLocalArtUri(context, contentUri)
                            val artworkUrl = cachedArt ?: if (albumId > 0) ContentUris.withAppendedId(albumArtBaseUri, albumId).toString() else "local_art://$contentUri"
                            val durationText = durationMs.takeIf { it > 0 }?.let(::formatDuration)
                            val trackNumber = if (trackCol >= 0 && !cursor.isNull(trackCol)) {
                                trackNumberFromMediaStore(cursor.getInt(trackCol))
                            } else {
                                null
                            }

                            val song = Song(
                                videoId = videoId,
                                title = title,
                                artist = artist,
                                thumbnailUrl = artworkUrl,
                                durationText = durationText,
                                albumName = albumName,
                                localUri = contentUri,
                                localPath = path,
                                localDateAddedSeconds = cursor.getLong(dateAddedCol),
                                localDateModifiedSeconds = cursor.getLong(dateModifiedCol),
                                trackNumber = trackNumber,
                                folderUri = folderPath,
                            )
                            collectedSongs.add(song)
                            normPath?.let { seenKeys.add(it) }
                            seenKeys.add(contentUri)
                        }
                    }
                }.onFailure { Log.w(TAG, "Failed scanning device local music: ${it.message}") }

                mediaStoreFolderCounts.forEach { (path, count) ->
                    val norm = path.normalizedPath()
                    if (path !in excludedFolders && norm !in excludedFolders && foldersList.none { it.path.normalizedPath() == norm }) {
                        val name = path.substringAfterLast('/').ifBlank { "Storage" }
                        foldersList.add(DeviceFolder(name, path, count, isCustom = false))
                    }
                }
            }

            detectedAudioFolders.value = foldersList.sortedWith(
                compareByDescending<DeviceFolder> { it.isCustom }
                    .thenByDescending { it.songCount }
            )
            allLocalSongsFlow.value = collectedSongs
            saveCachedIndex(context, collectedSongs)

            // Trigger background Pass 2 enrichment
            triggerEnrichmentPass(context, collectedSongs)

            extraLocalMusic?.invoke(context, collectedSongs) ?: collectedSongs
        }
    }

    suspend fun getAllDeviceAudioFolders(context: Context): List<DeviceFolder> = withContext(Dispatchers.IO) {
        val current = detectedAudioFolders.value
        if (current.isNotEmpty()) current else {
            refreshLocalMusic(context)
            detectedAudioFolders.value
        }
    }

    suspend fun getAllLocalSongsRaw(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val current = allLocalSongsFlow.value
        if (current.isNotEmpty()) current else {
            refreshLocalMusic(context)
        }
    }

    /** Human-readable path for the folder setting without exposing provider internals. */
    fun selectedFolderLabel(treeUri: String): String? =
        selectedFolder(treeUri)?.label ?: runCatching {
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            Uri.decode(docId.substringAfterLast(':').substringAfterLast('/'))
        }.getOrNull()

    fun foldersSummaryLabel(folderUris: Set<String>, singleUri: String): String? {
        val customFolders = AppSettings.localMusicCustomFolders.value
        val all = (folderUris + singleUri + customFolders).filter { it.isNotBlank() }
        if (all.isEmpty()) return null
        if (all.size == 1) {
            val item = all.first()
            return if (item.startsWith("/")) item.substringAfterLast('/') else selectedFolderLabel(item)
        }
        return "${all.size} folders"
    }

    internal fun isInSelectedFolder(path: String?, treeUri: String): Boolean {
        if (treeUri.isBlank()) return true
        if (path.isNullOrBlank()) return false

        if (path.equals(treeUri, ignoreCase = true) || path.startsWith(treeUri, ignoreCase = true)) return true

        if (treeUri.startsWith("/")) {
            val candidate = path.normalizedPath()
            val norm = treeUri.normalizedPath()
            return candidate == norm || candidate.startsWith("$norm/")
        }
        val folder = selectedFolder(treeUri)
        if (folder != null) {
            val candidate = path.normalizedPath()
            if (candidate == folder.absolutePath || candidate.startsWith("${folder.absolutePath}/")) {
                return true
            }
        }
        if (path.startsWith("content://") && treeUri.startsWith("content://")) {
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull()
            val docId = runCatching { DocumentsContract.getDocumentId(Uri.parse(path)) }.getOrNull()
            if (treeId != null && docId != null && docId.startsWith(treeId)) {
                return true
            }
        }
        return false
    }

    fun isInSelectedFolders(path: String?, folderUris: Set<String>, singleUri: String): Boolean {
        if (AppSettings.scanAllAudioFolders.value && folderUris.isEmpty() && singleUri.isBlank()) {
            return true
        }
        val all = (folderUris + singleUri + AppSettings.localMusicCustomFolders.value).filter { it.isNotBlank() }
        if (all.isEmpty()) return true
        val candidate = path?.normalizedPath() ?: return false
        return all.any { uriOrPath ->
            isInSelectedFolder(path, uriOrPath)
        }
    }



    private data class SelectedFolder(val absolutePath: String, val label: String)

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:").normalizedPath()
            return@runCatching SelectedFolder(path, path.substringAfterLast('/'))
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        SelectedFolder(
            absolutePath = listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/").normalizedPath(),
            label = relative.ifBlank { volume },
        )
    }.getOrNull()

    private fun String.normalizedPath(): String =
        replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     */
    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean {
        if (durationMs in 1..MIN_LOCAL_MUSIC_DURATION_MS) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null
        var trackNumber: Int? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uriStr))
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val metaTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)

                if (!metaTitle.isNullOrBlank()) title = metaTitle
                if (!metaArtist.isNullOrBlank()) artist = metaArtist
                albumName = metaAlbum.cleanTag()
                if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
                trackNumber = trackNumberFromTag(metaTrack)
            } finally {
                retriever.release()
            }
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = scanned?.artworkUrl ?: "local_art://$uriStr",
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
            trackNumber = trackNumber,
        )
    }

    /**
     * MediaStore packs the disc into the same int (`disc * 1000 + track`), so
     * 1003 is disc 1 track 3 rather than track 1003. Unpacks it; null when the
     * scanner wrote nothing usable.
     */
    internal fun trackNumberFromMediaStore(raw: Int?): Int? {
        if (raw == null || raw <= 0) return null
        val track = if (raw >= 1000) raw % 1000 else raw
        return track.takeIf { it in 1..999 }
    }

    /**
     * `CD_TRACK_NUMBER` arrives as `"3"` or `"3/12"`. Takes the head; null when
     * it isn't a number.
     */
    internal fun trackNumberFromTag(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim() ?: return null
        return head.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
