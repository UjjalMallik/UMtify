package com.music.bitchord.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import androidx.annotation.RequiresApi
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * The destination is the device's own public Music folder, in a `UMtify`
 * subfolder ("Music/UMtify/") — somewhere the file manager lists, other players can open,
 * and a user can access, play, share or backup directly without going through this app.
 *
 *  - **API 29+ (Android 10+)** writes to the public `Music/UMtify/` folder through [MediaStore].
 *    `IS_PENDING` keeps the row invisible to everything else until the bytes are all there.
 *    `TITLE`, `ARTIST`, `ALBUM`, and `IS_MUSIC = 1` are set on the record.
 *  - **API 26–28 (Android 9 and below)** writes directly to `File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "UMtify")`
 *    and triggers [MediaScannerConnection.scanFile] upon completion so files appear immediately
 *    in device file managers and external music players.
 */
object DownloadStore {

    private const val TAG = "UMtify"

    /** The subfolder of Music that everything lands in. */
    const val FOLDER = "UMtify"

    private val relativePath = "${Environment.DIRECTORY_MUSIC}/$FOLDER"

    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    // ---- Naming -------------------------------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     *
     * Artist first because a Music folder is sorted by name and nothing
     * else — no tags to group by — so leading with the artist is the only thing
     * that puts an album back together in the listing.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Long enough for anything real, short of the 255-byte filename ceiling. */
    private const val MAX_STEM_CHARS = 120

    /** What a file of some codec is called and what the store is told it is. */
    class Storable(val extension: String, val mimeType: String)

    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        "mp3" -> Storable("mp3", "audio/mpeg")
        "ogg" -> Storable("ogg", "audio/ogg")
        else -> null
    }

    // ---- Lookup -------------------------------------------------------------

    fun existing(context: Context, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val entry = mediaStoreEntry(context, name)
            if (entry != null) return entry
        }
        val legacy = legacyFile(name)
        if (legacy.exists() && legacy.length() > 0) return Uri.fromFile(legacy)
        val priv = privateFile(context, name)
        if (priv.exists() && priv.length() > 0) return Uri.fromFile(priv)
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreEntry(context: Context, name: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "%$FOLDER%"),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
    }.onFailure { Log.w(TAG, "media store lookup failed for $name: ${it.message}") }.getOrNull()

    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls") {
                    file.parentFile?.deleteRecursively() == true
                } else file.delete()
            } == true
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    // ---- Writing ------------------------------------------------------------

    class Pending internal constructor(
        private val context: Context,
        val uri: Uri,
        val name: String,
        private val part: File?,
        private val target: File?,
        private val song: Song? = null,
    ) {
        val tagUri: Uri get() = part?.let(Uri::fromFile) ?: uri

        fun openStream(): OutputStream =
            part?.outputStream()
                ?: context.contentResolver.openOutputStream(uri)
                ?: error("Could not open $name for writing")

        /** @return the public uri the finished file can be reached at. */
        fun commit(): Uri {
            if (part != null && target != null) {
                if (!part.renameTo(target)) error("Could not finish writing $name")
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    null,
                    null,
                )
                return Uri.fromFile(target)
            }
            val updateValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                song?.title?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.TITLE, it) }
                song?.artist?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                song?.albumName?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            context.contentResolver.update(
                uri,
                updateValues,
                null,
                null,
            )
            return uri
        }

        fun abort() {
            part?.delete()
            if (part == null) runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /**
     * Reserve [name] in public "Music/UMtify/" folder and return somewhere to write it.
     */
    fun begin(
        context: Context,
        name: String,
        mimeType: String,
        song: Song? = null,
    ): Pending {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                song?.title?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.TITLE, it) }
                song?.artist?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                song?.albumName?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrElse { cause ->
                Log.w(TAG, "the media store refused $mimeType for $name: ${cause.message}")
                error("Android won't store ${name.substringAfterLast('.', mimeType)} files in Music")
            } ?: error("Could not create $name in Music")
            return Pending(context, uri, name, part = null, target = null, song = song)
        }

        val target = legacyFile(name)
        val folder = target.parentFile ?: error("No Music folder on this device")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = File(folder, "$name.part")
        part.delete()
        return Pending(context, Uri.fromFile(target), name, part = part, target = target, song = song)
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(name: String) = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER),
        name,
    )

    private fun privateFile(context: Context, name: String) =
        File(File(context.filesDir, "downloads"), name)
}
