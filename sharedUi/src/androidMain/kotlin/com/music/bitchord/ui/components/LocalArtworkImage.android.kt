package com.music.bitchord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

fun localArtHash(key: String): String = cleanLocalArtPath(key).hashCode().toUInt().toString()

fun extractArtHash(key: String): String {
    val clean = cleanLocalArtPath(key)
    val name = File(clean).name
    if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true)) {
        val baseName = name.substringBeforeLast('.')
        if (baseName.isNotEmpty() && baseName.all { it.isDigit() }) {
            return baseName
        }
    }
    return localArtHash(clean)
}

fun localFullArtFile(context: Context, key: String): File {
    val hash = extractArtHash(key)
    val fullDir = File(context.filesDir, "local_art_full").apply { if (!exists()) mkdirs() }
    val f = File(fullDir, "$hash.jpg")
    if (f.exists() && f.length() > 0) return f

    // Legacy migration check from cacheDir
    val legacyFullDir = File(context.cacheDir, "local_art")
    val legacyFull = File(legacyFullDir, "$hash.jpg")
    if (legacyFull.exists() && legacyFull.length() > 0) {
        runCatching {
            legacyFull.copyTo(f, overwrite = true)
        }
        return f
    }
    return f
}

fun findLocalArtFullFile(context: Context, key: String): File? {
    val f = localFullArtFile(context, key)
    return if (f.exists() && f.length() > 0) f else null
}

fun calculateInSampleSize(width: Int, height: Int, maxSizePx: Int): Int {
    var inSampleSize = 1
    if (width > maxSizePx || height > maxSizePx) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= maxSizePx && (halfWidth / inSampleSize) >= maxSizePx) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

fun decodeSampledBitmapFromFile(path: String, maxSizePx: Int): Bitmap? {
    return runCatching {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, boundsOptions)
        val w = boundsOptions.outWidth
        val h = boundsOptions.outHeight
        if (w <= 0 || h <= 0) return null

        val sampleSize = calculateInSampleSize(w, h, maxSizePx)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = if (maxSizePx <= 256) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, decodeOptions)
    }.getOrNull()
}

fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxSizePx: Int): Bitmap? {
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, boundsOptions)
            val w = boundsOptions.outWidth
            val h = boundsOptions.outHeight
            if (w <= 0 || h <= 0) return null

            val sampleSize = calculateInSampleSize(w, h, maxSizePx)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (maxSizePx <= 256) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOptions)
        }
    }.getOrNull()
}

fun localThumbFile(context: Context, clean: String): File {
    val cleaned = cleanLocalArtPath(clean)
    val thumbDir = File(context.filesDir, "local_art_thumb").apply { if (!exists()) mkdirs() }
    val name = File(cleaned).name
    if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true)) {
        val directThumb = File(thumbDir, name)
        if (directThumb.exists() && directThumb.length() > 0) return directThumb
    }

    val hash = extractArtHash(cleaned)
    val hashThumb = File(thumbDir, "$hash.jpg")
    if (hashThumb.exists() && hashThumb.length() > 0) return hashThumb

    // Legacy migration check from cacheDir
    val legacyThumbDir = File(context.cacheDir, "local_art_thumb")
    val legacyThumb = File(legacyThumbDir, "$hash.jpg")
    if (legacyThumb.exists() && legacyThumb.length() > 0) {
        runCatching {
            legacyThumb.copyTo(hashThumb, overwrite = true)
        }
        return hashThumb
    }

    return hashThumb
}

fun findLocalArtThumbFile(context: Context, clean: String): File? {
    val f = localThumbFile(context, clean)
    return if (f.exists() && f.length() > 0) f else null
}

fun saveThumbnailFileIfMissing(context: Context, clean: String, bmp: Bitmap) {
    runCatching {
        val targetFile = localThumbFile(context, clean)
        if (!targetFile.exists() || targetFile.length() == 0L) {
            val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
            val scaled = if (bmp.width > 128 || bmp.height > 128) {
                Bitmap.createScaledBitmap(bmp, 128, 128, true)
            } else {
                bmp
            }
            FileOutputStream(tmp).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (scaled != bmp) scaled.recycle()
            if (!tmp.renameTo(targetFile)) {
                tmp.copyTo(targetFile, overwrite = true)
                tmp.delete()
            }
        }
    }
}

