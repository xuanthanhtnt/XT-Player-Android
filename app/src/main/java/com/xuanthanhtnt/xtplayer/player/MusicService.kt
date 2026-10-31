package com.xuanthanhtnt.xtplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.xuanthanhtnt.xtplayer.MainActivity

/**
 * Background music (NowPlayingController of the Apple app): puts [MusicPlayer]'s player in a media session, so it
 * keeps playing with the screen off or in another app, with a media notification, lock-screen and headset controls.
 * Media3 moves the service to the foreground while music plays.
 */
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = MusicPlayer.player
        if (player == null) {
            stopSelf()
            return
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Its own id: the video player has a session of its own at the same time.
        session = MediaSession.Builder(this, player).setId("xtplayer-music").setSessionActivity(open).build().also(::addSession)
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        if (instance === this) instance = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun release() {
        session?.let(::removeSession)
        session?.release()
        session = null
        stopSelf()
    }

    companion object {
        private var instance: MusicService? = null

        /** The queue was stopped: drop the session and the notification. */
        fun shutdown() {
            instance?.release()
            instance = null
        }
    }
}
