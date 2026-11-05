package com.flipdesk.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.TvOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.display.BigScreen
import com.flipdesk.app.input.Modifiers
import com.flipdesk.app.input.RemoteInput
import com.flipdesk.app.net.Hub
import com.flipdesk.app.util.Haptics

/**
 * While the PC is full screen on an external display, the phone is its laptop deck: a big
 * trackpad with mouse buttons and a key row. Half-folded, keys go on the top half and the
 * trackpad on the bottom, with nothing across the hinge.
 */
@Composable
fun BigScreenRemote(posture: Posture?, landscape: Boolean, onTabs: () -> Unit, onSettings: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    KeepScreenOn(true)
    val bar: @Composable () -> Unit = { BigScreenBar(onTabs, onSettings) }
    val keys: @Composable ColumnScope.() -> Unit = {
        KeyRow(typing) { typing = !typing }
        AnimatedVisibility(typing) { TypeField(Modifier.fillMaxWidth(), autoFocus = true, minHeight = 44.dp) }
        ArrowKeys(Modifier.fillMaxWidth(), height = 40.dp)
    }
    val pad: @Composable ColumnScope.() -> Unit = {
        Trackpad(Modifier.weight(1f).fillMaxWidth(), corner = 22.dp)
        MouseButtonsRow(Modifier.fillMaxWidth(), height = 48.dp)
    }
    when {
        posture?.laptop == true -> {
            val density = LocalDensity.current
            val topDp = with(density) { posture.hingeTopPx.toDp() }
            val hingeDp = with(density) { (posture.hingeBottomPx - posture.hingeTopPx).toDp() }
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().height(topDp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                    bar()
                    Spacer(Modifier.weight(1f))
                    keys()
                }
                Spacer(Modifier.height(hingeDp + 12.dp)) // dead zone over the hinge
                Column(Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 10.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { pad() }
            }
        }
        landscape -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 10.dp)) {
            bar()
            Row(Modifier.weight(1f).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) { pad() }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) { keys() }
            }
        }
        else -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bar()
            keys()
            pad()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BigScreenBar(onTabs: () -> Unit, onSettings: () -> Unit) {
    val state by Hub.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state)
        HSpace(10.dp)
        Column(Modifier.weight(1f)) {
            Text("On the big screen", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(state.label(), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { Haptics.tick(); onTabs() }) { Icon(Icons.Rounded.Apps, "Media, keys and launcher") }
        IconButton(onClick = { Haptics.heavy(); BigScreen.close() }) { Icon(Icons.Rounded.TvOff, "Stop showing on the big screen") }
        IconButton(onClick = { Haptics.tick(); onSettings() }) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

@Composable
private fun KeyRow(typing: Boolean, onTyping: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val h = 44.dp
        KeyCap("Esc", { RemoteInput.key("esc") }, Modifier.weight(1f), height = h)
        KeyCap("Tab", { RemoteInput.key("tab") }, Modifier.weight(1f), height = h)
        for (m in Modifiers.Mod.entries) {
            val latch = Modifiers.state[m]
            KeyCap(m.label.take(4), { Modifiers.tap(m) }, Modifier.weight(1f), height = h,
                selected = latch == Modifiers.Latch.ONCE, locked = latch == Modifiers.Latch.LOCKED)
        }
        KeyCap("Keys", onTyping, Modifier.weight(1f), height = h,
            icon = if (typing) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard, selected = typing)
    }
}

/** The deck can be swapped for the normal tabs (media, keys, launcher) and back. */
object Deck {
    var hidden by mutableStateOf(false)
}

/** Top-bar button: back to the trackpad deck, or put the PC on the big screen again after closing it. */
@Composable
fun BigScreenButton() {
    val showing by BigScreen.showingOn.collectAsStateWithLifecycle()
    val external by BigScreen.external.collectAsStateWithLifecycle()
    val dismissed by BigScreen.dismissed.collectAsStateWithLifecycle()
    val ext = external
    when {
        showing != null -> IconButton(onClick = { Haptics.tick(); Deck.hidden = false }) { Icon(Icons.Rounded.Tv, "Back to the big-screen trackpad") }
        ext != null && dismissed == ext.displayId -> IconButton(onClick = { Haptics.tick(); BigScreen.reopen() }) { Icon(Icons.Rounded.Tv, "Show the PC on the big screen") }
    }
}
