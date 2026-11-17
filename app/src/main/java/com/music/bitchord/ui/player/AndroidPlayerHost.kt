package com.music.bitchord.ui.player

import com.music.bitchord.wear.WearBridge
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.VideoQualityOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.data.SongEditStore
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongEdit
import com.music.bitchord.data.model.durationMillis
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

    override val musicVideoSupported: Boolean get() = true

    override val isVideoFrameRendered: StateFlow<Boolean>
        get() = PlaybackService.isVideoFrameRendered

    override val videoStatusText: StateFlow<String>
        get() = PlaybackService.videoStatusText

    override val isVideoPlaying: StateFlow<Boolean>
        get() = PlaybackService.isVideoPlaying

    override val videoPositionMs: StateFlow<Long>
        get() = PlaybackService.videoPositionMs

    override val videoDurationMs: StateFlow<Long>
        get() = PlaybackService.videoDurationMs

    override fun toggleVideoPlayPause() {
        val vp = PlaybackService.activeVideoExoPlayer ?: return
        if (vp.isPlaying) {
            vp.pause()
        } else {
            vp.play()
        }
    }

    override fun seekVideo(positionMs: Long) {
        val vp = PlaybackService.activeVideoExoPlayer ?: return
        vp.seekTo(positionMs)
    }

    private fun Context.findActivity(): Activity? {
        var cur: Context? = this
        while (cur is android.content.ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return cur as? Activity
    }

    data class VideoAudioTrack(
        val groupIndex: Int,
        val trackIndex: Int,
        val name: String,
        val isSelected: Boolean,
    )

    @Composable
    override fun VideoPlayer(modifier: Modifier) {
        val activeTrackId by PlaybackService.activeVideoTrackId.collectAsStateWithLifecycle()
        val activeSong by PlaybackService.activeVideoSong.collectAsStateWithLifecycle()
        val isVideoMode by PlaybackService.isVideoMode.collectAsStateWithLifecycle()
        val mainPlayer = PlaybackService.activeExoPlayer ?: activeController

        val currentMediaItem = mainPlayer?.currentMediaItem
        val mediaId = activeTrackId
            ?: activeSong?.videoId
            ?: currentMediaItem?.mediaId
            ?: return

        val title = activeSong?.title
            ?: currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        val artist = activeSong?.artist
            ?: currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
        val durationSec = (
            (activeSong?.durationMillis()?.takeIf { it > 0L }
                ?: mainPlayer?.duration?.takeIf { it > 0L }
                ?: 0L) / 1000L
        ).toInt()

        val context = LocalContext.current

        var isFullscreen by remember { mutableStateOf(false) }
        var showControls by remember { mutableStateOf(true) }
        var qualityMenuOpen by remember { mutableStateOf(false) }
        var audioMenuOpen by remember { mutableStateOf(false) }

        var availableQualities by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
        var selectedQualityLabel by remember { mutableStateOf("Auto") }
        var currentAudioUrl by remember { mutableStateOf<String?>(null) }
        var currentHasEmbeddedAudio by remember { mutableStateOf(false) }
        var currentHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var currentUa by remember { mutableStateOf("") }
        var audioTracks by remember { mutableStateOf<List<VideoAudioTrack>>(emptyList()) }

        var seekNoticeLeft by remember { mutableStateOf(false) }
        var seekNoticeRight by remember { mutableStateOf(false) }

        val inlineTextureRef = remember { mutableStateOf<TextureView?>(null) }
        val fullscreenTextureRef = remember { mutableStateOf<TextureView?>(null) }

        // Adaptive Bitrate DefaultTrackSelector with Mixed MIME and non-seamless adaptiveness
        val trackSelector = remember(context) {
            DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                        .setAllowVideoNonSeamlessAdaptiveness(true)
                )
            }
        }

        // Dedicated ExoPlayer for video+audio rendering (plays the YT video's own audio+video)
        val videoExoPlayer = remember(context, trackSelector) {
            ExoPlayer.Builder(
                context,
                DefaultRenderersFactory(context).setEnableDecoderFallback(true),
            )
                .setTrackSelector(trackSelector)
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
                .setHandleAudioBecomingNoisy(true)
                .build().apply {
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    volume = 1f
                    repeatMode = Player.REPEAT_MODE_ONE
                }
        }

        // Register active video player and clean up on dispose
        DisposableEffect(videoExoPlayer) {
            PlaybackService.activeVideoExoPlayer = videoExoPlayer
            PlaybackService.activeVideoExoPlayerFlow.value = videoExoPlayer
            onDispose {
                PlaybackService.isVideoFrameRendered.value = false
                PlaybackService.videoStatusText.value = ""
                PlaybackService.activeVideoExoPlayer = null
                PlaybackService.activeVideoExoPlayerFlow.value = null
                val exitPosition = if (currentHasEmbeddedAudio) {
                    videoExoPlayer.currentPosition
                } else {
                    mainPlayer?.currentPosition ?: videoExoPlayer.currentPosition
                }.coerceAtLeast(0L)
                inlineTextureRef.value?.let { videoExoPlayer.clearVideoTextureView(it) }
                fullscreenTextureRef.value?.let { videoExoPlayer.clearVideoTextureView(it) }
                inlineTextureRef.value = null
                fullscreenTextureRef.value = null
                videoExoPlayer.stop()
                videoExoPlayer.clearMediaItems()
                videoExoPlayer.release()
                // Resume mainPlayer at the video's exit position
                mainPlayer?.let { mp ->
                    if (exitPosition > 0L) {
                        mp.seekTo(exitPosition)
                    }
                    mp.play()
                }
            }
        }

        // Auto-dismiss seek visual feedback notices after 650ms
        LaunchedEffect(seekNoticeLeft) {
            if (seekNoticeLeft) {
                delay(650)
                seekNoticeLeft = false
            }
        }
        LaunchedEffect(seekNoticeRight) {
            if (seekNoticeRight) {
                delay(650)
                seekNoticeRight = false
            }
        }

        // Auto-hide controls after 3.5s of playback
        LaunchedEffect(showControls, videoExoPlayer.isPlaying) {
            if (showControls && videoExoPlayer.isPlaying) {
                delay(3500)
                showControls = false
            }
        }

        fun updateAudioTracksList(tracks: Tracks) {
            val list = mutableListOf<VideoAudioTrack>()
            for ((gIdx, group) in tracks.groups.withIndex()) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (tIdx in 0 until group.length) {
                        val format = group.getTrackFormat(tIdx)
                        val lang = format.language?.uppercase()?.takeIf { it.isNotBlank() && it != "UND" }
                        val label = format.label?.takeIf { it.isNotBlank() }
                        val bitrate = if (format.bitrate > 0) "${format.bitrate / 1000} kbps" else null
                        val name = listOfNotNull(label, lang, bitrate).joinToString(" · ").ifBlank { "Track ${list.size + 1}" }
                        list.add(
                            VideoAudioTrack(
                                groupIndex = gIdx,
                                trackIndex = tIdx,
                                name = name,
                                isSelected = group.isTrackSelected(tIdx),
                            )
                        )
                    }
                }
            }
            audioTracks = list
        }

        // Periodically sync video position, duration, and playing state to PlaybackService
        LaunchedEffect(videoExoPlayer, currentHasEmbeddedAudio) {
            while (true) {
                PlaybackService.isVideoPlaying.value = videoExoPlayer.isPlaying
                PlaybackService.videoPositionMs.value = videoExoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = videoExoPlayer.duration
                if (dur > 0L) {
                    PlaybackService.videoDurationMs.value = dur
                }
                if (!currentHasEmbeddedAudio && videoExoPlayer.isPlaying && mainPlayer != null && mainPlayer.isPlaying) {
                    val vPos = videoExoPlayer.currentPosition
                    val aPos = mainPlayer.currentPosition
                    if (kotlin.math.abs(vPos - aPos) > 400L) {
                        videoExoPlayer.seekTo(aPos)
                    }
                }
                delay(200)
            }
        }

        // Listen for rendered first frame, STATE_READY, track changes, and onPlayerError
        DisposableEffect(videoExoPlayer, currentHasEmbeddedAudio) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    PlaybackService.isVideoFrameRendered.value = true
                    PlaybackService.videoStatusText.value = ""
                    videoExoPlayer.play()
                    if (currentHasEmbeddedAudio) {
                        mainPlayer?.pause()
                    } else {
                        mainPlayer?.let { mp ->
                            if (!mp.isPlaying) mp.play()
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        PlaybackService.isVideoFrameRendered.value = true
                        PlaybackService.videoStatusText.value = ""
                        videoExoPlayer.play()
                        if (currentHasEmbeddedAudio) {
                            mainPlayer?.pause()
                        } else {
                            mainPlayer?.let { mp ->
                                if (!mp.isPlaying) mp.play()
                            }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    PlaybackService.isVideoPlaying.value = isPlaying
                    if (!currentHasEmbeddedAudio) {
                        if (isPlaying && mainPlayer?.isPlaying == false) {
                            mainPlayer.play()
                        } else if (!isPlaying && mainPlayer?.isPlaying == true) {
                            mainPlayer.pause()
                        }
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (!currentHasEmbeddedAudio && reason == Player.DISCONTINUITY_REASON_SEEK) {
                        mainPlayer?.seekTo(newPosition.positionMs)
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    updateAudioTracksList(tracks)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    TrackLog.w("VideoPlayer", "videoExoPlayer error: ${error.message}, errorCode: ${error.errorCodeName}")
                    val msg = "Video playback error: ${error.message ?: error.errorCodeName}"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    PlaybackService.dismissVideoLoading()
                }
            }
            videoExoPlayer.addListener(listener)
            onDispose {
                videoExoPlayer.removeListener(listener)
            }
        }

        // Helper to load or switch video stream quality
        fun playStream(
            videoUrl: String,
            hasEmbeddedAudio: Boolean,
            isHls: Boolean,
            headers: Map<String, String>,
            ua: String,
            syncTime: Long = 0L,
        ) {
            val reqHeaders = HashMap<String, String>().apply {
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
                headers.forEach { (k, v) -> put(k, v) }
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(ua.ifBlank { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36" })
                .setDefaultRequestProperties(reqHeaders)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)

            val videoSource = if (isHls) {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUrl))
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(videoUrl))
            }

            val targetPos = if (syncTime > 0L) {
                syncTime
            } else if (videoExoPlayer.currentPosition > 0L) {
                videoExoPlayer.currentPosition
            } else {
                mainPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
            }

            if (hasEmbeddedAudio) {
                videoExoPlayer.volume = 1f
                mainPlayer?.pause()
            } else {
                videoExoPlayer.volume = 0f
                mainPlayer?.let { mp ->
                    if (targetPos > 0L) mp.seekTo(targetPos)
                    mp.play()
                }
            }

            videoExoPlayer.setMediaSource(videoSource)
            if (targetPos > 0L) {
                videoExoPlayer.seekTo(targetPos)
            }
            videoExoPlayer.playWhenReady = true
            videoExoPlayer.prepare()
        }

        // Helper to select audio track
        fun selectAudioTrack(track: VideoAudioTrack) {
            val group = videoExoPlayer.currentTracks.groups.getOrNull(track.groupIndex) ?: return
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setOverrideForType(
                    TrackSelectionOverride(
                        group.mediaTrackGroup,
                        listOf(track.trackIndex),
                    )
                )
                .build()
        }

        // Initial resolution on mediaId change
        LaunchedEffect(mediaId) {
            PlaybackService.isVideoFrameRendered.value = false
            PlaybackService.videoStatusText.value = "Finding video..."
            val syncTime = mainPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            mainPlayer?.pause()

            videoExoPlayer.stop()
            videoExoPlayer.clearMediaItems()
            availableQualities = emptyList()
            selectedQualityLabel = "Auto"

            try {
                val stream = withContext(Dispatchers.IO) {
                    StreamResolver.resolveVideoStreamForTrack(
                        videoId = mediaId,
                        title = title,
                        artist = artist,
                        durationSec = durationSec,
                        candidateOffset = 0,
                        onStatusUpdate = { status ->
                            PlaybackService.videoStatusText.value = status
                        },
                    )
                }

                if (stream != null) {
                    PlaybackService.videoStatusText.value = "Buffering video..."
                    val headers = if (stream.headers.isNotEmpty()) stream.headers else StreamResolver.mediaHeadersFor(stream.url)
                    val ua = stream.userAgent.ifBlank {
                        headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
                    }
                    currentHeaders = headers
                    currentUa = ua
                    currentAudioUrl = stream.audioStreamUrl
                    currentHasEmbeddedAudio = stream.hasEmbeddedAudio
                    availableQualities = stream.availableQualities

                    // ABR Auto: default to highest available quality or HLS
                    val highestStream = stream.availableQualities.firstOrNull { it.isHls }
                        ?: stream.availableQualities.firstOrNull { it.height >= 720 }
                        ?: stream.availableQualities.maxByOrNull { it.height }
                        ?: stream.availableQualities.firstOrNull()

                    val targetUrl = highestStream?.url ?: stream.url
                    val targetIsHls = highestStream?.isHls ?: stream.isHls

                    playStream(
                        videoUrl = targetUrl,
                        hasEmbeddedAudio = stream.hasEmbeddedAudio,
                        isHls = targetIsHls,
                        headers = headers,
                        ua = ua,
                        syncTime = syncTime,
                    )
                } else {
                    TrackLog.w("VideoPlayer", "Failed to resolve video stream for $mediaId ($title - $artist)")
                    Toast.makeText(context, "Video unavailable", Toast.LENGTH_SHORT).show()
                    mainPlayer?.let { mp ->
                        if (syncTime > 0L) mp.seekTo(syncTime)
                        mp.play()
                    }
                    PlaybackService.dismissVideoLoading()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                TrackLog.w("VideoPlayer", "Exception resolving video for $mediaId: ${e.message}")
                Toast.makeText(context, "Error: ${e.message ?: "Video unavailable"}", Toast.LENGTH_SHORT).show()
                mainPlayer?.let { mp ->
                    if (syncTime > 0L) mp.seekTo(syncTime)
                    mp.play()
                }
                PlaybackService.dismissVideoLoading()
            }
        }

        val qualityOptions = remember(availableQualities) {
            val list = mutableListOf<VideoQualityOption>()
            list.add(VideoQualityOption(label = "Auto", height = 0, url = ""))
            list.addAll(availableQualities.distinctBy { it.height }.sortedByDescending { it.height })
            list
        }

        fun onSelectQuality(option: VideoQualityOption) {
            selectedQualityLabel = option.label
            val currentPos = videoExoPlayer.currentPosition
            if (option.label == "Auto") {
                trackSelector.parameters = trackSelector.buildUponParameters()
                    .clearVideoSizeConstraints()
                    .build()
                val autoStream = availableQualities.firstOrNull { it.isHls }
                    ?: availableQualities.firstOrNull { it.height >= 720 }
                    ?: availableQualities.firstOrNull()
                if (autoStream != null) {
                    playStream(
                        videoUrl = autoStream.url,
                        hasEmbeddedAudio = currentHasEmbeddedAudio,
                        isHls = autoStream.isHls,
                        headers = currentHeaders,
                        ua = currentUa,
                        syncTime = currentPos,
                    )
                }
            } else {
                trackSelector.parameters = trackSelector.buildUponParameters()
                    .setMaxVideoSize(1920, option.height)
                    .build()
                if (option.url.isNotBlank()) {
                    playStream(
                        videoUrl = option.url,
                        hasEmbeddedAudio = currentHasEmbeddedAudio,
                        isHls = option.isHls,
                        headers = currentHeaders,
                        ua = currentUa,
                        syncTime = currentPos,
                    )
                }
            }
        }

        var currentPositionMs by remember { mutableStateOf(videoExoPlayer.currentPosition) }
        val durationMs = videoExoPlayer.duration.takeIf { it > 0 } ?: (mainPlayer?.duration?.takeIf { it > 0 } ?: 1L)
        val isVideoPlaying = videoExoPlayer.isPlaying
        val isBuffering = !PlaybackService.isVideoFrameRendered.value || videoExoPlayer.playbackState == Player.STATE_BUFFERING

        LaunchedEffect(Unit) {
            while (isActive) {
                currentPositionMs = videoExoPlayer.currentPosition
                delay(200)
            }
        }

        // Inline Video Container (16:9 Clean Frame)
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(videoExoPlayer) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = { offset ->
                            val isRight = offset.x > size.width / 2
                            val dur = videoExoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            if (isRight) {
                                val target = (videoExoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                                videoExoPlayer.seekTo(target)
                                seekNoticeRight = true
                            } else {
                                val target = (videoExoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                videoExoPlayer.seekTo(target)
                                seekNoticeLeft = true
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // TextureView
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        keepScreenOn = true
                        videoExoPlayer.setVideoTextureView(this)
                        inlineTextureRef.value = this
                    }
                },
                update = { view ->
                    if (!isFullscreen) {
                        videoExoPlayer.setVideoTextureView(view)
                        inlineTextureRef.value = view
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Buffering Indicator
            if (isBuffering) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = Color.White.copy(alpha = 0.85f),
                            strokeWidth = 3.dp,
                        )
                        val status = PlaybackService.videoStatusText.value
                        if (status.isNotBlank()) {
                            Text(
                                text = status,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            // Double Tap Visual Notice (Left: -10s)
            if (seekNoticeLeft) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.45f)
                        .align(Alignment.CenterStart)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "-10s",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Double Tap Visual Notice (Right: +10s)
            if (seekNoticeRight) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.45f)
                        .align(Alignment.CenterEnd)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.FastForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "+10s",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Inline Controls Overlay
            if (showControls) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    // Top Bar (Switch to audio + Audio tracks + Quality)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Switch to Audio pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable {
                                    val syncPos = if (currentHasEmbeddedAudio) {
                                        videoExoPlayer.currentPosition
                                    } else {
                                        mainPlayer?.currentPosition ?: videoExoPlayer.currentPosition
                                    }.coerceAtLeast(0L)
                                    videoExoPlayer.pause()
                                    mainPlayer?.let { mp ->
                                        if (syncPos > 0L) {
                                            mp.seekTo(syncPos)
                                        }
                                        mp.play()
                                    }
                                    PlaybackService.onRequestSwitchToAudio?.invoke() ?: PlaybackService.dismissVideoLoading()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "Audio",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Audio Track Selector
                            if (audioTracks.isNotEmpty()) {
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .clickable { audioMenuOpen = true }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.GraphicEq,
                                            contentDescription = "Audio Track",
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = audioMenuOpen,
                                        onDismissRequest = { audioMenuOpen = false },
                                    ) {
                                        audioTracks.forEach { track ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = track.name,
                                                        fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    )
                                                },
                                                onClick = {
                                                    audioMenuOpen = false
                                                    selectAudioTrack(track)
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            // Quality Selector
                            if (qualityOptions.isNotEmpty()) {
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .clickable { qualityMenuOpen = true }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Settings,
                                                contentDescription = "Quality",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                text = selectedQualityLabel,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = qualityMenuOpen,
                                        onDismissRequest = { qualityMenuOpen = false },
                                    ) {
                                        qualityOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = option.label,
                                                        fontWeight = if (selectedQualityLabel == option.label) FontWeight.Bold else FontWeight.Normal,
                                                    )
                                                },
                                                onClick = {
                                                    qualityMenuOpen = false
                                                    onSelectQuality(option)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Center Transport Controls (Rewind 10s, Play/Pause, Forward 10s)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                val target = (videoExoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                videoExoPlayer.seekTo(target)
                                seekNoticeLeft = true
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FastRewind,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isVideoPlaying) {
                                    videoExoPlayer.pause()
                                } else {
                                    videoExoPlayer.play()
                                }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(26.dp)),
                        ) {
                            Icon(
                                imageVector = if (isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isVideoPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }

                        IconButton(
                            onClick = {
                                val dur = videoExoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                val target = (videoExoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                                videoExoPlayer.seekTo(target)
                                seekNoticeRight = true
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FastForward,
                                contentDescription = "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Bottom Bar (Timestamp + Fullscreen)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val curMin = (currentPositionMs / 1000) / 60
                        val curSec = (currentPositionMs / 1000) % 60
                        val durMin = (durationMs / 1000) / 60
                        val durSec = (durationMs / 1000) % 60
                        Text(
                            text = "%d:%02d / %d:%02d".format(curMin, curSec, durMin, durSec),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )

                        IconButton(
                            onClick = { isFullscreen = true },
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen Landscape Video Dialog
        if (isFullscreen) {
            Dialog(
                onDismissRequest = { isFullscreen = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                BackHandler { isFullscreen = false }

                val dialogView = LocalView.current
                DisposableEffect(Unit) {
                    val activity = context.findActivity() ?: dialogView.context.findActivity()
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    activity?.let { a ->
                        WindowCompat.setDecorFitsSystemWindows(a.window, false)
                        WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            hide(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                    val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
                    dialogWindow?.let { dw ->
                        dw.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        WindowCompat.setDecorFitsSystemWindows(dw, false)
                        WindowCompat.getInsetsController(dw, dw.decorView).apply {
                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            hide(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                    onDispose {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        activity?.let { a ->
                            WindowCompat.setDecorFitsSystemWindows(a.window, true)
                            WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
                                show(WindowInsetsCompat.Type.systemBars())
                            }
                        }
                        fullscreenTextureRef.value?.let { videoExoPlayer.clearVideoTextureView(it) }
                        fullscreenTextureRef.value = null
                        inlineTextureRef.value?.let { videoExoPlayer.setVideoTextureView(it) }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(videoExoPlayer) {
                            detectTapGestures(
                                onTap = {
                                    showControls = !showControls
                                },
                                onDoubleTap = { offset ->
                                    val isRight = offset.x > size.width / 2
                                    val dur = videoExoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                    if (isRight) {
                                        val target = (videoExoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                                        videoExoPlayer.seekTo(target)
                                        seekNoticeRight = true
                                    } else {
                                        val target = (videoExoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                        videoExoPlayer.seekTo(target)
                                        seekNoticeLeft = true
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Fullscreen TextureView - 16:9 centered in landscape, zero stretching
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                keepScreenOn = true
                                videoExoPlayer.setVideoTextureView(this)
                                fullscreenTextureRef.value = this
                            }
                        },
                        update = { view ->
                            if (isFullscreen) {
                                videoExoPlayer.setVideoTextureView(view)
                                fullscreenTextureRef.value = view
                            }
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                            .align(Alignment.Center),
                    )

                    // Double Tap Visual Notice (Left: -10s)
                    if (seekNoticeLeft) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.35f)
                                .align(Alignment.CenterStart)
                                .background(
                                    Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.FastRewind,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(42.dp),
                                )
                                Text(
                                    text = "-10 seconds",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    // Double Tap Visual Notice (Right: +10s)
                    if (seekNoticeRight) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.35f)
                                .align(Alignment.CenterEnd)
                                .background(
                                    Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.FastForward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(42.dp),
                                )
                                Text(
                                    text = "+10 seconds",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    // Fullscreen Controls Overlay
                    if (showControls) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                        ) {
                            // Top Bar: title, artist, audio tracks, quality, exit fullscreen
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                    if (artist.isNotBlank()) {
                                        Text(
                                            text = artist,
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    // Audio Track Selector
                                    if (audioTracks.isNotEmpty()) {
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color.White.copy(alpha = 0.2f))
                                                    .clickable { audioMenuOpen = true }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.GraphicEq,
                                                        contentDescription = "Audio Track",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Text(
                                                        text = "Audio",
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = audioMenuOpen,
                                                onDismissRequest = { audioMenuOpen = false },
                                            ) {
                                                audioTracks.forEach { track ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = track.name,
                                                                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                                                            )
                                                        },
                                                        onClick = {
                                                            audioMenuOpen = false
                                                            selectAudioTrack(track)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Quality Selector
                                    if (qualityOptions.isNotEmpty()) {
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color.White.copy(alpha = 0.2f))
                                                    .clickable { qualityMenuOpen = true }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Settings,
                                                        contentDescription = "Quality",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Text(
                                                        text = selectedQualityLabel,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = qualityMenuOpen,
                                                onDismissRequest = { qualityMenuOpen = false },
                                            ) {
                                                qualityOptions.forEach { option ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = option.label,
                                                                fontWeight = if (selectedQualityLabel == option.label) FontWeight.Bold else FontWeight.Normal,
                                                            )
                                                        },
                                                        onClick = {
                                                            qualityMenuOpen = false
                                                            onSelectQuality(option)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Center Play/Pause & 10s Skips
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalArrangement = Arrangement.spacedBy(40.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        val target = (videoExoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                        videoExoPlayer.seekTo(target)
                                        seekNoticeLeft = true
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(26.dp)),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FastRewind,
                                        contentDescription = "Rewind 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (isVideoPlaying) {
                                            videoExoPlayer.pause()
                                        } else {
                                            videoExoPlayer.play()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(68.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(34.dp)),
                                ) {
                                    Icon(
                                        imageVector = if (isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isVideoPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val dur = videoExoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                        val target = (videoExoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                                        videoExoPlayer.seekTo(target)
                                        seekNoticeRight = true
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(26.dp)),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FastForward,
                                        contentDescription = "Forward 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }

                            // Bottom Bar: seekbar, time, exit fullscreen
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                            ) {
                                Slider(
                                    value = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                                    onValueChange = { newPos ->
                                        currentPositionMs = newPos.toLong()
                                        videoExoPlayer.seekTo(newPos.toLong())
                                    },
                                    valueRange = 0f..durationMs.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val curMin = (currentPositionMs / 1000) / 60
                                    val curSec = (currentPositionMs / 1000) % 60
                                    val durMin = (durationMs / 1000) / 60
                                    val durSec = (durationMs / 1000) % 60
                                    Text(
                                        text = "%d:%02d / %d:%02d".format(curMin, curSec, durMin, durSec),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                    )

                                    IconButton(
                                        onClick = { isFullscreen = false },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FullscreenExit,
                                            contentDescription = "Exit Fullscreen",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) =
        VideoPlayer(modifier)

    override fun showsMusicVideoPicture(videoId: String): Boolean = true

    override val musicVideoAspect: StateFlow<Float> get() = MutableStateFlow(16f / 9f)

    override val musicVideoQuality: StateFlow<MusicVideoQualityUi> get() = MutableStateFlow(MusicVideoQualityUi())

    override fun setMusicVideoQuality(height: Int?) = Unit

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
