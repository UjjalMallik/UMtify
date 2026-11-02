package com.music.bitchord.playback

import android.app.Notification
import android.os.Build
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Wraps Media3's own notification so the playback channel can be *promoted*.
 *
 * `FLAG_PROMOTED_ONGOING` (Android 16 and newer) is what tells the system this
 * ongoing notification is worth more room than a line in the shade. Samsung's
 * Live Update and the OEM "dynamic island" pills on Funtouch/ColorOS/HyperOS key
 * off it — and off the album-art bitmap that now rides in the session metadata —
 * so a notification that only sets the MediaStyle without the flag is what makes
 * the pill appear on a Pixel and stay missing on an iQOO.
 *
 * The remaining flags are the strict set those same capsules look for: the
 * notification must read as an un-dismissable, max-priority, lockscreen-visible
 * ongoing event. [androidx.media3.session.DefaultMediaNotificationProvider]
 * already attaches `MediaStyle(mediaSession)`, already loads the `setLargeIcon()`
 * bitmap through the session's [SessionArtworkLoader], and already asks for
 * `FOREGROUND_SERVICE_IMMEDIATE` on API 31+, so this class only fills in what it
 * leaves at its default: no-clear, priority and visibility. Re-implementing the
 * notification here would mean rebuilding the action buttons, the custom layout
 * and the seek bar by hand for a few bits.
 */
class PromotedOngoingNotificationProvider(
    private val delegate: MediaNotification.Provider,
) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val built = delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            onNotificationChangedCallback,
        )
        val n = built.notification
        var flags = n.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        if (Build.VERSION.SDK_INT >= PROMOTED_ONGOING_SINCE) {
            flags = flags or Notification.FLAG_PROMOTED_ONGOING
        }
        n.flags = flags
        n.priority = Notification.PRIORITY_MAX
        n.visibility = Notification.VISIBILITY_PUBLIC
        return built
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    private companion object {
        /** Android 16 (API 36), where the promoted-ongoing flag was added. */
        const val PROMOTED_ONGOING_SINCE = 36
    }
}
