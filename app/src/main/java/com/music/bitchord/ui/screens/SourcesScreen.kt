package com.music.bitchord.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.music.bitchord.playback.audio.LosslessOutput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.ExtensionRepoManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.smb.SmbRepository
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceHealthTracker
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceRuntimeStatus
import com.music.bitchord.ui.components.AddonEditorAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Where the app is allowed to get audio from.
 *
 * The order is fixed rather than something to argue with: the addons a user
 * added are tried first, because they are the sources chosen on purpose, and
 * YouTube Music is tried last, since it needs no setup and has the full
 * catalogue behind it. Nothing on this screen downloads code, and nothing on
 * it can teach the app a new way to behave after it has shipped — an addon
 * answers questions with JSON, and this app is the only thing here running
 * anything.
 */
@Composable
fun SourcesScreen(
    contentPadding: PaddingValues,
    /**
     * Asks the activity to open the editor for a source — or for a new one,
     * when handed a config the registry does not have yet.
     *
     * Raised rather than drawn here, and not as a style preference: this screen
     * sits inside the subtree carrying `hazeSource`, so a frosted card drawn
     * from within it is part of the very layer it samples and comes out with no
     * background at all. Hosting it at the activity — where every other alert
     * in this app already lives — is also what lets the scrim cover the tab bar
     * and the mini player.
     */
    onEditSource: (SourceConfig) -> Unit,
    /**
     * Asks the activity to open the WebDAV editor — raised for the same
     * reason as [onEditSource]: the frosted alert cannot be drawn from inside
     * this subtree.
     */
    onEditWebDav: () -> Unit,
    /** As [onEditWebDav], for the SMB share editor. */
    onEditSmb: () -> Unit,
    /** Opens the full-window warning before JioSaavn is opted into. */
    onConfirmJioSaavn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val upgradeLengthSlackSeconds by AppSettings.upgradeLengthSlackSeconds.collectAsStateWithLifecycle()
    val webdavUrl by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val webdavUsername by AppSettings.webdavUsername.collectAsStateWithLifecycle()
    val webdavPassword by AppSettings.webdavPassword.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val smbUsername by AppSettings.smbUsername.collectAsStateWithLifecycle()
    val smbPassword by AppSettings.smbPassword.collectAsStateWithLifecycle()

    val allRepoExtensions by ExtensionRepoManager.extensions.collectAsStateWithLifecycle()
    val installedExtensionIds by ExtensionRepoManager.installedIds.collectAsStateWithLifecycle()
    val installedVersions by ExtensionRepoManager.installedVersions.collectAsStateWithLifecycle()
    val autoUpdatedIds by ExtensionRepoManager.autoUpdatedIds.collectAsStateWithLifecycle()
    val runtimeStates by SourceHealthTracker.states.collectAsStateWithLifecycle()
    val metadataStates by com.music.bitchord.data.MetadataDiscoveryProvider.providerStates.collectAsStateWithLifecycle()
    val cfEpoch by com.music.bitchord.data.CloudflareSessionManager.verificationEpoch.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var updatingExtIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Last known reachability per source, filled in as the probes come back. */
    val health = remember { mutableStateMapOf<String, SourceHealth>() }
    val losslessOutput by LosslessOutput.state.collectAsStateWithLifecycle()

    LaunchedEffect(cfEpoch) {
        if (cfEpoch > 0L) {
            SourceRegistry.downloadableExtensionSources().forEach { src ->
                SourceHealthTracker.clear(src.id)
                health.remove(src.id)
            }
        }
    }

    // Live GitHub silent auto-update check whenever Sources opens (throttled to 30m)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ExtensionRepoManager.refreshRegistry(context, autoUpdate = true, respectThrottle = true)
        }
    }

    // Live Metadata & Home Discovery provider probe when Sources opens or metadata toggles change
    val metadataProbeKey = configs.filter { it.kind == SourceKind.EXTENSION && it.metadataOnly && it.enabled }
        .joinToString { it.id }
    LaunchedEffect(metadataProbeKey) {
        withContext(Dispatchers.IO) {
            com.music.bitchord.data.MetadataDiscoveryProvider.probeMetadataProviders()
        }
    }

    // Probe enabled audio-capable addons and synced extensions concurrently (skip metadata-only)
    val probeKey = configs.filter { (it.kind.needsServer || (it.kind == SourceKind.EXTENSION && (it.enabled || it.isDownloadEnabled))) && !it.metadataOnly }
        .joinToString { "${it.id}@${it.baseUrl}@${it.enabled}@${it.downloadEnabled}@${it.checkValidLossless}" } + "@${losslessOutput.capable}@$cfEpoch"
    LaunchedEffect(probeKey) {
        coroutineScope {
            configs.filter {
                !it.metadataOnly &&
                    ((it.kind.needsServer && it.isComplete) || (it.kind == SourceKind.EXTENSION && (it.enabled || it.isDownloadEnabled))) &&
                    !it.awaitsLosslessOutput &&
                    !SourceHealthTracker.isHealthFresh(it.id)
            }.forEach { config ->
                launch {
                    val source = SourceRegistry.instance(config.id) ?: return@launch
                    val startMs = System.currentTimeMillis()
                    val probed = withContext(Dispatchers.IO) {
                        runCatching { source.health() }
                            .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
                    }
                    health[config.id] = probed
                    val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    when (probed) {
                        is SourceHealth.Ok -> {
                            if (SourceHealthTracker.statusOf(config.id) !is SourceRuntimeStatus.Ready) {
                                SourceHealthTracker.recordReady(config.id, latencyMs)
                            }
                        }
                        is SourceHealth.Rejected -> SourceHealthTracker.recordError(config.id, probed.reason)
                        is SourceHealth.Unreachable -> SourceHealthTracker.recordError(config.id, probed.reason)
                    }
                    if (source is AddonSource) {
                        val isUmtifyHq = config.id == SourceRegistry.UMTIFY_HQ_ADDON_ID || config.baseUrl.contains("umtifyhq")
                        val named = if (isUmtifyHq) "UMtify" else withContext(Dispatchers.IO) { runCatching { source.manifestName() }.getOrNull() }
                        if (named != null && named != config.label) {
                            SourceRegistry.update(config.copy(label = named))
                        }
                    }
                }
            }
        }
    }

    val ceiling = if (metered == true) cellularQuality else wifiQuality
    val cappedByQuality = ceiling != AudioQuality.LOSSLESS
    val streamingSources = remember(configs) { SourceRegistry.streamingGroupConfigs(configs) }
    val downloadSources = remember(configs) {
        SourceRegistry.downloadGroupConfigs(configs).sortedWith(
            compareBy<SourceConfig>(
                {
                    when {
                        it.id == SourceRegistry.UMTIFY_HQ_ADDON_ID || it.baseUrl.contains("umtifyhq") -> 0
                        it.kind == SourceKind.ADDON -> 1
                        it.kind == SourceKind.EXTENSION -> 2
                        it.kind == SourceKind.JIOSAAVN -> 3
                        it.kind == SourceKind.YOUTUBE -> 4
                        else -> 5
                    }
                },
                { it.displayName.lowercase() },
            ),
        )
    }
    val metadataSources = remember(configs) {
        SourceRegistry.metadataGroupConfigs(configs).sortedBy { it.displayName.lowercase() }
    }
    val anyLosslessSource = streamingSources.any { it.enabled && it.isComplete && it.kind.canServeLossless }
    val extensionsById = remember(allRepoExtensions) { allRepoExtensions.associateBy { it.id } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.source),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 72.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        // Group 1 — AUDIO STREAMING (TRIED IN THIS ORDER) (Draggable; only direct audio/FLAC streaming sources)
        SettingsGroup(
            header = "AUDIO STREAMING (TRIED IN THIS ORDER)",
            footer = buildString {
                append(stringResource(R.string.sources_order_footer))
                if (cappedByQuality) {
                    append("\n\n")
                    append(stringResource(R.string.sources_quality_capped))
                } else if (!anyLosslessSource) {
                    append("\n\n")
                    append(stringResource(R.string.sources_no_lossless))
                }
            },
        ) {
            ReorderableSources(
                sources = streamingSources,
                onReorder = { SourceRegistry.reorder(it.map(SourceConfig::id)) },
                row = { position, config, handle ->
                    ManagedSourceRow(
                        position = position,
                        config = config,
                        health = health[config.id],
                        runtimeStatus = runtimeStates[config.id],
                        extensionsById = extensionsById,
                        installedExtensionIds = installedExtensionIds,
                        installedVersions = installedVersions,
                        autoUpdatedIds = autoUpdatedIds,
                        metadataStates = metadataStates,
                        updatingExtIds = updatingExtIds,
                        onSetUpdating = { extId, busy ->
                            updatingExtIds = if (busy) updatingExtIds + extId else updatingExtIds - extId
                        },
                        losslessOutput = losslessOutput,
                        metered = metered == true,
                        ceiling = ceiling,
                        onEditSource = onEditSource,
                        onConfirmJioSaavn = onConfirmJioSaavn,
                        handle = handle,
                        applyPlaybackQualityGate = true,
                    )
                },
            )

            val waiting = streamingSources.any { it.enabled && it.awaitsLosslessOutput }
            if (waiting && losslessOutput.bluetoothCodecUnknown) {
                BluetoothCodecPermissionRow()
                RowDivider()
            }

            AddSourceRow(
                onClick = { onEditSource(SourceConfig(kind = SourceKind.ADDON)) },
            )
        }

        // Group 2 — DOWNLOAD PROVIDERS
        if (downloadSources.isNotEmpty()) {
            SettingsGroup(
                header = "DOWNLOAD PROVIDERS",
                footer = "Select Audio Format & Size strictly queries enabled providers in this list.",
            ) {
                downloadSources.forEachIndexed { index, config ->
                    val extId = if (config.kind == SourceKind.EXTENSION) {
                        SourceRegistry.extensionIdFor(config.id)
                    } else null
                    ManagedSourceRow(
                        position = index + 1,
                        config = config,
                        health = health[config.id],
                        runtimeStatus = runtimeStates[config.id],
                        extensionsById = extensionsById,
                        installedExtensionIds = installedExtensionIds,
                        installedVersions = installedVersions,
                        autoUpdatedIds = autoUpdatedIds,
                        metadataStates = metadataStates,
                        updatingExtIds = updatingExtIds,
                        onSetUpdating = { id, busy ->
                            updatingExtIds = if (busy) updatingExtIds + id else updatingExtIds - id
                        },
                        losslessOutput = losslessOutput,
                        metered = metered == true,
                        ceiling = ceiling,
                        onEditSource = onEditSource,
                        onConfirmJioSaavn = onConfirmJioSaavn,
                        checkedOverride = config.isDownloadEnabled,
                        applyPlaybackQualityGate = false,
                        allowYoutubeToggle = true,
                        showStatusPill = true,
                        isDownloadRow = true,
                        onToggleOverride = { enabled ->
                            SourceRegistry.setDownloadEnabled(config.id, enabled)
                            if (extId != null && enabled && extId !in installedExtensionIds) {
                                ExtensionRepoManager.toggleExtension(extId, true, syncSourceRegistry = false)
                            }
                        },
                    )
                    if (index < downloadSources.lastIndex) {
                        RowDivider()
                    }
                }
            }
        }

        // Group 3 — METADATA & HOME DISCOVERY
        if (metadataSources.isNotEmpty()) {
            SettingsGroup(
                header = "METADATA & HOME DISCOVERY",
                footer = "Enriches Home recommendations, charts, artist portraits, and lyrics",
            ) {
                metadataSources.forEachIndexed { index, config ->
                    val extId = SourceRegistry.extensionIdFor(config.id) ?: config.id
                    ManagedSourceRow(
                        position = index + 1,
                        config = config,
                        health = health[config.id],
                        runtimeStatus = runtimeStates[config.id],
                        extensionsById = extensionsById,
                        installedExtensionIds = installedExtensionIds,
                        installedVersions = installedVersions,
                        autoUpdatedIds = autoUpdatedIds,
                        metadataStates = metadataStates,
                        updatingExtIds = updatingExtIds,
                        onSetUpdating = { id, busy ->
                            updatingExtIds = if (busy) updatingExtIds + id else updatingExtIds - id
                        },
                        losslessOutput = losslessOutput,
                        metered = metered == true,
                        ceiling = ceiling,
                        onEditSource = onEditSource,
                        onConfirmJioSaavn = onConfirmJioSaavn,
                        applyPlaybackQualityGate = false,
                        showStatusPill = true,
                        onToggleOverride = { enabled ->
                            ExtensionRepoManager.toggleExtension(extId, enabled, syncSourceRegistry = true)
                            if (enabled) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    com.music.bitchord.data.MetadataDiscoveryProvider.probeMetadataProviders(force = true)
                                    com.music.bitchord.data.MetadataDiscoveryProvider.refreshChartDiscoveryShelf()
                                }
                            }
                        },
                    )
                    if (index < metadataSources.lastIndex) {
                        RowDivider()
                    }
                }
            }
        }

        // A personal library rather than a catalogue in the order above: it
        // holds the listener's own files instead of answering for YouTube's,
        // so it takes no part in the ranking and gets its own group. The
        // editor is raised to the activity like every other alert.
        RemoteLibrarySection(
            icon = Icons.Rounded.Cloud,
            title = stringResource(R.string.webdav),
            description = stringResource(R.string.webdav_description),
            subtitle = if (webdavUrl.isBlank()) {
                stringResource(R.string.webdav_not_configured)
            } else if (webdavUsername.isNotBlank()) {
                "$webdavUrl · $webdavUsername"
            } else {
                webdavUrl
            },
            listSubtitle = stringResource(R.string.webdav_subtitle),
            disconnectTitle = stringResource(R.string.webdav_disconnect),
            configured = webdavUrl.isNotBlank(),
            testFailedRes = R.string.webdav_test_failed,
            onEdit = onEditWebDav,
            onTest = {
                WebDavRepository.testConnection(webdavUrl, webdavUsername, webdavPassword)
            },
            onDisconnect = { AppSettings.clearWebDav() },
        )

        // A personal library on a file share, filed next to the WebDAV one
        // for the same reason: it holds the listener's own files instead of
        // answering for YouTube's, so it takes no part in the ranking above.
        RemoteLibrarySection(
            icon = Icons.Rounded.Storage,
            title = stringResource(R.string.smb),
            description = stringResource(R.string.smb_description),
            subtitle = if (smbHost.isBlank() || smbShare.isBlank()) {
                stringResource(R.string.smb_not_configured)
            } else if (smbUsername.isNotBlank()) {
                "$smbHost · $smbShare · $smbUsername"
            } else {
                "$smbHost · $smbShare"
            },
            listSubtitle = stringResource(R.string.smb_subtitle),
            disconnectTitle = stringResource(R.string.smb_disconnect),
            configured = smbHost.isNotBlank() && smbShare.isNotBlank(),
            testFailedRes = R.string.smb_test_failed,
            onEdit = onEditSmb,
            onTest = {
                SmbRepository.testConnection(
                    smbHost,
                    smbShare,
                    AppSettings.smbBasePath.value,
                    smbUsername,
                    smbPassword,
                )
            },
            onDisconnect = { AppSettings.clearSmb() },
        )

        SettingsGroup(
            header = stringResource(R.string.source_matching),
            footer = stringResource(R.string.upgrade_length_slack_footer),
        ) {
            SliderRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.upgrade_length_slack),
                subtitle = stringResource(R.string.upgrade_length_slack_subtitle),
                value = stringResource(R.string.seconds_short, upgradeLengthSlackSeconds),
                sliderValue = upgradeLengthSlackSeconds.toFloat(),
                onSliderValue = {
                    AppSettings.setUpgradeLengthSlackSeconds(it.roundToInt())
                },
                valueRange = AppSettings.MIN_UPGRADE_LENGTH_SLACK_SECONDS.toFloat()..
                    AppSettings.MAX_UPGRADE_LENGTH_SLACK_SECONDS.toFloat(),
                steps = AppSettings.MAX_UPGRADE_LENGTH_SLACK_SECONDS -
                    AppSettings.MIN_UPGRADE_LENGTH_SLACK_SECONDS - 1,
            )
        }

        Spacer(Modifier.height(32.dp))
    }

}


