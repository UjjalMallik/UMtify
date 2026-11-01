package com.music.bitchord.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Resolves a session's artwork to the bitmap every system media surface draws.
 *
 * Media3's `DefaultMediaNotificationProvider` turns a track's cover into the
 * notification's artwork by asking the session's [BitmapLoader] for it, and a
 * session built without one gets Media3's fallback, which reads local files and
 * nothing else. This app's covers are `https` URLs — [toMediaItem]'s
 * `setArtworkUri` — so the question has been going unanswered for every streamed
 * track: the notification has gone out with no large icon, and the pill One UI
 * draws, ColorOS's and realme's Mini Capsule and HyperOS's status-bar capsule,
 * has had no circular cover to draw. The metadata itself was always complete;
 * only the step that turns its URI into pixels was missing.
 *
 * Coil, not a hand-rolled fetch: [com.music.bitchord.BitChordApplication] owns
 * the one loader that knows how to reach a cover here, so WebDAV credentials,
 * the SMB share and the sized-URL cache entries the rows and the widget already
 * filled all come with it — a track visible on screen reaches the pill off disk.
 *
 * [scope] is the service's, so a load still in flight when the service is torn
 * down goes with it rather than outliving the session it was answering for.
 */
internal class SessionArtworkLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(data, /* offset= */ 0, /* size= */ data.size)
                ?: throw IOException("Artwork attached to the session is not a decodable image.")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(uri)
            // The size the URI was already asked for, so this lands on the cache
            // entry the UI made rather than a second copy of the same cover.
            .size(NOTIFICATION_ART_PX)
            // Media3 scales whatever it is handed, and scaling a hardware bitmap
            // is the "cannot use a recycled source in createBitmap" crash this
            // service has been bitten by once before.
            .allowHardware(false)
            // A cover is a thing that costs data, so Offline Mode's promise
            // reaches here too: reads stay served, so a download whose art the
            // UI already filled still shows it in the pill, and nothing new is
            // fetched for it.
            .networkCachePolicy(
                if (AppSettings.isOffline) CachePolicy.DISABLED else CachePolicy.ENABLED
            )
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        (result as? SuccessResult)?.image?.toBitmap()
            ?: throw IOException("No artwork at $uri.")
    }
}
