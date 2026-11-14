package com.music.bitchord.ui.screens

import android.content.Context
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicManagerDialog(
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onAddSongs: () -> Unit,
) {
    val context = LocalContext.current
    val isBn = remember { Locale.getDefault().language == "bn" }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val localFolders by AppSettings.localMusicFolders.collectAsStateWithLifecycle()
    val singleFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val excludedSongs by AppSettings.localMusicExcludedSongs.collectAsStateWithLifecycle()
    val selectedSongs by AppSettings.localMusicSelectedSongs.collectAsStateWithLifecycle()

    var detectedFolders by remember { mutableStateOf<List<LocalMediaRepository.DeviceFolder>>(emptyList()) }
    var allLocalSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var folderSongSearchQuery by remember { mutableStateOf("") }
    var drilledFolder by remember { mutableStateOf<LocalMediaRepository.DeviceFolder?>(null) }
    var loading by remember { mutableStateOf(true) }

    val allFoldersActive = localFolders.isEmpty() && singleFolderUri.isBlank()

    LaunchedEffect(Unit) {
        val folders = LocalMediaRepository.getAllDeviceAudioFolders(context)
        val songs = LocalMediaRepository.getAllLocalSongsRaw(context)
        detectedFolders = folders
        allLocalSongs = songs
        loading = false
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
                        text = if (isBn) "ফোল্ডার ও পছন্দের গান নির্বাচন করুন" else "Select folders & filter individual songs",
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
                        // Folder Drill-Down View
                        val folderSongs = remember(allLocalSongs, currentDrill) {
                            allLocalSongs.filter { LocalMediaRepository.isInSelectedFolder(it.localPath, currentDrill.path) }
                        }
                        val filteredFolderSongs = remember(folderSongs, folderSongSearchQuery) {
                            if (folderSongSearchQuery.isBlank()) folderSongs else {
                                folderSongs.filter {
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
                                    text = "${folderSongs.size} ${if (isBn) "টি গান" else "tracks"} · ${currentDrill.path}",
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
                                        if (!allFoldersActive && currentDrill.path !in localFolders) {
                                            AppSettings.setLocalMusicFolders(localFolders + currentDrill.path)
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
                                            } else {
                                                AppSettings.includeLocalSongs(songKeys)
                                                if (!allFoldersActive && currentDrill.path !in localFolders) {
                                                    AppSettings.setLocalMusicFolders(localFolders + currentDrill.path)
                                                }
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
                                                if (!song.thumbnailUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = song.thumbnailUrl,
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Rounded.MusicNote,
                                                        contentDescription = null,
                                                        tint = Color.White.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(18.dp),
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
                                                    text = "${song.artist} · ${song.durationText ?: ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White.copy(alpha = 0.45f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Checkbox(
                                            checked = isIncluded,
                                            onCheckedChange = {
                                                if (isIncluded) {
                                                    AppSettings.excludeLocalSongs(songKeys)
                                                } else {
                                                    AppSettings.includeLocalSongs(songKeys)
                                                    if (!allFoldersActive && currentDrill.path !in localFolders) {
                                                        AppSettings.setLocalMusicFolders(localFolders + currentDrill.path)
                                                    }
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
                                    if (allFoldersActive) {
                                        val first = detectedFolders.firstOrNull()?.path
                                        if (first != null) AppSettings.setLocalMusicFolders(setOf(first))
                                    } else {
                                        AppSettings.setLocalMusicFolders(emptySet())
                                        AppSettings.setLocalMusicFolderUri("")
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
                                            text = if (isBn) "সকল ফোল্ডার থেকে গান অন্তর্ভুক্ত করবে" else "Scan and include all device audio folders",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.60f),
                                        )
                                    }
                                }
                                Switch(
                                    checked = allFoldersActive,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            AppSettings.setLocalMusicFolders(emptySet())
                                            AppSettings.setLocalMusicFolderUri("")
                                        } else {
                                            val first = detectedFolders.firstOrNull()?.path
                                            if (first != null) AppSettings.setLocalMusicFolders(setOf(first))
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

                        // Actions: Add Custom Folder & Add Files
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(0.6.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                    .clickable { onAddFolder() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CreateNewFolder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = if (isBn) "+ ফোল্ডার যোগ" else "+ Add folder",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(0.6.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                    .clickable { onAddSongs() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = if (isBn) "+ ফাইল যোগ" else "+ Add files",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = if (isBn) "ডিভাইসে প্রাপ্ত অডিও ফোল্ডারসমূহ" else "Detected Audio Folders",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.50f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )

                        // List of detected device folders
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(detectedFolders, key = { it.path }) { folder ->
                                val isChecked = allFoldersActive || (folder.path in localFolders) || (singleFolderUri == folder.path)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White.copy(alpha = if (isChecked) 0.08f else 0.03f))
                                        .border(0.6.dp, Color.White.copy(alpha = if (isChecked) 0.25f else 0.08f), RoundedCornerShape(16.dp))
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
                                                tint = if (isChecked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
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
                                                contentDescription = "View songs",
                                                tint = Color.White.copy(alpha = 0.40f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                if (allFoldersActive) {
                                                    val remaining = detectedFolders.map { it.path }.filter { it != folder.path }.toSet()
                                                    AppSettings.setLocalMusicFolders(remaining)
                                                    AppSettings.setLocalMusicFolderUri("")
                                                } else {
                                                    AppSettings.toggleLocalMusicFolder(folder.path)
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
                    }

            // Bottom Done Button
            Button(
                onClick = onDismiss,
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