/**
 * A personal library section: the server row, plus test and disconnect rows
 * once something is configured. Shared by WebDAV and SMB, which differ only
 * in what "configured" means and which repository answers the test — the
 * test state lives here rather than in the screen, so each section tracks
 * its own result.
 */
@Composable
private fun RemoteLibrarySection(
    icon: ImageVector,
    title: String,
    description: String,
    subtitle: String,
    listSubtitle: String,
    disconnectTitle: String,
    configured: Boolean,
    testFailedRes: Int,
    onEdit: () -> Unit,
    onTest: suspend () -> Result<Unit>,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    SettingsGroup(header = title, footer = description) {
        SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onEdit)
        if (configured) {
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Dns,
                title = if (testing) {
                    stringResource(R.string.testing)
                } else {
                    testResult ?: stringResource(R.string.test)
                },
                subtitle = listSubtitle,
                onClick = {
                    if (testing) return@SettingsRow
                    testing = true
                    testResult = null
                    scope.launch {
                        onTest().fold(
                            onSuccess = {
                                testResult = context.getString(R.string.connected)
                            },
                            onFailure = {
                                testResult = context.getString(
                                    testFailedRes,
                                    it.message ?: context.getString(R.string.failed),
                                )
                            },
                        )
                        testing = false
                    }
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = disconnectTitle,
                onClick = {
                    onDisconnect()
                    testResult = null
                },
            )
        }
    }
}
/**
 * The addon rows, draggable by their handles to set which is asked first.
 *
 * The mechanics are [LyricsSourcesDialog][com.music.bitchord.ui.components.LyricsSourcesDialog]'s,
 * because that list solved the same problem first and its notes are worth
 * reading: the gesture keeps only how far the finger has travelled and which
 * slot it started on, and both where the row is drawn and which slot it
 * occupies are derived from those two numbers, so they cannot drift apart
 * however many swaps happen along the way.
 *
 * Two things differ here. The list is on a vertically scrolling screen rather
 * than in a dialog, which is why the drag lives on the handle alone —
 * [detectDragGestures] there consumes the vertical drag before the scroll
 * container sees it, while a drag anywhere on the row would make the list
 * impossible to scroll past. And the order is only written back when the
 * gesture ends: [SourceRegistry.reorderAddons] persists to encrypted prefs and
 * rebuilds the source instances, which is not work to do on every frame of a
 * drag.
 *
 * No handle is drawn for a single addon. There is nothing to reorder, and a
 * grip that cannot move anything is a control that lies.
 */
