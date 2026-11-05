package com.flipdesk.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.flipdesk.app.MainActivity
import com.flipdesk.app.R
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.PcState
import com.flipdesk.app.service.ConnectionService

/** Home-screen widget: now playing, play/pause, volume -/+, lock PC. */
class HomeRemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        RemoteWidgets.updateAll(context, Hub.state.value, Hub.pc.value)
}

/** Same remote, laid out for the Flip's near-square cover screen (FlexWindow). */
class CoverRemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        RemoteWidgets.updateAll(context, Hub.state.value, Hub.pc.value)
}

object RemoteWidgets {
    fun updateAll(context: Context, s: ConnState, pc: PcState) {
        val mgr = AppWidgetManager.getInstance(context)
        for ((cls, layout) in listOf(HomeRemoteWidget::class.java to R.layout.widget_home,
            CoverRemoteWidget::class.java to R.layout.widget_cover)) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) continue
            mgr.updateAppWidget(ids, build(context, layout, s, pc))
        }
    }

    private fun build(context: Context, layout: Int, s: ConnState, pc: PcState): RemoteViews {
        val v = RemoteViews(context.packageName, layout)
        val np = pc.nowPlaying.takeIf { s is ConnState.Connected }
        val title = when {
            np != null -> np.title.ifEmpty { "Playing on PC" }
            s is ConnState.Connected -> s.serverName
            s is ConnState.Connecting || s is ConnState.Waiting -> "Connecting…"
            s is ConnState.Unpaired -> "Open FlipDesk to pair"
            s is ConnState.Away -> "Not on home Wi-Fi"
            else -> "FlipDesk · tap a button to connect"
        }
        v.setTextViewText(R.id.w_title, title)
        v.setTextViewText(R.id.w_sub, np?.artist ?: if (s is ConnState.Connected) "Volume ${(pc.volume * 100).toInt()}%" else "")
        v.setImageViewResource(R.id.w_play, if (np?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        v.setOnClickPendingIntent(R.id.w_play, ConnectionService.servicePending(context, ConnectionService.ACTION_PLAY_PAUSE))
        v.setOnClickPendingIntent(R.id.w_vol_down, ConnectionService.servicePending(context, ConnectionService.ACTION_VOL_DOWN))
        v.setOnClickPendingIntent(R.id.w_vol_up, ConnectionService.servicePending(context, ConnectionService.ACTION_VOL_UP))
        v.setOnClickPendingIntent(R.id.w_lock, ConnectionService.servicePending(context, ConnectionService.ACTION_LOCK))
        if (layout == R.layout.widget_cover) {
            v.setOnClickPendingIntent(R.id.w_prev, ConnectionService.servicePending(context, ConnectionService.ACTION_PREV))
            v.setOnClickPendingIntent(R.id.w_next, ConnectionService.servicePending(context, ConnectionService.ACTION_NEXT))
        }
        v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        return v
    }
}
