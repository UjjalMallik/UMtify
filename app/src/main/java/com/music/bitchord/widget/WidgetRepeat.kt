package com.music.bitchord.widget

import android.content.Context
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.common.Player
import com.music.bitchord.R

/**
 * The Repeat key's glyph and label, shared by all four widgets.
 *
 * The icon is picked from the mode the snapshot carries rather than from
 * anything the widget tracks itself: the service is the only place that knows
 * the real mode, and a widget drawn before this was wired up reads back as off.
 */
internal object WidgetRepeat {

    @DrawableRes
    fun icon(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_ONE -> R.drawable.ic_widget_repeat_one
        Player.REPEAT_MODE_ALL -> R.drawable.ic_widget_repeat_on
        else -> R.drawable.ic_widget_repeat
    }

    @StringRes
    fun label(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_ONE -> R.string.widget_repeat_one
        Player.REPEAT_MODE_ALL -> R.string.widget_repeat_all
        else -> R.string.widget_repeat_off
    }

    /** Binds both into [views], for a layout that has a `widget_repeat`. */
    fun bind(views: RemoteViews, context: Context, mode: Int) {
        views.setImageViewResource(R.id.widget_repeat, icon(mode))
        views.setContentDescription(R.id.widget_repeat, context.getString(label(mode)))
    }
}
