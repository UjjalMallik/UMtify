package com.music.bitchord

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
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
 * The three variants are sliced from one sheet of wave-music-note artwork;
 * Default is the pastel one and what a fresh install gets. Its note and wave
 * band carry colour right out to the edges of the artwork, where the glass pair
 * fades to near-black and reads as empty space, so Default is drawn at 255 px on
 * the 432 px adaptive canvas — about 59% — against the pair's 300 px, and its rim
 * is dissolved into the background over the last 34 px so nothing ends in a hard
 * cut inside the mask. Older
 * aliases were removed, so a device that still has one
 * enabled ends up with every known alias disabled — [ensureValid] repairs that
 * by re-enabling Default. [previewRes] is the finished 1:1 icon, drawn to the
 * same crop the launcher shows, so the Settings cards
 * never show a different picture of the choice.
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

        /**
         * Writes the whole alias set in one go, so the device is never in a state
         * the launcher can read wrongly: no moment with two entries — which is what
         * makes some launchers put up a duplicate icon — and no moment with none.
         *
         * API 33 has [PackageManager.setComponentEnabledSettings], which applies a
         * list atomically. Below it the same result is reached one call at a time in
         * the order that keeps an entry live throughout: enable the pick first, then
         * retire the others.
         *
         * The [selected] flow is written last and [refreshTaskDescription] is what
         * Recents listens to, so the repaint is already queued by the time this
         * returns — the package manager's own write is synchronous, there is no
         * broadcast to wait on.
         *
         * A settings page that reads the *application* icon instead of the launcher
         * component is outside reach: PackageManager exposes no runtime write for it,
         * so that one keeps showing Default until the next install.
         */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.setComponentEnabledSettings(
                    entries.map {
                        PackageManager.ComponentEnabledSetting(
                            it.component(context),
                            if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    },
                )
            } else {
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
            _selected.value = icon
        }

        /**
         * Recents does not read the launcher icon — it shows the artwork its task
         * was handed, which is set once and kept. So a pick in Settings repaints
         * the home screen and nothing else until [Activity.setTaskDescription]
         * runs again with the new icon.
         *
         * From API 28 the *mipmap* id goes over, not the Settings preview: the
         * system resolves the resource itself, so on a device that reads the
         * adaptive XML it gets the masked launcher icon and on one that doesn't it
         * gets the same square PNG the launcher falls back to. The two agree with
         * the home screen by construction, which the flat preview never quite did.
         *
         * API 26 and 27 have no resource-id form at all, so that release still
         * rasterises [AppIcon.previewRes] by hand — the adaptive drawable would
         * draw with the launcher's 108dp padding baked into the bitmap.
         */
        @Suppress("DEPRECATION")
        fun refreshTaskDescription(activity: Activity) {
            val icon = current(activity)
            val label = activity.getString(R.string.app_name)
            val description = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setIcon(icon.iconRes)
                        .build()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    ActivityManager.TaskDescription(label, icon.iconRes)
                else ->
                    ActivityManager.TaskDescription(label, previewBitmap(activity, icon))
            }
            activity.setTaskDescription(description)
        }

        /** [previewRes] drawn at the density Recents would have produced for it. */
        private fun previewBitmap(context: Context, icon: AppIcon): Bitmap {
            val drawable = ContextCompat.getDrawable(context, icon.previewRes)
                ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
            val size = (96f * context.resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            return bitmap
        }
    }
}
