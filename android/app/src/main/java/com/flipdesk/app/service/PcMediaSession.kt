package com.flipdesk.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.flipdesk.app.MainActivity
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.NowPlaying

/**
 * Mirrors the PC's now-playing state into an Android MediaSession, so the lock screen,
 * the notification shade and the Flip's cover-screen media controls can drive the PC.
 */
class PcMediaSession(private val context: Context) {
    val session = MediaSession(context, "FlipDesk PC").apply {
        setSessionActivity(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = media("play_pause", wantPlaying = true)
            override fun onPause() = media("play_pause", wantPlaying = false)
            override fun onSkipToNext() = media("next")
            override fun onSkipToPrevious() = media("prev")
            override fun onStop() = media("stop")
        })
    }
    private var last: NowPlaying? = null

    private fun media(action: String, wantPlaying: Boolean? = null) {
        val np = last
        // play/pause is a toggle on the PC; skip it if the PC is already in the wanted state.
        if (wantPlaying != null && np != null && np.playing == wantPlaying) return
        Hub.sendOrQueue(context, "media", "action" to action)
    }

    fun update(np: NowPlaying?) {
        last = np
        if (np == null) {
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build())
            session.isActive = false
            return
        }
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, np.title.ifEmpty { "Playing on PC" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, np.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (np.durationMs > 0) np.durationMs else -1)
            .build())
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (np.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                np.positionMs.coerceAtLeast(0), if (np.playing) 1f else 0f,
                android.os.SystemClock.elapsedRealtime() - (System.currentTimeMillis() - np.receivedAt))
            .build())
        session.isActive = true
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
