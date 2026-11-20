package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

fun isLocalArtUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val clean = url.trim()
    return clean.startsWith("file://") || clean.startsWith("/") ||
        clean.startsWith("local_art://") || clean.startsWith("content://")
}

fun cleanLocalArtPath(url: String): String = when {
    url.startsWith("file://") -> url.removePrefix("file://")
    url.startsWith("local_art://file://") -> url.removePrefix("local_art://file://")
    url.startsWith("local_art://") -> url.removePrefix("local_art://")
    else -> url
}

@Composable
expect fun LocalArtworkImage(
    url: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxSizePx: Int = 160,
    onLoadingStateChanged: ((isLoading: Boolean, isSuccess: Boolean) -> Unit)? = null,
    fallback: @Composable () -> Unit = { DefaultCoverTile(modifier = modifier, contentScale = contentScale) },
)
