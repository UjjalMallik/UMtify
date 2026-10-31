package com.music.bitchord

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The home-screen icon choices. Each is an `<activity-alias>` in the manifest
 * carrying the launcher intent filter; exactly one is enabled at a time.
 *
 * The three variants are sliced from one sheet of wave-music-note artwork at
 * identical scale and centring; Liquid Glass is the shipped default. Older
 * aliases were removed, so a device that still has one enabled ends up with
 * every known alias disabled — [ensureValid] repairs that by re-enabling
 * Default. [previewRes] is the finished 1:1 icon, identical in scale and
 * centring to what the launcher draws, so the Settings cards never show a
 * different crop.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int, val previewRes: Int) {
    Default("LauncherDefault", R.mipmap.ic_launcher, R.string.app_icon_default, R.drawable.ic_preview_default),
    Normal("LauncherNormal", R.mipmap.ic_launcher_normal, R.string.app_icon_normal, R.drawable.ic_preview_normal),
    LiquidGlassDark("LauncherLiquidGlassDark", R.mipmap.ic_launcher_liquid_glass_dark, R.string.app_icon_liquid_glass_dark, R.drawable.ic_preview_liquid_glass_dark);

    private fun component(context: Context) =
        ComponentName(context.packageName, "com.music.bitchord.$alias")

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: Default
        }

        /**
         * Repairs the one state [current] cannot represent: a device that had a
         * now-removed alias enabled ends up with every known alias disabled
         * (the old choice simply no longer exists in the manifest), which would
         * leave the user with no launcher entry at all. Called from
         * [BitChordApplication] before anything reads the current icon.
         */
        fun ensureValid(context: Context) {
            val pm = context.packageManager
            val anyEnabled = entries.any {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (!anyEnabled) apply(context, Default)
        }

        /** Enables [icon] before disabling the rest so there is never a moment with no launcher entry. */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
