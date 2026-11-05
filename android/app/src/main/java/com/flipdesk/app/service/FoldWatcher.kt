package com.flipdesk.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * Detects the phone being shut. Primary signal: the hinge-angle sensor. Fallback for devices
 * that don't expose it: the main display turns off while another built-in display (the
 * Flip's cover screen) is on.
 */
class FoldWatcher(context: Context, private val onClosed: () -> Unit) : SensorEventListener, DisplayManager.DisplayListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val hinge: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private var wasOpen = false
    private var lastFire = 0L

    fun start() {
        if (hinge != null) {
            sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
            wasOpen = mainDisplayOn()
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        displays.unregisterDisplayListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        if (angle > 60f) wasOpen = true
        else if (angle < 10f && wasOpen) {
            wasOpen = false
            fire()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun mainDisplayOn() = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    override fun onDisplayChanged(displayId: Int) {
        val mainOn = mainDisplayOn()
        val otherOn = displays.displays.any { it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON }
        if (mainOn) wasOpen = true
        else if (wasOpen && otherOn) {
            wasOpen = false
            fire()
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastFire > 3_000) {
            lastFire = now
            onClosed()
        }
    }
}