fun decodeSampledBitmap(context: Context, source: String, maxSizePx: Int): Bitmap? {
    val clean = cleanLocalArtPath(source)
    if (clean.isBlank()) return null

    // For thumbnails (list items / mini-player), check 128px disk thumbnail cache first (<2ms load)
    if (maxSizePx <= 200) {
        val thumbFile = findLocalArtThumbFile(context, clean)
        if (thumbFile != null) {
            val thumbBmp = decodeSampledBitmapFromFile(thumbFile.absolutePath, maxSizePx)
            if (thumbBmp != null) return thumbBmp
        }
    }

    val file = File(clean)
    if (file.exists() && file.length() > 0) {
        val bmp = decodeSampledBitmapFromFile(file.absolutePath, maxSizePx)
        if (bmp != null && maxSizePx <= 200) {
            saveThumbnailFileIfMissing(context, clean, bmp)
        }
        return bmp
    }

    val fullFile = findLocalArtFullFile(context, clean)
    if (fullFile != null) {
        val bmp = decodeSampledBitmapFromFile(fullFile.absolutePath, maxSizePx)
        if (bmp != null && maxSizePx <= 200) {
            saveThumbnailFileIfMissing(context, clean, bmp)
        }
        return bmp
    }

    if (clean.startsWith("content://")) {
        val uri = Uri.parse(clean)
        val bmp = decodeSampledBitmapFromUri(context, uri, maxSizePx)
        if (bmp != null && maxSizePx <= 200) {
            saveThumbnailFileIfMissing(context, clean, bmp)
        }
        return bmp
    }

    return null
}

object LocalArtworkCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 6).coerceIn(32 * 1024, 96 * 1024) // in KB (32MB - 96MB for 500+ thumbnails)

    private val lruCache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    private val fullHdLruCache = object : LruCache<String, ImageBitmap>(5) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    fun getFullHd(key: String): ImageBitmap? {
        val hash = extractArtHash(key)
        return fullHdLruCache.get(hash)
    }

    fun putFullHd(key: String, bitmap: ImageBitmap) {
        val hash = extractArtHash(key)
        fullHdLruCache.put(hash, bitmap)
    }

    val artworkVersionFlow = MutableStateFlow(0)

    fun bumpArtworkVersion() {
        artworkVersionFlow.value++
    }

    fun clearNoArt(context: Context) {
        synchronized(noArtLock) {
            noArtHashes.clear()
            runCatching {
                val file = File(File(context.filesDir, "local_art_thumb"), "no_art.txt")
                if (file.exists()) file.delete()
            }
        }
    }

    private val noArtHashes = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var noArtFileLoaded = false
    private val noArtLock = Any()

    val extractionJobs = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    val extractionDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun cacheKey(path: String, maxSizePx: Int): String {
        return "${cleanLocalArtPath(path)}:$maxSizePx"
    }

    fun get(path: String, maxSizePx: Int = 160): ImageBitmap? = lruCache.get(cacheKey(path, maxSizePx))

    fun put(path: String, maxSizePx: Int = 160, bitmap: ImageBitmap) {
        lruCache.put(cacheKey(path, maxSizePx), bitmap)
    }

    fun clear() {
        lruCache.evictAll()
    }

    private const val NO_ART_VERSION = 2

    fun initNoArt(context: Context) {
        if (noArtFileLoaded) return
        synchronized(noArtLock) {
            if (noArtFileLoaded) return
            val thumbDir = File(context.filesDir, "local_art_thumb").apply { if (!exists()) mkdirs() }
            val marker = File(thumbDir, "no_art_v$NO_ART_VERSION.marker")
            if (!marker.exists()) {
                // Clear legacy no_art blacklist once so improved FLAC/ID3 extractor retries
                runCatching {
                    File(thumbDir, "no_art.txt").delete()
                    marker.createNewFile()
                }
                noArtHashes.clear()
            } else {
                val file = File(thumbDir, "no_art.txt")
                if (file.exists()) {
                    runCatching {
                        file.readLines().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) noArtHashes.add(trimmed)
                        }
                    }
                }
            }
            noArtFileLoaded = true
        }
    }

    fun isNoArt(context: Context, key: String): Boolean {
        initNoArt(context)
        val hash = localArtHash(cleanLocalArtPath(key))
        return noArtHashes.contains(hash)
    }

    fun markNoArt(context: Context, key: String) {
        initNoArt(context)
        val hash = localArtHash(cleanLocalArtPath(key))
        if (noArtHashes.add(hash)) {
            runCatching {
                val thumbDir = File(context.filesDir, "local_art_thumb").apply { if (!exists()) mkdirs() }
                File(thumbDir, "no_art.txt").appendText("$hash\n")
            }
        }
    }

    fun removeNoArt(context: Context, key: String) {
        initNoArt(context)
        val hash = localArtHash(cleanLocalArtPath(key))
        if (noArtHashes.remove(hash)) {
            runCatching {
                val file = File(File(context.filesDir, "local_art_thumb"), "no_art.txt")
                if (file.exists()) {
                    val remaining = noArtHashes.joinToString("\n")
                    file.writeText(remaining)
                }
            }
        }
    }

    fun saveAndCacheThumbnail(context: Context, key: String, picBytes: ByteArray) {
        val clean = cleanLocalArtPath(key)
        val targetFile = localThumbFile(context, clean)
        val fullFile = localFullArtFile(context, clean)
        runCatching {
            if (!fullFile.exists() || fullFile.length() == 0L) {
                val tmpFull = File(fullFile.parentFile, "${fullFile.name}.tmp")
                tmpFull.writeBytes(picBytes)
                if (!tmpFull.renameTo(fullFile)) {
                    tmpFull.copyTo(fullFile, overwrite = true)
                    tmpFull.delete()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, bounds)
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 128)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
            if (bmp != null) {
                val scaled = if (bmp.width > 128 || bmp.height > 128) {
                    Bitmap.createScaledBitmap(bmp, 128, 128, true)
                } else bmp

                val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
                FileOutputStream(tmp).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                if (!tmp.renameTo(targetFile)) {
                    tmp.copyTo(targetFile, overwrite = true)
                    tmp.delete()
                }

                val imageBitmap = scaled.asImageBitmap()
                put(clean, 128, imageBitmap)
                put(clean, 160, imageBitmap)
                put(key, 128, imageBitmap)
                put(key, 160, imageBitmap)
                if (scaled != bmp) bmp.recycle()
                removeNoArt(context, key)
                removeNoArt(context, clean)
            }
        }
    }

    fun prewarm(path: String, maxSizePx: Int = 160, context: Context? = null) {
        val key = cacheKey(path, maxSizePx)
        if (lruCache.get(key) != null) return
        val clean = cleanLocalArtPath(path)
        val file = File(clean)
        val bmp: Bitmap? = if (file.exists() && file.length() > 0) {
            decodeSampledBitmapFromFile(file.absolutePath, maxSizePx)
        } else if (context != null) {
            decodeSampledBitmap(context, clean, maxSizePx)
        } else null

        if (bmp != null) {
            lruCache.put(key, bmp.asImageBitmap())
        }
    }
}

/**
 * Fast binary FLAC METADATA_BLOCK_PICTURE and ID3v2 APIC extractor.
 * Solves MediaMetadataRetriever failing on 24-bit Hi-Res FLAC files,
 * files with ID3v2 tags prepended before "fLaC", or ID3v2 tags containing APIC frames.
 */
