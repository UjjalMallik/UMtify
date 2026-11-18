package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.utils.containSheetGestures
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val DrawerShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
private val ChipShape = RoundedCornerShape(12.dp)
private val ScrimColor = Color.Black.copy(alpha = 0.5f)
private val DrawerMaxWidth = 640.dp
private const val DismissDragFraction = 0.25f

private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val PITCH_PRESETS = listOf(0.8f, 1.0f, 1.2f)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun SpeedControllerSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedFlow by PlayerPlatform.host.playbackSpeed.collectAsStateWithLifecycle()
    val pitchFlow by PlayerPlatform.host.playbackPitch.collectAsStateWithLifecycle()

    var currentSpeed by remember(speedFlow) { mutableFloatStateOf(speedFlow) }
    var currentPitch by remember(pitchFlow) { mutableFloatStateOf(pitchFlow) }

    val haptics = rememberHaptics()
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val drawerOffset by animateFloatAsState(
        targetValue = drag,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "speedControllerDrawerOffset",
    )
    val scrimAlpha = if (height > 0) {
        (1f - drawerOffset / height).coerceIn(0f, 1f)
    } else {
        1f
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    fun updateSpeedAndPitch(speed: Float, pitch: Float) {
        currentSpeed = speed
        currentPitch = pitch
        PlayerPlatform.host.setPlaybackSpeedAndPitch(speed, pitch)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .background(ScrimColor.copy(alpha = ScrimColor.alpha * scrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = DrawerMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .offset { IntOffset(0, drawerOffset.roundToInt()) }
                    .clip(DrawerShape)
                    .then(
                        if (reduceDynamicBlur) {
                            Modifier.background(Color(0xFF121212))
                        } else {
                            Modifier
                                .optimizedHazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.regular(Color(0xFF141414)),
                                )
                                .background(Color(0xFF121212).copy(alpha = 0.92f))
                        },
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .pointerInput(height) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (height > 0 && drag > height * DismissDragFraction) {
                                    onDismiss()
                                } else {
                                    drag = 0f
                                }
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, delta -> drag = (drag + delta).coerceAtLeast(0f) }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top handle pill
                Box(
                    Modifier
                        .padding(bottom = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                )

                // Title row with icon and reset button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Speed Controller",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                            )
                            Text(
                                text = "Fine-tune speed & pitch dynamically",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            haptics.play(Haptic.Tap)
                            updateSpeedAndPitch(1.0f, 1.0f)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reset speed and pitch",
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }

                // --- SPEED CONTROLLER ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Speed",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fx", currentSpeed),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                Slider(
                    value = currentSpeed,
                    onValueChange = { newSpeed ->
                        val rounded = (newSpeed * 20f).roundToInt() / 20f
                        currentSpeed = rounded
                        PlayerPlatform.host.setPlaybackSpeedAndPitch(rounded, currentPitch)
                    },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )

                // Speed preset chips (0.75x, 1x, 1.25x, 1.5x, 2x)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in SPEED_PRESETS) {
                        val isSelected = abs(currentSpeed - preset) < 0.02f
                        val label = if (preset == 1.0f || preset == 2.0f) "${preset.toInt()}x" else "${preset}x"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(ChipShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                    else Color.White.copy(alpha = 0.08f),
                                )
                                .clickable {
                                    haptics.play(Haptic.Tap)
                                    updateSpeedAndPitch(preset, currentPitch)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }

                // --- PITCH CONTROLLER ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Pitch",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fx", currentPitch),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                Slider(
                    value = currentPitch,
                    onValueChange = { newPitch ->
                        val rounded = (newPitch * 20f).roundToInt() / 20f
                        currentPitch = rounded
                        PlayerPlatform.host.setPlaybackSpeedAndPitch(currentSpeed, rounded)
                    },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )

                // Pitch preset chips (0.8x, 1x, 1.2x)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in PITCH_PRESETS) {
                        val isSelected = abs(currentPitch - preset) < 0.02f
                        val label = if (preset == 1.0f) "1x (Normal)" else "${preset}x"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(ChipShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                    else Color.White.copy(alpha = 0.08f),
                                )
                                .clickable {
                                    haptics.play(Haptic.Tap)
                                    updateSpeedAndPitch(currentSpeed, preset)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}
