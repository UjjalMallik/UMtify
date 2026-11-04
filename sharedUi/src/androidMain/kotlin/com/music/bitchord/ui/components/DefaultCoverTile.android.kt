package com.music.bitchord.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.music.bitchord.sharedui.R

/**
 * The phone's copy of the fallback sleeve, drawn from this module's own
 * `default_cover`, which also answers for every Coil request in the app through
 * [com.music.bitchord.image.DefaultCoverFallback].
 */
@Composable
actual fun DefaultCoverTile(modifier: Modifier, contentScale: ContentScale) {
    Image(
        painter = painterResource(R.drawable.default_cover),
        contentDescription = null,
        contentScale = contentScale,
        modifier = Modifier.fillMaxSize().then(modifier),
    )
}
