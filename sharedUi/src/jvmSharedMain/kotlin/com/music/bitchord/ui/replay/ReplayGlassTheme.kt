package com.music.bitchord.ui.replay

import androidx.compose.ui.graphics.Color

/**
 * Selectable multi-tone Dynamic Glass Palettes for Replay share cards and
 * Listening Experience cards.
 */
enum class ReplayGlassTheme(
    val id: String,
    val displayName: String,
    val stops: List<Color>,
    val argbColors: List<Int>,
) {
    AUTO(
        id = "AUTO",
        displayName = "App Theme",
        stops = listOf(Color(0xFF0F172A), Color(0xFF3B82F6), Color(0xFF60A5FA)),
        argbColors = listOf(0xFF0F172A.toInt(), 0xFF3B82F6.toInt(), 0xFF60A5FA.toInt()),
    ),
    COSMIC_AURORA(
        id = "COSMIC_AURORA",
        displayName = "Cosmic Aurora",
        stops = listOf(Color(0xFF0F172A), Color(0xFF6D28D9), Color(0xFF06B6D4)),
        argbColors = listOf(0xFF0F172A.toInt(), 0xFF6D28D9.toInt(), 0xFF06B6D4.toInt()),
    ),
    ROYAL_AMETHYST(
        id = "ROYAL_AMETHYST",
        displayName = "Royal Amethyst",
        stops = listOf(Color(0xFF1E1035), Color(0xFF9333EA), Color(0xFFEC4899)),
        argbColors = listOf(0xFF1E1035.toInt(), 0xFF9333EA.toInt(), 0xFFEC4899.toInt()),
    ),
    EMERALD_NEBULA(
        id = "EMERALD_NEBULA",
        displayName = "Emerald Nebula",
        stops = listOf(Color(0xFF06281E), Color(0xFF10B981), Color(0xFF14B8A6)),
        argbColors = listOf(0xFF06281E.toInt(), 0xFF10B981.toInt(), 0xFF14B8A6.toInt()),
    ),
    CRIMSON_VELVET(
        id = "CRIMSON_VELVET",
        displayName = "Crimson Velvet",
        stops = listOf(Color(0xFF2A0812), Color(0xFFE11D48), Color(0xFFFB7185)),
        argbColors = listOf(0xFF2A0812.toInt(), 0xFFE11D48.toInt(), 0xFFFB7185.toInt()),
    ),
    MIDNIGHT_SAPPHIRE(
        id = "MIDNIGHT_SAPPHIRE",
        displayName = "Midnight Sapphire",
        stops = listOf(Color(0xFF08142C), Color(0xFF2563EB), Color(0xFF38BDF8)),
        argbColors = listOf(0xFF08142C.toInt(), 0xFF2563EB.toInt(), 0xFF38BDF8.toInt()),
    ),
    SUNSET_AMBER(
        id = "SUNSET_AMBER",
        displayName = "Sunset Amber",
        stops = listOf(Color(0xFF271206), Color(0xFFD97706), Color(0xFFEA580C)),
        argbColors = listOf(0xFF271206.toInt(), 0xFFD97706.toInt(), 0xFFEA580C.toInt()),
    );

    companion object {
        fun fromId(id: String?): ReplayGlassTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }

    fun resolvedStops(themeColors: List<Color>?): List<Color> =
        if (this == AUTO && !themeColors.isNullOrEmpty()) themeColors else stops

    fun resolvedArgbColors(themeArgb: List<Int>?): List<Int> =
        if (this == AUTO && !themeArgb.isNullOrEmpty()) themeArgb else argbColors

    fun localizedName(isBn: Boolean): String = if (!isBn) displayName else when (this) {
        AUTO -> "অ্যাপ থিম"
        COSMIC_AURORA -> "কসমিক অরোরা"
        ROYAL_AMETHYST -> "রয়্যাল অ্যামিথিস্ট"
        EMERALD_NEBULA -> "এমেরাল্ড নেবুলা"
        CRIMSON_VELVET -> "ক্রিমসন ভেলভেট"
        MIDNIGHT_SAPPHIRE -> "মিডনাইট স্যাফায়ার"
        SUNSET_AMBER -> "সানসেট অ্যাম্বার"
    }
}
