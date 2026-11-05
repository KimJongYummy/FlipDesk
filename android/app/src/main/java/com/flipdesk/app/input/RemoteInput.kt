package com.flipdesk.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.Hub
import com.flipdesk.app.util.Haptics
import kotlin.math.abs
import kotlin.math.ln

/** Sticky modifier keys shared by the keyboard pane and the laptop key row. */
object Modifiers {
    enum class Mod(val wire: String, val label: String) { CTRL("ctrl", "Ctrl"), ALT("alt", "Alt"), SHIFT("shift", "Shift"), WIN("win", "Win") }
    enum class Latch { OFF, ONCE, LOCKED }

    val state = mutableStateMapOf<Mod, Latch>()

    /** Tap cycles OFF → ONCE (next key only) → LOCKED → OFF. */
    fun tap(m: Mod) {
        state[m] = when (state[m] ?: Latch.OFF) {
            Latch.OFF -> Latch.ONCE
            Latch.ONCE -> Latch.LOCKED
            Latch.LOCKED -> Latch.OFF
        }
        Haptics.tick()
    }

    fun active(): List<String> = Mod.entries.filter { (state[it] ?: Latch.OFF) != Latch.OFF }.map { it.wire }

    /** Release one-shot modifiers after they've been used. */
    fun consume() {
        for (m in Mod.entries) if (state[m] == Latch.ONCE) state[m] = Latch.OFF
    }

    val any get() = active().isNotEmpty()
}

/** Thin, intention-named wrappers over the wire protocol. */
object RemoteInput {
    fun key(key: String, extraMods: List<String> = emptyList()) {
        val mods = (Modifiers.active() + extraMods).distinct()
        Hub.send("key", "key" to key, "mods" to mods.ifEmpty { null })
        Modifiers.consume()
        Haptics.tick()
    }

    /** Text from the IME. With sticky modifiers held, characters become shortcuts (e.g. Ctrl+C). */
    fun typed(edit: TypingEdit) {
        if (Modifiers.any) {
            repeat(edit.backspaces) { key("backspace") }
            edit.insert.forEach { c -> key(if (c == '\n') "enter" else c.lowercaseChar().toString()) }
            return
        }
        repeat(edit.backspaces) { Hub.send("key", "key" to "backspace") }
        if (edit.insert.isNotEmpty()) Hub.send("text", "text" to edit.insert)
    }

    fun shortcut(name: String) {
        Hub.send("shortcut", "name" to name)
        Haptics.click()
    }

    fun media(action: String) {
        Hub.send("media", "action" to action)
        Haptics.click()
    }

    fun volumeStep(delta: Double) {
        Hub.send("volume_step", "delta" to delta)
        Haptics.tick()
    }
}

/** Sends trackpad gestures to the PC with haptic feedback on clicks. */
open class PcTrackpadSink : TrackpadSink {
    private var pinchAcc = 0f
    private var pinching = false

    override fun move(dx: Int, dy: Int) { Hub.send("mouse_move", "dx" to dx, "dy" to dy) }
    override fun click(button: MouseButton) {
        Hub.send("mouse_button", "button" to button.wire, "action" to "click")
        Haptics.click()
    }
    override fun doubleClick() {
        Hub.send("mouse_button", "button" to "left", "action" to "double")
        Haptics.click()
    }
    override fun buttonDown(button: MouseButton) {
        Hub.send("mouse_button", "button" to button.wire, "action" to "down")
        Haptics.heavy()
    }
    override fun buttonUp(button: MouseButton) {
        Hub.send("mouse_button", "button" to button.wire, "action" to "up")
        Haptics.tick()
    }
    override fun scroll(dx: Float, dy: Float) { Hub.send("scroll", "dx" to dx.toDouble(), "dy" to dy.toDouble()) }
    override fun swipe3(dir: SwipeDir) {
        Hub.send("shortcut", "name" to when (dir) {
            SwipeDir.UP -> "task_view"
            SwipeDir.DOWN -> "show_desktop"
            SwipeDir.LEFT -> "app_prev"
            SwipeDir.RIGHT -> "app_next"
        })
        Haptics.heavy()
    }

    /** Pinch on the trackpad = Ctrl + wheel on the PC (zoom in browsers, editors, Explorer...). */
    override fun pinch(factor: Float, ended: Boolean) {
        if (ended) {
            if (pinching) Hub.send("key", "key" to "ctrl", "action" to "up")
            pinching = false
            pinchAcc = 0f
            return
        }
        if (!pinching) {
            Hub.send("key", "key" to "ctrl", "action" to "down")
            pinching = true
        }
        pinchAcc += ln(factor) * 6f
        if (abs(pinchAcc) >= 0.25f) {
            Hub.send("scroll", "dx" to 0.0, "dy" to pinchAcc.toDouble())
            pinchAcc = 0f
        }
    }
}

/** Gyroscope "air mouse": while active, phone rotation moves the cursor. */
class AirMouse(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var lastT = 0L
    private var remX = 0f
    private var remY = 0f
    var active by mutableStateOf(false)
        private set
    val available get() = gyro != null

    fun start() {
        if (gyro == null || active) return
        lastT = 0L
        remX = 0f; remY = 0f
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        active = true
        Haptics.tick()
    }

    fun stop() {
        if (!active) return
        sm.unregisterListener(this)
        active = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (lastT == 0L) { lastT = e.timestamp; return }
        val dt = (e.timestamp - lastT) / 1e9f
        lastT = e.timestamp
        // Phone held like a remote (top edge toward the PC): yaw = values[2], pitch = values[0] (rad/s).
        val k = 900f * Prefs.settings.value.airSensitivity
        val yaw = -e.values[2]
        val pitch = -e.values[0]
        fun dz(v: Float) = if (abs(v) < 0.02f) 0f else v // dead zone kills hand tremor drift
        remX += dz(yaw) * dt * k
        remY += dz(pitch) * dt * k
        val ix = remX.toInt()
        val iy = remY.toInt()
        remX -= ix; remY -= iy
        if (ix != 0 || iy != 0) Hub.send("mouse_move", "dx" to ix, "dy" to iy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
