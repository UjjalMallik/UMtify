package com.music.bitchord.ui.screens

import com.music.bitchord.wear.WatchInstaller
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WebAssetOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.data.lyrics.translationLanguageName
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.theme.DYNAMIC_VARIANT_COVER_ART
import com.music.bitchord.ui.theme.PremiumDynamicThemeVariants
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.AppIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.CustomFont
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.R
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onWearOs: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    onEditSignatureApi: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineModeEnabled.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    // A property of the hardware, so it is read once rather than remembered
    // against a key that can never change — see [DeviceCodecs.playsDolbyAtmos],
    // which caches the codec-list walk for the life of the process.
    val dolbyAtmosSupported = DeviceCodecs.playsDolbyAtmos
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val customFont by AppSettings.customFont.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val signatureApiEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureApiUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val dynamicThemeVariant by AppSettings.dynamicThemeVariant.collectAsStateWithLifecycle()
    val customDynamicColors by AppSettings.customDynamicColors.collectAsStateWithLifecycle()
    var showCustomColorPicker by remember { mutableStateOf(false) }
    val fontScale by AppSettings.fontScale.collectAsStateWithLifecycle()
    val displayScale by AppSettings.displayScale.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
    val playingDolbyAtmos = playingFormat?.isDolbyAtmos == true
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val showPlayerBottomToolbar by AppSettings.showPlayerBottomToolbar.collectAsStateWithLifecycle()
    val showQueueButton by AppSettings.showQueueButton.collectAsStateWithLifecycle()
    val showSleepTimerButton by AppSettings.showSleepTimerButton.collectAsStateWithLifecycle()
    val showShuffleButton by AppSettings.showShuffleButton.collectAsStateWithLifecycle()
    val showLyricsButton by AppSettings.showLyricsButton.collectAsStateWithLifecycle()
    val showRepeatButton by AppSettings.showRepeatButton.collectAsStateWithLifecycle()
    val showMoreButton by AppSettings.showMoreButton.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val showQueueHistory by AppSettings.showQueueHistory.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }

    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    // Scrobbling states
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()

    // Read here so the row can say "In a party · ABC123" rather than making
    // somebody open the screen to find out whether they are still in one.
    val party by ListenTogether.state.collectAsStateWithLifecycle()

    // Filters the rows below — see [SettingsSearch]. Blank shows everything,
    // exactly as if the field weren't there.
    var searchQuery by remember { mutableStateOf("") }

    // Which category cards are open. Default to collapsed (false) so settings
    // are clean and compact on entrance, opening on explicit user selection.
    var audioOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var playbackOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var storageOpen by rememberSaveable { mutableStateOf(false) }
    var dataOpen by rememberSaveable { mutableStateOf(false) }
    var miscOpen by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf<QualityTarget?>(null) }
    var pickingDownloadQuality by remember { mutableStateOf(false) }
    var pickingAutomixPerformance by remember { mutableStateOf(false) }
    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmClearSongCache by remember { mutableStateOf(false) }
    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    val backupScope = rememberCoroutineScope()

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showPerformanceConfirmation = true
    }
    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            importStatus = Backup.importFrom(context, source).fold(
                onSuccess = {
                    context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                },
                onFailure = {
                    context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    /**
     * Fonts are copied into the app's own storage rather than read through the
     * picker's grant, which dies with the process: the face the listener chose
     * has to still be there on the next cold start. The copy is done off the
     * main thread because the file can be a few megabytes and a stalled frame
     * while the sheet is open is exactly what makes the import feel broken.
     */
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            val name = withContext(Dispatchers.IO) { CustomFont.import(context, source) }
            if (name == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.custom_font_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                AppSettings.setCustomFont(name)
            }
        }
    }

    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // Made fresh on every pass rather than remembered: it tallies the rows
    // that answered to the query, and that tally has to start from zero each
    // time the query changes.
    val search = SettingsSearch(searchQuery)

    // What is left after a keystroke is a different list, and the offset the
    // last one was scrolled to means nothing in it.
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var isFirstSearchComposition by remember { mutableStateOf(true) }
    LaunchedEffect(searchQuery) {
        if (isFirstSearchComposition && searchQuery.isEmpty()) {
            // Skip the initial composition during Activity recreation:
            // rememberSaveable already restored the scroll position.
            isFirstSearchComposition = false
        } else {
            isFirstSearchComposition = false
            scrollState.scrollTo(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )
        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSubmit = {},
            placeholder = stringResource(R.string.settings_search_hint),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )

        // The two ways of being connected — an account, and a party — side by
        // side as cards rather than rows, because both are entry points to a
        // screen of their own rather than settings to flip here. Offline is the
        // third way of being connected, which is a switch rather than a place to
        // go, so it gets its own full-width card underneath.
        val accountTitle = stringResource(R.string.account_integrations)
        val listenTogetherTitle = stringResource(R.string.listen_together)
        val offlineModeTitle = stringResource(R.string.offline_mode)
        // Asked in the same breath as the rows they replace, so a search that
        // matches one of these still counts as a hit and still shows it.
        val showAccountCard =
            search.matches(accountTitle, "scrobbling", "listenbrainz", "sign in", "profile", "email")
        val showListenCard =
            search.matches(listenTogetherTitle, "jam", "party", "sync", "friends")
        val showOfflineCard =
            search.matches(offlineModeTitle, "offline", "downloaded", "local", "no internet")
        if (showAccountCard || showListenCard) {
            Spacer(Modifier.height(14.dp))
            Row(
                // IntrinsicSize.Max rather than a guessed height: the two cards
                // are as tall as whichever of them has more to say, so their
                // bottoms line up whatever the account's email or the party's
                // code happens to be.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .padding(horizontal = GROUP_INSET),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showAccountCard) {
                    SettingsFeatureCard(
                        icon = Icons.Rounded.Person,
                        title = accountTitle,
                        subtitle = account?.email?.takeIf { it.isNotBlank() }
                            ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                        onClick = onAccountScrobbling,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (showListenCard) {
                    SettingsFeatureCard(
                        icon = Icons.Rounded.Groups,
                        title = listenTogetherTitle,
                        subtitle = party.code?.let {
                            stringResource(R.string.listen_together_in_party, it)
                        } ?: stringResource(R.string.listen_together_subtitle),
                        badge = party.members.size.takeIf { party.inParty && it > 1 }?.toString(),
                        onClick = onListenTogether,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
        if (showOfflineCard) {
            Spacer(Modifier.height(12.dp))
            SettingsFeatureCard(
                icon = Icons.Rounded.CloudOff,
                title = offlineModeTitle,
                subtitle = stringResource(R.string.offline_mode_subtitle),
                trailing = {
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = AppSettings::setOfflineModeEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setOfflineModeEnabled(!offlineMode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GROUP_INSET),
            )
        }

        // The row that used to sit at the top of this group was called
        // "Lossless / HQ Audio" and toggled `SourceRegistry.setModuleEnabled` —
        // it switched the *module source* on and off, not lossless. Sources
        // above lists that as the module's own row now. Lossless itself is no
        // longer a setting at all — see
        // [SourceResolver.requestForNow][com.music.bitchord.data.sources.SourceResolver.requestForNow].
        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.GraphicEq,
            title = stringResource(R.string.audio_quality),
            subtitle = stringResource(R.string.settings_group_audio_subtitle),
            expanded = audioOpen,
            onToggle = { audioOpen = !audioOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val sourceTitle = stringResource(R.string.source)
            val sourceSubtitle = stringResource(R.string.sources_subtitle)
            row(sourceTitle, sourceSubtitle, "addon", "lossless") {
                SettingsRow(
                    icon = Icons.Rounded.Extension,
                    title = sourceTitle,
                    subtitle = sourceSubtitle,
                    onClick = onSources,
                )
            }
            val onWifiTitle = stringResource(R.string.on_wifi)
            row(onWifiTitle, "wi-fi", "streaming quality") {
                SettingsRow(
                    icon = Icons.Rounded.Wifi,
                    title = onWifiTitle,
                    badge = stringResource(R.string.in_use).takeIf { metered == false },
                    value = wifiQuality.localizedLabel(),
                    onClick = { picking = QualityTarget.WIFI },
                )
            }
            val onMobileDataTitle = stringResource(R.string.on_mobile_data)
            row(onMobileDataTitle, "cellular", "streaming quality") {
                SettingsRow(
                    icon = Icons.Rounded.SignalCellularAlt,
                    title = onMobileDataTitle,
                    badge = stringResource(R.string.in_use).takeIf { metered == true },
                    value = cellularQuality.localizedLabel(),
                    onClick = { picking = QualityTarget.CELLULAR },
                )
            }
            // Sits with the quality ceilings rather than with Playback: it
            // decides which version of a track gets fetched, the same question
            // the two rows above answer, and not how one is played back.
            //
            // Switchable on every device. It used to be greyed where the vendor
            // image shipped no Dolby decoder, which was honest while MediaCodec
            // was the only decoder in reach — an Atmos stream on such a phone
            // skipped itself rather than failing. The FFmpeg renderer behind it
            // now plays the bed on those devices and the built-in spatializer
            // widens it, so the only thing that can refuse Atmos is this switch
            // — see [AppSettings.dolbyAtmos]. The subtitle is where the two
            // cases still differ, because "the more immersive version" means
            // Dolby's own mix on one device and the app's stand-in on another.
            val dolbyAtmosTitle = stringResource(R.string.dolby_atmos)
            row(dolbyAtmosTitle, "surround", "e-ac-3") {
                SettingsRow(
                    iconPainter = painterResource(R.drawable.ic_dolby_atmos),
                    title = dolbyAtmosTitle,
                    subtitle = stringResource(
                        if (dolbyAtmosSupported) {
                            R.string.dolby_atmos_subtitle
                        } else {
                            R.string.dolby_atmos_fallback_subtitle
                        },
                    ),
                    trailing = {
                        Switch(
                            checked = dolbyAtmos,
                            onCheckedChange = AppSettings::setDolbyAtmos,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
                )
            }
        }

        // Its own group rather than rows bolted onto the two above, because a
        // download is not a third kind of connection. The ceilings answer "what
        // does this minute cost"; these answer "what am I keeping, and when may
        // it be fetched" — and those two questions only make sense read
        // together, which is what puts them side by side here.
        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.Download,
            title = stringResource(R.string.downloads),
            subtitle = stringResource(R.string.settings_group_downloads_subtitle),
            expanded = downloadsOpen,
            onToggle = { downloadsOpen = !downloadsOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val downloadQualityTitle = stringResource(R.string.download_quality)
            row(downloadQualityTitle, "offline", "lossless") {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = downloadQualityTitle,
                    subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                    value = downloadQuality.localizedLabel(),
                    onClick = { pickingDownloadQuality = true },
                )
            }
            // Reads as part of Download quality above it, not as a setting
            // of its own — same treatment as Play animated cover over
            // cellular gets under Animated cover art.
            val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
            row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
                SettingsSubRow(
                    title = downloadWifiOnlyTitle,
                    checked = wifiOnlyDownloads,
                    onCheckedChange = AppSettings::setWifiOnlyDownloads,
                    badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
                )
            }
            val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
            row(exportDownloadsTitle, "music folder", divided = false) {
                SettingsSubRow(
                    title = exportDownloadsTitle,
                    checked = exportDownloads,
                    onCheckedChange = AppSettings::setExportDownloads,
                    subtitle = "Music folder".takeIf { exportDownloads },
                )
            }
        }

        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.PlayArrow,
            title = stringResource(R.string.playback),
            subtitle = stringResource(R.string.settings_group_playback_subtitle),
            expanded = playbackOpen,
            onToggle = { playbackOpen = !playbackOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
            row(preferMusicOnlyTitle, "video", "audio", "music video") {
                SettingsRow(
                    icon = Icons.Rounded.SmartDisplay,
                    title = preferMusicOnlyTitle,
                    subtitle = stringResource(R.string.prefer_music_only_subtitle),
                    trailing = {
                        Switch(
                            checked = preferMusicOnly,
                            onCheckedChange = AppSettings::setPreferMusicOnly,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
                )
            }
            val outputPrecisionTitle = stringResource(R.string.output_precision)
            row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = outputPrecisionTitle,
                    subtitle = buildString {
                        append(outputStatus.sink)
                        append(" · ")
                        append(outputStatus.deviceName)
                        (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                            ?.let { append(" · ${it / 1000.0} kHz") }
                        append(" · ")
                        append(AudioOutputStatus.encodingLabel(outputStatus))
                    },
                )
                SegmentedControl(
                    options = OutputPcmMode.entries.map(OutputPcmMode::label),
                    selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                    onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                    modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
            row(preferUsbDacTitle, "headphone", "output") {
                SettingsSubRow(
                    title = preferUsbDacTitle,
                    checked = preferUsbDac,
                    onCheckedChange = AppSettings::setPreferUsbDac,
                    badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
                )
            }
            val loudnessTitle = stringResource(R.string.loudness_normalization)
            row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
                SettingsRow(
                    icon = Icons.Rounded.VolumeUp,
                    title = loudnessTitle,
                    subtitle = stringResource(R.string.loudness_normalization_subtitle),
                    trailing = {
                        Switch(
                            checked = loudnessNormalization,
                            onCheckedChange = AppSettings::setLoudnessNormalization,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                )
            }
            val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
            row(loudnessSpeakerTitle, "loudness", "speaker", "normalize") {
                SettingsSubRow(
                    title = loudnessSpeakerTitle,
                    checked = loudnessOffOnSpeaker,
                    onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                )
            }
            // Automix decides its own length from each pair of tracks —
            // tempo, key, structure — so it replaces the manual slider rather
            // than needing it set to anything first.
            if (!smartFade) {
                val crossfadeTitle = stringResource(R.string.crossfade)
                row(crossfadeTitle, "fade", "gapless") {
                    SliderRow(
                        icon = Icons.Rounded.Waves,
                        title = crossfadeTitle,
                        subtitle = stringResource(R.string.crossfade_subtitle),
                        value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                        sliderValue = crossfade.toFloat(),
                        onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                }
            }
            val automixTitle = stringResource(R.string.automix)
            row(automixTitle, "crossfade", "smart fade", "mix") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = automixTitle,
                    subtitle = if (smartFade) {
                        stringResource(R.string.automix_enabled_subtitle)
                    } else {
                        stringResource(R.string.automix_disabled_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = smartFade,
                            onCheckedChange = AppSettings::setSmartFadeEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                )
            }
            val automixPerformanceTitle = stringResource(R.string.automix_performance)
            row(automixPerformanceTitle, "cpu", "battery") {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = automixPerformanceTitle,
                    subtitle = stringResource(R.string.automix_performance_subtitle),
                    value = automixPerformance.localizedLabel(),
                    onClick = { pickingAutomixPerformance = true },
                )
            }
            val skipSilenceTitle = stringResource(R.string.skip_silence)
            row(skipSilenceTitle, "silence") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    title = skipSilenceTitle,
                    // Silence skipping is Media3's own processor, and
                    // `DefaultAudioSink.configure` appends that processor list
                    // only on its 16-bit branch — the float branch gets the
                    // format converter and nothing else. So on a float track
                    // this switch really does nothing, and the row says so
                    // rather than letting someone toggle it and wonder. The
                    // equaliser is unaffected: it runs upstream of that sink.
                    subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        stringResource(R.string.skip_silence_float_subtitle)
                    } else {
                        stringResource(R.string.skip_silence_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = AppSettings::setSkipSilence,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }
            val spatialAudioTitle = stringResource(R.string.spatial_audio)
            row(spatialAudioTitle, "surround", "3d") {
                SettingsRow(
                    icon = Icons.Rounded.SurroundSound,
                    title = spatialAudioTitle,
                    // Widening a JOC stream would fight the object-based mix
                    // Dolby has already spatialized, so the service holds this
                    // off for the duration of an Atmos track — see
                    // `PlaybackService.applySpatialAudioEnabled`. Said on the
                    // row because the switch stays where the listener left it,
                    // and a switch reading "on" over an effect that is not
                    // running is the same silent lie the equaliser screen used
                    // to tell. Only where Dolby's own decoder is the one
                    // rendering: on a device without it, this effect *is* the
                    // Atmos playback, and it runs.
                    subtitle = if (playingDolbyAtmos && dolbyAtmosSupported) {
                        stringResource(R.string.spatial_audio_atmos_subtitle)
                    } else {
                        stringResource(R.string.spatial_audio_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = spatialAudio,
                            onCheckedChange = AppSettings::setSpatialAudio,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                )
            }
            // The system panel is not listed here as well. A device with a
            // Dolby or Dirac panel has something BitChord cannot reproduce and
            // keeps its row — but one level in, at the foot of the equaliser
            // screen, rather than as a second equaliser entry alongside ours.
            val equalizerTitle = stringResource(R.string.equalizer)
            row(equalizerTitle, "eq", "bass", "treble") {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = equalizerTitle,
                    subtitle = stringResource(R.string.equalizer_subtitle),
                    onClick = onEqualizer,
                )
            }
        }

        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.Brightness4,
            title = stringResource(R.string.appearance),
            subtitle = stringResource(R.string.settings_group_appearance_subtitle),
            expanded = appearanceOpen,
            onToggle = { appearanceOpen = !appearanceOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val themeTitle = stringResource(R.string.theme)
            row(themeTitle, "dark mode", "light mode", "dynamic theme", "cover art") {
                SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                SegmentedControl(
                    options = ThemeMode.entries.map { it.localizedLabel() },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
                AnimatedVisibility(
                    visible = theme == ThemeMode.DYNAMIC,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    DynamicThemeVariantSelector(
                        selectedVariant = dynamicThemeVariant,
                        customColors = customDynamicColors,
                        onSelectVariant = AppSettings::setDynamicThemeVariant,
                        onAddCustomColor = { showCustomColorPicker = true },
                        onRemoveCustomColor = AppSettings::removeCustomDynamicColor,
                        modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                    )
                }
            }
            val customFontTitle = stringResource(R.string.custom_font)
            row(customFontTitle, "font", "typeface", "ttf", "otf") {
                SettingsRow(
                    icon = Icons.Rounded.TextFields,
                    title = customFontTitle,
                    subtitle = customFont
                        ?.let { stringResource(R.string.custom_font_active, it) }
                        ?: stringResource(R.string.custom_font_subtitle),
                    // Reset is its own button rather than a second row: the row
                    // itself is the picker, and clearing takes one tap.
                    trailing = if (customFont == null) {
                        null
                    } else {
                        {
                            TextButton(
                                onClick = {
                                    CustomFont.clear(context)
                                    AppSettings.setCustomFont(null)
                                },
                            ) {
                                Text(stringResource(R.string.custom_font_reset))
                            }
                        }
                    },
                    onClick = { fontPicker.launch(FONT_MIME_TYPES) },
                )
            }
            // Text and layout size get sliders rather than the four named steps
            // the theme row uses: the two controls sit one above the other, and
            // "Small / Default / Large / Extra large" twice over reads as a
            // choice already made. Twelve stops on each, five percent apart, is
            // the same range in a form that shows the number it is on.
            val fontSizeTitle = stringResource(R.string.font_size)
            row(fontSizeTitle, "text size", "type", "scale", "bigger", "smaller", "accessibility") {
                SliderRow(
                    icon = Icons.Rounded.TextFields,
                    title = fontSizeTitle,
                    subtitle = stringResource(R.string.font_size_subtitle),
                    value = stringResource(R.string.interface_scale_value, (fontScale * 100f).roundToInt()),
                    sliderValue = fontScale,
                    onSliderValue = AppSettings::setFontScale,
                    valueRange = AppSettings.MIN_INTERFACE_SCALE..AppSettings.MAX_INTERFACE_SCALE,
                    steps = INTERFACE_SCALE_STEPS,
                )
            }
            val displaySizeTitle = stringResource(R.string.display_size)
            row(displaySizeTitle, "layout size", "zoom", "scale", "bigger", "smaller", "accessibility") {
                SliderRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = displaySizeTitle,
                    subtitle = stringResource(R.string.display_size_subtitle),
                    value = stringResource(R.string.interface_scale_value, (displayScale * 100f).roundToInt()),
                    sliderValue = displayScale,
                    onSliderValue = AppSettings::setDisplayScale,
                    valueRange = AppSettings.MIN_INTERFACE_SCALE..AppSettings.MAX_INTERFACE_SCALE,
                    steps = INTERFACE_SCALE_STEPS,
                )
            }
            val interfaceScaleResetTitle = stringResource(R.string.interface_scale_reset)
            row(interfaceScaleResetTitle, "reset", "default", "scale", "font", "display", "text") {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = interfaceScaleResetTitle,
                    subtitle = stringResource(R.string.interface_scale_reset_subtitle),
                    onClick = { AppSettings.resetInterfaceScale() },
                )
            }
            val reduceAnimationTitle = stringResource(R.string.reduce_animation)
            row(reduceAnimationTitle, "motion") {
                SettingsRow(
                    icon = Icons.Rounded.MotionPhotosOff,
                    title = reduceAnimationTitle,
                    subtitle = stringResource(R.string.reduce_animation_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceAnimation,
                            onCheckedChange = AppSettings::setReduceAnimation,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                )
            }
            val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
            row(reduceDynamicBlurTitle, "blur", "performance") {
                SettingsRow(
                    icon = Icons.Rounded.BlurOff,
                    title = reduceDynamicBlurTitle,
                    subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceDynamicBlur,
                            onCheckedChange = AppSettings::setReduceDynamicBlur,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                )
            }
            val liquidGlassTitle = stringResource(R.string.liquid_glass)
            row(liquidGlassTitle, "glass", "blur") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = liquidGlassTitle,
                    subtitle = stringResource(
                        if (liquidGlassSupported) {
                            R.string.liquid_glass_subtitle
                        } else {
                            R.string.liquid_glass_unavailable
                        },
                    ),
                    enabled = liquidGlassSupported,
                    trailing = {
                        Switch(
                            checked = liquidGlass,
                            onCheckedChange = AppSettings::setLiquidGlass,
                            enabled = liquidGlassSupported,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                )
            }
            // Here rather than in a Performance group of its own, because what
            // it changes is how the app looks and moves: the same frames the two
            // rows above either freeze or blur.
            val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
            row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
                SettingsRow(
                    icon = BitChordIcons.Performance,
                    title = highPerformanceModeTitle,
                    subtitle = if (highPerformanceMode) {
                        stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                    } else {
                        stringResource(R.string.high_performance_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = highPerformanceMode,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showPerformanceWarning = true
                                } else {
                                    AppSettings.setHighPerformanceMode(false)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = {
                        if (highPerformanceMode) {
                            AppSettings.setHighPerformanceMode(false)
                        } else {
                            showPerformanceWarning = true
                        }
                    },
                )
            }
            if (highPerformanceMode) {
                val refreshRateTitle = stringResource(R.string.refresh_rate)
                row(refreshRateTitle, "hz", "frame rate") {
                    SettingsRow(
                        icon = BitChordIcons.FrameRate,
                        title = refreshRateTitle,
                    )
                    SegmentedControl(
                        options = supportedRefreshRates.map { "$it Hz" },
                        selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                        onSelect = { index ->
                            AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                        },
                        modifier = Modifier.padding(
                            start = TEXT_INSET,
                            end = ROW_INSET,
                            bottom = 14.dp,
                        ),
                    )
                }
            }
        }

        // The player's own page — the sleeve, the words over it, and the row of
        // buttons under it. Apart from Appearance because these are choices
        // about one screen, not about the app.
        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.Lyrics,
            title = stringResource(R.string.settings_group_player_lyrics),
            subtitle = stringResource(R.string.settings_group_player_subtitle),
            expanded = playerOpen,
            onToggle = { playerOpen = !playerOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                row(fullScreenCoverArtTitle, "artwork", "player") {
                    SettingsRow(
                        icon = Icons.Rounded.Fullscreen,
                        title = fullScreenCoverArtTitle,
                        subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                        trailing = {
                            Switch(
                                checked = fullBleedArtwork,
                                onCheckedChange = AppSettings::setFullBleedArtwork,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                    )
                }
            }
            val legacyMeshGradientTitle = stringResource(R.string.legacy_mesh_gradient)
            row(legacyMeshGradientTitle, "background", "player") {
                SettingsRow(
                    icon = Icons.Rounded.Gradient,
                    title = legacyMeshGradientTitle,
                    subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                    trailing = {
                        Switch(
                            checked = legacyMeshGradient,
                            onCheckedChange = AppSettings::setLegacyMeshGradient,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
                )
            }
            val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
            // Carries the keywords of the rows that only appear once it is on,
            // so searching "spotify" with animated covers switched off turns up
            // the switch that brings the Spotify row back rather than nothing.
            row(animatedCoverArtTitle, "canvas", "video", "spotify") {
                SettingsRow(
                    icon = Icons.Rounded.Animation,
                    title = animatedCoverArtTitle,
                    subtitle = stringResource(R.string.animated_cover_art_subtitle),
                    trailing = {
                        Switch(
                            checked = animatedCanvas,
                            onCheckedChange = AppSettings::setAnimatedCanvas,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
                )
            }
            // Reads as part of the Animated cover art option above it, not
            // as a separate setting. Nothing to narrow while the clip itself
            // is off. Defaults to off: a clip loops for as long as its track
            // plays, so on cellular this is not a one-time video cost but
            // that cost repeated on every loop — see AppSettings.canvasOverCellular.
            if (animatedCanvas) {
                val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
                row(coverCellularTitle, "canvas", "cellular", divided = false) {
                    SettingsSubRow(
                        title = coverCellularTitle,
                        checked = canvasOverCellular,
                        onCheckedChange = AppSettings::setCanvasOverCellular,
                    )
                }
                val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
                row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSpotifyCanvasAuth)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spotifyCanvasTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
            val artistSignaturesTitle = stringResource(R.string.artist_signatures)
            row(artistSignaturesTitle, "signature", "artist", "logo") {
                SettingsRow(
                    icon = Icons.Rounded.Draw,
                    title = artistSignaturesTitle,
                    subtitle = stringResource(R.string.artist_signatures_subtitle),
                    trailing = {
                        Switch(
                            checked = signatureApiEnabled,
                            onCheckedChange = AppSettings::setSignatureApiEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
                )
            }
            if (signatureApiEnabled) {
                val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
                row(signatureUrlTitle, "signature", "api", "url", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onEditSignatureApi)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = signatureApiUrl.ifBlank {
                                stringResource(R.string.artist_signatures_unconfigured)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art above: the lyrics source and
            // translation rows are only here while this is on.
            row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = syncedLyricsTitle,
                    subtitle = stringResource(R.string.synced_lyrics_subtitle),
                    trailing = {
                        Switch(
                            checked = syncedLyrics,
                            onCheckedChange = AppSettings::setSyncedLyrics,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
                row(lyricsBlurTitle, "lyrics", "blur") {
                    SettingsRow(
                        icon = Icons.Rounded.BlurOn,
                        title = lyricsBlurTitle,
                        subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = lyricsBlur,
                                onCheckedChange = AppSettings::setLyricsBlur,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                    )
                }
                val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
                row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                    SettingsRow(
                        icon = Icons.Rounded.Language,
                        title = lyricsSourcesTitle,
                        subtitle = lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                            .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                        trailing = { Chevron() },
                        onClick = onLyricsSources,
                    )
                }
                val translationLanguageTitle = stringResource(R.string.translation_language)
                row(translationLanguageTitle, "lyrics", "translate") {
                    SettingsRow(
                        icon = Icons.Rounded.Translate,
                        title = translationLanguageTitle,
                        subtitle = if (translationLanguage.isBlank()) {
                            stringResource(R.string.translation_language_subtitle)
                        } else {
                            translationLanguageName(
                                translationLanguage,
                                AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                            )
                        },
                        trailing = { Chevron() },
                        onClick = onTranslationLanguage,
                    )
                }
            }
            // The action bar is the player's own, not a part of lyrics:
            // these seven rows are here whether or not the words are singing.
            val showToolbarTitle = stringResource(R.string.show_player_bottom_toolbar)
            row(showToolbarTitle, "player", "toolbar", "action bar", "buttons", "bottom") {
                SettingsRow(
                    icon = Icons.Rounded.Apps,
                    title = showToolbarTitle,
                    subtitle = stringResource(R.string.show_player_bottom_toolbar_subtitle),
                    trailing = {
                        Switch(
                            checked = showPlayerBottomToolbar,
                            onCheckedChange = AppSettings::setShowPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = {
                        AppSettings.setShowPlayerBottomToolbar(!showPlayerBottomToolbar)
                    },
                )
            }
            val showQueueButtonTitle = stringResource(R.string.show_queue_button)
            row(showQueueButtonTitle, "player", "toolbar", "button", "queue") {
                SettingsRow(
                    icon = Icons.Rounded.QueueMusic,
                    title = showQueueButtonTitle,
                    enabled = showPlayerBottomToolbar,
                    trailing = {
                        Switch(
                            checked = showQueueButton,
                            onCheckedChange = AppSettings::setShowQueueButton,
                            enabled = showPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowQueueButton(!showQueueButton) },
                )
            }
            val showSleepTimerButtonTitle = stringResource(R.string.show_sleep_timer_button)
            row(showSleepTimerButtonTitle, "player", "toolbar", "button", "sleep timer") {
                SettingsRow(
                    icon = Icons.Rounded.Bedtime,
                    title = showSleepTimerButtonTitle,
                    enabled = showPlayerBottomToolbar,
                    trailing = {
                        Switch(
                            checked = showSleepTimerButton,
                            onCheckedChange = AppSettings::setShowSleepTimerButton,
                            enabled = showPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowSleepTimerButton(!showSleepTimerButton) },
                )
            }
            val showShuffleButtonTitle = stringResource(R.string.show_shuffle_button)
            row(showShuffleButtonTitle, "player", "toolbar", "button", "shuffle") {
                SettingsRow(
                    icon = Icons.Rounded.Shuffle,
                    title = showShuffleButtonTitle,
                    enabled = showPlayerBottomToolbar,
                    trailing = {
                        Switch(
                            checked = showShuffleButton,
                            onCheckedChange = AppSettings::setShowShuffleButton,
                            enabled = showPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowShuffleButton(!showShuffleButton) },
                )
            }
            val showLyricsButtonTitle = stringResource(R.string.show_lyrics_button)
            row(showLyricsButtonTitle, "player", "toolbar", "button", "lyrics") {
                SettingsRow(
                    icon = Icons.Rounded.Lyrics,
                    title = showLyricsButtonTitle,
                    enabled = showPlayerBottomToolbar,
                    trailing = {
                        Switch(
                            checked = showLyricsButton,
                            onCheckedChange = AppSettings::setShowLyricsButton,
                            enabled = showPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowLyricsButton(!showLyricsButton) },
                )
            }
            val showRepeatButtonTitle = stringResource(R.string.show_repeat_button)
            row(showRepeatButtonTitle, "player", "toolbar", "button", "repeat") {
                SettingsRow(
                    icon = Icons.Rounded.Repeat,
                    title = showRepeatButtonTitle,
                    enabled = showPlayerBottomToolbar,
                    trailing = {
                        Switch(
                            checked = showRepeatButton,
                            onCheckedChange = AppSettings::setShowRepeatButton,
                            enabled = showPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowRepeatButton(!showRepeatButton) },
                )
            }
            val showMoreButtonTitle = stringResource(R.string.show_more_button)
            row(showMoreButtonTitle, "player", "toolbar", "button", "more options", "menu") {
                SettingsRow(
                    icon = Icons.Rounded.MoreHoriz,
                    title = showMoreButtonTitle,
                    enabled = showPlayerBottomToolbar,
                    trailing = {
                        Switch(
                            checked = showMoreButton,
                            onCheckedChange = AppSettings::setShowMoreButton,
                            enabled = showPlayerBottomToolbar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowMoreButton(!showMoreButton) },
                )
            }
        }

        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.Folder,
            title = stringResource(R.string.settings_group_local_storage),
            subtitle = stringResource(R.string.settings_group_storage_subtitle),
            expanded = storageOpen,
            onToggle = { storageOpen = !storageOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val localMusicFolderTitle = stringResource(R.string.local_music_folder)
            row(localMusicFolderTitle, "folder", "offline", "files") {
                SettingsRow(
                    icon = Icons.Rounded.Folder,
                    title = localMusicFolderTitle,
                    subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                        ?: stringResource(R.string.all_audio_folders),
                    onClick = { localMusicFolderPicker.launch(null) },
                )
            }
            if (localMusicFolderUri.isNotBlank()) {
                val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
                row(useAllAudioFoldersTitle, "folder") {
                    SettingsRow(
                        icon = Icons.Rounded.LibraryMusic,
                        title = useAllAudioFoldersTitle,
                        subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                        onClick = { AppSettings.setLocalMusicFolderUri("") },
                    )
                }
            }
            val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
            row(filterNonMusicAudioTitle, "podcast", "recording") {
                SettingsRow(
                    icon = Icons.Rounded.FilterAlt,
                    title = filterNonMusicAudioTitle,
                    subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                    trailing = {
                        Switch(
                            checked = filterNonMusicAudio,
                            onCheckedChange = AppSettings::setFilterNonMusicAudio,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
                )
            }
            // The same card, because a folder on the device and the space its
            // cache may take are one question: what is here, locally.
            val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
            // The slider's last stop, one step past the largest fixed size, is
            // "Unlimited" — see [AppSettings.setAudioCacheLimitBytes].
            val cacheLimitMb = if (cacheUnlimited) CACHE_UNLIMITED_STOP_MB else (cacheLimitBytes / (1024 * 1024)).toInt()
            val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
            val cacheLimitLabel = if (cacheUnlimited) unlimitedLabel else formatCacheSize(cacheLimitMb)
            val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
            row(showCacheFolderTitle, "cache", "cached songs", "library", "folder") {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = showCacheFolderTitle,
                    subtitle = stringResource(R.string.show_cache_folder_subtitle),
                    trailing = {
                        Switch(
                            checked = showCacheFolder,
                            onCheckedChange = AppSettings::setShowCacheFolder,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
                )
            }
            val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
            row(songCacheLimitTitle, "cache", "space", "unlimited") {
                SliderRow(
                    icon = Icons.Rounded.Storage,
                    title = songCacheLimitTitle,
                    subtitle = when {
                        cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                        cacheLimitMb > CACHE_WARNING_MB ->
                            stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                        else -> stringResource(R.string.song_cache_limit_subtitle)
                    },
                    value = cacheLimitLabel,
                    sliderValue = cacheStopIndex(cacheLimitMb).toFloat(),
                    onSliderValue = { index ->
                        val mb = CACHE_LIMIT_STOPS_MB[
                            index.roundToInt().coerceIn(0, CACHE_LIMIT_STOPS_MB.lastIndex)
                        ]
                        AppSettings.setAudioCacheLimitBytes(
                            if (mb == CACHE_UNLIMITED_STOP_MB) {
                                AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
                            } else {
                                mb.toLong() * 1024 * 1024
                            },
                        )
                    },
                    valueRange = 0f..CACHE_LIMIT_STOPS_MB.lastIndex.toFloat(),
                    steps = CACHE_LIMIT_STOPS_MB.size - 2,
                )
            }
            val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
            row(clearSongCacheTitle, "cache", "free space") {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearSongCacheTitle,
                    subtitle = stringResource(R.string.clear_song_cache_subtitle),
                    onClick = { confirmClearSongCache = true },
                )
            }
            val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
            row(clearImageCacheTitle, "cache", "artwork", "free space") {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearImageCacheTitle,
                    subtitle = stringResource(R.string.clear_image_cache_subtitle),
                    onClick = {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // The watch row used to sit here, between Storage and Your data; it has
        // its own group below, with the other things that are not a category.

        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.BarChart,
            title = stringResource(R.string.your_data),
            subtitle = stringResource(R.string.settings_group_data_subtitle),
            expanded = dataOpen,
            onToggle = { dataOpen = !dataOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val replayTitle = stringResource(R.string.replay)
            row(replayTitle, "stats", "history", "wrapped") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = replayTitle,
                    subtitle = stringResource(R.string.replay_subtitle),
                    onClick = onOpenReplay,
                )
            }
            val exportDataTitle = stringResource(R.string.export_data)
            row(exportDataTitle, "backup") {
                SettingsRow(
                    icon = Icons.Rounded.FileUpload,
                    title = exportDataTitle,
                    subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                    onClick = { exportPicker.launch(Backup.suggestedName()) },
                )
            }
            val importDataTitle = stringResource(R.string.import_data)
            row(importDataTitle, "backup", "restore") {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = importDataTitle,
                    subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                    onClick = { confirmImport = true },
                )
            }
        }

        // No footer: it only restated the stop-on-close row's own subtitle,
        // which sits four rows above it and says the same thing in fewer words.
        SettingsCategoryCard(
            search = search,
            icon = Icons.Rounded.Tune,
            title = stringResource(R.string.miscellaneous),
            subtitle = stringResource(R.string.settings_group_misc_subtitle),
            expanded = miscOpen,
            onToggle = { miscOpen = !miscOpen },
            forceExpanded = searchQuery.isNotBlank(),
        ) {
            val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
            row(playNextOnSwipeTitle, "swipe", "queue") {
                SettingsRow(
                    icon = Icons.Rounded.PlaylistPlay,
                    title = playNextOnSwipeTitle,
                    subtitle = if (swipeToPlayNext) {
                        stringResource(R.string.swipe_plays_next)
                    } else {
                        stringResource(R.string.swipe_adds_to_queue)
                    },
                    trailing = {
                        Switch(
                            checked = swipeToPlayNext,
                            onCheckedChange = AppSettings::setSwipeToPlayNext,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
                )
            }
            val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
            row(dontRepeatSongsTitle, "radio", "suggestions") {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = dontRepeatSongsTitle,
                    subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                    trailing = {
                        Switch(
                            checked = dontRepeatSuggestions,
                            onCheckedChange = AppSettings::setDontRepeatSuggestions,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
                )
            }
            val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
            row(stopMusicOnCloseTitle, "notification", "background") {
                SettingsRow(
                    icon = Icons.Rounded.MusicOff,
                    title = stopMusicOnCloseTitle,
                    subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                    trailing = {
                        Switch(
                            checked = stopOnTaskRemoved,
                            onCheckedChange = AppSettings::setStopOnTaskRemoved,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
                )
            }
            val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
            row(hideVolumeBarTitle, "volume", "player") {
                SettingsRow(
                    icon = Icons.Rounded.VolumeOff,
                    title = hideVolumeBarTitle,
                    subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                    trailing = {
                        Switch(
                            checked = hideVolumeBar,
                            onCheckedChange = AppSettings::setHideVolumeBar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
                )
            }
            val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
            row(hideStatusBarTitle, "status bar", "immersive", "fullscreen", "player") {
                SettingsRow(
                    icon = Icons.Rounded.WebAssetOff,
                    title = hideStatusBarTitle,
                    subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                    trailing = {
                        Switch(
                            checked = hideStatusBarInPlayer,
                            onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
                )
            }
            val showQueueHistoryTitle = stringResource(R.string.show_queue_history)
            row(showQueueHistoryTitle, "player", "queue", "history", "previously played") {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = showQueueHistoryTitle,
                    subtitle = stringResource(R.string.show_queue_history_subtitle),
                    trailing = {
                        Switch(
                            checked = showQueueHistory,
                            onCheckedChange = AppSettings::setShowQueueHistory,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowQueueHistory(!showQueueHistory) },
                )
            }
            val hideSongStatusTitle = stringResource(R.string.hide_song_status)
            row(hideSongStatusTitle, "player", "playing from", "played by") {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = hideSongStatusTitle,
                    subtitle = stringResource(R.string.hide_song_status_subtitle),
                    trailing = {
                        Switch(
                            checked = hideSongStatus,
                            onCheckedChange = AppSettings::setHideSongStatus,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
                )
            }
        }

        // Not a category: one row that opens a screen of its own, and only in a
        // build that carries the watch app — see app/build.gradle.kts.
        if (remember { WatchInstaller.available(context) }) {
            SearchableSettingsGroup(search, header = stringResource(R.string.wear_os_section)) {
                row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                    WearOsSettingsRow(onClick = onWearOs)
                }
            }
        }

        SearchableSettingsGroup(search, header = stringResource(R.string.language)) {
            val appLanguageTitle = stringResource(R.string.app_language)
            row(appLanguageTitle, "locale", "translate") {
                val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
                    ?: Locale.getDefault().toLanguageTag()
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = appLanguageTitle,
                    subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                    onClick = onAppLanguage,
                )
            }
        }

        SearchableSettingsGroup(search, header = stringResource(R.string.advanced_options)) {
            val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
            row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = smartAlignmentTitle,
                    subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                    trailing = {
                        Switch(
                            checked = smartVersionAlignment,
                            onCheckedChange = AppSettings::setSmartVersionAlignment,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
                )
            }
            val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
            row(showNerdStatsTitle, "debug", "bitrate", "codec") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = showNerdStatsTitle,
                    subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                    trailing = {
                        Switch(
                            checked = nerdStats,
                            onCheckedChange = AppSettings::setShowNerdStats,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
            val appIconTitle = stringResource(R.string.app_icon)
            row(appIconTitle, "icon", "launcher", "logo") {
                var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
                SettingsRow(icon = Icons.Rounded.Apps, title = appIconTitle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon.entries.forEach { option ->
                        val selected = option == selectedIcon
                        Column(
                            modifier = Modifier
                                .width(84.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedIcon = option
                                    AppIcon.apply(context, option)
                                }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // One 1:1 preview per variant, generated from the same
                            // full-bleed crop the launcher icon uses: every card shows
                            // identical scale and centring, and the rounded clip cuts
                            // only the icon's own dark corner wedges.
                            Image(
                                painter = painterResource(option.previewRes),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(22.dp)),
                            )
                            Text(
                                text = stringResource(option.labelRes),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Read after every group above has had its turn at the query, which is
        // what makes this an accurate "nothing here" rather than a guess.
        if (!search.anyMatch) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }

        // The version line is the page's colophon, not a setting: it belongs to
        // the whole list, so it goes when the list is narrowed to a few rows.
        if (searchQuery.isBlank()) {
        Text(
            text = buildAnnotatedString {
                append("UMtify $version\n")
                append("Developed by Ujjal Mallik\n")
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                withLink(LinkAnnotation.Url("https://github.com/UjjalMallik/UMtify", linkStyles)) {
                    append("GitHub")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        }
    }

    picking?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { picking = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            QualitySheet(
                target = target,
                selected = when (target) {
                    QualityTarget.WIFI -> wifiQuality
                    QualityTarget.CELLULAR -> cellularQuality
                },
                // Writes the one ceiling that was being edited and nothing
                // else. There used to be a `SourceRegistry.applyQualityPreset`
                // call here that flipped the module and JioSaavn switches to
                // match — which meant budgeting *mobile data* switched those
                // sources off while sitting on Wi-Fi, and coming back to Wi-Fi
                // never switched them on again. Which sources a rung consults
                // is now read per stream off the connection in force; see
                // [AudioQuality.permits].
                onSelect = { quality ->
                    when (target) {
                        QualityTarget.WIFI -> AppSettings.setAudioQualityWifi(quality)
                        QualityTarget.CELLULAR -> AppSettings.setAudioQualityCellular(quality)
                    }
                    picking = null
                },
            )
        }
    }

    if (pickingDownloadQuality) {
        ModalBottomSheet(
            onDismissRequest = { pickingDownloadQuality = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            DownloadQualitySheet(
                selected = downloadQuality,
                onSelect = { quality ->
                    AppSettings.setDownloadQuality(quality)
                    pickingDownloadQuality = false
                },
            )
        }
    }

    if (pickingAutomixPerformance) {
        ModalBottomSheet(
            onDismissRequest = { pickingAutomixPerformance = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            AutomixPerformanceSheet(
                selected = automixPerformance,
                onSelect = { mode ->
                    AppSettings.setAutomixPerformanceMode(mode)
                    pickingAutomixPerformance = false
                },
            )
        }
    }

    // Everything in the Cached songs folder goes with it, so say so first.
    if (confirmClearSongCache) {
        AlertDialog(
            onDismissRequest = { confirmClearSongCache = false },
            title = { Text(stringResource(R.string.clear_song_cache_title)) },
            text = { Text(stringResource(R.string.clear_song_cache_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearSongCache = false
                    AudioCache.clear {
                        Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSongCache = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Asked before the picker opens rather than after a file is chosen: the
    // thing being confirmed is that this device's own history is about to be
    // thrown away, and that is true whichever file gets picked.
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = {
                Text(stringResource(R.string.import_backup_warning))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) {
                    Text(stringResource(R.string.choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPerformanceWarning) {
        AlertDialog(
            onDismissRequest = { showPerformanceWarning = false },
            title = { Text(stringResource(R.string.high_performance_before_enabling)) },
            text = { Text(stringResource(R.string.high_performance_battery_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceWarning = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        val settingsIntent = intent.takeIf {
                            it.resolveActivity(context.packageManager) != null
                        } ?: Intent(Settings.ACTION_SETTINGS)
                        batterySettingsLauncher.launch(settingsIntent)
                    },
                ) {
                    Text(stringResource(R.string.open_battery_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPerformanceConfirmation) {
        AlertDialog(
            onDismissRequest = { showPerformanceConfirmation = false },
            title = { Text(stringResource(R.string.enable_high_performance_title)) },
            text = { Text(stringResource(R.string.enable_high_performance_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceConfirmation = false
                        AppSettings.setHighPerformanceMode(true)
                    },
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceConfirmation = false }) {
                    Text(stringResource(R.string.not_yet))
                }
            },
        )
    }

    if (showListenBrainzTokenDialog) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        AlertDialog(
            onDismissRequest = { showListenBrainzTokenDialog = false },
            title = { Text(stringResource(R.string.listenbrainz_token)) },
            text = {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text(stringResource(R.string.api_token)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzTokenDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showListenBrainzTokenDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCustomColorPicker) {
        CustomColorPickerDialog(
            onDismiss = { showCustomColorPicker = false },
            onColorAdded = { colorArgb ->
                AppSettings.addCustomDynamicColor(colorArgb)
                AppSettings.setDynamicThemeVariant("CUSTOM_$colorArgb")
            },
        )
    }

}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

/** Which ceiling the open picker is editing. */
private enum class QualityTarget(val icon: ImageVector) {
    WIFI(Icons.Rounded.Wifi),
    CELLULAR(Icons.Rounded.SignalCellularAlt),
}

@Composable
private fun QualityTarget.localizedTitle(): String = stringResource(
    if (this == QualityTarget.WIFI) R.string.wifi else R.string.mobile_data,
)

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun DownloadQuality.localizedLabel(): String = stringResource(
    when (this) {
        DownloadQuality.STANDARD -> R.string.standard
        DownloadQuality.HIGH -> R.string.high
        DownloadQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
        ThemeMode.DYNAMIC -> R.string.dynamic
    },
)

@Composable
private fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

/**
 * Hands the device's own effect panel this app's audio session.
 *
 * Shared with [EqualizerScreen], which offers the same escape hatch a second
 * time next to the equaliser it might be an alternative to.
 */
internal fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
    }
}

/**
 * What the font picker offers. The trailing wildcard is not a fallback for
 * fonts: providers that hand out a generic octet-stream would otherwise hide
 * every .ttf on the device. [CustomFont] still rejects anything unreadable.
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/vnd.ms-opentype",
    "*/*",
)

/** Above this, the cache limit slider's subtitle warns rather than reassures. */
private const val CACHE_WARNING_MB = 2048

/**
 * Where the cache limit slider's "Unlimited" stop sits: one step past
 * [AppSettings.MAX_CACHE_LIMIT_BYTES], so the fixed sizes keep their positions.
 */
private const val CACHE_UNLIMITED_STOP_MB = 10 * 1024 + 512

/**
 * The cache limit's selectable rungs, in MB. The first is both the floor and
 * the default; [CACHE_UNLIMITED_STOP_MB] is the last stop and means
 * "Unlimited" rather than a size.
 */
private val CACHE_LIMIT_STOPS_MB = intArrayOf(
    (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toInt(),
    300,
    500,
    750,
    1024,
    1536,
    2048,
    3072,
    4096,
    6144,
    8192,
    10 * 1024,
    CACHE_UNLIMITED_STOP_MB,
)

/** Which rung [mb] is nearest, so a value stored off the ladder still lands on one. */
private fun cacheStopIndex(mb: Int): Int =
    CACHE_LIMIT_STOPS_MB.indices.minByOrNull { abs(CACHE_LIMIT_STOPS_MB[it] - mb) } ?: 0

/**
 * The text and layout size sliders' rungs: twelve stops, five percent apart,
 * across [AppSettings.MIN_INTERFACE_SCALE] to [AppSettings.MAX_INTERFACE_SCALE]
 * — so ten of them between the two ends.
 */
private const val INTERFACE_SCALE_STEPS = 10

/** "512 MB", "2 GB", "2.5 GB" — whichever reads more naturally at that size. */
private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}

/** Who you're signed in as, straight from YouTube Music's account menu. */
@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onClick: (() -> Unit)? = null,
    /** Sits inside a [SettingsGroup] that already supplies the card, so draws none of its own. */
    grouped: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (grouped) {
                    Modifier
                } else {
                    Modifier
                        .padding(horizontal = GROUP_INSET)
                        .clip(GroupShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .then(
                when {
                    signedIn && onClick != null -> Modifier.clickable(onClick = onClick)
                    !signedIn -> Modifier.clickable(onClick = onSignIn)
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.name
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        if (signedIn) R.string.youtube_music_account else R.string.tap_to_sign_in_google,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

/** The quality options for one connection, with what each costs in data. */
@Composable
private fun QualitySheet(
    target: QualityTarget,
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = target.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.audio_quality),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(
                        R.string.audio_quality_connection,
                        target.localizedTitle().lowercase(Locale.getDefault()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Best first — the option most people want shouldn't be last.
        AudioQuality.entries.reversed().forEach { quality ->
            val chosen = quality == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = quality.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.quality_hourly, quality.detail, quality.hourly),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** CPU budget picker for the background models that prepare Automix. */
@Composable
private fun AutomixPerformanceSheet(
    selected: AutomixPerformanceMode,
    onSelect: (AutomixPerformanceMode) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.automix_performance),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.automix_performance_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        AutomixPerformanceMode.entries.forEach { mode ->
            val chosen = mode == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(mode)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mode.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(
                            when (mode) {
                                AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                                AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                                AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * What to keep when a track is saved, with what each rung costs on disk.
 *
 * Priced per track rather than per hour, the way [QualitySheet] is. That sheet
 * is answering "what will listening cost me this hour", because a stream is
 * spent again on every replay; this one is answering "what will keeping this
 * cost me", and the answer is charged once. Same widget, different question, so
 * the numbers beside the options are in different units on purpose.
 */
@Composable
private fun DownloadQualitySheet(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.download_quality),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.download_quality_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Best first, matching [QualitySheet] — and here the best rung is also
        // the default, so the checkmark starts where the eye does.
        DownloadQuality.entries.reversed().forEach { quality ->
            val chosen = quality == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = quality.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.quality_per_track, quality.detail, quality.perTrack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

// ---- Building blocks --------------------------------------------------------

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

/** Where a row's text starts — dividers are inset to match, as on iOS. */
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

/**
 * What is typed into the field under the Settings title, and whether anything
 * on the screen has answered to it.
 *
 * A blank query matches everything, so the screen with the field untouched is
 * the screen as it was before there was a field. Rows ask [matches] as they
 * are composed; the empty state at the foot of the list reads [anyMatch] after
 * every group has had its turn, which is why this is made fresh on each pass
 * rather than remembered.
 */
private class SettingsSearch(query: String) {
    // Trimmed, because a trailing space is a typo rather than a search for
    // something ending in one — and on a phone keyboard it is one keystroke
    // away from every word typed.
    private val needle = query.trim()

    var anyMatch = false
        private set

    fun matches(vararg keywords: String?): Boolean {
        val hit = needle.isEmpty() ||
            keywords.any { it?.contains(needle, ignoreCase = true) == true }
        if (hit) anyMatch = true
        return hit
    }
}

/** Collects the rows of one group that survived the search. */
private class SettingsGroupScope(
    private val search: SettingsSearch,
    private val header: String?,
) {
    class Entry(val divided: Boolean, val content: @Composable () -> Unit)

    val entries = mutableListOf<Entry>()

    /**
     * One searchable setting. [keywords] is everything somebody might type
     * looking for it: its title at least, plus whatever its subtitle or the
     * controls under it say that the title doesn't — "Spotify", say, for the
     * canvas link that lives under Animated cover art.
     *
     * [divided] is false for the rows drawn as part of the row above them
     * rather than as settings in their own right, so they keep hugging their
     * parent when both survive the filter.
     */
    fun row(
        vararg keywords: String?,
        divided: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        // The header counts for every row beneath it: searching "playback"
        // should turn up the playback group entire, not nothing at all.
        if (search.matches(header, *keywords)) entries += Entry(divided, content)
    }
}

/**
 * A [SettingsGroup] whose rows are filtered by the search field, and which
 * takes itself off the screen when none of them are left. Dividers fall
 * between the rows that survived rather than around the gaps left by the ones
 * that didn't, so a filtered card is indistinguishable from a hand-written one.
 */
@Composable
private fun SearchableSettingsGroup(
    search: SettingsSearch,
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope(search, header)
    scope.content()
    if (scope.entries.isEmpty()) return
    SettingsGroup(header = header, footer = footer, topSpacing = topSpacing) {
        scope.entries.forEachIndexed { index, entry ->
            when {
                index > 0 && entry.divided -> RowDivider()
                // An undivided row is drawn tight to the top because something
                // it belongs to is usually above it. Filtered down to itself it
                // has nothing to sit under, so it is given that room back.
                index == 0 && !entry.divided -> Spacer(Modifier.height(10.dp))
            }
            entry.content()
        }
    }
}

/**
 * One category as a card that opens in place: the header is the card's own
 * first row, and the settings unfold underneath it on this page rather than on
 * a second one.
 *
 * Nothing is moved off the screen, which is the whole reason this is an
 * accordion and not a set of sub-screens — a category you have to navigate into
 * is a category whose contents can be missed. [SettingsGroupScope] still does
 * the filtering, so a search matches exactly the rows it always matched, and
 * while a query is typed every card holding a match is open, because a hit
 * inside a closed card is not a hit.
 */
@Composable
private fun SettingsCategoryCard(
    search: SettingsSearch,
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** Open this one whatever the user left it at, because a search is on. */
    forceExpanded: Boolean,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope(search, title)
    scope.content()
    if (scope.entries.isEmpty()) return

    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val open = expanded || forceExpanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(220),
        label = "settingsCategoryChevron",
    )

    Column(
        modifier = Modifier
            .padding(horizontal = GROUP_INSET)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = stringResource(
                    if (open) R.string.settings_group_collapse else R.string.settings_group_expand,
                    title,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = open,
            // Height is the only thing a closing card has to say; nothing about
            // it needs to move while the setting is asking for less motion.
            enter = if (reduceAnimation) {
                fadeIn()
            } else {
                expandVertically(tween(240)) + fadeIn(tween(180))
            },
            exit = if (reduceAnimation) {
                fadeOut()
            } else {
                shrinkVertically(tween(200)) + fadeOut(tween(140))
            },
        ) {
            Column {
                // The seam between the header and what it opens. The first row
                // below is then divided from its neighbours by the same hairline
                // the rows above the header never needed.
                RowDivider()
                scope.entries.forEachIndexed { index, entry ->
                    if (index > 0 && entry.divided) RowDivider()
                    // Same as [SearchableSettingsGroup]: an undivided row is a
                    // continuation of the one above it, and alone at the top it
                    // has nothing to continue, so it is given the room back.
                    if (index == 0 && !entry.divided) Spacer(Modifier.height(10.dp))
                    entry.content()
                }
            }
        }
    }
}

/**
 * The card at the head of the page: a place to go, or a mode the app can be
 * in, shown as a block rather than a row because neither is a setting to nudge.
 * [trailing] is for the one that is a switch and so has nowhere to go.
 */
@Composable
private fun SettingsFeatureCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (trailing != null) {
                // The switch is asked for directly, and consumes its own tap —
                // so it flips once here rather than twice through the card.
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Spacer(Modifier.height(6.dp))
            Badge(badge)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One inset card of rows, with an uppercase header above and an optional
 * plain-language [footer] below. Rows are separated by [RowDivider].
 */
@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    /** Room above the card, where there is no [header] to provide it. */
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right either
 * [trailing] (a switch, say) or the current [value] followed by a chevron.
 *
 * [iconPainter] is for the handful of rows whose glyph is a drawable rather
 * than a Material icon — the Dolby double-D, which is a mark and not something
 * to approximate with the nearest speaker outline. Exactly one of it and [icon]
 * is expected; the painter wins where both are given.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 */
@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Marks the connection whose ceiling is actually in force right now. */
@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

/** A continuous setting: label and current value on one line, track beneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            // Bare track: the step ticks and the end-stop dot are noise when the
            // value is already spelled out on the line above.
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

/** Sign out: centered, accent-coloured, no glyph — the shape of a real one. */
@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Sliding pill selector, for the handful of settings with two or three states. */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.outline)
            // Enough of a margin for the track to read as a track. At 2dp the
            // selected pill sat all but flush against the container's own edge,
            // so the two rounded rectangles merged into one shape and the
            // control stopped looking like something with a position in it.
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable(enabled = enabled) {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DynamicThemeVariantSelector(
    selectedVariant: String,
    customColors: List<Int>,
    onSelectVariant: (String) -> Unit,
    onAddCustomColor: () -> Unit,
    onRemoveCustomColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val pillShape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Cover Art Option (Default)
        val isCoverArtSelected = selectedVariant == DYNAMIC_VARIANT_COVER_ART
        val rainbowBorder = remember {
            Brush.sweepGradient(
                listOf(
                    Color(0xFFE11D48),
                    Color(0xFFF59E0B),
                    Color(0xFF10B981),
                    Color(0xFF0EA5E9),
                    Color(0xFF8B5CF6),
                    Color(0xFFEC4899),
                    Color(0xFFE11D48),
                ),
            )
        }
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(pillShape)
                .background(
                    if (isCoverArtSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                )
                .border(
                    width = if (isCoverArtSelected) 2.dp else 1.dp,
                    brush = if (isCoverArtSelected) rainbowBorder else Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f)),
                    ),
                    shape = pillShape,
                )
                .clickable { onSelectVariant(DYNAMIC_VARIANT_COVER_ART) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = if (isCoverArtSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Album Cover",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isCoverArtSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isCoverArtSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // 2. Built-in Premium Preset Variants
        PremiumDynamicThemeVariants.forEach { preset ->
            val isSelected = selectedVariant == preset.id
            ThemeVariantCircleSwatch(
                color = preset.color,
                label = preset.name,
                isSelected = isSelected,
                onClick = { onSelectVariant(preset.id) },
            )
        }

        // 3. User Custom Colors
        customColors.forEach { colorArgb ->
            val customId = "CUSTOM_$colorArgb"
            val isSelected = selectedVariant == customId
            ThemeVariantCircleSwatch(
                color = Color(colorArgb),
                label = "Custom",
                isSelected = isSelected,
                onClick = { onSelectVariant(customId) },
                onDelete = { onRemoveCustomColor(colorArgb) },
            )
        }

        // 4. + Add Custom Chip
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(pillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    shape = pillShape,
                )
                .clickable(onClick = onAddCustomColor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Custom",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ThemeVariantCircleSwatch(
    color: Color,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .shadow(
                elevation = if (isSelected) 4.dp else 1.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = color.copy(alpha = 0.35f),
                spotColor = color.copy(alpha = 0.50f),
            )
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = label,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CustomColorPickerDialog(
    onDismiss: () -> Unit,
    onColorAdded: (Int) -> Unit,
) {
    var red by remember { mutableFloatStateOf(160f) }
    var green by remember { mutableFloatStateOf(80f) }
    var blue by remember { mutableFloatStateOf(240f) }
    var hexText by remember {
        mutableStateOf(
            String.format(Locale.ROOT, "%02X%02X%02X", 160, 80, 240),
        )
    }

    fun updateFromRgb(r: Float, g: Float, b: Float) {
        red = r
        green = g
        blue = b
        hexText = String.format(Locale.ROOT, "%02X%02X%02X", r.roundToInt(), g.roundToInt(), b.roundToInt())
    }

    val previewColor = remember(red, green, blue) {
        Color(red.roundToInt(), green.roundToInt(), blue.roundToInt())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Custom Adaptive Color",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Color Preview Box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .shadow(4.dp, CircleShape)
                            .clip(CircleShape)
                            .background(previewColor)
                            .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Color Hex Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "#$hexText",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Sliders for R, G, B
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Red",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFFEF4444),
                        )
                        Text(
                            text = "${red.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Slider(
                        value = red,
                        onValueChange = { updateFromRgb(it, green, blue) },
                        valueRange = 0f..255f,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Green",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFF10B981),
                        )
                        Text(
                            text = "${green.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Slider(
                        value = green,
                        onValueChange = { updateFromRgb(red, it, blue) },
                        valueRange = 0f..255f,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Blue",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFF3B82F6),
                        )
                        Text(
                            text = "${blue.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Slider(
                        value = blue,
                        onValueChange = { updateFromRgb(red, green, it) },
                        valueRange = 0f..255f,
                    )
                }

                // Hex text input field
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val clean = input.removePrefix("#").take(6).uppercase(Locale.ROOT)
                        hexText = clean
                        if (clean.length == 6) {
                            runCatching {
                                val r = clean.substring(0, 2).toInt(16)
                                val g = clean.substring(2, 4).toInt(16)
                                val b = clean.substring(4, 6).toInt(16)
                                red = r.toFloat()
                                green = g.toFloat()
                                blue = b.toFloat()
                            }
                        }
                    },
                    label = { Text("Hex Code") },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onColorAdded(previewColor.toArgb())
                    onDismiss()
                },
            ) {
                Text("Add Color", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