@Composable
private fun ReorderableSources(
    sources: List<SourceConfig>,
    onReorder: (List<SourceConfig>) -> Unit,
    row: @Composable (Int, SourceConfig, (@Composable () -> Unit)?) -> Unit,
) {
    if (sources.isEmpty()) return
    if (sources.size == 1) {
        row(1, sources.first(), null)
        RowDivider()
        return
    }

    var liveOrder by remember { mutableStateOf(sources) }
    var dragged by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sources) {
        if (dragged == null) {
            liveOrder = sources
        }
    }

    var totalDrag by remember { mutableFloatStateOf(0f) }
    var startTopPx by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<String, Float>() }
    val defaultHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }

    fun heightOf(id: String): Float = rowHeights[id] ?: defaultHeightPx

    fun topOf(list: List<SourceConfig>, idx: Int): Float {
        var sum = 0f
        for (i in 0 until idx.coerceIn(0, list.size)) {
            sum += heightOf(list[i].id)
        }
        return sum
    }

    fun moveItem(list: List<SourceConfig>, fromIndex: Int, toIndex: Int): List<SourceConfig> {
        val clampedTo = toIndex.coerceIn(0, list.lastIndex)
        if (fromIndex == clampedTo || fromIndex !in list.indices) return list
        val updated = list.toMutableList()
        val movedItem = updated.removeAt(fromIndex)
        updated.add(clampedTo, movedItem)
        return updated
    }

    liveOrder.forEachIndexed { index, config ->
        key(config.id) {
            val isDragging = config.id == dragged
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 5f else 0f)
                    .onSizeChanged { rowHeights[config.id] = it.height.toFloat() }
                    .graphicsLayer {
                        scaleX = if (isDragging) 1.03f else 1f
                        scaleY = if (isDragging) 1.03f else 1f
                        shadowElevation = if (isDragging) 16f else 0f
                        translationY = if (isDragging) {
                            val currentIdx = liveOrder.indexOfFirst { it.id == config.id }.coerceAtLeast(0)
                            (startTopPx + totalDrag) - topOf(liveOrder, currentIdx)
                        } else {
                            0f
                        }
                    },
            ) {
                row(index + 1, config) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .pointerInput(config.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        val startIdx = liveOrder.indexOfFirst { it.id == config.id }.coerceAtLeast(0)
                                        dragged = config.id
                                        totalDrag = 0f
                                        startTopPx = topOf(liveOrder, startIdx)
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        val fromIdx = liveOrder.indexOfFirst { it.id == config.id }
                                        if (fromIdx < 0) return@detectDragGestures

                                        val totalListHeight = topOf(liveOrder, liveOrder.size)
                                        val draggedH = heightOf(config.id)
                                        val minTop = 0f
                                        val maxTop = (totalListHeight - draggedH).coerceAtLeast(0f)
                                        val minDrag = minTop - startTopPx
                                        val maxDrag = maxTop - startTopPx
                                        totalDrag = (totalDrag + delta.y).coerceIn(minDrag, maxDrag)

                                        val draggedCenterY = startTopPx + totalDrag + draggedH * 0.5f
                                        var at = fromIdx
                                        while (at < liveOrder.lastIndex) {
                                            val nextH = heightOf(liveOrder[at + 1].id)
                                            val nextThreshold = topOf(liveOrder, at) + draggedH + nextH * 0.5f
                                            if (draggedCenterY > nextThreshold) {
                                                liveOrder = moveItem(liveOrder, at, at + 1)
                                                at++
                                            } else {
                                                break
                                            }
                                        }
                                        while (at > 0) {
                                            val prevTop = topOf(liveOrder, at - 1)
                                            val prevH = heightOf(liveOrder[at - 1].id)
                                            val prevThreshold = prevTop + prevH * 0.5f
                                            if (draggedCenterY < prevThreshold) {
                                                liveOrder = moveItem(liveOrder, at, at - 1)
                                                at--
                                            } else {
                                                break
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragged = null
                                        totalDrag = 0f
                                        onReorder(liveOrder)
                                    },
                                    onDragCancel = {
                                        dragged = null
                                        totalDrag = 0f
                                        liveOrder = sources
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DragHandle,
                            contentDescription = stringResource(R.string.drag_to_reorder),
                            tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                RowDivider()
            }
        }
    }
}

