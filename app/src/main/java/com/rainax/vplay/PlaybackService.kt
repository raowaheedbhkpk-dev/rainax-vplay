package com.rainax.vplay

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps the player alive for background audio and shows the notification / lock-screen controls.
 * The activity and the service share the same ExoPlayer through [PlayerHolder].
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.get(this)

        val open = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_RESUME, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(pending)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from recents: stop playback and the service.
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        PlayerHolder.release()
        super.onDestroy()
    }
}
