package com.music.bitchord.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.music.bitchord.R

/**
 * The brand as it spells itself, wherever a slot is too wide for just a mark.
 *
 * The asset is pure white with the lettering carried in its alpha channel, so a
 * tint recolours it without touching the shape and [color] is free to be
 * whatever the surface needs. Only the height is ever given: the 800×275 ratio
 * is applied here, so no caller has to remember it and none can stretch the word.
 */
@Composable
fun UmifyWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Image(
        painter = painterResource(R.drawable.ic_umtify_wordmark),
        contentDescription = stringResource(R.string.app_name),
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.FillHeight,
        modifier = modifier.height(height).aspectRatio(WORDMARK_ASPECT),
    )
}

private const val WORDMARK_ASPECT = 800f / 275f
