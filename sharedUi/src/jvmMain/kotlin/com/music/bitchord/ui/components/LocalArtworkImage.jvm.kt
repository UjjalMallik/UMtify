package com.music.bitchord.ui.components

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

private val jvmImageDispatcher = Dispatchers.IO.limitedParallelism(6)

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

    val initialCached = remember(cleanPath, maxSizePx) {
        JvmLocalArtworkCache.get(cleanPath, maxSizePx)
            ?: if (maxSizePx > 256) JvmLocalArtworkCache.get(cleanPath, 160) else null
    }

    val state by produceState<LocalArtState>(
        initialValue = if (initialCached != null) LocalArtState.Loaded(initialCached) else LocalArtState.Loading,
        key1 = cleanPath,
        key2 = maxSizePx,
    ) {
        val exactCached = JvmLocalArtworkCache.get(cleanPath, maxSizePx)
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

        val loaded = withContext(jvmImageDispatcher) {
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
