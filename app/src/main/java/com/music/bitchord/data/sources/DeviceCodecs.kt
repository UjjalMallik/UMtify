package com.music.bitchord.data.sources

import android.media.MediaCodecList
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale

/**
 * What this particular phone can actually decode, asked once and remembered.
 *
 * One question so far, and it is **Dolby Atmos**. E-AC-3 is licensed, so its
 * decoder ships with the *vendor* image rather than with Android —
 * `c2.dolby.eac3.decoder` is present on the Samsung this was traced on and
 * absent on plenty of devices that look identical from up here.
 *
 * What the answer is used for changed in November 2026, and the two histories
 * are worth keeping straight, because the reasoning below is about the *first*
 * use. This used to decide whether an Atmos rendition was asked for at all, on
 * the grounds of how badly the player failed without a decoder:
 * `DefaultTrackSelector` declines to select a track whose support is
 * `FORMAT_UNSUPPORTED_SUBTYPE`, the period then ends with no renderer enabled,
 * and the queue moves on — a song that skipped itself instantly, with no
 * exception for
 * [PlaybackService.recoverFrom][com.music.bitchord.playback.PlaybackService] to
 * recover from and nothing on screen to explain it. The other half of the time
 * it was a decoder-init failure, which recovers, but only by spending the
 * attempt budget and landing on YouTube.
 *
 * Neither happens now: `PlaybackService.silenceSkippingRenderers` registers the
 * FFmpeg renderer behind MediaCodec and enables decoder fallback, so a phone
 * without Dolby's own decoder plays the 5.1 bed instead of declining the track,
 * and the spatializer stands in for the object layer
 * ([offersDolbyAtmos] is the switch that gates asking, and it is the listener's
 * answer alone). What still needs the hardware verdict is narrower, and it is
 * why this object stays: the Settings row says one thing where Dolby decodes
 * and another where the app has to fake it, and
 * [PlaybackService.applySpatialAudioEnabled][com.music.bitchord.playback.PlaybackService]
 * has to know whether the mix it is being handed is already spatial or is a
 * downmix that wants widening.
 *
 * ### Getting the answer right
 *
 * The verdict has to be the *renderer's* verdict, because the renderer is who
 * acts on it, and a hand-rolled second opinion that disagrees is worse than no
 * opinion at all — a false yes spends the fallback path on a device that would
 * have rendered Dolby's own mix, and a false no spends the listener's own
 * decoder. So [MediaCodecUtil] is asked first: it is precisely what
 * `MediaCodecSelector` hands `MediaCodecAudioRenderer`, including Media3's
 * per-device workarounds and its blocklists for decoders that are advertised
 * and don't work.
 *
 * Both mime types are asked for. A decoder that declares plain `audio/eac3` can
 * carry a JOC stream — the height objects are dropped and what comes out is the
 * 5.1 core downmixed, which is a lesser render but not a failure — and Media3
 * will fall back to one for exactly that reason. Asking for only the JOC mime
 * would report no Dolby decoder on every device that ships the ordinary E-AC-3
 * one.
 *
 * [MediaCodecList] is then a backstop rather than the primary answer, for the
 * case where the Media3 query throws (`DecoderQueryException` is a real
 * outcome on a device whose codec list is malformed). `REGULAR_CODECS`
 * deliberately, not `ALL_CODECS`: the extra entries in the full list exist only
 * for specific configurations, and counting them is how you end up saying yes
 * to a decoder the renderer will not be given.
 *
 * ### What this does not cover
 *
 * Passthrough. A phone with no decoder at all can still send E-AC-3 to a
 * receiver that has one, over HDMI or a capable USB interface, and Media3 will
 * do that when `AudioCapabilities` says the *current route* supports the
 * encoding. That answer changes when headphones are plugged in, so it cannot be
 * cached, and it is false on a phone's own speaker and over Bluetooth — which
 * is where this app is listened to. Leaving it out costs a listener running a
 * phone into an AVR the sentence that Dolby's own decoder is in use; they get
 * the software path instead, which is playable but is not what the receiver was
 * bought for. Left out knowingly.
 */
