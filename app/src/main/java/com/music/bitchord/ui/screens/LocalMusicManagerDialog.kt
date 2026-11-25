package com.music.bitchord.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.DefaultCoverTile
import com.music.bitchord.ui.components.LocalArtworkImage
import com.music.bitchord.ui.components.isLocalArtUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicManagerDialog(
    onDismiss: () -> Unit,
    onAddFolder: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isBn = remember { Locale.getDefault().language == "bn" }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val scanAllAudioFolders by AppSettings.scanAllAudioFolders.collectAsStateWithLifecycle()
    val customFolders by AppSettings.localMusicCustomFolders.collectAsStateWithLifecycle()
    val localFolders by AppSettings.localMusicFolders.collectAsStateWithLifecycle()
    val singleFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val excludedSongs by AppSettings.localMusicExcludedSongs.collectAsStateWithLifecycle()
    val excludedFolders by AppSettings.localMusicExcludedFolders.collectAsStateWithLifecycle()
    val selectedSongs by AppSettings.localMusicSelectedSongs.collectAsStateWithLifecycle()

    val detectedFolders by LocalMediaRepository.detectedAudioFolders.collectAsStateWithLifecycle()
    val activeFolders by LocalMediaRepository.activeAudioFoldersFlow.collectAsStateWithLifecycle()
    val allLocalSongs by LocalMediaRepository.allLocalSongsFlow.collectAsStateWithLifecycle()

    var folderSongSearchQuery by remember { mutableStateOf("") }
    var drilledFolder by remember { mutableStateOf<LocalMediaRepository.DeviceFolder?>(null) }
    var loading by remember { mutableStateOf(false) }

    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch(Dispatchers.IO) {
                LocalMediaRepository.refreshLocalMusic(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (scanAllAudioFolders && !LocalMediaRepository.hasStoragePermission(context)) {
            storagePermissionLauncher.launch(storagePermission)
        }
        LocalMediaRepository.refreshLocalMusic(context)
    }

    // Display folders: clean, deduplicated, and unified with LocalMediaRepository
    val displayFolders = remember(activeFolders, customFolders, detectedFolders, scanAllAudioFolders, allLocalSongs, excludedFolders) {
        if (activeFolders.isNotEmpty()) activeFolders else LocalMediaRepository.getVisibleFolders(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141923),
        tonalElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = if (isBn) "লোকাল মিউজিক ম্যানেজার" else "Local Music Manager",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = if (isBn) "পছন্দের ফোল্ডার ও গান নির্বাচন করুন" else "Select folders & filter individual tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val currentDrill = drilledFolder
            if (currentDrill != null) {
                // Folder Drill-Down View (Songs checklist)
                var localFolderSongs by remember(currentDrill) { mutableStateOf<List<Song>>(emptyList()) }

                LaunchedEffect(currentDrill, allLocalSongs) {
                    val inMemory = allLocalSongs.filter {
                        it.folderUri == currentDrill.path ||
                        LocalMediaRepository.isInSelectedFolder(it.localPath, currentDrill.path) ||
                        LocalMediaRepository.isInSelectedFolder(it.localUri, currentDrill.path)
                    }
                    if (inMemory.isNotEmpty()) {
                        localFolderSongs = inMemory
                    } else {
                        withContext(Dispatchers.IO) {
                            val loaded = if (currentDrill.path.startsWith("content://")) {
                                LocalMediaRepository.scanSafFolderFast(context, currentDrill.path)
                            } else {
                                LocalMediaRepository.getAllLocalSongsRaw(context).filter {
                                    it.folderUri == currentDrill.path ||
                                    LocalMediaRepository.isInSelectedFolder(it.localPath, currentDrill.path) ||
                                    LocalMediaRepository.isInSelectedFolder(it.localUri, currentDrill.path)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                localFolderSongs = loaded
                            }
                        }
                    }
                }

                val filteredFolderSongs = remember(localFolderSongs, folderSongSearchQuery) {
                    if (folderSongSearchQuery.isBlank()) localFolderSongs else {
                        localFolderSongs.filter {
                            it.title.contains(folderSongSearchQuery, ignoreCase = true) ||
                            it.artist.contains(folderSongSearchQuery, ignoreCase = true)
                        }
                    }
                }

                // Drill-Down Top Navigation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(
                        onClick = {
                            drilledFolder = null
                            folderSongSearchQuery = ""
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Folders",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentDrill.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${localFolderSongs.size} ${if (isBn) "টি গান" else "tracks"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Folder Song Search
                OutlinedTextField(
                    value = folderSongSearchQuery,
                    onValueChange = { folderSongSearchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)),
                    placeholder = {
                        Text(
                            if (isBn) "এই ফোল্ডারের গান খুঁজুন..." else "Search tracks in this folder...",
                            color = Color.White.copy(alpha = 0.45f),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                )

                Spacer(Modifier.height(8.dp))

                // Actions: Select All / Deselect All in this folder
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${filteredFolderSongs.size} ${if (isBn) "টি গান" else "tracks"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.60f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val keys = filteredFolderSongs.flatMap { listOfNotNull(it.localPath, it.localUri, it.videoId) }
                                AppSettings.includeLocalSongs(keys)
                                scope.launch(Dispatchers.IO) {
                                    LocalMediaRepository.refreshLocalMusic(context)
                                }
                            },
                        ) {
                            Text(
                                if (isBn) "সব চালু" else "Select All",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(
                            onClick = {
                                val keys = filteredFolderSongs.flatMap { listOfNotNull(it.localPath, it.localUri, it.videoId) }
                                AppSettings.excludeLocalSongs(keys)
                                scope.launch(Dispatchers.IO) {
                                    LocalMediaRepository.refreshLocalMusic(context)
                                }
                            },
                        ) {
                            Text(
                                if (isBn) "সব বাদ" else "Deselect All",
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                // List of songs in the drilled folder
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filteredFolderSongs, key = { it.localPath ?: it.localUri ?: it.videoId }) { song ->
                        val songKeys = setOfNotNull(song.localPath, song.localUri, song.videoId)
                        val isIncluded = songKeys.none { it in excludedSongs } &&
                            (selectedSongs.isEmpty() || songKeys.any { it in selectedSongs })

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = if (isIncluded) 0.07f else 0.02f))
                                .border(0.6.dp, Color.White.copy(alpha = if (isIncluded) 0.20f else 0.06f), RoundedCornerShape(14.dp))
                                .clickable {
                                    if (isIncluded) {
                                        AppSettings.excludeLocalSongs(songKeys)
                                        LocalMediaRepository.removeSong(context, song)
                                    } else {
                                        AppSettings.includeLocalSongs(songKeys)
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        LocalMediaRepository.refreshLocalMusic(context)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White.copy(alpha = 0.08f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val songArt = song.thumbnailUrl
                                        val localUri = song.localUri ?: song.localPath
                                        val isLocal = isLocalArtUrl(songArt) || !localUri.isNullOrBlank()
                                        if (isLocal) {
                                            LocalArtworkImage(
                                                url = songArt ?: localUri.orEmpty(),
                                                localUri = localUri,
                                                title = song.title,
                                                artist = song.artist,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                                maxSizePx = 96,
                                                fallback = {
                                                    DefaultCoverTile(
                                                        modifier = Modifier.fillMaxSize(),
                                                        title = song.title,
                                                    )
                                                },
                                            )
                                        } else if (!songArt.isNullOrBlank()) {
                                            AsyncImage(
                                                model = songArt,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            DefaultCoverTile(
                                                modifier = Modifier.fillMaxSize(),
                                                title = song.title,
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = song.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isIncluded) Color.White else Color.White.copy(alpha = 0.45f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${song.artist}${if (!song.durationText.isNullOrBlank()) " · ${song.durationText}" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.45f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = isIncluded,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            AppSettings.includeLocalSongs(songKeys)
                                        } else {
                                            AppSettings.excludeLocalSongs(songKeys)
                                            LocalMediaRepository.removeSong(context, song)
                                        }
                                        scope.launch(Dispatchers.IO) {
                                            LocalMediaRepository.refreshLocalMusic(context)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        checkmarkColor = Color.White,
                                    ),
                                )
                            }
                        }
                    }
                }
            } else {
                // "All audio folders" Master Switch
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                        .clickable {
                            val newScanAll = !scanAllAudioFolders
                            AppSettings.setScanAllAudioFolders(newScanAll)
                            if (newScanAll && !LocalMediaRepository.hasStoragePermission(context)) {
                                storagePermissionLauncher.launch(storagePermission)
                            }
                            scope.launch(Dispatchers.IO) {
                                LocalMediaRepository.refreshLocalMusic(context)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.all_audio_folders),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                Text(
                                    text = if (isBn) "সকল ডিভাইস ফোল্ডার স্ক্যান করবে" else "Scan and include all device audio folders",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.60f),
                                )
                            }
                        }
                        Switch(
                            checked = scanAllAudioFolders,
                            onCheckedChange = { checked ->
                                AppSettings.setScanAllAudioFolders(checked)
                                if (checked) {
                                    AppSettings.setRemovedAudioFolders(emptySet())
                                    AppSettings.setLocalMusicExcludedFolders(emptySet())
                                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                                        storagePermissionLauncher.launch(storagePermission)
                                    }
                                }
                                scope.launch(Dispatchers.IO) {
                                    LocalMediaRepository.refreshLocalMusic(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = if (scanAllAudioFolders) {
                        if (isBn) "সকল অডিও ফোল্ডারসমূহ" else "Audio Folders"
                    } else {
                        if (isBn) "যুক্ত করা ফোল্ডারসমূহ" else "Selected Storage Folders"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.50f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )

                // List of folders
                if (displayFolders.isEmpty() && !loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isBn) "কোনো অডিও ফোল্ডার সক্রিয় নেই। আপনার ডিভাইসের গান স্ক্যান করতে উপরে 'সকল অডিও ফোল্ডার' চালু করুন।"
                            else "No audio folders active. Toggle 'All audio folders' above to scan your device music.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(displayFolders, key = { it.path }) { folder ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(0.6.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { drilledFolder = folder },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = folder.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${folder.songCount} ${if (isBn) "টি গান" else "tracks"} · ${if (isBn) "গান দেখতে স্পর্শ করুন" else "tap to view tracks"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White.copy(alpha = 0.55f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = "View tracks",
                                            tint = Color.White.copy(alpha = 0.40f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            if (folder.isCustom) {
                                                runCatching {
                                                    context.contentResolver.releasePersistableUriPermission(
                                                        Uri.parse(folder.path),
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                    )
                                                }
                                                AppSettings.removeLocalMusicCustomFolder(folder.path)
                                            }
                                            AppSettings.removeLocalMusicFolder(folder.path)
                                            AppSettings.addRemovedAudioFolder(folder.path)
                                            AppSettings.addRemovedAudioFolder(folder.name)
                                            AppSettings.addLocalMusicExcludedFolder(folder.path)
                                            scope.launch(Dispatchers.IO) {
                                                LocalMediaRepository.refreshLocalMusic(context)
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = if (isBn) "মুছে ফেলুন" else "Remove",
                                            tint = Color.White.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Done Button
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        LocalMediaRepository.refreshLocalMusic(context)
                    }
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.done),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
        }
    }
}
