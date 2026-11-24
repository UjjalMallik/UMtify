package com.music.bitchord.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.music.bitchord.R
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.replay.ShareAction
import com.music.bitchord.ui.replay.saveToGallery
import com.music.bitchord.ui.replay.sendIntent
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Liquid Crystal Glass Story Image Generator & Sharing Sheet.
 *
 * Supports:
 *  - 9:16 Story format (1080x1920) and Square 1:1 post format (1080x1080).
 *  - 3 background style chips (Ambient Blur, Deep Glass, Minimal Dark).
 *  - Direct sharing to WhatsApp, Facebook, Instagram Story, and System Share Sheet.
 *  - High-res Canvas Bitmap rendering exported to `cacheDir/shared_lyrics/umtify_lyrics.png`.
 *  - Save to Gallery via MediaStore.
 */
@Composable
internal fun LyricsCardShareSheet(
    hazeState: HazeState,
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var image by remember(request) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    var storyFormat by remember(request) { mutableStateOf(true) } // true = 9:16 Story, false = Square 1:1
    var selectedStyle by remember(request) { mutableStateOf(LyricsCardStyle.AMBIENT_BLUR) }

    LaunchedEffect(request, storyFormat, selectedStyle) {
        image = null
        failed = false
        saved = false
        image = runCatching { renderLyricsShareCard(context, request, storyFormat, selectedStyle) }
            .onFailure { failed = true }
            .getOrNull()
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.lyrics_share_title),
        onDismiss = onDismiss,
        modifier = modifier,
        titleGap = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Style & Format Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Format toggle chip
                GlassPillChip(
                    label = if (storyFormat) "Story 9:16" else "Square 1:1",
                    icon = if (storyFormat) Icons.Rounded.PhoneIphone else Icons.Rounded.CropSquare,
                    selected = true,
                    onClick = {
                        haptics.play(Haptic.Tap)
                        storyFormat = !storyFormat
                    },
                )

                // 3 Background style chips
                LyricsCardStyle.values().forEach { style ->
                    GlassPillChip(
                        label = style.label,
                        icon = null,
                        selected = selectedStyle == style,
                        onClick = {
                            haptics.play(Haptic.Tap)
                            selectedStyle = style
                        },
                    )
                }
            }

            // Live preview
            val bitmap = image
            val ratio = if (storyFormat) (9f / 16f) else 1f
            val preview = Modifier
                .fillMaxWidth(if (storyFormat) 0.58f else 0.82f)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp))

            if (bitmap == null) {
                Box(
                    modifier = preview.background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.your_lyrics_card),
                    modifier = preview,
                )
            }

            if (failed) {
                Text(
                    text = stringResource(R.string.couldnt_draw_picture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            // Quick-action Direct Share Buttons: WhatsApp, Facebook, Instagram Story, More
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DirectShareButton(
                    label = "Instagram",
                    icon = Icons.Rounded.PhoneIphone,
                    accent = false,
                    enabled = image != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image ?: return@DirectShareButton
                        haptics.play(Haptic.Select)
                        scope.launch {
                            val uri = cacheLyricsForSharing(context, drawn)
                            if (uri != null) {
                                shareToInstagramStory(context, uri)
                            } else {
                                failed = true
                            }
                        }
                    },
                )

                DirectShareButton(
                    label = "WhatsApp",
                    icon = Icons.Rounded.Chat,
                    accent = false,
                    enabled = image != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image ?: return@DirectShareButton
                        haptics.play(Haptic.Select)
                        scope.launch {
                            val uri = cacheLyricsForSharing(context, drawn)
                            if (uri != null) {
                                shareToWhatsApp(context, uri)
                            } else {
                                failed = true
                            }
                        }
                    },
                )

                DirectShareButton(
                    label = "Facebook",
                    icon = Icons.Rounded.Public,
                    accent = false,
                    enabled = image != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image ?: return@DirectShareButton
                        haptics.play(Haptic.Select)
                        scope.launch {
                            val uri = cacheLyricsForSharing(context, drawn)
                            if (uri != null) {
                                shareToFacebook(context, uri)
                            } else {
                                failed = true
                            }
                        }
                    },
                )

                DirectShareButton(
                    label = "More",
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = image != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image ?: return@DirectShareButton
                        haptics.play(Haptic.Select)
                        scope.launch {
                            val uri = cacheLyricsForSharing(context, drawn)
                            if (uri != null) {
                                context.startActivity(
                                    Intent.createChooser(
                                        sendIntent(uri),
                                        context.getString(R.string.share_your_lyrics),
                                    ),
                                )
                            } else {
                                failed = true
                            }
                        }
                    },
                )
            }

            // Save to Gallery row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            ) {
                ShareAction(
                    label = stringResource(R.string.save),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = image != null,
                    modifier = Modifier.fillMaxWidth(),
                    saved = saved,
                    savedLabel = stringResource(R.string.saved_bang),
                    onClick = {
                        val drawn = image ?: return@ShareAction
                        haptics.play(Haptic.Tap)
                        scope.launch {
                            saved = saveToGallery(
                                context,
                                drawn,
                                request.song.title,
                                prefix = "UMtify_Lyrics",
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GlassPillChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)
    val border = if (selected) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.15f)
    val textCol = if (selected) Color.White else Color.White.copy(alpha = 0.75f)

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, border, CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textCol,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = textCol,
        )
    }
}

@Composable
private fun DirectShareButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color.White.copy(alpha = 0.05f)
        accent -> Color.White.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val border = if (accent) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.18f)
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Saves generated card to `cacheDir/shared_lyrics/umtify_lyrics.png` and exposes it via FileProvider.
 */
internal suspend fun cacheLyricsForSharing(
    context: Context,
    bitmap: Bitmap,
): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val folder = File(context.cacheDir, "shared_lyrics").apply { mkdirs() }
        val file = File(folder, "umtify_lyrics.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/**
 * Direct sharing to Instagram Story with fallback.
 */
internal fun shareToInstagramStory(context: Context, uri: Uri) {
    val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
        setDataAndType(uri, "image/png")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    val directIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        setPackage("com.instagram.android")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    try {
        context.startActivity(storyIntent)
    } catch (e: Exception) {
        try {
            context.startActivity(directIntent)
        } catch (e2: Exception) {
            context.startActivity(Intent.createChooser(sendIntent(uri), "Share to Instagram"))
        }
    }
}

/**
 * Direct sharing to WhatsApp with fallback.
 */
internal fun shareToWhatsApp(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        setPackage("com.whatsapp")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        context.startActivity(Intent.createChooser(sendIntent(uri), "Share to WhatsApp"))
    }
}

/**
 * Direct sharing to Facebook Stories with fallback.
 */
internal fun shareToFacebook(context: Context, uri: Uri) {
    val storyIntent = Intent("com.facebook.stories.ADD_TO_STORY").apply {
        setDataAndType(uri, "image/png")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    val directIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        setPackage("com.facebook.katana")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    try {
        context.startActivity(storyIntent)
    } catch (e: Exception) {
        try {
            context.startActivity(directIntent)
        } catch (e2: Exception) {
            context.startActivity(Intent.createChooser(sendIntent(uri), "Share to Facebook"))
        }
    }
}
