package com.flipdesk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.BuildConfig
import com.flipdesk.app.util.BatteryExemption
import com.flipdesk.app.data.FoldCloseAction
import com.flipdesk.app.data.LayoutOverride
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.data.Settings
import com.flipdesk.app.data.StreamQuality
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.shortFingerprint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onPair: () -> Unit) {
    val s by Prefs.settings.collectAsStateWithLifecycle()
    val server by Prefs.server.collectAsStateWithLifecycle()
    val state by Hub.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmForget by remember { mutableStateOf(false) }
    fun set(f: (Settings) -> Settings) = Prefs.update(f)

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Trackpad")
            SliderRow("Pointer speed", s.sensitivity, 0.3f..3f, "%.1f×") { v -> set { it.copy(sensitivity = v) } }
            SliderRow("Acceleration", s.acceleration, 0f..2f, "%.1f") { v -> set { it.copy(acceleration = v) } }
            SliderRow("Scroll speed", s.scrollSpeed, 0.3f..3f, "%.1f×") { v -> set { it.copy(scrollSpeed = v) } }
            SwitchRow("Natural scrolling", "Content follows your fingers, like on the phone", s.naturalScroll) { v -> set { it.copy(naturalScroll = v) } }
            SliderRow("Air mouse speed", s.airSensitivity, 0.3f..3f, "%.1f×") { v -> set { it.copy(airSensitivity = v) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Live screen")
            SwitchRow("Live view above controls", "Show the PC screen on the Trackpad and Keys tabs", s.livePreview) { v -> set { it.copy(livePreview = v) } }
            SwitchRow("Direct touch", "Tap the stream where you want to click (off = use it as a trackpad)", s.directTouch) { v -> set { it.copy(directTouch = v) } }
            ChoiceRow("Quality", StreamQuality.entries, s.quality, { q -> when (q) { StreamQuality.AUTO -> "Auto"; StreamQuality.SAVER -> "Data saver"; StreamQuality.SHARP -> "Sharp" } }) { q ->
                set { it.copy(quality = q) }; Hub.restartStream()
            }
            SwitchRow("Mini-map when zoomed", "Laptop mode: shows where the zoomed view is on the desktop", s.miniMap) { v -> set { it.copy(miniMap = v) } }
            SwitchRow("PC on external display", "DeX, HDMI or Smart View: the PC fills the big screen and the phone becomes its trackpad and keyboard", s.bigScreen) { v -> set { it.copy(bigScreen = v) } }
            ChoiceRow("Max frame rate", listOf(15, 30, 60), s.maxFps, { "$it fps" }) { f -> set { it.copy(maxFps = f) }; Hub.restartStream() }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Phone")
            SwitchRow("Volume keys control PC", "While FlipDesk is open and connected", s.volumeKeys) { v -> set { it.copy(volumeKeys = v) } }
            SwitchRow("Haptics", null, s.haptics) { v -> set { it.copy(haptics = v) } }
            SwitchRow("Keep screen awake", "In trackpad, screen and laptop modes", s.keepAwake) { v -> set { it.copy(keepAwake = v) } }
            ChoiceRow("When I close the phone", FoldCloseAction.entries, s.foldClose, { a -> when (a) {
                FoldCloseAction.NOTHING -> "Nothing"; FoldCloseAction.PAUSE_MEDIA -> "Pause media"; FoldCloseAction.LOCK_PC -> "Lock PC" } }) { a ->
                set { it.copy(foldClose = a) }
            }
            ChoiceRow("Layout", LayoutOverride.entries, s.layout, { if (it == LayoutOverride.AUTO) "Automatic" else "Always cover remote" }) { l ->
                set { it.copy(layout = l) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Paired computer")
            server?.let { srv ->
                Text(srv.name, style = MaterialTheme.typography.titleMedium)
                Text("Security code ${shortFingerprint(srv.fingerprint)} · ${(listOfNotNull(srv.lastHost) + srv.hosts).distinct().joinToString()}:${srv.port}",
                    color = Muted, fontSize = 13.sp)
            } ?: Text("None", color = Muted)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is ConnState.Connected || state is ConnState.Connecting || state is ConnState.Waiting || state is ConnState.Away) {
                    OutlinedButton(onClick = { Hub.stop(context) }) { Text("Disconnect") }
                } else if (server != null) {
                    OutlinedButton(onClick = { Hub.start(context) }) { Text("Connect") }
                }
                OutlinedButton(onClick = onPair) { Text("Pair new PC") }
                if (server != null) TextButton(onClick = { confirmForget = true }) { Text("Forget", color = Danger) }
            }
            if (server != null) {
                var exempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryExemption.isExempt(context) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stay connected in the background")
                        Text(if (exempt) "On: battery use is Unrestricted, so FlipDesk stays connected while you're home"
                            else "Off: the phone may put FlipDesk to sleep and drop the connection",
                            color = if (exempt) Muted else Warn, fontSize = 13.sp)
                    }
                    if (!exempt) TextButton(onClick = { BatteryExemption.request(context) }) { Text("Allow") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("FlipDesk ${BuildConfig.VERSION_NAME} · LAN only — no cloud, no accounts, no analytics.", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this PC?") },
            text = { Text("You'll need to pair again. Also remove the phone from the PC tray › Paired devices.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; Hub.forget(context) }) { Text("Forget", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(fmt.format(value), color = Muted, fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(name(o)) })
        }
    }
}
