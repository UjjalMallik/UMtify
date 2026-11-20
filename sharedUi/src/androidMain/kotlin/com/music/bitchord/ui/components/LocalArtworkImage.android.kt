package com.music.bitchord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

fun decodeSampledBitmap(context: Context, source: String, maxSizePx: Int): Bitmap? {
    val clean = cleanLocalArtPath(source)
    val file = File(clean)
    if (file.exists() && file.length() > 0) {
        return decodeSampledBitmapFromFile(file.absolutePath, maxSizePx)
    }

    if (clean.startsWith("content://")) {
        val hash = clean.hashCode().toUInt().toString()
        val cachedFile = File(File(context.cacheDir, "local_art"), "$hash.jpg")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return decodeSampledBitmapFromFile(cachedFile.absolutePath, maxSizePx)
        }
        val uri = Uri.parse(clean)
        return decodeSampledBitmapFromUri(context, uri, maxSizePx)
    }

    return null
}

object LocalArtworkCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(16 * 1024, 64 * 1024) // in KB

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

private val localImageDispatcher = Dispatchers.IO.limitedParallelism(2)

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
    // For large player sleeves (maxSizePx > 256), instantly fall back to the 160px thumbnail if available
    val initialCached = remember(cleanPath, maxSizePx) {
        LocalArtworkCache.get(cleanPath, maxSizePx)
            ?: if (maxSizePx > 256) LocalArtworkCache.get(cleanPath, 160) else null
    }
    val context = LocalContext.current.applicationContext

    val bitmapState by produceState<ImageBitmap?>(initialValue = initialCached, key1 = cleanPath, key2 = maxSizePx) {
        val exactCached = LocalArtworkCache.get(cleanPath, maxSizePx)
        if (exactCached != null) {
            value = exactCached
            onLoadingStateChanged?.invoke(false, true)
            return@produceState
        }

        if (initialCached != null) {
            onLoadingStateChanged?.invoke(false, true)
        } else {
            onLoadingStateChanged?.invoke(true, false)
        }

        val loaded = withContext(localImageDispatcher) {
            val bmp = decodeSampledBitmap(context, cleanPath, maxSizePx)
            bmp?.asImageBitmap()?.also {
                LocalArtworkCache.put(cleanPath, maxSizePx, it)
            }
        }
        if (loaded != null) {
            value = loaded
            onLoadingStateChanged?.invoke(false, true)
        } else if (initialCached == null) {
            onLoadingStateChanged?.invoke(false, false)
        }
    }

    val bmp = bitmapState
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        fallback()
    }
}
