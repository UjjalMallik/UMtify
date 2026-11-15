package com.music.bitchord.ui.player

import com.music.bitchord.wear.WearBridge
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.bitchord.data.SongEditStore
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongEdit
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.cast.CastController
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The phone's answers to [PlayerHost]: AppSettings, the Media3 Canvas decoder,
 * AudioRouting and the music stream's volume, the party, and the lyric
 * translation endpoint — each exactly what the player read directly before it
 * moved into the shared module.
 */
class AndroidPlayerHost(context: Context) : PlayerHost {
    companion object {
        var activeController: MediaController? = null
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override val settings: PlayerSettingsSource = object : PlayerSettingsSource {
        override val animatedCanvas get() = AppSettings.animatedCanvas
        override val audioQualityCellular get() = AppSettings.audioQualityCellular
        override val audioQualityWifi get() = AppSettings.audioQualityWifi
        override val canvasOverCellular get() = AppSettings.canvasOverCellular
        override val dynamicThemeVariant get() = AppSettings.dynamicThemeVariant
        override val fullBleedArtwork get() = AppSettings.fullBleedArtwork
        override val hideSongStatus get() = AppSettings.hideSongStatus
        override val showQueueHistory get() = AppSettings.showQueueHistory
        override val hideVolumeBar get() = AppSettings.hideVolumeBar
        override val showPlayerBottomToolbar get() = AppSettings.showPlayerBottomToolbar
        override val showQueueButton get() = AppSettings.showQueueButton
        override val showSleepTimerButton get() = AppSettings.showSleepTimerButton
        override val showShuffleButton get() = AppSettings.showShuffleButton
        override val showLyricsButton get() = AppSettings.showLyricsButton
        override val showRepeatButton get() = AppSettings.showRepeatButton
        override val showMoreButton get() = AppSettings.showMoreButton
        override val hideStatusBar get() = AppSettings.hideStatusBarInPlayer
        override val lastPlayerScreen get() = AppSettings.lastPlayerScreen
        override val legacyMeshGradient get() = AppSettings.legacyMeshGradient
        override val lyricsBlur get() = AppSettings.lyricsBlur
        override val lyricsOffsetMs get() = AppSettings.lyricsOffsetMs
        override val lyricsSourceOrder get() = AppSettings.lyricsSourceOrder
        override val meteredConnection get() = AppSettings.meteredConnection
        override val preferUsbDac get() = AppSettings.preferUsbDac
        override val prioritizeSpotifyCanvas get() = AppSettings.prioritizeSpotifyCanvas
        override val reduceAnimation get() = AppSettings.reduceAnimation
        override val reduceDynamicBlur get() = AppSettings.reduceDynamicBlur
        override val showNerdStats get() = AppSettings.showNerdStats
        override val smartAnalysis get() = AppSettings.smartAnalysis
        override val smartFadeEnabled get() = AppSettings.smartFadeEnabled
        override val smartMixInProgress get() = AppSettings.smartMixInProgress
        override val smartMixBlend get() = AppSettings.smartMixBlend
        override val smartTransitionWindow get() = AppSettings.smartTransitionWindow
        override val spotifyCanvasAutoHide get() = AppSettings.spotifyCanvasAutoHide
        override val syncedLyrics get() = AppSettings.syncedLyrics
        override val translationLanguage get() = AppSettings.translationLanguage
        override val versionAlignmentInProgress get() = AppSettings.versionAlignmentInProgress

        override fun setLastPlayerScreen(value: LastPlayerScreen) = AppSettings.setLastPlayerScreen(value)
        override fun setLyricsOffsetMs(value: Int) = AppSettings.setLyricsOffsetMs(value)
    }

    override fun cachedCanvas(song: Song): CanvasArtwork? = CanvasRepository.cached(song)

    override suspend fun canvasFor(song: Song): CanvasArtwork? = CanvasRepository.canvasFor(song)

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = AndroidCanvasVideo(spec, modifier)

    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? =
        com.music.bitchord.ui.components.rememberRemoteArtworkUrl(song)

    @Composable
    override fun rememberOriginalArtworkUrl(song: Song?): String? =
        com.music.bitchord.ui.components.rememberRemoteArtworkUrl(song, ignoreCustom = true)

    override val songEdits: StateFlow<Map<String, SongEdit>> get() = SongEditStore.edits

    override fun setSongEdit(songId: String, edit: SongEdit) = SongEditStore.set(songId, edit)

    override fun clearSongEdit(songId: String) = SongEditStore.clear(songId)

    override suspend fun saveSongCover(songId: String, coverUri: String): String? =
        SongEditStore.saveCover(app, songId, coverUri)

    override fun deleteSongCover(path: String?) = SongEditStore.deleteCover(path)

    @Composable
    override fun rememberCoverPicker(onPicked: (String) -> Unit): () -> Unit {
        // PickVisualMedia, not GetContent: it is the system photo picker on
        // current Android and falls back to the document picker below it, and
        // it grants access to just the one image the listener chose.
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri -> uri?.let { onPicked(it.toString()) } }
        return remember(launcher) {
            {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }
    }

    // Lazy, all three: the player is not the first thing the process does, and
    // none of these objects should be woken for a launch that never opens it.
    override val volume: SystemVolume by lazy { CastAwareVolume(MusicStreamVolume(app), scope) }

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> {
        val outputs = rememberAndroidAudioOutputs()
        val cast by CastController.state.collectAsStateWithLifecycle()
        val watch by WearBridge.watchOutput.collectAsStateWithLifecycle()
        // While a receiver is the speaker no output of this phone is the one
        // playing, so none of them is lit; the Cast or watch row is.
        return if (cast.casting || watch.active) outputs.map { it.copy(isActive = false) } else outputs
    }

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = rememberAndroidOutputPicker(onOpen)

    override fun selectAudioOutput(id: Int?) {
        // Picking an output of this phone is picking this phone: the receiver
        // is let go of, and the music carries on here if it was playing.
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = true)
        AudioRouting.select(id)
        WearBridge.leaveWatchOutput()
    }

