package com.music.bitchord.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * Android implementation of UMtify's aesthetic Liquid Glass artwork placeholder.
 */
@Composable
actual fun BrandWordmarkLogo(modifier: Modifier) {
    Image(
        painter = painterResource(com.music.bitchord.sharedui.R.drawable.ic_umtify_wordmark),
        contentDescription = "UMtify",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

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
