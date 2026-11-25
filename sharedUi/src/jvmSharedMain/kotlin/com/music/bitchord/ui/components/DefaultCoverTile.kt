package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

private const val WORDMARK_ASPECT = 800f / 275f

/**
 * 5 curated, modern dark-jewel palettes for Liquid Glass default cover:
 * Deep Indigo/Violet, Midnight Teal/Cyan, Obsidian Plum/Rose, Charcoal/Royal Blue, Espresso/Amber.
 */
private data class JewelPalette(
    val baseDark: Color,
    val glow1: Color,
    val glow2: Color,
)

private val JEWEL_PALETTES = listOf(
    // 0: Deep Indigo / Violet
    JewelPalette(
        baseDark = Color(0xFF0D0C18),
        glow1 = Color(0xFF6366F1),
        glow2 = Color(0xFF38BDF8),
    ),
    // 1: Midnight Teal / Cyan
    JewelPalette(
        baseDark = Color(0xFF081216),
        glow1 = Color(0xFF0D9488),
        glow2 = Color(0xFF06B6D4),
    ),
    // 2: Obsidian Plum / Rose
    JewelPalette(
        baseDark = Color(0xFF160A14),
        glow1 = Color(0xFFDB2777),
        glow2 = Color(0xFFF43F5E),
    ),
    // 3: Charcoal / Royal Blue
    JewelPalette(
        baseDark = Color(0xFF0A0F18),
        glow1 = Color(0xFF2563EB),
        glow2 = Color(0xFF818CF8),
    ),
    // 4: Dark Espresso / Amber
    JewelPalette(
        baseDark = Color(0xFF140D09),
        glow1 = Color(0xFFD97706),
        glow2 = Color(0xFFFBBF24),
    ),
)

/**
 * Modern branded default artwork placeholder:
 * Clean ambient dark-glass with dual-point radial ambient glow, 1.dp frosted glass inner border,
 * center liquid glass medallion with crisp white music emblem, and official UMtify header logo below on large cards.
 */
@Composable
fun DefaultArtworkPlaceholder(
    title: String = "",
    modifier: Modifier = Modifier,
) {
    val palette = remember(title) {
        if (title.isBlank()) {
            JEWEL_PALETTES[0]
        } else {
            val hash = title.hashCode().absoluteValue
            JEWEL_PALETTES[hash % JEWEL_PALETTES.size]
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val isLarge = maxWidth >= 120.dp
        val minDimDp = minOf(maxWidth, maxHeight)

        // 1. Clean Ambient Dark-Glass Background (No wavy lines)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val minDim = minOf(w, h)

            // Deep linear gradient base
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        palette.baseDark,
                        Color(0xFF050608),
                    ),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
                size = size,
            )

            // Subtle dual-point radial ambient glow
            val orb1Radius = minDim * 0.75f
            val orb1Center = Offset(w * 0.28f, h * 0.25f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.glow1.copy(alpha = 0.38f),
                        palette.glow1.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = orb1Center,
                    radius = orb1Radius,
                ),
                radius = orb1Radius,
                center = orb1Center,
            )

            val orb2Radius = minDim * 0.65f
            val orb2Center = Offset(w * 0.75f, h * 0.72f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.glow2.copy(alpha = 0.32f),
                        palette.glow2.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    center = orb2Center,
                    radius = orb2Radius,
                ),
                radius = orb2Radius,
                center = orb2Center,
            )

            // Delicate 1.dp frosted glass inner border
            val borderStroke = maxOf(1f, 1.dp.toPx())
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.05f),
                    ),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
                size = size,
                style = Stroke(width = borderStroke),
            )
        }

        // 2 & 3: Center Medallion + Optional UMtify Brand Logo
        if (isLarge) {
            val medallionSize = (minDimDp * 0.38f).coerceIn(48.dp, 120.dp)
            val logoWidth = minDimDp * 0.32f
            val spacing = (minDimDp * 0.05f).coerceIn(8.dp, 20.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Sleek Liquid Glass Medallion
                Box(
                    modifier = Modifier
                        .size(medallionSize)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    Color.White.copy(alpha = 0.05f),
                                ),
                            ),
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.28f),
                                    Color.White.copy(alpha = 0.06f),
                                ),
                            ),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.size(medallionSize * 0.52f),
                    )
                }

                Spacer(Modifier.height(spacing))

                // Official UMtify Header Wordmark Logo
                Box(
                    modifier = Modifier
                        .width(logoWidth)
                        .aspectRatio(WORDMARK_ASPECT)
                        .alpha(0.85f),
                    contentAlignment = Alignment.Center,
                ) {
                    BrandWordmarkLogo(Modifier.fillMaxSize())
                }
            }
        } else {
            // Small list thumbnail: clean ambient background + centered modern music emblem
            val medallionSize = (minDimDp * 0.46f).coerceAtLeast(18.dp)
            Box(
                modifier = Modifier
                    .size(medallionSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.28f),
                                Color.White.copy(alpha = 0.06f),
                            ),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(medallionSize * 0.54f),
                )
            }
        }
    }
}

/**
 * Official UMtify Brand Wordmark Logo.
 */
@Composable
expect fun BrandWordmarkLogo(modifier: Modifier = Modifier)

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
