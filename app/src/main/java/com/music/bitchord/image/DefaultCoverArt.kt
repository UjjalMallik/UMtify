package com.music.bitchord.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.error
import coil3.request.fallback
import com.music.bitchord.sharedui.R
import java.io.ByteArrayOutputStream

/**
 * The cover UMtify draws when a track has none.
 *
 * A missing sleeve used to mean a blank box in a list, a grey note glyph in the
 * player and no large icon in the notification — three readings of "something
 * is broken" for a state that is normal (local files with no embedded picture,
 * WebDAV and SMB shares, an offline cache miss, a deleted YouTube thumbnail).
 * One picture, shipped with the app, answers all of them: [DefaultCoverFallback]
 * hands it to every Coil request as the error/fallback image, [bitmap] gives it
 * to the surfaces that need pixels rather than a URL (the widget, the media
 * session's `artworkData`), and [com.music.bitchord.ui.components.DefaultCoverTile]
 * to the Compose empty states that never issue a request at all.
 */
object DefaultCoverArt {

    /**
     * The shared artwork, at 1024px — square, so any tile can centre-crop it.
     *
     * The file lives in `:sharedUi`'s `androidMain` resources, which is where
     * [com.music.bitchord.ui.components.DefaultCoverTile] reads it from too;
     * `nonTransitiveRClass` is on, so reaching it takes that module's `R` and not
     * this app's.
     */
    val RES_ID = R.drawable.default_cover

    /**
     * The cover as a [px]-sized bitmap.
     *
     * The drawable's decoded bitmap is held by [android.content.res.Resources]
     * once and shared by every call, so this copy is the only per-call cost —
     * and it is scaled here rather than left to the caller, because a hardware
     * bitmap read back by the notification or the widget is the
     * "cannot use a recycled source in createBitmap" crash this app has been
     * bitten by before.
     */
    fun bitmap(context: Context, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val side = maxOf(1, px)
        runCatching {
            ContextCompat.getDrawable(context, RES_ID)?.apply { setBounds(0, 0, side, side) }?.draw(canvas)
        }
        return out
    }

    /** The cover as JPEG bytes, for `MediaMetadata.artworkData`. */
    fun jpeg(context: Context, px: Int, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            bitmap(context, px).compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}

/**
 * Gives every image request in the app the UMtify cover to fall back on.
 *
 * Registered after the other interceptors in
 * [com.music.bitchord.BitChordApplication], so it sees requests they already
 * shaped. It never removes one: `ImageRequest` fills an unset error image from
 * the loader's defaults, so "not the default" is how a request says it chose
 * its own picture, and that one is left alone.
 *
 * Where it lands: a null or blank URL (Coil's `NullRequestDataException` path
 * resolves `fallback`, which is the row's case for a track with no
 * `thumbnailUrl`) and a 404 or an offline failure (`error`) — both of which are
 * answers that do not change, so painting them costs the user nothing to look
 * at.
 *
 * Never as a `placeholder`, which is the same picture one step earlier and is
 * the wrong step: a placeholder is shown while a real cover is still on its way,
 * so every track with artwork would open on UMtify's sleeve and swap a moment
 * later. An unloaded tile therefore stays exactly as its host drew it — the
 * palette-tinted square behind a row or the player sleeve — and the cover
 * arrives once, when there is a cover or when there never will be.
 */
class DefaultCoverFallback : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val defaults = request.defaults
        val chosenByCaller = request.errorFactory !== defaults.errorFactory ||
            request.fallbackFactory !== defaults.fallbackFactory
        if (chosenByCaller) return chain.proceed()
        return chain.withRequest(
            request.newBuilder()
                .error(DefaultCoverArt.RES_ID)
                .fallback(DefaultCoverArt.RES_ID)
                .build(),
        ).proceed()
    }
}
