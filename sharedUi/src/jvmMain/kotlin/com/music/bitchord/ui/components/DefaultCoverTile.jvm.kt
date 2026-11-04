package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * The desktop has no copy of the sleeve — the artwork is an Android resource —
 * so an absent cover reads here as the flat surface every other empty tile in
 * this module already uses.
 */
@Composable
actual fun DefaultCoverTile(modifier: Modifier, contentScale: ContentScale) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(modifier),
    )
}