/**
 * The one row on this screen that adds something rather than describing
 * something.
 *
 * Deliberately at the bottom of the same group as the sources rather than off
 * in a section of its own: what it adds goes to the *top* of that list, and a
 * row sitting under the numbered ones is the clearest way to say "and you can
 * put another one in here".
 */
@Composable
private fun AddSourceRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Aligned with the numbers above it rather than with their icons, so
        // the plus reads as belonging to the same column the list is indexed by.
        Spacer(Modifier.width(24.dp))
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.add_addon),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.add_addon_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Asks for Nearby devices so the Bluetooth codec can be read — the only way an
 * LDAC, LHDC or SSC UHQ headset qualifies for an addon's `checkValidLossless`. Draws
 * nothing where the permission does not exist (before Android 12) or is
 * already granted, since asking again would do nothing.
 */
@Composable
private fun BluetoothCodecPermissionRow() {
    val context = LocalContext.current
    val permission = "android.permission.BLUETOOTH_CONNECT"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    fun isGranted() =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(isGranted()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) LosslessOutput.refresh()
    }
    // Back from system Settings, where it may have been switched on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = isGranted()
        if (now && !granted) LosslessOutput.refresh()
        granted = now
        if (now) denied = false
    }
    // Asked as soon as the screen shows the shut-out it would fix, and again
    // on every later visit, since this row is composed afresh each time. After
    // a second refusal Android answers without a dialog, which lands in
    // [denied] and the Settings prompt below.
    LaunchedEffect(Unit) {
        if (!isGranted()) ask.launch(permission)
    }
    if (granted) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (denied) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                } else {
                    ask.launch(permission)
                }
            }
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(24.dp))
        Icon(
            imageVector = Icons.Rounded.Bluetooth,
            contentDescription = null,
            tint = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (denied) R.string.lossless_output_permission_denied else R.string.lossless_output_permission,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (denied) R.string.lossless_output_permission_denied_detail else R.string.lossless_output_permission_detail,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ManagedSourceRow(
    position: Int,
    config: SourceConfig,
    health: SourceHealth?,
    runtimeStatus: SourceRuntimeStatus?,
    extensionsById: Map<String, com.music.bitchord.data.RepoExtension>,
    installedExtensionIds: Set<String>,
    installedVersions: Map<String, String>,
    autoUpdatedIds: Set<String>,
    metadataStates: Map<String, com.music.bitchord.data.MetadataDiscoveryProvider.MetadataProviderStatus>,
    updatingExtIds: Set<String>,
    onSetUpdating: (String, Boolean) -> Unit,
    losslessOutput: LosslessOutput.State,
    metered: Boolean,
    ceiling: AudioQuality,
    onEditSource: (SourceConfig) -> Unit,
    onConfirmJioSaavn: () -> Unit,
    handle: (@Composable () -> Unit)? = null,
    checkedOverride: Boolean? = null,
    applyPlaybackQualityGate: Boolean = true,
    allowYoutubeToggle: Boolean = false,
    showStatusPill: Boolean = true,
    isDownloadRow: Boolean = false,
    onToggleOverride: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val extId = if (config.kind == SourceKind.EXTENSION) {
        SourceRegistry.extensionIdFor(config.id)
    } else null
    val repoExt = extId?.let { extensionsById[it] }
    val isInstalled = extId != null && extId in installedExtensionIds
    val localVer = extId?.let { installedVersions[it] }
    val updateAvailable = repoExt != null && (isInstalled || config.metadataOnly) &&
        ExtensionRepoManager.isNewerVersion(repoExt.version, localVer ?: "1.0.0")
    val isUpdating = extId != null && extId in updatingExtIds
    val displayVer = localVer ?: repoExt?.version
    val versionStatusLabel = if (config.kind == SourceKind.EXTENSION && displayVer != null && !updateAvailable) {
        val statusSuffix = if (extId != null && extId in autoUpdatedIds) "Auto-updated" else "Up to date"
        "v$displayVer • $statusSuffix"
    } else null
    val metadataStatus = if (config.metadataOnly) {
        val key = com.music.bitchord.data.MetadataDiscoveryProvider.providerKeyFor("${extId ?: config.id} ${config.displayName}")
        metadataStates[key]
    } else null

    SourceRow(
        position = position,
        config = config,
        health = health,
        runtimeStatus = runtimeStatus,
        metadataStatus = metadataStatus,
        versionStatusLabel = versionStatusLabel,
        updateVersion = repoExt?.version?.takeIf { updateAvailable },
        isUpdating = isUpdating,
        onUpdateClick = if (extId != null && repoExt != null && updateAvailable) {
            {
                if (extId !in updatingExtIds) {
                    onSetUpdating(extId, true)
                    coroutineScope.launch {
                        ExtensionRepoManager.updateExtension(context, repoExt)
                        onSetUpdating(extId, false)
                    }
                }
            }
        } else null,
        checkedOverride = checkedOverride,
        showStatusPill = showStatusPill,
        isDownloadRow = isDownloadRow,
        skippedByQuality = applyPlaybackQualityGate && config.enabled && !ceiling.permits(config.kind),
        awaitingOutput = if (applyPlaybackQualityGate && config.enabled && config.awaitsLosslessOutput) losslessOutput else null,
        onMetered = metered,
        ceiling = ceiling,
        onClick = when {
            config.kind.needsServer -> ({ onEditSource(config) })
            isDownloadRow && extId != null && (
                (runtimeStatus is SourceRuntimeStatus.Error && (
                    runtimeStatus.reason.contains("Verification", ignoreCase = true) ||
                        runtimeStatus.reason.contains("Rate", ignoreCase = true) ||
                        runtimeStatus.reason.contains("403") ||
                        runtimeStatus.reason.contains("503")
                    )) ||
                    (health is SourceHealth.Unreachable && (
                        health.reason.contains("Verification", ignoreCase = true) ||
                            health.reason.contains("Rate", ignoreCase = true) ||
                            health.reason.contains("403") ||
                            health.reason.contains("503")
                        ))
                ) -> ({
                coroutineScope.launch {
                    val verifyUrl = com.music.bitchord.data.ExtensionRepoManager.getExtractedManifestJson(extId)
                        ?.let {
                            runCatching {
                                org.json.JSONObject(it).optJSONObject("signedSession")?.optString("baseUrl")
                            }.getOrNull()
                        }
                        ?.takeIf { it.isNotBlank() }
                        ?: "https://api.zarz.moe/v2/health"
                    val ok = com.music.bitchord.data.CloudflareSessionManager.requestVerification(
                        url = verifyUrl,
                        forceInteractive = true,
                    )
                    if (ok) {
                        com.music.bitchord.data.sources.SourceRegistry.downloadableExtensionSources().forEach { src ->
                            SourceHealthTracker.clear(src.id)
                        }
                    }
                }
            })
            else -> null
        },
        onToggle = when {
            onToggleOverride != null -> onToggleOverride
            config.kind == SourceKind.YOUTUBE && !allowYoutubeToggle -> null
            else -> { enabled ->
                if (config.kind == SourceKind.JIOSAAVN && enabled && !config.enabled) {
                    onConfirmJioSaavn()
                } else if (config.kind == SourceKind.EXTENSION && extId != null) {
                    ExtensionRepoManager.toggleExtension(extId, enabled, syncSourceRegistry = true)
                } else {
                    SourceRegistry.setEnabled(config.id, enabled)
                }
            }
        },
        handle = handle,
    )
}

@Composable
private fun SourceRow(
    position: Int,
    config: SourceConfig,
    health: SourceHealth?,
    runtimeStatus: SourceRuntimeStatus? = null,
    metadataStatus: com.music.bitchord.data.MetadataDiscoveryProvider.MetadataProviderStatus? = null,
    versionStatusLabel: String? = null,
    updateVersion: String? = null,
    isUpdating: Boolean = false,
    onUpdateClick: (() -> Unit)? = null,
    checkedOverride: Boolean? = null,
    showStatusPill: Boolean = true,
    isDownloadRow: Boolean = false,
    onClick: (() -> Unit)?,
    /** Null for a source that cannot be switched off, which gets a label instead. */
    onToggle: ((Boolean) -> Unit)?,
    /** On, but skipped by the ceiling the current connection is set to. */
    skippedByQuality: Boolean = false,
    /** On, but its addon needs a lossless output and this is what is connected instead. Null when not waiting. */
    awaitingOutput: LosslessOutput.State? = null,
    /** Which of the two ceilings [ceiling] is, so the row can name it. */
    onMetered: Boolean = false,
    ceiling: AudioQuality = AudioQuality.LOSSLESS,
    /**
     * The drag grip, for a row whose position is the user's to set. Passed in
     * rather than drawn here because the gesture belongs to the list that knows
     * the other rows — see [ReorderableAddons] — and null for every row whose
     * rank is fixed by its kind.
     */
    handle: (@Composable () -> Unit)? = null,
) {
    val isChecked = checkedOverride ?: config.enabled
    val dimmed = !isChecked || skippedByQuality || awaitingOutput != null
    val effectiveStatus: SourceRuntimeStatus = when {
        runtimeStatus is SourceRuntimeStatus.Error -> runtimeStatus
        runtimeStatus is SourceRuntimeStatus.Ready -> runtimeStatus
        health is SourceHealth.Rejected -> SourceRuntimeStatus.Error(health.reason)
        health is SourceHealth.Unreachable -> SourceRuntimeStatus.Error(health.reason)
        health is SourceHealth.Ok -> SourceRuntimeStatus.Ready(0L)
        else -> SourceRuntimeStatus.Idle
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(18.dp)
                .alpha(if (dimmed) 0.4f else 1f),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = when (config.kind) {
                SourceKind.ADDON, SourceKind.EXTENSION, SourceKind.CUSTOM_MODULE, SourceKind.MODULE -> Icons.Rounded.Extension
                SourceKind.INTERNET_ARCHIVE -> Icons.Rounded.Cloud
                SourceKind.JAMENDO -> Icons.Rounded.GraphicEq
                SourceKind.SOUNDCLOUD -> Icons.Rounded.GraphicEq
                SourceKind.JIOSAAVN -> Icons.Rounded.GraphicEq
                SourceKind.YOUTUBE -> Icons.Rounded.PlayCircle
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(ICON_SIZE)
                .alpha(if (dimmed) 0.4f else 1f),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(
            Modifier
                .weight(1f)
                .alpha(if (dimmed && effectiveStatus !is SourceRuntimeStatus.Error) 0.4f else 1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (updateVersion != null && onUpdateClick != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = !isUpdating, onClick = onUpdateClick),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Text(
                                text = if (isUpdating) "Updating..." else "Update v$updateVersion",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                } else if (versionStatusLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    ) {
                        Text(
                            text = versionStatusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = when {
                    awaitingOutput != null -> when {
                        awaitingOutput.bluetoothCodecUnknown ->
                            stringResource(R.string.source_needs_lossless_bt_unknown)
                        awaitingOutput.bluetoothCodec != null ->
                            stringResource(R.string.source_needs_lossless_bt_codec, awaitingOutput.bluetoothCodec)
                        else -> stringResource(R.string.source_needs_lossless_output)
                    }
                    skippedByQuality -> stringResource(
                        R.string.source_skipped_by_quality,
                        stringResource(if (onMetered) R.string.mobile_data else R.string.wifi),
                        ceiling.localizedLabel(),
                    )
                    else -> config.statusLine(health, isDownloadRow)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    awaitingOutput != null -> MaterialTheme.colorScheme.error
                    skippedByQuality -> MaterialTheme.colorScheme.onSurfaceVariant
                    health is SourceHealth.Rejected -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
            )

            // Live Error & Health Status Badge
            if (showStatusPill && config.metadataOnly) {
                if (isChecked) {
                    when {
                        metadataStatus?.ready == true -> {
                            Spacer(Modifier.height(4.dp))
                            val enrichedPart = if (metadataStatus.enrichedCount > 0) {
                                " • Enriched ${metadataStatus.enrichedCount} items"
                            } else {
                                ""
                            }
                            val latencyPart = if (metadataStatus.latencyMs > 0L) {
                                " • ${metadataStatus.latencyMs}ms"
                            } else {
                                ""
                            }
                            StatusBadgePill(
                                text = "Active$latencyPart$enrichedPart",
                                dotColor = Color(0xFF4CAF50),
                                containerColor = Color(0xFF4CAF50).copy(alpha = 0.14f),
                                textColor = Color(0xFF2E7D32),
                            )
                        }
                        metadataStatus?.error != null -> {
                            Spacer(Modifier.height(4.dp))
                            StatusBadgePill(
                                text = "Offline • ${metadataStatus.error}",
                                dotColor = MaterialTheme.colorScheme.error,
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                textColor = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else if (showStatusPill && isChecked && awaitingOutput == null && !skippedByQuality) {
                when (effectiveStatus) {
                    is SourceRuntimeStatus.Ready -> {
                        Spacer(Modifier.height(4.dp))
                        val label = if (effectiveStatus.latencyMs > 0L) {
                            "Active • ${effectiveStatus.latencyMs}ms"
                        } else {
                            "Active"
                        }
                        StatusBadgePill(
                            text = label,
                            dotColor = Color(0xFF4CAF50),
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.14f),
                            textColor = Color(0xFF2E7D32),
                        )
                    }
                    is SourceRuntimeStatus.Error -> {
                        Spacer(Modifier.height(4.dp))
                        val rawReason = effectiveStatus.reason.trim()
                        val errText = when {
                            rawReason.equals("Unavailable", ignoreCase = true) ||
                                rawReason.startsWith("Offline", ignoreCase = true) ||
                                rawReason.contains("401") ||
                                rawReason.contains("403") ||
                                rawReason.contains("404") -> "Unavailable"
                            else -> "Unavailable • $rawReason"
                        }
                        StatusBadgePill(
                            text = errText,
                            dotColor = MaterialTheme.colorScheme.error,
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            textColor = MaterialTheme.colorScheme.error,
                        )
                    }
                    SourceRuntimeStatus.Idle -> Unit
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (onToggle == null) {
            Text(
                text = stringResource(R.string.always_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Switch(
                checked = isChecked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        if (handle != null) {
            Spacer(Modifier.width(4.dp))
            handle()
        }
    }
}

@Composable
private fun StatusBadgePill(
    text: String,
    dotColor: Color,
    containerColor: Color,
    textColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    },
)

/** The second line of a row: what this source is, or what is wrong with it. */
@Composable
private fun SourceConfig.statusLine(health: SourceHealth?, isDownloadRow: Boolean = false): String = when {
    id == SourceRegistry.UMTIFY_HQ_ADDON_ID || baseUrl.contains("umtifyhq") -> {
        if (isDownloadRow) {
            if (isDownloadEnabled) "UMtify HQ · FLAC · Lossless · Downloads enabled" else "UMtify HQ · Disabled for downloads"
        } else {
            "UMtify HQ · FLAC · Lossless · Hi-Res"
        }
    }
    !isComplete -> stringResource(R.string.source_setup_required)
    kind == SourceKind.ADDON -> {
        if (isDownloadRow) {
            if (isDownloadEnabled) "Custom Addon · FLAC · Lossless · Downloads enabled" else "Custom Addon · Disabled for downloads"
        } else {
            (health as? SourceHealth.Ok)?.detail ?: "Custom Addon · FLAC · Lossless"
        }
    }
    kind == SourceKind.EXTENSION && metadataOnly -> "Charts, Artist Portraits & Lyrics"
    kind == SourceKind.EXTENSION && isDownloadRow -> "Download Provider · FLAC · Lossless"
    kind == SourceKind.EXTENSION -> "Audio Extension · FLAC · Lossless"
    kind == SourceKind.JIOSAAVN -> stringResource(R.string.jiosaavn_mismatch_warning)
    health is SourceHealth.Ok -> listOfNotNull(
        health.detail,
        kind.labels.take(3).joinToString(" · "),
        stringResource(R.string.source_downloads_off).takeIf { !allowDownloads && isDownloadRow },
    ).joinToString(" · ")
    health is SourceHealth.Rejected -> health.reason
    health is SourceHealth.Unreachable -> stringResource(R.string.source_unreachable, health.reason)
    kind.needsServer -> stringResource(R.string.checking)
    else -> kind.labels.take(3).joinToString(" · ")
}

/**
 * Add or edit a source that has an address.
 *
 * Test is offered rather than required: a server that happens to be asleep is
 * still worth saving, and refusing to store it until it answers would make
 * setting one up from a coffee shop impossible. What Test does buy is the
 * difference between "not answering" and "answering, but not with something
 * this app can use" — a manifest missing the `stream` resource is a mistake
 * worth hearing about before the first track rather than after it.
 */
@Composable
internal fun SourceEditorAlert(
    hazeState: HazeState,
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDelete: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val isNew = SourceRegistry.config(config.id) == null
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }

    val connected = stringResource(R.string.connected)
    val unreadable = stringResource(R.string.source_unrecognised)
    val alreadyAdded = stringResource(R.string.source_already_added)

    /**
     * Identify what is at the URL, then say so.
     *
     * Both buttons run this — the only difference is whether a success is then
     * stored. Testing and saving asking the *same* question is the point: a
     * Save that skipped identification could store a config whose kind was
     * guessed, and the guess is exactly what this screen no longer makes.
     */
    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val identified = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.identify(baseUrl.trim(), config.takeUnless { isNew }) }
                    .getOrElse { Result.failure(it) }
            }
            val found = identified.getOrNull()
            if (found == null) {
                statusIsGood = false
                status = identified.exceptionOrNull()?.message ?: unreadable
                busy = false
                return@launch
            }

            // Already here? Checked against the *identified* base rather than
            // the typed text, so an addon's root and its manifest.json are
            // recognised as the one source they are — which is the whole reason
            // this runs after identification and not before it. Reported on
            // Test as well as Save: finding out by pressing Test beats finding
            // out by ending up with the same catalogue searched twice on every
            // track.
            SourceRegistry.duplicateOf(found.baseUrl, exceptId = config.id.takeUnless { isNew })
                ?.let { existing ->
                    statusIsGood = false
                    status = String.format(alreadyAdded, existing.displayName)
                    busy = false
                    return@launch
                }

            // Identified, and now asked whether it actually works. The two are
            // different questions: a manifest can be perfectly well formed and
            // its server still be refusing every search.
            val health = withContext(Dispatchers.IO) {
                runCatching { SourceRegistry.probeCandidate(found) }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            statusIsGood = health.isOk
            status = when (health) {
                is SourceHealth.Ok -> listOfNotNull(
                    connected,
                    found.kind.label,
                    health.detail,
                ).joinToString(" · ")
                is SourceHealth.Rejected -> health.reason
                is SourceHealth.Unreachable -> health.reason
            }
            busy = false

            // Saved even when the probe came back unhappy, but only once the
            // format is known: a server that is asleep is still worth storing —
            // that was true before and is why Test was never mandatory — while
            // a URL nothing can be made of is not.
            if (thenSave) {
                if (isNew) SourceRegistry.add(found) else SourceRegistry.update(found)
                onSaved()
            }
        }
    }

    AddonEditorAlert(
        hazeState = hazeState,
        title = if (isNew) {
            stringResource(R.string.add_addon)
        } else {
            config.displayName
        },
        description = stringResource(R.string.addon_url_description),
        urlValue = baseUrl,
        // A result describes the address it was run against, so the moment that
        // address is edited it stops being true and is cleared. Left up, it
        // would report "Connected" over a URL nobody has tried.
        onUrlChange = { baseUrl = it; status = null },
        urlPlaceholder = "https://my-addon.example.com",
        status = status,
        statusIsGood = statusIsGood,
        testing = busy,
        canSubmit = baseUrl.isNotBlank(),
        onTest = { run(thenSave = false) },
        onSave = { run(thenSave = true) },
        onRemove = if (isNew) null else onDelete,
        onDismiss = onDismiss,
    )
}

