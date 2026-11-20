package com.music.bitchord.ui.components

import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalArtworkCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(10 * 1024, 40 * 1024) // in KB

    private val lruCache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    fun get(path: String): ImageBitmap? = lruCache.get(path)
    fun put(path: String, bitmap: ImageBitmap) { lruCache.put(path, bitmap) }
    fun clear() { lruCache.evictAll() }
}

@Composable
actual fun LocalArtworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    onLoadingStateChanged: ((isLoading: Boolean, isSuccess: Boolean) -> Unit)?,
    fallback: @Composable () -> Unit,
) {
    val cleanPath = when {
        url.startsWith("file://") -> url.removePrefix("file://")
        url.startsWith("local_art://file://") -> url.removePrefix("local_art://file://")
        url.startsWith("local_art://") -> url.removePrefix("local_art://")
        else -> url
    }

    val cached = remember(cleanPath) { LocalArtworkCache.get(cleanPath) }

    val bitmapState by produceState<ImageBitmap?>(initialValue = cached, key1 = cleanPath) {
        if (cached != null) {
            value = cached
            onLoadingStateChanged?.invoke(false, true)
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(cleanPath)
                if (file.exists() && file.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    bmp?.asImageBitmap()?.also {
                        LocalArtworkCache.put(cleanPath, it)
                    }
                } else null
            }.getOrNull()
        }
        value = loaded
        onLoadingStateChanged?.invoke(false, loaded != null)
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
