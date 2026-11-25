package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * 5 curated, harmonious 3-color dark-aurora palettes for Liquid Crystal Glass:
 * Deterministically selected by title hash.
 */
private data class AuroraPalette(
    val baseDark: Color,
    val midTone: Color,
    val highTone: Color,
)

private val AURORA_PALETTES = listOf(
    // 0: Nebula: Deep Indigo (#15142E) + Soft Violet (#7C4DFF) + Cyan Glow (#26C6DA)
    AuroraPalette(
        baseDark = Color(0xFF15142E),
        midTone = Color(0xFF7C4DFF),
        highTone = Color(0xFF26C6DA),
    ),
    // 1: Emerald Tide: Deep Obsidian Teal (#0E1F24) + Emerald (#10B981) + Soft Aqua (#38BDF8)
    AuroraPalette(
        baseDark = Color(0xFF0E1F24),
        midTone = Color(0xFF10B981),
        highTone = Color(0xFF38BDF8),
    ),
    // 2: Velvet Sunset: Deep Plum (#211226) + Rose (#EC4899) + Warm Amber (#F59E0B)
    AuroraPalette(
        baseDark = Color(0xFF211226),
        midTone = Color(0xFFEC4899),
        highTone = Color(0xFFF59E0B),
    ),
    // 3: Sapphire Mist: Midnight Navy (#0F172A) + Royal Blue (#3B82F6) + Lavender (#A78BFA)
    AuroraPalette(
        baseDark = Color(0xFF0F172A),
        midTone = Color(0xFF3B82F6),
        highTone = Color(0xFFA78BFA),
    ),
    // 4: Aurora Copper: Deep Espresso (#1E1518) + Coral (#F43F5E) + Soft Gold (#FBBF24)
    AuroraPalette(
        baseDark = Color(0xFF1E1518),
        midTone = Color(0xFFF43F5E),
        highTone = Color(0xFFFBBF24),
    ),
)

/**
 * Aesthetic Liquid Crystal Glass Aurora + layered glowing waves + crisp geometric soundwave emblem.
 * Features deterministic multi-tone aurora mesh gradients, 3 translucent liquid glass wave ribbons,
 * and a center frosted glass circle with a razor-sharp 5-bar symmetrical soundwave emblem.
 */
