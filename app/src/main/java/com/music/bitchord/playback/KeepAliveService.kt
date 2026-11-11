package com.music.bitchord.playback

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Secondary-process companion service running in android:process=":keepalive".
 *
 * Runs outside the main activity's task/process group with stopWithTask="false".
 * Holds an active BIND_IMPORTANT ServiceConnection to PlaybackService so Android and
 * aggressive task managers (especially Vivo/iQOO OriginOS com.tencent.qqmusic) do NOT kill
 * the PlaybackService process when the user swipes MainActivity from Recent Apps.
 *
 * If the main process is ever killed by an aggressive kernel task killer, this service's
 * onServiceDisconnected callback immediately detects it and restarts PlaybackService
 * via ContextCompat.startForegroundService within <50ms.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        const val ACTION_SYNC = "com.music.bitchord.action.KEEPALIVE_SYNC"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_MEDIA_ID = "extra_media_id"
        const val EXTRA_POSITION_MS = "extra_position_ms"
    }

    private var isPlaying: Boolean = false
    private var lastMediaId: String? = null
    private var lastPositionMs: Long = 0L
    private var isBoundToPlayback = false

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBoundToPlayback = true
            Log.d(TAG, "Connected to PlaybackService from :keepalive process")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBoundToPlayback = false
            Log.w(TAG, "PlaybackService disconnected unexpectedly! isPlaying=$isPlaying")
            if (isPlaying) {
                // Main process was killed (e.g. OriginOS swipe-away).
                // Resurrect immediately within 50ms so Atomic Island and playback never die.
                resurrectPlaybackService()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            isBoundToPlayback = false
            Log.w(TAG, "PlaybackService binding died! isPlaying=$isPlaying")
            if (isPlaying) {
                resurrectPlaybackService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeepAliveService created in :keepalive process")
        bindToPlaybackService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_IS_PLAYING)) {
                isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
            }
            if (intent.hasExtra(EXTRA_MEDIA_ID)) {
                lastMediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
            }
            if (intent.hasExtra(EXTRA_POSITION_MS)) {
                lastPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
            }
        }
        if (!isBoundToPlayback) {
            bindToPlaybackService()
        }
        return START_STICKY
    }

    private fun bindToPlaybackService() {
        try {
            val playbackIntent = Intent(this, PlaybackService::class.java)
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            isBoundToPlayback = bindService(playbackIntent, playbackConnection, flags)
            Log.d(TAG, "Binding to PlaybackService with BIND_IMPORTANT: success=$isBoundToPlayback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to PlaybackService", e)
        }
    }

    private fun resurrectPlaybackService() {
        try {
            val restartIntent = Intent(this, PlaybackService::class.java).apply {
                action = "com.music.bitchord.action.KEEP_ALIVE"
                putExtra("media_id", lastMediaId)
                putExtra("position_ms", lastPositionMs)
                putExtra("play_when_ready", true)
            }
            ContextCompat.startForegroundService(this, restartIntent)
            Log.d(TAG, "Resurrected PlaybackService via startForegroundService")
            // Re-bind after brief delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                bindToPlaybackService()
            }, 100L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resurrect PlaybackService", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): KeepAliveService = this@KeepAliveService
    }

    override fun onDestroy() {
        if (isBoundToPlayback) {
            runCatching { unbindService(playbackConnection) }
            isBoundToPlayback = false
        }
        super.onDestroy()
    }
}