    override val watchOutput: StateFlow<WatchOutputUi> by lazy {
        WearBridge.watchOutput
            .map { WatchOutputUi(available = it.available, name = it.name, active = it.active) }
            .stateIn(scope, SharingStarted.Eagerly, WatchOutputUi())
    }

    override fun refreshWatchOutput() = WearBridge.refreshWatchOutput(app)

    override fun selectWatchOutput() {
        // One speaker at a time: a receiver lets go first, quietly, and the
        // watch picks the queue up from where the phone's player is.
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = false)
        WearBridge.selectWatchOutput()
    }

    override val outputFormat: StateFlow<OutputFormatUi> by lazy {
        AudioOutputStatus.current
            .map { it.toOutputFormat() }
            .stateIn(scope, SharingStarted.Eagerly, AudioOutputStatus.current.value.toOutputFormat())
    }

    override val party: StateFlow<PartyUi> by lazy {
        ListenTogether.state
            .map { it.toPartyUi() }
            .stateIn(scope, SharingStarted.Eagerly, ListenTogether.state.value.toPartyUi())
    }

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    override fun showMessage(message: String) {
        main.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
    }

    override val castState: StateFlow<CastUi> by lazy {
        CastController.ensureStarted(app)
        CastController.state
            .map { CastUi(supported = it.supported, connectedName = it.connectedName, connecting = it.connecting) }
            .stateIn(scope, SharingStarted.Eagerly, CastUi())
    }

    @Composable
    override fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) =
        com.music.bitchord.ui.components.CastDialog(hazeState = hazeState, onDismiss = onDismiss)

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
        com.music.bitchord.ui.components.AudioPipelineDialog(
            hazeState = hazeState,
            onDismiss = onDismiss,
            isPlaying = isPlaying,
        )

    // The phone is the platform that can turn picked lyrics into a picture: a
    // bitmap, a canvas, the gallery and the chooser are all Android's here. The
    // desktop has no answer to it, so it never sees the pick mode at all.
    override val lyricsShareAvailable: Boolean get() = true

    override val isVideoFrameRendered: StateFlow<Boolean>
        get() = PlaybackService.isVideoFrameRendered

    @Composable
    override fun VideoPlayer(modifier: Modifier) {
        val activeTrackId by PlaybackService.activeVideoTrackId.collectAsStateWithLifecycle()
        val player = PlaybackService.activeExoPlayer ?: activeController
        val videoId = activeTrackId
            ?: player?.currentMediaItem?.mediaId
            ?: return

        var webViewRef by remember { mutableStateOf<WebView?>(null) }

        // Seamless sync: bridge play/pause and seeking between main audio player and YouTube WebView
        DisposableEffect(player) {
            if (player == null) return@DisposableEffect onDispose {}

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val cmd = if (isPlaying) {
                        """(function() {
                            const v = document.querySelector('video');
                            if (v && v.paused) v.play();
                            window.postMessage('{"event":"command","func":"playVideo","args":""}', '*');
                        })();"""
                    } else {
                        """(function() {
                            const v = document.querySelector('video');
                            if (v && !v.paused) v.pause();
                            window.postMessage('{"event":"command","func":"pauseVideo","args":""}', '*');
                        })();"""
                    }
                    webViewRef?.evaluateJavascript(cmd, null)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        val targetSec = (newPosition.positionMs / 1000f).coerceAtLeast(0f)
                        val cmd = """(function() {
                            const v = document.querySelector('video');
                            if (v) v.currentTime = $targetSec;
                            window.postMessage('{"event":"command","func":"seekTo","args":[$targetSec, true]}', '*');
                        })();"""
                        webViewRef?.evaluateJavascript(cmd, null)
                    }
                }
            }

            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
            }
        }

        val startSec = ((player?.currentPosition ?: 0L) / 1000L).coerceAtLeast(0L)
        val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId" +
            "?autoplay=1&mute=0&controls=0&modestbranding=1&rel=0&showinfo=0&iv_load_policy=3&playsinline=1&enablejsapi=1&start=$startSec"

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(false)
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    val adBlockAndCleanJs = """
                        (function() {
                            function injectStyle() {
                                if (document.getElementById('umtify-video-clean-style')) return;
                                const style = document.createElement('style');
                                style.id = 'umtify-video-clean-style';
                                style.textContent = `
                                    .ytp-chrome-top, .ytp-chrome-bottom, .ytp-watermark,
                                    .ytp-youtube-button, .ytp-pause-overlay, .ytp-ce-element,
                                    .ytp-ce-covering-overlay, .ytp-ce-element-shadow,
                                    .ytp-gradient-top, .ytp-gradient-bottom, .ytp-show-cards-title,
                                    .ytp-contextmenu, .ytp-title-channel, .ytp-title,
                                    .ytp-share-button, .ytp-watch-later-button, .branding-img,
                                    .ytp-paid-content-overlay, .ytp-ad-overlay-container,
                                    .video-ads, .ytp-ad-module, .ytp-ad-player-overlay,
                                    .ytp-ad-image-overlay {
                                        display: none !important;
                                        opacity: 0 !important;
                                        pointer-events: none !important;
                                        visibility: hidden !important;
                                    }
                                    html, body {
                                        margin: 0 !important;
                                        padding: 0 !important;
                                        overflow: hidden !important;
                                        background-color: #000000 !important;
                                        width: 100% !important;
                                        height: 100% !important;
                                    }
                                    .html5-video-player {
                                        background-color: #000000 !important;
                                    }
                                    video {
                                        width: 100% !important;
                                        height: 100% !important;
                                        object-fit: cover !important;
                                    }
                                `;
                                (document.head || document.documentElement).appendChild(style);
                            }
                            function cleanAndSkip() {
                                injectStyle();
                                const v = document.querySelector('video');
                                const ad = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay');
                                if (ad && v && isFinite(v.duration)) {
                                    v.currentTime = v.duration;
                                }
                                const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                                if (skipBtn) skipBtn.click();
                            }
                            injectStyle();
                            cleanAndSkip();
                            setInterval(cleanAndSkip, 300);
                        })();
                    """.trimIndent()

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (newProgress > 25) {
                                PlaybackService.isVideoFrameRendered.value = true
                                view?.evaluateJavascript(adBlockAndCleanJs, null)
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            PlaybackService.isVideoFrameRendered.value = true
                            view?.evaluateJavascript(adBlockAndCleanJs, null)
                        }
                    }

                    loadUrl(embedUrl)
                    webViewRef = this
                }
            },
            update = { view ->
                webViewRef = view
                if (view.url?.contains(videoId) != true) {
                    view.loadUrl(embedUrl)
                }
            },
            onRelease = { view ->
                if (webViewRef == view) webViewRef = null
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            },
            modifier = modifier,
        )
    }

    @Composable
    override fun LyricsShareSheet(
        hazeState: HazeState,
        request: LyricsShareRequest,
        onDismiss: () -> Unit,
    ) = AndroidLyricsShareSheet(hazeState = hazeState, request = request, onDismiss = onDismiss)
}

