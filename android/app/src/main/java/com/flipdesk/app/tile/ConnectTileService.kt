package com.flipdesk.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.flipdesk.app.MainActivity
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: tap to connect to / disconnect from the paired PC. */
class ConnectTileService : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = Hub.scope.launch { Hub.state.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    private fun render(s: ConnState) {
        val tile = qsTile ?: return
        tile.state = when (s) {
            is ConnState.Connected -> Tile.STATE_ACTIVE
            ConnState.Unpaired -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (s) {
            is ConnState.Connected -> s.serverName
            is ConnState.Connecting, is ConnState.Waiting -> "Connecting…"
            ConnState.Unpaired -> "Not paired"
            is ConnState.AuthFailed -> "Pair again"
            ConnState.Offline -> "Off"
            ConnState.Away -> "Not on Wi-Fi"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val s = Hub.state.value
        when {
            Prefs.server.value == null || s is ConnState.AuthFailed -> openApp()
            s is ConnState.Offline -> try {
                Hub.start(this)
            } catch (_: IllegalStateException) {
                openApp() // background FGS start refused on this Android version; let the app do it
            }
            else -> Hub.stop(this)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
