package com.flipdesk.app.display

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flipdesk.app.input.HardwareKeys
import com.flipdesk.app.input.VolumeKeys
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.ui.FlipDeskTheme
import com.flipdesk.app.ui.StreamView
import com.flipdesk.app.ui.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Full-screen PC view on an external display (DeX desktop, HDMI, Smart View). A mouse and
 * keyboard used on that screen go straight to the PC; the phone stays the trackpad/keyboard.
 */
class BigScreenActivity : ComponentActivity() {
    private var shownOn = -1
    private var askedFullscreen = false
    private var displayGone = false
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        // Unplugged: Android would move us onto the phone, which has its own UI; close instead.
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != shownOn) return
            displayGone = true
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = display
        if (!BigScreen.isExternal(d)) {
            // Android put us on the phone instead: that display only takes a Presentation.
            BigScreen.misplaced(intent.getIntExtra(EXTRA_DISPLAY, -1))
            finish()
            return
        }
        shownOn = d.displayId
        BigScreen.shown(shownOn)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        displays.registerDisplayListener(displayListener, Handler(mainLooper))
        // DeX turns a right-click nobody handles into Back; the click already went to the PC.
        onBackPressedDispatcher.addCallback(this) {}
        setContentView(bigScreenRoot(this))
        val closes = BigScreen.closeRequests.value
        lifecycleScope.launch {
            BigScreen.closeRequests.first { it != closes }
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < 37) Hub.onForeground(this)
    }

    override fun onResume() {
        super.onResume()
        // DeX / desktop windowing opens apps in a window; ask for real full screen once.
        if (Build.VERSION.SDK_INT >= 35 && isInMultiWindowMode && !askedFullscreen) {
            askedFullscreen = true
            runCatching { requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, null) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) HardwareKeys.releaseAll()
    }

    @SuppressLint("RestrictedApi") // public Activity API; androidx.core only marks its own override restricted
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (HardwareKeys.isPhysicalKeyboard(event) && HardwareKeys.handle(event)) return true
        if (VolumeKeys.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (shownOn >= 0) {
            displays.unregisterDisplayListener(displayListener)
            // Closed by hand (DeX window X, swiped away): don't pop straight back up.
            if (isFinishing && !displayGone) BigScreen.closedOn(shownOn)
            BigScreen.hidden(shownOn)
            HardwareKeys.releaseAll()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DISPLAY = "display"
    }
}

/** Fallback for displays that won't host an activity: a Presentation owned by the phone's activity. */
class StreamPresentation(private val owner: ComponentActivity, display: Display) : Presentation(owner, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(bigScreenRoot(context))
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(owner)
            it.setViewTreeViewModelStoreOwner(owner)
            it.setViewTreeSavedStateRegistryOwner(owner)
        }
    }

    override fun onStart() {
        super.onStart()
        BigScreen.shown(display.displayId)
    }

    override fun onStop() {
        BigScreen.hidden(display.displayId)
        super.onStop()
    }
}

private fun bigScreenRoot(context: Context): View = FrameLayout(context).apply {
    setBackgroundColor(Color.BLACK)
    addView(ComposeView(context).apply { setContent { FlipDeskTheme { BigScreenContent() } } })
    addView(MouseLayer(context))
}

@Composable
private fun BigScreenContent() {
    val mouse by BigScreen.mouseActive.collectAsStateWithLifecycle()
    val state by Hub.state.collectAsStateWithLifecycle()
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(5_000)
        hint = false
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        StreamView(Modifier.fillMaxSize(), showToolbar = false, corner = 0.dp, onBigScreen = true,
            drawCursor = !mouse, touchInput = false)
        AnimatedVisibility(hint || state !is ConnState.Connected, Modifier.align(Alignment.BottomCenter).padding(24.dp),
            enter = fadeIn(), exit = fadeOut()) {
            val text = if (state is ConnState.Connected) "FlipDesk · use your phone as the trackpad and keyboard, or a mouse and keyboard here"
            else "FlipDesk · ${state.label()}"
            Text(text, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                modifier = Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

/**
 * Transparent top layer that turns a mouse (or touches) on the big screen into absolute PC
 * pointer moves, button presses and wheel scrolls. While the mouse moves, Android's own pointer
 * is shown (zero lag); once it rests, it's hidden and the PC's cursor is drawn instead, so
 * moves from the phone's trackpad show up too.
 */
@SuppressLint("ViewConstructor")
private class MouseLayer(context: Context) : View(context) {
    private val hiddenPointer = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    private var buttons = 0
    private var touching = false
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private val rest = Runnable {
        BigScreen.setMouseActive(false)
        pointerIcon = hiddenPointer
    }

    init {
        pointerIcon = hiddenPointer
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { moveTo(e.x, e.y); mouseMoved() }
            MotionEvent.ACTION_SCROLL -> Hub.send("scroll",
                "dx" to e.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(), "dy" to e.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble())
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> { moveTo(e.x, e.y); syncButtons(e.buttonState) }
            MotionEvent.ACTION_HOVER_EXIT -> {}
            else -> return false
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility") // the PC screen; there's nothing to "click" for accessibility here
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val mouse = e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        moveTo(e.x, e.y)
        if (mouse) {
            mouseMoved()
            syncButtons(if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else e.buttonState)
            return true
        }
        // A touchscreen monitor: tap = click there, drag = drag.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; Hub.send("mouse_button", "button" to "left", "action" to "down") }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (touching) {
                touching = false
                Hub.send("mouse_button", "button" to "left", "action" to "up")
            }
        }
        return true
    }

    /** Sends presses/releases for whichever buttons changed, however the event reported them. */
    private fun syncButtons(state: Int) {
        for ((mask, name) in BUTTONS) {
            val now = state and mask != 0
            if (now != (buttons and mask != 0)) Hub.send("mouse_button", "button" to name, "action" to if (now) "down" else "up")
        }
        buttons = state and BUTTONS.sumOf { it.first }
    }

    private fun mouseMoved() {
        if (pointerIcon != null) {
            pointerIcon = null // the system arrow, drawn with no network lag
            BigScreen.setMouseActive(true)
        }
        removeCallbacks(rest)
        postDelayed(rest, 1_500)
    }

    private fun moveTo(x: Float, y: Float) {
        if (x == lastX && y == lastY) return
        val f = Hub.fullFrame.value ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / f.image.width, height.toFloat() / f.image.height)
        val w = f.image.width * scale
        val h = f.image.height * scale
        val nx = ((x - (width - w) / 2) / w).coerceIn(0f, 1f)
        val ny = ((y - (height - h) / 2) / h).coerceIn(0f, 1f)
        lastX = x
        lastY = y
        Hub.send("mouse_abs", "x" to nx.toDouble(), "y" to ny.toDouble(), "monitor" to f.header.monitor)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(rest)
        syncButtons(0)
        super.onDetachedFromWindow()
    }

    private companion object {
        val BUTTONS = listOf(MotionEvent.BUTTON_PRIMARY to "left", MotionEvent.BUTTON_SECONDARY to "right", MotionEvent.BUTTON_TERTIARY to "middle")
    }
}