fun extractFlacPictureFromStream(inputStream: java.io.InputStream): ByteArray? {
    return runCatching {
        val bis = java.io.BufferedInputStream(inputStream, 32768)
        val dis = java.io.DataInputStream(bis)
        val initial = ByteArray(4)
        dis.readFully(initial)

        var hasFlacHeader = false

        // Check if stream starts with ID3 header: "ID3" (0x49, 0x44, 0x33)
        if (initial[0] == 0x49.toByte() && initial[1] == 0x44.toByte() && initial[2] == 0x33.toByte()) {
            val majorVersion = initial[3].toInt() and 0xFF
            val revision = dis.readUnsignedByte()
            val flags = dis.readUnsignedByte()
            val tagSize = (dis.readUnsignedByte() shl 21) or
                (dis.readUnsignedByte() shl 14) or
                (dis.readUnsignedByte() shl 7) or
                dis.readUnsignedByte()
            val hasFooter = (flags and 0x10) != 0
            val totalTagBytes = tagSize + (if (hasFooter) 10 else 0)

            var foundApic: ByteArray? = null
            if (tagSize in 10..25_000_000) {
                runCatching {
                    val tagData = ByteArray(tagSize)
                    dis.readFully(tagData)
                    foundApic = parseApicFromId3(tagData, majorVersion)
                    if (hasFooter) {
                        skipFully(dis, 10)
                    }
                }
            } else {
                skipFully(dis, totalTagBytes)
            }

            if (foundApic != null && foundApic.isNotEmpty()) {
                return@runCatching foundApic
            }

            // After ID3 tag, check if "fLaC" follows
            val nextHeader = ByteArray(4)
            dis.readFully(nextHeader)
            if (nextHeader[0] == 0x66.toByte() && nextHeader[1] == 0x4C.toByte() &&
                nextHeader[2] == 0x61.toByte() && nextHeader[3] == 0x43.toByte()
            ) {
                hasFlacHeader = true
            } else {
                return@runCatching null
            }
        } else if (initial[0] == 0x66.toByte() && initial[1] == 0x4C.toByte() &&
            initial[2] == 0x61.toByte() && initial[3] == 0x43.toByte()
        ) {
            hasFlacHeader = true
        }

        if (!hasFlacHeader) {
            return@runCatching null
        }

        while (true) {
            val b0 = dis.readUnsignedByte()
            val isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val blockLength = (dis.readUnsignedByte() shl 16) or
                (dis.readUnsignedByte() shl 8) or
                dis.readUnsignedByte()

            if (blockType == 6) { // METADATA_BLOCK_PICTURE
                val picType = dis.readInt()
                val mimeLength = dis.readInt()
                if (mimeLength > 0) skipFully(dis, mimeLength)
                val descLength = dis.readInt()
                if (descLength > 0) skipFully(dis, descLength)
                val width = dis.readInt()
                val height = dis.readInt()
                val depth = dis.readInt()
                val colors = dis.readInt()
                val picDataLength = dis.readInt()
                if (picDataLength in 1..35_000_000) {
                    val picData = ByteArray(picDataLength)
                    dis.readFully(picData)
                    return@runCatching picData
                }
                return@runCatching null
            }

            if (isLast) break
            skipFully(dis, blockLength)
        }
        null
    }.getOrNull()
}

private fun parseApicFromId3(tagData: ByteArray, majorVersion: Int): ByteArray? {
    var pos = 0
    val len = tagData.size
    while (pos + 10 <= len) {
        if (tagData[pos] == 0.toByte()) break
        val id = String(tagData, pos, 4, Charsets.ISO_8859_1)
        val frameSize = if (majorVersion == 4) {
            ((tagData[pos + 4].toInt() and 0x7F) shl 21) or
                ((tagData[pos + 5].toInt() and 0x7F) shl 14) or
                ((tagData[pos + 6].toInt() and 0x7F) shl 7) or
                (tagData[pos + 7].toInt() and 0x7F)
        } else {
            ((tagData[pos + 4].toInt() and 0xFF) shl 24) or
                ((tagData[pos + 5].toInt() and 0xFF) shl 16) or
                ((tagData[pos + 6].toInt() and 0xFF) shl 8) or
                (tagData[pos + 7].toInt() and 0xFF)
        }
        if (frameSize <= 0 || pos + 10 + frameSize > len) break

        if (id == "APIC") {
            val dataStart = pos + 10
            val textEncoding = tagData[dataStart].toInt() and 0xFF
            var mimeEnd = dataStart + 1
            while (mimeEnd < dataStart + frameSize && tagData[mimeEnd] != 0.toByte()) {
                mimeEnd++
            }
            val picTypePos = mimeEnd + 1
            val descStart = picTypePos + 1
            var picDataStart: Int
            if (textEncoding == 1 || textEncoding == 2) {
                var descEnd = descStart
                while (descEnd + 1 < dataStart + frameSize &&
                    !(tagData[descEnd] == 0.toByte() && tagData[descEnd + 1] == 0.toByte())
                ) {
                    descEnd += 2
                }
                picDataStart = descEnd + 2
            } else {
                var descEnd = descStart
                while (descEnd < dataStart + frameSize && tagData[descEnd] != 0.toByte()) {
                    descEnd++
                }
                picDataStart = descEnd + 1
            }
            if (picDataStart < dataStart + frameSize) {
                val picLength = (dataStart + frameSize) - picDataStart
                if (picLength in 1..35_000_000) {
                    return tagData.copyOfRange(picDataStart, picDataStart + picLength)
                }
            }
            return null
        }
        pos += 10 + frameSize
    }
    return null
}

