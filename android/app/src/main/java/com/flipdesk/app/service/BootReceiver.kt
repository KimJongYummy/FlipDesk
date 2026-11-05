package com.flipdesk.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.Hub

/** Brings the connection service back after a reboot or an app update, unless the user disconnected. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.server.value == null || !Prefs.stayConnected) return
        runCatching { Hub.start(context) } // the system may still refuse a background start; the app retries when opened
    }
}
