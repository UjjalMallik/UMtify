package com.music.bitchord.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.sharedui.resources.Res
import org.jetbrains.compose.resources.painterResource

/**
 * UMtify's own sleeve, drawn as a composables-level fallback for a cover that
 * isn't there.
 *
 * Most missing art goes through Coil — [com.music.bitchord.image.DefaultCoverFallback]
 * puts this same picture in every request's `error`/`fallback`, so a null
 * `thumbnailUrl` or a 404 lands on it without the caller deciding anything. This
 * is for the places that never issue a request: the shared player's empty sleeve
 * is painted by hand because it animates with the card, and a Compose-only host
 * has no drawable resource to point a request at.
 *
 * Lives here rather than in the app module so every surface that shows covers —
 * player, lists, the host that isn't Android — has one answer to "no artwork",
 * and the answer is the same picture in all of them.
 */
@Composable
fun DefaultCoverTile(
    modifier: Modifier = Modifier,
    /** A disc-shaped host crops to the circle; a square one needs nothing. */
    contentScale: ContentScale = ContentScale.Crop,
) {
    Image(
        painter = painterResource(Res.drawable.default_cover),
        contentDescription = null,
        contentScale = contentScale,
        modifier = Modifier.fillMaxSize().then(modifier),
    )
}
