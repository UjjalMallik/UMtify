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
import com.music.bitchord.image.DefaultCoverArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Decodes a local file path or file:// URI into a scaled Bitmap, completely
 * bypassing network loaders and Coil for local music covers.
 */
internal fun decodeLocalBitmap(pathOrUri: String, maxDim: Int = NOTIFICATION_ART_PX): Bitmap? {
    return runCatching {
        val path = if (pathOrUri.startsWith("file://")) {
            Uri.parse(pathOrUri).path ?: pathOrUri.removePrefix("file://")
        } else {
            pathOrUri
        }
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        val maxSide = maxOf(origW, origH)
        while (maxSide / (sampleSize * 2) >= maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

        if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
    }.getOrNull()
}

/**
 * Loads downsampled JPEG bytes for a local file to attach directly as
 * [androidx.media3.common.MediaMetadata.artworkData] for lock screen & notifications.
 */
internal fun loadLocalArtworkBytes(pathOrUri: String, maxDim: Int = SESSION_ART_PX): ByteArray? {
    return runCatching {
        val bitmap = decodeLocalBitmap(pathOrUri, maxDim) ?: return null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, SESSION_ART_JPEG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() && it.size <= LEGACY_ARTWORK_DATA_LIMIT }
        }
    }.getOrNull()
}

/**
 * Resolves a session's artwork to the bitmap every system media surface draws.
 *
 * Media3's `DefaultMediaNotificationProvider` turns a track's cover into the
 * notification's artwork by asking the session's [BitmapLoader] for it, and a
 * session built without one gets Media3's fallback, which reads local files and
 * nothing else. This app's covers are `https` URLs — [toMediaItem]'s
 * `setArtworkUri` — so the question has been going unanswered for every streamed
 * track: the notification has gone out with no large icon, and the surfaces
 * that ask the session for a bitmap of their own have had nothing to draw.
 * The metadata itself was always complete; only the step that turns its URI
 * into pixels was missing.
 *
 * Coil, not a hand-rolled fetch: [com.music.bitchord.BitChordApplication] owns
 * the one loader that knows how to reach a cover here, so WebDAV credentials,
 * the SMB share and the sized-URL cache entries the rows and the widget already
 * filled all come with it — a track visible on screen reaches the notification
 * off disk.
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
        val uriStr = uri.toString()
        if (uri.scheme == "file" || uri.scheme == null || uriStr.startsWith("file://") || uriStr.startsWith("/")) {
            val path = uri.path ?: uriStr.removePrefix("file://")
            val local = decodeLocalBitmap(path, NOTIFICATION_ART_PX)
            if (local != null) return@future local
        } else if (uri.scheme == "content") {
            val contentBitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                    var sampleSize = 1
                    while (maxSide / (sampleSize * 2) >= NOTIFICATION_ART_PX) {
                        sampleSize *= 2
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }.getOrNull()
            if (contentBitmap != null) return@future contentBitmap
        }

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
            // UI already filled still shows it in the notification, and nothing
            // new is fetched for it.
            .networkCachePolicy(
                if (AppSettings.isOffline) CachePolicy.DISABLED else CachePolicy.ENABLED
            )
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        // UMtify's own sleeve rather than a failed future: Media3 turns a thrown
        // load into "this track has no artwork", which is the blank notification
        // this class exists to prevent. A cover that can't be reached is still a
        // track with a cover as far as every system surface is concerned.
        (result as? SuccessResult)?.image?.toBitmap()
            ?: DefaultCoverArt.bitmap(context, NOTIFICATION_ART_PX)
    }
}

/**
 * The cover as encoded bytes, for a track's `MediaMetadata.artworkData`.
 *
 * A URI is not enough for every surface. Media3's legacy bridge hands the
 * system a `MediaMetadataCompat` built from these fields, and the bitmap it
 * puts in that bundle comes from decoding `artworkData`. An `artworkUri`
 * pointing at `https` arrives as a URL string those surfaces never fetch for
 * themselves, which is why a track can be fully described on the lock screen
 * and still render with no cover, or none at all.
 *
 * JPEG at [SESSION_ART_PX], because the whole payload travels inside a Bundle:
 * Media3 drops `artworkData` rather than sending it once it passes
 * 500,000 bytes, and a 512px cover at this quality sits an order of magnitude
 * under that. Returns null on any failure — a cover that cannot be reached is
 * metadata with the art left out, not a track that stops playing.
 */