fun extractFlacPicture(context: Context, uri: Uri, directPath: String? = null): ByteArray? {
    return runCatching {
        val stream = if (directPath != null && File(directPath).exists()) {
            File(directPath).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: return@runCatching null

        stream.use { rawStream ->
            extractFlacPictureFromStream(rawStream)
        }
    }.getOrNull()
}

private fun skipFully(dis: java.io.DataInputStream, bytesToSkip: Int) {
    var remaining = bytesToSkip.toLong()
    while (remaining > 0) {
        val skipped = dis.skip(remaining)
        if (skipped <= 0) {
            if (dis.read() == -1) break
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

suspend fun extractThumbnailOnDemand(
    context: Context,
    key: String,
    audioUriStr: String,
): Bitmap? = coroutineScope {
    val cleanAudioUri = cleanLocalArtPath(audioUriStr)
    val hash = localArtHash(cleanAudioUri)

    // Check disk first
    val thumbFile = findLocalArtThumbFile(context, cleanAudioUri) ?: findLocalArtThumbFile(context, key)
    if (thumbFile != null && thumbFile.length() > 0) {
        val bmp = decodeSampledBitmapFromFile(thumbFile.absolutePath, 160)
        if (bmp != null) {
            val img = bmp.asImageBitmap()
            LocalArtworkCache.put(key, 160, img)
            LocalArtworkCache.put(cleanAudioUri, 160, img)
            return@coroutineScope bmp
        }
    }

    if (LocalArtworkCache.isNoArt(context, cleanAudioUri)) return@coroutineScope null

    val deferred = LocalArtworkCache.extractionJobs.computeIfAbsent(hash) {
        async(LocalArtworkCache.extractionDispatcher) {
            try {
                // Secondary disk check within worker
                val diskFile = findLocalArtThumbFile(context, cleanAudioUri) ?: findLocalArtThumbFile(context, key)
                if (diskFile != null && diskFile.length() > 0) {
                    return@async decodeSampledBitmapFromFile(diskFile.absolutePath, 160)
                }

                var picBytes: ByteArray? = null
                var loadedBmp: Bitmap? = null

                val directFile = if (cleanAudioUri.startsWith("/")) File(cleanAudioUri).takeIf { it.exists() } else null
                if (directFile != null) {
                    runCatching {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(directFile.path)
                            picBytes = r.embeddedPicture
                        } finally {
                            r.release()
                        }
                    }
                }

                val uri = runCatching { Uri.parse(cleanAudioUri) }.getOrNull()
                // 1. Try context.contentResolver.openFileDescriptor(audioUri, "r")
                if (picBytes == null && uri != null && !cleanAudioUri.startsWith("/")) {
                    runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            val r = MediaMetadataRetriever()
                            try {
                                r.setDataSource(pfd.fileDescriptor)
                                picBytes = r.embeddedPicture
                            } finally {
                                r.release()
                            }
                        }
                    }
                }

                // 2. Try retriever.setDataSource(context, uri)
                if (picBytes == null && uri != null && !cleanAudioUri.startsWith("/")) {
                    runCatching {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, uri)
                            picBytes = r.embeddedPicture
                        } finally {
                            r.release()
                        }
                    }
                }

                // 3. Android 10+ (Q) loadThumbnail fallback
                if (picBytes == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null && !cleanAudioUri.startsWith("/")) {
                    runCatching {
                        loadedBmp = context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
                    }
                }

                // 4. Lightweight FLAC Picture Block Extractor fallback (handles 24-bit Hi-Res FLAC)
                if (picBytes == null && loadedBmp == null) {
                    val flacDirect = if (cleanAudioUri.startsWith("/")) cleanAudioUri else null
                    val flacUri = runCatching { Uri.parse(cleanAudioUri) }.getOrNull()
                    if (flacUri != null || flacDirect != null) {
                        picBytes = extractFlacPicture(context, flacUri ?: Uri.EMPTY, flacDirect)
                    }
                }

                val finalBmp: Bitmap? = when {
                    loadedBmp != null -> {
                        if (loadedBmp.width != 128 || loadedBmp.height != 128) {
                            val scaled = Bitmap.createScaledBitmap(loadedBmp, 128, 128, true)
                            if (scaled != loadedBmp) loadedBmp.recycle()
                            scaled
                        } else loadedBmp
                    }
                    picBytes != null && picBytes.isNotEmpty() -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, bounds)
                        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 128)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val decoded = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
                        if (decoded != null && (decoded.width > 128 || decoded.height > 128)) {
                            val scaled = Bitmap.createScaledBitmap(decoded, 128, 128, true)
                            if (scaled != decoded) decoded.recycle()
                            scaled
                        } else decoded
                    }
                    else -> null
                }

                if (finalBmp != null) {
                    val thumbDir = File(context.filesDir, "local_art_thumb").apply { if (!exists()) mkdirs() }
                    val targetFile = File(thumbDir, "$hash.jpg")
                    val tmp = File(thumbDir, "$hash.tmp")
                    runCatching {
                        FileOutputStream(tmp).use { out ->
                            finalBmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        if (!tmp.renameTo(targetFile)) {
                            tmp.copyTo(targetFile, overwrite = true)
                            tmp.delete()
                        }
                    }
                    LocalArtworkCache.removeNoArt(context, cleanAudioUri)
                    finalBmp
                } else {
                    LocalArtworkCache.markNoArt(context, cleanAudioUri)
                    null
                }
            } finally {
                LocalArtworkCache.extractionJobs.remove(hash)
            }
        }
    }

    deferred.await()
}

