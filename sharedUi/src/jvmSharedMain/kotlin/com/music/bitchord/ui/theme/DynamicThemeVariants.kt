package com.music.bitchord.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
    DynamicThemePreset("EMERALD_GLASS", "Emerald Glass", Color(0xFF10B981)),
    DynamicThemePreset("ROYAL_AMETHYST", "Royal Amethyst", Color(0xFF8B5CF6)),
    DynamicThemePreset("CYBER_AZURE", "Cyber Azure", Color(0xFF0EA5E9)),
    DynamicThemePreset("AMBER_GOLD", "Amber Gold", Color(0xFFF59E0B)),
    DynamicThemePreset("SAKURA_ROSE", "Sakura Rose", Color(0xFFEC4899)),
    DynamicThemePreset("MATCHA_ZEN", "Matcha Zen", Color(0xFF65A30D)),
    DynamicThemePreset("CRIMSON_VELVET", "Crimson Velvet", Color(0xFFE11D48)),
    DynamicThemePreset("MIDNIGHT_INDIGO", "Midnight Indigo", Color(0xFF6366F1)),
    DynamicThemePreset("SUNSET_CORAL", "Sunset Coral", Color(0xFFF97316)),
    DynamicThemePreset("TEAL_LAGOON", "Teal Lagoon", Color(0xFF14B8A6)),
    DynamicThemePreset("CHAMPAGNE_BRONZE", "Champagne Bronze", Color(0xFFD97706)),
    DynamicThemePreset("NORDIC_FROST", "Nordic Frost", Color(0xFF38BDF8)),
)

fun resolveVariantColor(variantId: String): Color? {
    if (variantId == DYNAMIC_VARIANT_COVER_ART) return null
    val preset = PremiumDynamicThemeVariants.firstOrNull { it.id == variantId }
    if (preset != null) return preset.color
    // Backward compatibility aliases
    if (variantId == "OCEAN_SAPPHIRE") return Color(0xFF0EA5E9)
    if (variantId == "SUNSET_AMBER") return Color(0xFFF59E0B)
    if (variantId == "CYBER_MAGENTA") return Color(0xFFEC4899)
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
    val animatedSeed = animateColorAsState(seedColor, tween(450), label = "dynamicSeed").value
    return remember(animatedSeed, darkTheme) {
        if (darkTheme) {
            dynamicDarkScheme(dominant = animatedSeed, accent = animatedSeed)
        } else {
            dynamicLightScheme(dominant = animatedSeed, accent = animatedSeed)
        }
    }
}
