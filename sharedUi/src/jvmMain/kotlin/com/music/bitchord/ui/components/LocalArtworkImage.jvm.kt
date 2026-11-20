package com.music.bitchord.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object JvmLocalArtworkCache {
    private val map = ConcurrentHashMap<String, ImageBitmap>()
    fun get(path: String, maxSizePx: Int = 160): ImageBitmap? = map["${cleanLocalArtPath(path)}:$maxSizePx"]
    fun put(path: String, maxSizePx: Int = 160, bmp: ImageBitmap) { map["${cleanLocalArtPath(path)}:$maxSizePx"] = bmp }
}

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
    val cached = remember(cleanPath, maxSizePx) { JvmLocalArtworkCache.get(cleanPath, maxSizePx) }

    val bitmapState by produceState<ImageBitmap?>(initialValue = cached, key1 = cleanPath, key2 = maxSizePx) {
        if (cached != null) {
            value = cached
            onLoadingStateChanged?.invoke(false, true)
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(cleanPath)
                if (file.exists() && file.length() > 0) {
                    val bytes = file.readBytes()
                    val skia = SkiaImage.makeFromEncoded(bytes)
                    skia.asComposeImageBitmap().also {
                        JvmLocalArtworkCache.put(cleanPath, maxSizePx, it)
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
