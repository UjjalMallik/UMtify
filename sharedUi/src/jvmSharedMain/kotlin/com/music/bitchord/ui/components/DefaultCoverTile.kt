package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * UMtify's own sleeve, drawn as a composables-level fallback for a cover that
 * isn't there.
 *
 * Most missing art never reaches a composable: the app's one Coil loader gives
 * every request UMtify's cover as its `error`/`fallback`, so a null
 * `thumbnailUrl` or a 404 lands on it without the caller deciding anything — see
 * [com.music.bitchord.image.DefaultCoverFallback]. This is for the places that
 * never issue a request at all, of which the shared player's empty sleeve is the
 * only one: it is painted by hand because it animates with the card.
 *
 * The picture itself is an Android resource — `sharedUi/src/androidMain/res/`
 * `drawable-nodpi/default_cover.webp` — and the actual below reads it through
 * this module's own generated `R`. It deliberately does not use
 * `composeResources` and `Res.drawable`: the same file placed there failed to
 * resolve as a generated accessor on the CI runner twice, in two formats, while
 * plain Android resources of this module compile every build.
 *
 * Expected rather than shared, because the module compiles for the desktop too
 * and Android's `R` does not exist there.
 */
@Composable
expect fun DefaultCoverTile(
    modifier: Modifier,
    /** A disc-shaped host crops to the circle; a square one needs nothing. */
    contentScale: ContentScale,
)