suspend fun extractFullArtworkOnDemand(
    context: Context,
    key: String,
    audioUriStr: String?,
    maxSizePx: Int = 1440,
): Bitmap? = withContext(LocalArtworkCache.extractionDispatcher) {
    // 1. Check if full-resolution file already exists on disk in filesDir/local_art_full/<hash>.jpg
    val fullFile = findLocalArtFullFile(context, key)
        ?: audioUriStr?.let { findLocalArtFullFile(context, it) }
    if (fullFile != null && fullFile.length() > 0) {
        val bmp = decodeSampledBitmapFromFile(fullFile.absolutePath, maxSizePx)
        if (bmp != null) return@withContext bmp
    }

    // 2. Check if a non-thumb raw file path was passed directly (e.g. an album art image file)
    val cleanKey = cleanLocalArtPath(key)
    if (cleanKey.startsWith("/") && !cleanKey.contains("local_art_thumb")) {
        val rawFile = File(cleanKey)
        if (rawFile.exists() && rawFile.length() > 0) {
            val bmp = decodeSampledBitmapFromFile(rawFile.absolutePath, maxSizePx)
            if (bmp != null) return@withContext bmp
        }
    }

    // 3. Extract embedded picture from audioUriStr or key
    val candidateUri = audioUriStr?.takeIf { it.isNotBlank() } ?: key.takeIf {
        it.startsWith("content://") || (it.startsWith("/") && !it.contains("local_art_thumb"))
    }
    if (candidateUri.isNullOrBlank()) return@withContext null

    val cleanAudioUri = cleanLocalArtPath(candidateUri)
    val hash = extractArtHash(cleanAudioUri)

    var picBytes: ByteArray? = null

    val directFile = if (cleanAudioUri.startsWith("/")) File(cleanAudioUri).takeIf { it.exists() } else null
    if (directFile != null) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(directFile.path)
                picBytes = r.embeddedPicture
            } finally {
                r.release()
            }
        }
    }

    val uri = runCatching { Uri.parse(cleanAudioUri) }.getOrNull()
    if (picBytes == null && uri != null && !cleanAudioUri.startsWith("/")) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(pfd.fileDescriptor)
                    picBytes = r.embeddedPicture
                } finally {
                    r.release()
                }
            }
        }
    }

    if (picBytes == null && uri != null && !cleanAudioUri.startsWith("/")) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                picBytes = r.embeddedPicture
            } finally {
                r.release()
            }
        }
    }

    // Lightweight FLAC Picture Block Extractor fallback for full-HD artwork
    if (picBytes == null) {
        val flacDirect = if (cleanAudioUri.startsWith("/")) cleanAudioUri else null
        val flacUri = runCatching { Uri.parse(cleanAudioUri) }.getOrNull()
        if (flacUri != null || flacDirect != null) {
            picBytes = extractFlacPicture(context, flacUri ?: Uri.EMPTY, flacDirect)
        }
    }

    if (picBytes != null && picBytes.isNotEmpty()) {
        // Save raw bytes to local_art_full/<hash>.jpg for permanent instant full-HD reloads
        val fullDir = File(context.filesDir, "local_art_full").apply { if (!exists()) mkdirs() }
        val targetFull = File(fullDir, "$hash.jpg")
        val tmpFull = File(fullDir, "$hash.tmp")
        runCatching {
            FileOutputStream(tmpFull).use { out ->
                out.write(picBytes)
            }
            if (!tmpFull.renameTo(targetFull)) {
                tmpFull.copyTo(targetFull, overwrite = true)
                tmpFull.delete()
            }
        }

        // Also ensure 128px thumb exists
        LocalArtworkCache.saveAndCacheThumbnail(context, cleanAudioUri, picBytes)

        // Decode with ARGB_8888 up to maxSizePx (e.g. 1440)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, bounds)
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return@withContext BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
    }

    // Android 10+ loadThumbnail fallback at full resolution
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null && !cleanAudioUri.startsWith("/")) {
        val qBmp = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(maxSizePx, maxSizePx), null)
        }.getOrNull()
        if (qBmp != null) {
            val fullDir = File(context.filesDir, "local_art_full").apply { if (!exists()) mkdirs() }
            val targetFull = File(fullDir, "$hash.jpg")
            val tmpFull = File(fullDir, "$hash.tmp")
            runCatching {
                FileOutputStream(tmpFull).use { out ->
                    qBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (!tmpFull.renameTo(targetFull)) {
                    tmpFull.copyTo(targetFull, overwrite = true)
                    tmpFull.delete()
                }
            }
            return@withContext qBmp
        }
    }

    null
}

