package com.music.bitchord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

fun findLocalArtThumbFile(context: Context, clean: String): File? {
    val thumbDir = File(context.cacheDir, "local_art_thumb")
    if (!thumbDir.exists()) return null

    val name = File(clean).name
    if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true)) {
        val directThumb = File(thumbDir, name)
        if (directThumb.exists() && directThumb.length() > 0) return directThumb
    }

    val hash = clean.hashCode().toUInt().toString()
    val hashThumb = File(thumbDir, "$hash.jpg")
    if (hashThumb.exists() && hashThumb.length() > 0) return hashThumb

    return null
}

fun saveThumbnailFileIfMissing(context: Context, clean: String, bmp: Bitmap) {
    runCatching {
        val thumbDir = File(context.cacheDir, "local_art_thumb").apply { if (!exists()) mkdirs() }
        val name = File(clean).name
        val targetFile = if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true)) {
            File(thumbDir, name)
        } else {
            val hash = clean.hashCode().toUInt().toString()
            File(thumbDir, "$hash.jpg")
        }
        if (!targetFile.exists() || targetFile.length() == 0L) {
            val tmp = File(thumbDir, "${targetFile.name}.tmp")
            val scaled = if (bmp.width > 128 || bmp.height > 128) {
                Bitmap.createScaledBitmap(bmp, 128, 128, true)
            } else {
                bmp
            }
            java.io.FileOutputStream(tmp).use { out ->
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

    if (clean.startsWith("content://")) {
        val hash = clean.hashCode().toUInt().toString()
        val cachedFile = File(File(context.cacheDir, "local_art"), "$hash.jpg")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            val bmp = decodeSampledBitmapFromFile(cachedFile.absolutePath, maxSizePx)
            if (bmp != null && maxSizePx <= 200) {
                saveThumbnailFileIfMissing(context, clean, bmp)
            }
            return bmp
        }
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

private val localImageDispatcher = Dispatchers.IO.limitedParallelism(6)

@Composable
actual fun LocalArtworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    maxSizePx: Int,
    onLoadingStateChanged: ((isLoading: Boolean, isSuccess: Boolean) -> Unit)?,
    fallback: @Composable () -> Unit,
) {
    val cleanPath = cleanLocalArtPath(url)
    if (cleanPath.isBlank()) {
        fallback()
        return
    }

    // Synchronously check RAM LruCache first (exact size, then any size if large)
    val initialCached = remember(cleanPath, maxSizePx) {
        LocalArtworkCache.get(cleanPath, maxSizePx)
            ?: if (maxSizePx > 256) LocalArtworkCache.get(cleanPath, 160) else null
    }
    val context = LocalContext.current.applicationContext

    val state by produceState<LocalArtState>(
        initialValue = if (initialCached != null) LocalArtState.Loaded(initialCached) else LocalArtState.Loading,
        key1 = cleanPath,
        key2 = maxSizePx,
    ) {
        val exactCached = LocalArtworkCache.get(cleanPath, maxSizePx)
        if (exactCached != null) {
            value = LocalArtState.Loaded(exactCached)
            onLoadingStateChanged?.invoke(false, true)
            return@produceState
        }

        if (initialCached != null) {
            value = LocalArtState.Loaded(initialCached)
            onLoadingStateChanged?.invoke(false, true)
        } else {
            value = LocalArtState.Loading
            onLoadingStateChanged?.invoke(true, false)
        }

        val loaded = withContext(localImageDispatcher) {
            val bmp = decodeSampledBitmap(context, cleanPath, maxSizePx)
            bmp?.asImageBitmap()?.also {
                LocalArtworkCache.put(cleanPath, maxSizePx, it)
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
            // Clean, subtle dark surface placeholder with ZERO bright neon flash!
            Box(
                modifier = modifier.background(Color.White.copy(alpha = 0.06f)),
            )
        }
        is LocalArtState.Missing -> {
            fallback()
        }
    }
}
