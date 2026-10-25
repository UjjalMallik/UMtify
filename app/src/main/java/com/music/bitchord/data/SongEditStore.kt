package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.music.bitchord.data.model.SongEdit
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The listener's own title, artist, album and cover for individual tracks.
 *
 * Kept entirely on this device, keyed by [com.music.bitchord.data.model.Song.videoId]
 * — the YouTube id for a catalogue track or a download, the content URI for a
 * file found on the phone. Nothing is written back into MediaStore or the
 * download's file: those are the source's metadata, this is a layer over it,
 * and a layer can always be removed without touching the music.
 *
 * Covers live in `filesDir/custom_covers`, named after the track plus a
 * timestamp. The timestamp is what makes a replacement visible — Coil keys its
 * memory and disk entries on the URL, so writing a new image over the same
 * path would leave the old one on screen until the cache was purged. The
 * previous file is deleted whenever its edit is replaced or cleared.
 */
object SongEditStore {
    private const val PREFS = "song_edits"
    private const val ENTRIES = "entries"
    private const val COVER_DIR = "custom_covers"

    /** Covers are drawn at a card's size at most; more is memory spent on nothing. */
    private const val MAX_COVER_PX = 1200
    private const val COVER_QUALITY = 90

    private val json = Json { ignoreUnknownKeys = true }
    private val entrySerializer = MapSerializer(String.serializer(), SongEdit.serializer())

    private val _edits = MutableStateFlow<Map<String, SongEdit>>(emptyMap())
    val edits: StateFlow<Map<String, SongEdit>> = _edits.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private lateinit var covers: File

    /**
     * Opens the store. Called once from [com.music.bitchord.BitChordApplication]
     * before anything can read a cover, and cheap enough to do there: one
     * preferences read and one directory that almost always already exists.
     */
    fun init(context: Context) {
        synchronized(this) {
            if (::prefs.isInitialized) return
            val app = context.applicationContext
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            covers = File(app.filesDir, COVER_DIR).apply { mkdirs() }
            _edits.value = prefs.getString(ENTRIES, null)
                ?.let(::decode)
                ?: emptyMap()
        }
    }

    /**
     * Replaces [songId]'s edit. An edit whose every field is null is a deletion;
     * a cover that the new edit no longer points at is removed from disk.
     */
    fun set(songId: String, edit: SongEdit) {
        synchronized(this) {
            val previous = _edits.value[songId]
            val next = _edits.value.toMutableMap()
            if (edit.isEmpty) next.remove(songId) else next[songId] = edit
            _edits.value = next
            prefs.edit().putString(ENTRIES, encode(next)).apply()
            if (previous?.coverPath != null && previous.coverPath != edit.coverPath) {
                deleteCover(previous.coverPath)
            }
        }
    }

    /** Drops every override for [songId], the cover file included. */
    fun clear(songId: String) {
        synchronized(this) {
            val previous = _edits.value[songId] ?: return
            val next = _edits.value - songId
            _edits.value = next
            prefs.edit().putString(ENTRIES, encode(next)).apply()
            deleteCover(previous.coverPath)
        }
    }

    /**
     * Copies the image behind [coverUri] into this app's storage, downsampled
     * and re-encoded, and answers with its absolute path. Null when the image
     * cannot be read or written — a picked document that has since been
     * revoked, most often.
     */
    suspend fun saveCover(context: Context, songId: String, coverUri: String): String? =
        withContext(Dispatchers.IO) {
            val bitmap = runCatching { decodePicked(context, Uri.parse(coverUri)) }.getOrNull()
                ?: return@withContext null
            try {
                covers.mkdirs()
                // Two files can be written in the same millisecond (replace twice
                // from a slow picker), and the second must not land on the first.
                var file = File(covers, "${fileStem(songId)}-${System.currentTimeMillis()}.jpg")
                var suffix = 0
                while (file.exists()) {
                    suffix++
                    file = File(covers, "${fileStem(songId)}-${System.currentTimeMillis()}-$suffix.jpg")
                }
                val written = runCatching {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
                    }
                }.getOrDefault(false)
                if (written) file.absolutePath else null
            } finally {
                bitmap.recycle()
            }
        }

    fun deleteCover(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            // Only ever inside this store's own directory: the path came from
            // here, and a stale edit must not be able to point anywhere else.
            val file = File(path).canonicalFile
            if (file.parentFile?.canonicalFile == covers.canonicalFile) file.delete()
        }
    }

    private fun decodePicked(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder honours the file's EXIF orientation, which a camera
            // photo needs before it can be shown as a square cover.
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_COVER_PX) {
                    val scale = MAX_COVER_PX.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_COVER_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** A filesystem-safe stem that still names the track it belongs to. */
    private fun fileStem(songId: String): String {
        val cleaned = songId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
            .take(48)
        val hash = songId.hashCode().toUInt().toString(16).padStart(8, '0')
        return if (cleaned.isEmpty()) hash else "$cleaned-$hash"
    }

    private fun encode(edits: Map<String, SongEdit>): String =
        json.encodeToString(entrySerializer, edits)

    private fun decode(raw: String): Map<String, SongEdit> =
        runCatching { json.decodeFromString(entrySerializer, raw) }.getOrDefault(emptyMap())
}