internal suspend fun sessionArtworkBytes(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        val uriStr = uri.toString()
        if (uri.scheme == "file" || uri.scheme == null || uriStr.startsWith("file://") || uriStr.startsWith("/")) {
            val path = uri.path ?: uriStr.removePrefix("file://")
            val localBytes = loadLocalArtworkBytes(path, SESSION_ART_PX)
            if (localBytes != null) return@withContext localBytes
        } else if (uri.scheme == "content") {
            val contentBytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                    val scaled = if (bitmap.width > SESSION_ART_PX || bitmap.height > SESSION_ART_PX) {
                        val scale = minOf(SESSION_ART_PX.toFloat() / bitmap.width, SESSION_ART_PX.toFloat() / bitmap.height)
                        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                            if (it != bitmap) bitmap.recycle()
                        }
                    } else {
                        bitmap
                    }
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, SESSION_ART_JPEG_QUALITY, out)
                        scaled.recycle()
                        out.toByteArray().takeIf { it.isNotEmpty() && it.size <= LEGACY_ARTWORK_DATA_LIMIT }
                    }
                }
            }.getOrNull()
            if (contentBytes != null) return@withContext contentBytes
        }

        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(SESSION_ART_PX)
            .allowHardware(false)
            .networkCachePolicy(
                if (AppSettings.isOffline) CachePolicy.DISABLED else CachePolicy.ENABLED
            )
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
            ?.image?.toBitmap()
            ?: DefaultCoverArt.bitmap(context, SESSION_ART_PX)
        runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, SESSION_ART_JPEG_QUALITY, out)
                out.toByteArray().takeIf { it.size <= LEGACY_ARTWORK_DATA_LIMIT && it.isNotEmpty() }
            }
        }.getOrNull()
    }

/**
 * UMtify's own sleeve as `artworkData`, for the track with no cover URL at all.
 *
 * [sessionArtworkBytes] can only answer for a URI; a local file with no embedded
 * picture, or a queue item whose thumbnail was never learned, has none. Without
 * this the session metadata goes out with the art field empty and every surface
 * that reads it — the lock screen, the legacy bundle, Android Auto's now-playing
 * — draws its own idea of "no cover", which is rarely the same picture twice.
 */
internal suspend fun defaultSessionArtworkBytes(context: Context): ByteArray? =
    withContext(Dispatchers.IO) {
        DefaultCoverArt.jpeg(context, SESSION_ART_PX, SESSION_ART_JPEG_QUALITY)
            ?.takeIf { it.size <= LEGACY_ARTWORK_DATA_LIMIT }
    }

/**
 * The cover as a bitmap, at the size a caller will actually draw it.
 *
 * Left on Coil's default of hardware bitmaps, because this one only ever goes
 * into an `ImageView` and a hardware bitmap is drawn by the GPU without a copy
 * into RAM. Anything that reads pixels back — the notification's large icon, the
 * legacy metadata bundle, the palette — goes through [sessionArtworkBytes] or
 * [SessionArtworkLoader.loadBitmap] instead, both of which ask for software.
 */
internal suspend fun sessionArtworkBitmap(context: Context, uri: Uri, px: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(px)
            .networkCachePolicy(
                if (AppSettings.isOffline) CachePolicy.DISABLED else CachePolicy.ENABLED
            )
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

/** The edge the published cover is resized to, and the quality it is encoded at. */
private const val SESSION_ART_PX = 512
private const val SESSION_ART_JPEG_QUALITY = 85

/** Media3's own ceiling for `artworkData` in a Bundle. */
private const val LEGACY_ARTWORK_DATA_LIMIT = 500_000
