package com.music.bitchord.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.player.MeshPalette

data class DynamicThemePreset(
    val id: String,
    val name: String,
    val color: Color,
)

const val DYNAMIC_VARIANT_COVER_ART = "COVER_ART"

val PremiumDynamicThemeVariants = listOf(
    DynamicThemePreset("CRIMSON_VELVET", "Crimson Velvet", Color(0xFFE11D48)),
    DynamicThemePreset("EMERALD_GLASS", "Emerald Glass", Color(0xFF10B981)),
    DynamicThemePreset("ROYAL_AMETHYST", "Royal Amethyst", Color(0xFF8B5CF6)),
    DynamicThemePreset("OCEAN_SAPPHIRE", "Ocean Sapphire", Color(0xFF0EA5E9)),
    DynamicThemePreset("SUNSET_AMBER", "Sunset Amber", Color(0xFFF59E0B)),
    DynamicThemePreset("CYBER_MAGENTA", "Cyber Magenta", Color(0xFFEC4899)),
)

fun resolveVariantColor(variantId: String): Color? {
    if (variantId == DYNAMIC_VARIANT_COVER_ART) return null
    val preset = PremiumDynamicThemeVariants.firstOrNull { it.id == variantId }
    if (preset != null) return preset.color
    if (variantId.startsWith("CUSTOM_")) {
        val colorInt = variantId.removePrefix("CUSTOM_").toIntOrNull()
        if (colorInt != null) return Color(colorInt)
    }
    return null
}

fun colorSeedMeshPalette(seed: Color): MeshPalette {
    val color1 = seed
    val color2 = seed.withHsl(
        hue = { (it + 35f) % 360f },
        saturation = { (it * 0.9f).coerceIn(0.4f, 1f) },
        lightness = { (it * 1.15f).coerceIn(0.3f, 0.75f) },
    )
    val color3 = seed.withHsl(
        hue = { (it - 30f + 360f) % 360f },
        saturation = { (it * 1.05f).coerceIn(0.4f, 1f) },
        lightness = { (it * 0.85f).coerceIn(0.2f, 0.6f) },
    )
    val color4 = seed.withHsl(
        hue = { (it + 70f) % 360f },
        saturation = { (it * 0.75f).coerceIn(0.3f, 0.85f) },
        lightness = { 0.15f },
    )
    return MeshPalette(listOf(color1, color2, color3, color4))
}

@Composable
fun rememberColorSeedScheme(seedColor: Color, darkTheme: Boolean = true): ColorScheme {
    return remember(seedColor, darkTheme) {
        if (darkTheme) {
            dynamicDarkScheme(dominant = seedColor, accent = seedColor)
        } else {
            dynamicLightScheme(dominant = seedColor, accent = seedColor)
        }
    }
}
