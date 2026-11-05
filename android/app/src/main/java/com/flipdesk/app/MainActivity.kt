package com.flipdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.display.BigScreen
import com.flipdesk.app.display.StreamPresentation
import com.flipdesk.app.input.HardwareKeys
import com.flipdesk.app.input.VolumeKeys
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.ui.FlipDeskTheme
import com.flipdesk.app.ui.MainScreen
import com.flipdesk.app.ui.PairingScreen
import com.flipdesk.app.ui.SettingsScreen
import com.flipdesk.app.ui.postureOf
import com.flipdesk.app.util.BatteryExemption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val fold = MutableStateFlow<FoldingFeature?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Prefs.server.value != null) {
            Hub.start(this)
            askBatteryExemptionOnce()
        }
    }

    private enum class Page { MAIN, SETTINGS, PAIR }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        preferHighRefreshRate()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    fold.value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                merge(BigScreen.external, BigScreen.showingOn, BigScreen.dismissed, BigScreen.retry, Prefs.server, Prefs.settings)
                    .collect { updateBigScreen() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Hub.toasts.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            }
        }

        setContent {
            FlipDeskTheme {
                val server by Prefs.server.collectAsStateWithLifecycle()
                val state by Hub.state.collectAsStateWithLifecycle()
                val foldFeature by fold.collectAsStateWithLifecycle()
                var page by rememberSaveable { mutableStateOf(Page.MAIN) }
                val effective = if (server == null || state is ConnState.AuthFailed) Page.PAIR else page
                LaunchedEffect(server) {
                    if (server != null && page == Page.PAIR) page = Page.MAIN
                    if (server != null && !permissionsPending()) askBatteryExemptionOnce()
                }
                BackHandler(enabled = effective != Page.MAIN && server != null) { page = Page.MAIN }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AnimatedContent(effective, transitionSpec = {
                        (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260))) togetherWith fadeOut(tween(140))
                    }, label = "page") { p ->
                        when (p) {
                            Page.MAIN -> MainScreen(postureOf(foldFeature)) { page = Page.SETTINGS }
                            Page.SETTINGS -> SettingsScreen(onBack = { page = Page.MAIN }, onPair = { page = Page.PAIR })
                            Page.PAIR -> PairingScreen(onBack = if (server != null && state !is ConnState.AuthFailed) ({ page = Page.MAIN }) else null)
                        }
                    }
                }
            }
        }
        askPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Opening the app always (re)connects right away, even if a retry was scheduled for later,
        // and checks an existing connection is still alive.
        if (hasLocalNetwork()) Hub.onForeground(this)
    }

    /** Once, after pairing: ask One UI not to put FlipDesk to sleep, so it stays connected at home. */
    private fun askBatteryExemptionOnce() {
        if (Prefs.server.value == null || Prefs.askedBatteryExemption || BatteryExemption.isExempt(this)) return
        Prefs.askedBatteryExemption = true
        BatteryExemption.request(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        preferHighRefreshRate() // the cover screen and main screen have different display modes
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) updateBigScreen() // DeX may move us between screens
    }

    private fun hasLocalNetwork(): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions() = buildList {
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun permissionsPending() = missingPermissions().isNotEmpty()

    private fun askPermissions() {
        val want = missingPermissions()
        if (want.isNotEmpty()) permissions.launch(want.toTypedArray())
    }

    /** Ask for the panel's fastest mode at the current resolution (120 Hz on the Flip8). */
    private fun preferHighRefreshRate() {
        val d = display ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    @SuppressLint("RestrictedApi") // public Activity API; androidx.core only marks its own override restricted
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // With the PC on the big screen, a keyboard paired to the phone types on the PC even
        // while the phone screen has focus (DeX moves focus to whichever screen was touched last).
        if (BigScreen.showingOn.value != null && HardwareKeys.isPhysicalKeyboard(event) && HardwareKeys.handle(event)) return true
        if (VolumeKeys.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) HardwareKeys.releaseAll()
    }

    // --- big screen (DeX / HDMI / Smart View) ----------------------------------------------

    private var presentation: StreamPresentation? = null

    /** Put the PC full screen on an external display, if there is one and it's wanted. */
    private fun updateBigScreen() {
        val d = BigScreen.external.value
        val here = display
        if (here != null && BigScreen.isExternal(here)) {
            // Opened from the DeX taskbar: this window is on the big screen itself, so become the PC view.
            presentation?.dismiss()
            presentation = null
            if (BigScreen.wanted(here) && BigScreen.showingOn.value != here.displayId && BigScreen.launch(this, here)) finish()
            return
        }
        if (d == null || !BigScreen.wanted(d)) {
            presentation?.dismiss()
            presentation = null
            return
        }
        if (presentation?.display?.displayId == d.displayId || BigScreen.showingOn.value == d.displayId) return
        if (!BigScreen.activityRefused(d.displayId) && BigScreen.launch(this, d)) return
        presentation?.dismiss()
        presentation = runCatching { StreamPresentation(this, d).also { it.show() } }
            .onFailure { Log.w("FlipDesk", "couldn't show on display ${d.displayId}", it) }
            .getOrNull()
    }

    override fun onDestroy() {
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }
}
