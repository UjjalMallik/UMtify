package com.music.bitchord.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.music.bitchord.data.TrackLog

/**
 * Hardware AlarmManager broadcast receiver that immediately resurrects PlaybackService
 * if the process is terminated when swiped from Recents (especially on OriginOS).
 */
class PlaybackKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        TrackLog.d("BitChord", "PlaybackKeepAliveReceiver triggered keep-alive resurrection")
        runCatching {
            val serviceIntent = Intent(appContext, PlaybackService::class.java).apply {
                action = "com.music.bitchord.action.KEEP_ALIVE"
                intent?.extras?.let { putExtras(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        }
    }
}