object DeviceCodecs {

    private const val TAG = "BitChord"

    /**
     * E-AC-3 JOC, and the plain E-AC-3 core it degrades to. Either decoder is
     * enough to say yes — see the note on fallback above. Spelt out rather
     * than taken from Media3's `MimeTypes`, so that everything outside
     * [media3Decoders] stays clear of the unstable API surface.
     */
    private val DOLBY_MIMES = listOf("audio/eac3-joc", "audio/eac3")

    @Volatile
    private var probed: Boolean? = null

    /**
     * Test seam, and the field override of last resort. Exists so a caller can
     * stand in for a device without a Dolby decoder — which now changes only
     * what the Settings row says and whether the spatializer stands in, since
     * [offersDolbyAtmos] no longer asks this.
     */
    @Volatile
    internal var forced: Boolean? = null

    /**
     * Whether an Atmos rendition may be asked for and played right now.
     *
     * The listener's switch and nothing else. This used to be folded together
     * with [playsDolbyAtmos] at every call site, which was the right answer
     * while the only decoder in reach was a MediaCodec one: handing an E-AC-3
     * stream to a phone without Dolby's decoder was a silent skip rather than a
     * fallback. That is no longer true — [androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer]
     * sits behind the MediaCodec renderer on every device and decodes E-AC-3
     * (and AC-4) to its bed, and [com.music.bitchord.playback.SpatialAudioProcessor]
     * widens what comes out when the switch asks for Atmos on a device with no
     * native decoder. Refusing on the device's behalf now only costs the
     * listener the rendition they asked for, so the hardware answer is kept for
     * two things that genuinely need it: what the Settings row says, and whether
     * the spatializer has to stand in.
     */
    val offersDolbyAtmos: Boolean
        get() = AppSettings.dolbyAtmos.value

    /**
     * Whether an E-AC-3 (JOC) stream would find a decoder on this device.
     *
     * Answered once per process and cached: the codec list cannot change while
     * the app is running, and the query is not free — a `MediaCodecList` walk
     * costs tens of milliseconds, on a path that is otherwise holding up audio.
     */
    val playsDolbyAtmos: Boolean
        get() = forced ?: probed ?: probe().also { probed = it }

    /**
     * Yes on anything that cannot be established.
     *
     * A probe that fails outright is exotic and an unsupported device is not,
     * but the cost of the two mistakes is still not symmetric: guessing "no" on
     * a capable flagship spends the app's own decoder and spatializer on a mix
     * Dolby meant to be rendered by hardware, while guessing "yes" only plays
     * the track the way every device without the decoder now plays it. So the
     * uncertain case keeps the native path.
     */
    private fun probe(): Boolean {
        val viaMedia3 = media3Decoders()
        if (viaMedia3 != null) {
            TrackLog.d(TAG, "Dolby Atmos decoders (Media3): ${viaMedia3.ifEmpty { "none" }}")
            return viaMedia3.isNotEmpty()
        }
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.lowercase(Locale.ROOT) in DOLBY_MIMES
                }
            }.map { it.name }
        }.onSuccess {
            TrackLog.d(TAG, "Dolby Atmos decoders (platform): ${it.ifEmpty { "none" }}")
        }.map {
            it.isNotEmpty()
        }.getOrElse {
            TrackLog.w(TAG, "could not read the codec list; assuming Dolby Atmos plays — ${it.message}")
            true
        }
    }

    /**
     * The decoders Media3 itself would offer the renderer for an Atmos stream,
     * by name, or null if the query failed and the caller should fall back to
     * the platform's own list.
     *
     * Isolated so the unstable-API surface is one function wide.
     */
    @UnstableApi
    private fun media3Decoders(): List<String>? = runCatching {
        DOLBY_MIMES.flatMap { MediaCodecUtil.getDecoderInfos(it, false, false) }
            .map { it.name }
            .distinct()
    }.getOrElse {
        TrackLog.w(TAG, "Media3 could not enumerate Dolby decoders — ${it.message}")
        null
    }
}
