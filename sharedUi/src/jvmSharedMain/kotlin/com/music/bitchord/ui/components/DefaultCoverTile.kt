package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import kotlin.math.absoluteValue

/**
 * 8 Sleek aesthetic Liquid Glass palettes:
 * Dark, rich ambient backdrops with translucent glowing wave curves and soft glass rings.
 */
private data class GlassPalette(
    val bgStart: Color,
    val bgEnd: Color,
    val accentWave1: Color,
    val accentWave2: Color,
    val ringColor: Color,
)

private val GLASS_PALETTES = listOf(
    // 0: Deep Sapphire / Twilight Indigo
    GlassPalette(
        bgStart = Color(0xFF0F172A),
        bgEnd = Color(0xFF090D16),
        accentWave1 = Color(0xFF38BDF8).copy(alpha = 0.22f),
        accentWave2 = Color(0xFF6366F1).copy(alpha = 0.18f),
        ringColor = Color(0xFF38BDF8).copy(alpha = 0.12f),
    ),
    // 1: Cosmic Amethyst / Royal Violet
    GlassPalette(
        bgStart = Color(0xFF1E1035),
        bgEnd = Color(0xFF0E071A),
        accentWave1 = Color(0xFFA855F7).copy(alpha = 0.22f),
        accentWave2 = Color(0xFFEC4899).copy(alpha = 0.16f),
        ringColor = Color(0xFFA855F7).copy(alpha = 0.12f),
    ),
    // 2: Cyber Emerald / Forest Night
    GlassPalette(
        bgStart = Color(0xFF06281E),
        bgEnd = Color(0xFF03140F),
        accentWave1 = Color(0xFF10B981).copy(alpha = 0.22f),
        accentWave2 = Color(0xFF14B8A6).copy(alpha = 0.16f),
        ringColor = Color(0xFF10B981).copy(alpha = 0.12f),
    ),
    // 3: Obsidian Amber / Sunset Glow
    GlassPalette(
        bgStart = Color(0xFF26140E),
        bgEnd = Color(0xFF140A07),
        accentWave1 = Color(0xFFF97316).copy(alpha = 0.22f),
        accentWave2 = Color(0xFFF43F5E).copy(alpha = 0.16f),
        ringColor = Color(0xFFF97316).copy(alpha = 0.12f),
    ),
    // 4: Electric Cyan / Arctic Abyss
    GlassPalette(
        bgStart = Color(0xFF08202E),
        bgEnd = Color(0xFF041017),
        accentWave1 = Color(0xFF06B6D4).copy(alpha = 0.24f),
        accentWave2 = Color(0xFF3B82F6).copy(alpha = 0.18f),
        ringColor = Color(0xFF06B6D4).copy(alpha = 0.12f),
    ),
    // 5: Rose Quartz / Magenta Dark Glass
    GlassPalette(
        bgStart = Color(0xFF2B0E1E),
        bgEnd = Color(0xFF14060E),
        accentWave1 = Color(0xFFF43F5E).copy(alpha = 0.22f),
        accentWave2 = Color(0xFFD946EF).copy(alpha = 0.16f),
        ringColor = Color(0xFFF43F5E).copy(alpha = 0.12f),
    ),
    // 6: Deep Auric / Dark Gold Glass
    GlassPalette(
        bgStart = Color(0xFF221A0C),
        bgEnd = Color(0xFF100C06),
        accentWave1 = Color(0xFFEAB308).copy(alpha = 0.20f),
        accentWave2 = Color(0xFFF59E0B).copy(alpha = 0.16f),
        ringColor = Color(0xFFEAB308).copy(alpha = 0.12f),
    ),
    // 7: Sleek Midnight Slate / Titanium Glass
    GlassPalette(
        bgStart = Color(0xFF181B22),
        bgEnd = Color(0xFF0D0F14),
        accentWave1 = Color(0xFF94A3B8).copy(alpha = 0.22f),
        accentWave2 = Color(0xFF64748B).copy(alpha = 0.18f),
        ringColor = Color(0xFFCBD5E1).copy(alpha = 0.12f),
    ),
)

/**
 * Aesthetic Liquid Glass wavy/ambient artwork placeholder.
 * Features deterministic ambient gradients derived from title, subtle sound-wave curves,
 * soft concentric glass rings, and a glowing glass emblem with centered music note.
 */
@Composable
fun DefaultArtworkPlaceholder(
    title: String = "",
    modifier: Modifier = Modifier,
) {
    val palette = remember(title) {
        val idx = if (title.isBlank()) 7 else (title.hashCode().absoluteValue % GLASS_PALETTES.size)
        GLASS_PALETTES[idx]
    }

    Box(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(palette.bgStart, palette.bgEnd),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val cx = w / 2f
            val cy = h / 2f

            // 1. Soft concentric glass rings
            val maxR = minOf(w, h) * 0.46f
            drawCircle(
                color = palette.ringColor,
                radius = maxR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f),
            )
            drawCircle(
                color = palette.ringColor.copy(alpha = palette.ringColor.alpha * 0.6f),
                radius = maxR * 0.72f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.0f),
            )

            // 2. Subtle wavy sound-wave curves
            val wavePath1 = Path().apply {
                moveTo(0f, cy + h * 0.18f)
                cubicTo(
                    w * 0.25f, cy + h * 0.05f,
                    w * 0.50f, cy + h * 0.28f,
                    w, cy + h * 0.14f,
                )
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(path = wavePath1, color = palette.accentWave1)

            val wavePath2 = Path().apply {
                moveTo(0f, cy + h * 0.26f)
                cubicTo(
                    w * 0.35f, cy + h * 0.35f,
                    w * 0.70f, cy + h * 0.12f,
                    w, cy + h * 0.24f,
                )
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(path = wavePath2, color = palette.accentWave2)
        }

        // 3. Crisp glowing glass emblem with centered music note
        Box(
            modifier = Modifier
                .fillMaxSize(0.42f)
                .clip(CircleShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.04f),
                        ),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BitChordIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxSize(0.55f),
            )
        }
    }
}

/**
 * UMtify's default artwork placeholder.
 */
@Composable
expect fun DefaultCoverTile(
    modifier: Modifier = Modifier,
    /** A disc-shaped host crops to the circle; a square one needs nothing. */
    contentScale: ContentScale = ContentScale.Crop,
    title: String? = null,
)
