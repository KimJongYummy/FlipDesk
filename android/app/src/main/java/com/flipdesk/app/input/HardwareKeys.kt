package com.flipdesk.app.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.Hub
import com.flipdesk.app.util.Haptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Forwards a physical keyboard (Bluetooth / USB, e.g. with DeX) to the PC. Modifiers are held
 * on the PC exactly as on the keyboard, so Ctrl+C, Shift+arrows and Ctrl+click all work. The
 * on-screen sticky modifiers apply too, which also reaches shortcuts Android keeps for itself.
 */
object HardwareKeys {
    private val _attached = MutableStateFlow(false)
    /** Whether a physical keyboard is connected to the phone right now. */
    val attached: StateFlow<Boolean> = _attached.asStateFlow()

    /** Keeps [attached] up to date for as long as the app runs. */
    fun watch(context: Context) {
        val input = context.getSystemService(InputManager::class.java)
        fun refresh() {
            val now = input.inputDeviceIds.any { id ->
                InputDevice.getDevice(id)?.let {
                    !it.isVirtual && it.isExternal && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                } == true
            }
            if (now && !_attached.value && Hub.isConnected) Hub.toastLocal("Keyboard connected: typing goes to the PC")
            _attached.value = now
        }
        input.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }, Handler(Looper.getMainLooper()))
        refresh()
    }

    private val modifierKeys = mapOf(
        KeyEvent.KEYCODE_CTRL_LEFT to "ctrl", KeyEvent.KEYCODE_CTRL_RIGHT to "ctrl",
        KeyEvent.KEYCODE_ALT_LEFT to "alt", KeyEvent.KEYCODE_ALT_RIGHT to "alt",
        KeyEvent.KEYCODE_SHIFT_LEFT to "shift", KeyEvent.KEYCODE_SHIFT_RIGHT to "shift",
        KeyEvent.KEYCODE_META_LEFT to "win", KeyEvent.KEYCODE_META_RIGHT to "win",
    )

    private val namedKeys = buildMap {
        put(KeyEvent.KEYCODE_ENTER, "enter"); put(KeyEvent.KEYCODE_NUMPAD_ENTER, "enter")
        put(KeyEvent.KEYCODE_DEL, "backspace"); put(KeyEvent.KEYCODE_FORWARD_DEL, "delete")
        put(KeyEvent.KEYCODE_TAB, "tab"); put(KeyEvent.KEYCODE_ESCAPE, "esc"); put(KeyEvent.KEYCODE_SPACE, "space")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "left"); put(KeyEvent.KEYCODE_DPAD_RIGHT, "right")
        put(KeyEvent.KEYCODE_DPAD_UP, "up"); put(KeyEvent.KEYCODE_DPAD_DOWN, "down")
        put(KeyEvent.KEYCODE_MOVE_HOME, "home"); put(KeyEvent.KEYCODE_MOVE_END, "end")
        put(KeyEvent.KEYCODE_PAGE_UP, "pageup"); put(KeyEvent.KEYCODE_PAGE_DOWN, "pagedown")
        put(KeyEvent.KEYCODE_INSERT, "insert"); put(KeyEvent.KEYCODE_SYSRQ, "printscreen")
        put(KeyEvent.KEYCODE_BREAK, "pause"); put(KeyEvent.KEYCODE_MENU, "menu")
        put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "media_play_pause"); put(KeyEvent.KEYCODE_MEDIA_NEXT, "media_next")
        put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "media_prev"); put(KeyEvent.KEYCODE_MEDIA_STOP, "media_stop")
        put(KeyEvent.KEYCODE_VOLUME_MUTE, "volume_mute")
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, "f${i + 1}")
    }

    /** Modifiers currently held down on the PC. */
    private val held = mutableSetOf<String>()

    /** Only real keyboards: not the on-screen keyboard and not the phone's own buttons. */
    fun isPhysicalKeyboard(e: KeyEvent): Boolean {
        val d = e.device ?: return false
        return !d.isVirtual && e.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
            d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    /** Sends [e] to the PC. Returns true if it was consumed. */
    fun handle(e: KeyEvent): Boolean {
        if (!Hub.isConnected) {
            held.clear() // the server let go of them when the connection dropped
            return false
        }
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return true
        modifierKeys[e.keyCode]?.let { m ->
            if (down && e.repeatCount == 0 && held.add(m)) Hub.send("key", "key" to m, "action" to "down")
            if (!down && held.remove(m)) Hub.send("key", "key" to m, "action" to "up")
            return true
        }
        namedKeys[e.keyCode]?.let { name ->
            if (Modifiers.any) {
                if (down) RemoteInput.key(name)
            } else {
                Hub.send("key", "key" to name, "action" to if (down) "down" else "up")
            }
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK || e.keyCode == KeyEvent.KEYCODE_NUM_LOCK) return true
        // A shortcut needs the plain key (Ctrl+C, Win+E…). Android reports no character at all
        // for a key pressed with Ctrl, Alt or Meta, so ask for the one without modifiers.
        val shortcut = Modifiers.any || held.any { it != "shift" }
        val ch = if (shortcut) e.getUnicodeChar(0) else e.unicodeChar
        if (ch == 0 || ch and KeyCharacterMap.COMBINING_ACCENT != 0) return false
        if (!down) return true // printable keys are sent whole on key down (repeats included)
        val typed = String(Character.toChars(ch))
        when {
            Modifiers.any -> RemoteInput.key(typed.lowercase())
            shortcut -> Hub.send("key", "key" to typed.lowercase())
            else -> Hub.send("text", "text" to typed)
        }
        return true
    }

    /** Window lost focus mid-press: don't leave Ctrl or Shift stuck down on the PC. */
    fun releaseAll() {
        if (held.isEmpty()) return
        for (m in held) Hub.send("key", "key" to m, "action" to "up")
        held.clear()
    }
}

/** The phone's volume keys adjust the PC's volume while FlipDesk is open (toggle in settings). */
object VolumeKeys {
    private fun forPc(keyCode: Int) =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            Prefs.settings.value.volumeKeys && Hub.isConnected

    /** Returns true if [e] was a volume key and went to the PC. */
    fun handle(e: KeyEvent): Boolean {
        if (!forPc(e.keyCode)) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            Hub.send("volume_step", "delta" to if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.04 else -0.04)
            Haptics.tick()
        }
        return true
    }
}
