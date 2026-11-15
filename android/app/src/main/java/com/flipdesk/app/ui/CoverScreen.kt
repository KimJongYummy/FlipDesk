package com.flipdesk.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.max
import kotlin.math.min

/**
 * The Flip's cover screen in real pixels: small and nearly square (the Flip8's is 1048×948).
 * Pixels rather than dp, because One UI scales the cover screen differently from the main one.
 * The main screen (1080×2640) and the Fold's square inner screen (over 1900 px) don't match.
 */
fun isCoverPixels(w: Int, h: Int): Boolean {
    val big = max(w, h)
    val small = min(w, h)
    return small > 0 && big <= 1400 && big.toFloat() / small < 1.4f
}

/**
 * True while FlipDesk is showing on the cover screen. Two independent signals, either is enough:
 * the hinge is shut (FlipDesk is visible, so it must be on the outside screen), or the screen
 * the app is on is cover-sized in real pixels.
 */
@Composable
fun rememberOnCoverScreen(): Boolean {
    val view = LocalView.current
    LocalConfiguration.current // re-read when the window moves between screens
    val hingeShut = rememberHingeShut()
    val mode = view.display?.mode
    val coverPixels = mode != null && isCoverPixels(mode.physicalWidth, mode.physicalHeight)
    return hingeShut || coverPixels
}

/** Hinge-angle sensor: shut below 15°, open again above 35° (so it doesn't flicker in between). */
@Composable
private fun rememberHingeShut(): Boolean {
    val context = LocalContext.current
    var shut by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val sm = context.getSystemService(SensorManager::class.java)
        val hinge = sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val angle = e.values.firstOrNull() ?: return
                if (angle < 15f) shut = true else if (angle > 35f) shut = false
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (hinge != null) sm.registerListener(listener, hinge, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm?.unregisterListener(listener) }
    }
    return shut
}
