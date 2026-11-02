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
 * Everything else is left exactly as [androidx.media3.session.DefaultMediaNotificationProvider]
 * built it, including the large icon it loads through the session's
 * [SessionArtworkLoader]. Re-implementing that here would mean rebuilding the
 * action buttons, the custom layout and the seek bar by hand for one bit.
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
        if (Build.VERSION.SDK_INT < PROMOTED_ONGOING_SINCE) return built
        val flags = built.notification.flags or Notification.FLAG_PROMOTED_ONGOING
        if (flags == built.notification.flags) return built
        built.notification.flags = flags
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
