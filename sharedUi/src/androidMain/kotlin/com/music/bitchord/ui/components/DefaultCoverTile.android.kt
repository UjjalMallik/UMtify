package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * A lightweight, clean minimalist vector placeholder for missing artwork:
 * dark surface background (#1A1A1E), subtle 1dp border, and centered music note glyph.
 */
@Composable
actual fun DefaultCoverTile(modifier: Modifier, contentScale: ContentScale) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A1A1E))
            .border(1.dp, Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxSize(0.38f),
        )
    }
}
