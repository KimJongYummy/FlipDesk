package com.flipdesk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import android.service.quicksettings.TileService
import com.flipdesk.app.MainActivity
import com.flipdesk.app.R
import com.flipdesk.app.data.FoldCloseAction
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.PcState
import com.flipdesk.app.tile.ConnectTileService
import com.flipdesk.app.widget.RemoteWidgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the PC connection alive in the foreground (type connectedDevice), independent of the
 * Activity, and hosts the media session, the fold-close action and widget/tile updates.
 */
class ConnectionService : Service() {
    private lateinit var media: PcMediaSession
    private lateinit var fold: FoldWatcher
    private var observer: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel(this)
        media = PcMediaSession(this)
        fold = FoldWatcher(this, ::onFoldClosed).also { it.start() }
        observer = Hub.scope.launch {
            // Ignore the ping latency so the notification and widgets only refresh on real changes.
            combine(Hub.state, Hub.pc.map { it.copy(latencyMs = null) }, Hub.transfer) { s, pc, tr -> Triple(s, pc, tr) }
                .distinctUntilChanged()
                .collect { (s, pc, tr) ->
                    media.update(if (s is ConnState.Connected) pc.nowPlaying else null)
                    if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(s, pc, tr))
                    RemoteWidgets.updateAll(this@ConnectionService, s, pc)
                    TileService.requestListeningState(this@ConnectionService, ComponentName(this@ConnectionService, ConnectTileService::class.java))
                    if (s is ConnState.Unpaired || s is ConnState.AuthFailed) stopSelf()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) {
            try {
                startForeground(NOTIF_ID, build(Hub.state.value, Hub.pc.value, null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e: RuntimeException) {
                // A system restart in the background can be refused (no battery exemption);
                // don't crash, the app reconnects next time it's opened.
                Log.w("FlipDesk", "foreground start refused", e)
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
        }
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                Prefs.setStayConnected(false)
                Hub.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> Hub.sendOrQueue(this, "media", "action" to "play_pause")
            ACTION_NEXT -> Hub.sendOrQueue(this, "media", "action" to "next")
            ACTION_PREV -> Hub.sendOrQueue(this, "media", "action" to "prev")
            ACTION_VOL_UP -> Hub.sendOrQueue(this, "volume_step", "delta" to 0.05)
            ACTION_VOL_DOWN -> Hub.sendOrQueue(this, "volume_step", "delta" to -0.05)
            ACTION_LOCK -> Hub.sendOrQueue(this, "power", "action" to "lock")
        }
        if (Prefs.server.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        observer?.cancel()
        fold.stop()
        media.release()
        RemoteWidgets.updateAll(this, ConnState.Offline, Hub.pc.value)
        TileService.requestListeningState(this, ComponentName(this, ConnectTileService::class.java))
        super.onDestroy()
    }

    private fun onFoldClosed() {
        if (!Hub.isConnected) return
        when (Prefs.settings.value.foldClose) {
            FoldCloseAction.NOTHING -> {}
            FoldCloseAction.PAUSE_MEDIA -> if (Hub.pc.value.nowPlaying?.playing == true) Hub.send("media", "action" to "play_pause")
            FoldCloseAction.LOCK_PC -> Hub.send("power", "action" to "lock")
        }
    }

    private fun build(s: ConnState, pc: PcState, transfer: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = transfer ?: when (s) {
            is ConnState.Connected -> pc.nowPlaying?.let { listOf(it.title, it.artist).filter(String::isNotEmpty).joinToString(" · ") }
                ?: "Connected to ${s.serverName}"
            is ConnState.Connecting -> "Connecting to ${s.host}…"
            is ConnState.Waiting -> "${s.reason} — retrying"
            ConnState.Away -> "Not on Wi-Fi — reconnects automatically when you're home"
            is ConnState.AuthFailed -> "Pairing lost — open FlipDesk to pair again"
            else -> "Disconnected"
        }
        val title = if (s is ConnState.Connected && pc.nowPlaying != null) "Playing on ${s.serverName}" else "FlipDesk"
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_desktop)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (s is ConnState.Connected && pc.nowPlaying != null) {
            val playing = pc.nowPlaying.playing
            b.addAction(action(R.drawable.ic_skip_previous, "Previous", ACTION_PREV))
            b.addAction(action(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", ACTION_PLAY_PAUSE))
            b.addAction(action(R.drawable.ic_skip_next, "Next", ACTION_NEXT))
            b.addAction(action(R.drawable.ic_stat_desktop, "Disconnect", ACTION_DISCONNECT))
            b.setStyle(Notification.MediaStyle().setMediaSession(media.session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        } else {
            b.addAction(action(R.drawable.ic_stat_desktop, "Disconnect", ACTION_DISCONNECT))
        }
        return b.build()
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, servicePending(this, act)).build()

    companion object {
        @Volatile var running = false
            private set
        const val CHANNEL = "connection"
        const val NOTIF_ID = 1
        const val ACTION_CONNECT = "com.flipdesk.app.CONNECT"
        const val ACTION_DISCONNECT = "com.flipdesk.app.DISCONNECT"
        const val ACTION_PLAY_PAUSE = "com.flipdesk.app.PLAY_PAUSE"
        const val ACTION_NEXT = "com.flipdesk.app.NEXT"
        const val ACTION_PREV = "com.flipdesk.app.PREV"
        const val ACTION_VOL_UP = "com.flipdesk.app.VOL_UP"
        const val ACTION_VOL_DOWN = "com.flipdesk.app.VOL_DOWN"
        const val ACTION_LOCK = "com.flipdesk.app.LOCK"

        fun servicePending(context: Context, action: String): PendingIntent =
            PendingIntent.getForegroundService(context, action.hashCode(),
                Intent(context, ConnectionService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL, "PC connection & media", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while FlipDesk is connected to your computer, with media controls"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
