package com.flipdesk.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * One UI puts apps it considers idle to sleep, which silently kills the PC connection. Being
 * exempt from battery optimization ("Unrestricted" battery use) keeps the service alive at home.
 */
object BatteryExemption {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Shows the system "Let FlipDesk always run in the background?" dialog; falls back to the settings list. */
    @SuppressLint("BatteryLife") // sideloaded remote control whose whole job is a persistent LAN link
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
