package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Android implementation of UMtify's aesthetic Liquid Glass artwork placeholder.
 */
@Composable
actual fun DefaultCoverTile(
    modifier: Modifier,
    contentScale: ContentScale,
    title: String?,
) {
    DefaultArtworkPlaceholder(
        title = title.orEmpty(),
        modifier = modifier,
    )
}
