package com.music.bitchord

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home-screen icon choices, in the order Settings shows them. Each is an
 * `<activity-alias>` in the manifest carrying the launcher intent filter — the
 * only place the launcher entry lives — so exactly one is enabled at a time and
 * switching is a component toggle, never a rebuild.
 *
 * The three variants are sliced from one sheet of wave-music-note artwork at
 * identical scale and centring; Default is the pastel one and what a fresh
 * install gets. Older aliases were removed, so a device that still has one
 * enabled ends up with every known alias disabled — [ensureValid] repairs that
 * by re-enabling Default. [previewRes] is the finished 1:1 icon, identical in
 * scale and centring to what the launcher draws, so the Settings cards never
 * show a different crop.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int, val previewRes: Int) {
    Default("LauncherDefault", R.mipmap.ic_launcher, R.string.app_icon_default, R.drawable.ic_preview_default),
    LiquidGlass("LauncherLiquidGlass", R.mipmap.ic_launcher_liquid_glass, R.string.app_icon_liquid_glass, R.drawable.ic_preview_liquid_glass),
    LiquidGlassDark("LauncherLiquidGlassDark", R.mipmap.ic_launcher_liquid_glass_dark, R.string.app_icon_liquid_glass_dark, R.drawable.ic_preview_liquid_glass_dark);

    private fun component(context: Context) =
        ComponentName(context.packageName, "com.music.bitchord.$alias")

    private fun isEnabled(context: Context) =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    companion object {
        /**
         * The enabled alias, mirrored as a flow because Recents needs to be told
         * and it cannot poll the package manager for itself. [current] writes it,
         * so the cold-launch value lands here as soon as anything asks.
         */
        private val _selected = MutableStateFlow(Default)
        val selected: StateFlow<AppIcon> = _selected.asStateFlow()

        fun current(context: Context): AppIcon =
            (entries.firstOrNull { it.isEnabled(context) } ?: Default)
                .also { _selected.value = it }

        /**
         * Repairs the one state [current] cannot represent: a device that had a
         * now-removed alias enabled ends up with every known alias disabled
         * (the old choice simply no longer exists in the manifest), which would
         * leave the user with no launcher entry at all. Called from
         * [BitChordApplication] before anything reads the current icon.
         */
        fun ensureValid(context: Context) {
            if (entries.none { it.isEnabled(context) }) apply(context, Default)
            current(context)
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
            _selected.value = icon
        }

        /**
         * Recents does not read the launcher icon — it shows the bitmap its task
         * was handed, which is set once and kept. So a pick in Settings repaints
         * the home screen and nothing else until [Activity.setTaskDescription]
         * runs again with the new artwork; -1 as the header colour leaves that to
         * the launcher rather than painting a band the theme does not own.
         */
        fun refreshTaskDescription(activity: Activity) {
            val icon = current(activity)
            val drawable = ContextCompat.getDrawable(activity, icon.previewRes) ?: return
            val size = (96f * activity.resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            activity.setTaskDescription(
                ActivityManager.TaskDescription(
                    activity.getString(R.string.app_name),
                    Color.TRANSPARENT,
                    bitmap,
                ),
            )
        }
    }
}