@Composable
fun DefaultArtworkPlaceholder(
    title: String = "",
    modifier: Modifier = Modifier,
) {
    val palette = remember(title) {
        if (title.isBlank()) {
            AURORA_PALETTES[0]
        } else {
            val hash = title.hashCode().absoluteValue
            AURORA_PALETTES[hash % AURORA_PALETTES.size]
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val minDim = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f

        // 1. Deep Base Gradient
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    palette.baseDark,
                    Color(0xFF07080D),
                ),
                start = Offset.Zero,
                end = Offset(w, h),
            ),
            size = size,
        )

        // 2. Soft Overlapping Radial Gradient Glow Orbs (alpha 0.35f - 0.50f)
        val orb1Radius = minDim * 0.72f
        val orb1Center = Offset(w * 0.80f, h * 0.22f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.midTone.copy(alpha = 0.48f),
                    palette.midTone.copy(alpha = 0.20f),
                    Color.Transparent,
                ),
                center = orb1Center,
                radius = orb1Radius,
            ),
            radius = orb1Radius,
            center = orb1Center,
        )

        val orb2Radius = minDim * 0.68f
        val orb2Center = Offset(w * 0.20f, h * 0.82f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.highTone.copy(alpha = 0.40f),
                    palette.highTone.copy(alpha = 0.15f),
                    Color.Transparent,
                ),
                center = orb2Center,
                radius = orb2Radius,
            ),
            radius = orb2Radius,
            center = orb2Center,
        )

        val orb3Radius = minDim * 0.52f
        val orb3Center = Offset(w * 0.45f, h * 0.48f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.midTone.copy(alpha = 0.35f),
                    palette.highTone.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = orb3Center,
                radius = orb3Radius,
            ),
            radius = orb3Radius,
            center = orb3Center,
        )

        // 3. Layered Translucent Liquid Glass Waves (3 flowing Bezier wave ribbons)
        val strokeWidth = maxOf(1f, 1.2.dp.toPx())

        // Wave Ribbon 1 (Back, alpha = 0.08f)
        val wave1Top = Path().apply {
            moveTo(0f, h * 0.56f)
            cubicTo(
                w * 0.28f, h * 0.46f,
                w * 0.68f, h * 0.66f,
                w, h * 0.52f,
            )
        }
        val wave1Fill = Path().apply {
            addPath(wave1Top)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = wave1Fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.03f),
                ),
                startY = h * 0.46f,
                endY = h,
            ),
        )
        drawPath(
            path = wave1Top,
            color = Color.White.copy(alpha = 0.25f),
            style = Stroke(width = strokeWidth),
        )

        // Wave Ribbon 2 (Middle, alpha = 0.11f)
        val wave2Top = Path().apply {
            moveTo(0f, h * 0.68f)
            cubicTo(
                w * 0.32f, h * 0.76f,
                w * 0.68f, h * 0.58f,
                w, h * 0.68f,
            )
        }
        val wave2Fill = Path().apply {
            addPath(wave2Top)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = wave2Fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.11f),
                    Color.White.copy(alpha = 0.04f),
                ),
                startY = h * 0.58f,
                endY = h,
            ),
        )
        drawPath(
            path = wave2Top,
            color = Color.White.copy(alpha = 0.25f),
            style = Stroke(width = strokeWidth),
        )

        // Wave Ribbon 3 (Front, alpha = 0.14f)
        val wave3Top = Path().apply {
            moveTo(0f, h * 0.80f)
            cubicTo(
                w * 0.24f, h * 0.74f,
                w * 0.76f, h * 0.86f,
                w, h * 0.78f,
            )
        }
        val wave3Fill = Path().apply {
            addPath(wave3Top)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = wave3Fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.06f),
                ),
                startY = h * 0.74f,
                endY = h,
            ),
        )
        drawPath(
            path = wave3Top,
            color = Color.White.copy(alpha = 0.25f),
            style = Stroke(width = strokeWidth),
        )

        // 4. Crisp Center Glass Badge + Symmetrical Soundwave Emblem (size = minDim * 0.42f)
        val badgeRadius = (minDim * 0.42f) / 2f
        if (badgeRadius > 2f) {
            // Subtle ambient inner frosted glass fill
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0.06f),
                    ),
                    start = Offset(cx - badgeRadius, cy - badgeRadius),
                    end = Offset(cx + badgeRadius, cy + badgeRadius),
                ),
                radius = badgeRadius,
                center = Offset(cx, cy),
            )

            // Diagonal specular border (0.35f fading to 0.08f)
            val borderStroke = maxOf(1f, 1.2.dp.toPx())
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.08f),
                    ),
                    start = Offset(cx - badgeRadius, cy - badgeRadius),
                    end = Offset(cx + badgeRadius, cy + badgeRadius),
                ),
                radius = badgeRadius,
                center = Offset(cx, cy),
                style = Stroke(width = borderStroke),
            )

            // 5. Crisp Symmetrical 5-Bar Rounded Audio Waveform Emblem
            val heights = floatArrayOf(0.32f, 0.65f, 1.0f, 0.65f, 0.32f)
            val maxWaveHeight = badgeRadius * 1.05f
            val barWidth = maxOf(1.6f, badgeRadius * 0.13f)
            val barSpacing = badgeRadius * 0.28f

            for (i in 0..4) {
                val barX = cx + (i - 2) * barSpacing
                val barH = maxWaveHeight * heights[i]
                val lineHalf = maxOf(0f, (barH - barWidth) / 2f)
                drawLine(
                    color = Color.White.copy(alpha = 0.92f),
                    start = Offset(barX, cy - lineHalf),
                    end = Offset(barX, cy + lineHalf),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
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