private val localImageDispatcher = Dispatchers.IO.limitedParallelism(6)

@Composable
actual fun LocalArtworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    maxSizePx: Int,
    localUri: String?,
    onLoadingStateChanged: ((isLoading: Boolean, isSuccess: Boolean) -> Unit)?,
    fallback: @Composable () -> Unit,
) {
    val primaryKey = localUri?.takeIf { it.isNotBlank() } ?: cleanLocalArtPath(url)
    val fallbackKey = cleanLocalArtPath(url)
    if (primaryKey.isBlank() && fallbackKey.isBlank()) {
        fallback()
        return
    }

    val context = LocalContext.current.applicationContext
    val artworkVersion by LocalArtworkCache.artworkVersionFlow.collectAsState()
    val isFullHd = maxSizePx > 256

    // Step A (Instant RAM):
    // For Full HD: check fullHdLruCache first. If not found, use 128px thumb as instant placeholder!
    // For thumbnails (<= 256): check standard lruCache.
    val initialCached = remember(primaryKey, fallbackKey, maxSizePx) {
        if (isFullHd) {
            LocalArtworkCache.getFullHd(primaryKey)
                ?: LocalArtworkCache.getFullHd(fallbackKey)
                ?: LocalArtworkCache.get(primaryKey, 160)
                ?: LocalArtworkCache.get(fallbackKey, 160)
                ?: LocalArtworkCache.get(primaryKey, 128)
                ?: LocalArtworkCache.get(fallbackKey, 128)
        } else {
            LocalArtworkCache.get(primaryKey, maxSizePx)
                ?: LocalArtworkCache.get(fallbackKey, maxSizePx)
        }
    }

    val state by produceState<LocalArtState>(
        initialValue = if (initialCached != null) LocalArtState.Loaded(initialCached) else LocalArtState.Loading,
        primaryKey,
        fallbackKey,
        maxSizePx,
        artworkVersion,
    ) {
        if (isFullHd) {
            val hd = LocalArtworkCache.getFullHd(primaryKey) ?: LocalArtworkCache.getFullHd(fallbackKey)
            if (hd != null) {
                value = LocalArtState.Loaded(hd)
                onLoadingStateChanged?.invoke(false, true)
                return@produceState
            }
        } else {
            val exactCached = LocalArtworkCache.get(primaryKey, maxSizePx) ?: LocalArtworkCache.get(fallbackKey, maxSizePx)
            if (exactCached != null) {
                value = LocalArtState.Loaded(exactCached)
                onLoadingStateChanged?.invoke(false, true)
                return@produceState
            }
        }

        if (initialCached != null) {
            value = LocalArtState.Loaded(initialCached)
            onLoadingStateChanged?.invoke(false, true)
        } else {
            value = LocalArtState.Loading
            onLoadingStateChanged?.invoke(true, false)
        }

        val loaded = withContext(localImageDispatcher) {
            if (isFullHd) {
                val hdBitmap = extractFullArtworkOnDemand(context, fallbackKey, localUri ?: primaryKey, maxSizePx)
                if (hdBitmap != null) {
                    val img = hdBitmap.asImageBitmap()
                    LocalArtworkCache.putFullHd(primaryKey, img)
                    LocalArtworkCache.putFullHd(fallbackKey, img)
                    img
                } else {
                    var bmp = decodeSampledBitmap(context, fallbackKey, maxSizePx)
                    if (bmp == null && primaryKey != fallbackKey) {
                        bmp = decodeSampledBitmap(context, primaryKey, maxSizePx)
                    }
                    bmp?.asImageBitmap()?.also {
                        LocalArtworkCache.putFullHd(primaryKey, it)
                        LocalArtworkCache.putFullHd(fallbackKey, it)
                    }
                }
            } else {
                // Step B (Instant Disk): Check filesDir/local_art_thumb/<hash>.jpg
                var bmp = decodeSampledBitmap(context, fallbackKey, maxSizePx)
                if (bmp == null && primaryKey != fallbackKey) {
                    bmp = decodeSampledBitmap(context, primaryKey, maxSizePx)
                }

                // Step C (One-Time Lazy Fallback): On-demand extraction if thumbnail missing
                if (bmp == null) {
                    val candidateAudioUri = when {
                        localUri?.isNotBlank() == true -> localUri
                        url.startsWith("local_art://") -> cleanLocalArtPath(url)
                        url.startsWith("content://") -> url
                        url.startsWith("/") -> url
                        url.startsWith("file://") -> cleanLocalArtPath(url)
                        else -> null
                    }
                    if (candidateAudioUri != null) {
                        bmp = extractThumbnailOnDemand(context, primaryKey, candidateAudioUri)
                    }
                }

                bmp?.asImageBitmap()?.also {
                    LocalArtworkCache.put(primaryKey, maxSizePx, it)
                    LocalArtworkCache.put(fallbackKey, maxSizePx, it)
                }
            }
        }

        if (loaded != null) {
            value = LocalArtState.Loaded(loaded)
            onLoadingStateChanged?.invoke(false, true)
        } else if (initialCached != null) {
            value = LocalArtState.Loaded(initialCached)
            onLoadingStateChanged?.invoke(false, true)
        } else {
            value = LocalArtState.Missing
            onLoadingStateChanged?.invoke(false, false)
        }
    }

    when (val s = state) {
        is LocalArtState.Loaded -> {
            Image(
                bitmap = s.bitmap,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
            )
        }
        is LocalArtState.Loading -> {
            Box(
                modifier = modifier.background(Color.White.copy(alpha = 0.06f)),
            )
        }
        is LocalArtState.Missing -> {
            fallback()
        }
    }
}
