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
    fun get(path: String): ImageBitmap? = map[path]
    fun put(path: String, bmp: ImageBitmap) { map[path] = bmp }
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

    val cached = remember(cleanPath) { JvmLocalArtworkCache.get(cleanPath) }

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
                    val bytes = file.readBytes()
                    val skia = SkiaImage.makeFromEncoded(bytes)
                    skia.asComposeImageBitmap().also {
                        JvmLocalArtworkCache.put(cleanPath, it)
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