private fun AudioOutputStatus.Snapshot.toOutputFormat(): OutputFormatUi {
    val encoding = AudioOutputStatus.encodingLabel(this)
    val rate = actualSampleRateHz?.takeIf { it > 0 }
    val summary = if (rate == null) {
        encoding
    } else {
        "$encoding · ${"%.1f".format(Locale.ROOT, rate / 1000f).removeSuffix(".0")} kHz"
    }
    // What the route is *capable* of, read off the negotiated AudioTrack.
    val carriesHiRes = when (actualEncoding) {
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        -> true
        else -> (actualSampleRateHz ?: 0) > 48_000
    }
    return OutputFormatUi(summary = summary, carriesHiRes = carriesHiRes)
}

private fun ListenTogether.State.toPartyUi() = PartyUi(
    inParty = inParty,
    controlsLocked = controlsLocked,
    members = members,
    you = you,
    code = code,
)

/**
 * The volume bar's level: the receiver's while the music is on one, the music
 * stream's otherwise. The two are different numbers on different devices, and
 * the bar must never show one while moving the other.
 */
private class CastAwareVolume(
    private val system: SystemVolume,
    scope: CoroutineScope,
) : SystemVolume {
    override val level: StateFlow<Float> = combine(
        system.level,
        CastController.volume,
        CastController.state.map { it.casting },
        WearBridge.watchOutput,
    ) { phone, receiver, casting, watch ->
        when {
            casting -> receiver
            watch.active -> watch.volume
            else -> phone
        }
    }
        .stateIn(scope, SharingStarted.Eagerly, system.level.value)

    override fun set(level: Float) {
        when {
            CastController.state.value.casting -> CastController.setVolume(level)
            WearBridge.watchOutput.value.active -> WearBridge.setWatchVolume(level)
            else -> system.set(level)
        }
    }

    override fun refresh() = system.refresh()
}

/**
 * The music stream's volume. Hardware keys and the system panel change it
 * behind the app's back, so Settings is watched and the level follows.
 */
private class MusicStreamVolume(private val context: Context) : SystemVolume {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var lastIntVol: Int = manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    private val _level = MutableStateFlow(read())
    override val level: StateFlow<Float> = _level.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refresh()
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            } catch (_: Exception) {}
        }
    }

    private fun max(): Int = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private fun read(): Float =
        (manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max()

    override fun set(level: Float) {
        val target = (level * max()).roundToInt()
        if (target != lastIntVol) {
            lastIntVol = target
            manager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    override fun refresh() {
        val current = manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
        if (current != lastIntVol) {
            lastIntVol = current
            _level.value = current.toFloat() / max()
        }
    }
}
