package com.flipdesk.app.ui

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.input.AirMouse
import com.flipdesk.app.input.Modifiers
import com.flipdesk.app.input.RemoteInput
import com.flipdesk.app.input.TypingDiff
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.Launcher
import com.flipdesk.app.util.Haptics
import kotlinx.coroutines.launch

// --- Trackpad ---------------------------------------------------------------------------------
@Composable
fun TrackpadPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val air = remember { AirMouse(context) }
    DisposableEffect(Unit) { onDispose { air.stop() } }
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    KeepScreenOn(true)
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Trackpad(Modifier.weight(1f).fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyCap("Left", { RemoteClick.left() }, Modifier.weight(1f), height = 56.dp)
            if (air.available) {
                // Hold to steer the cursor by tilting the phone.
                val active = air.active
                Box(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                air.start()
                                tryAwaitRelease()
                                air.stop()
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Air, "Hold for air mouse",
                        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
            KeyCap("Right", { RemoteClick.right() }, Modifier.weight(1f), height = 56.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", color = Muted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            Slider(
                value = settings.sensitivity, onValueChange = { v -> Prefs.update { it.copy(sensitivity = v) } },
                valueRange = 0.3f..3f, modifier = Modifier.weight(1f).height(32.dp),
            )
            Text("%.1f×".format(settings.sensitivity), color = Muted, fontSize = 12.sp, modifier = Modifier.width(40.dp),
                textAlign = TextAlign.End)
        }
    }
}

object RemoteClick {
    fun left() { Hub.send("mouse_button", "button" to "left", "action" to "click"); Haptics.click() }
    fun right() { Hub.send("mouse_button", "button" to "right", "action" to "click"); Haptics.click() }
}

// --- Keyboard ---------------------------------------------------------------------------------
@Composable
fun TypeField(modifier: Modifier = Modifier, autoFocus: Boolean = false, minHeight: androidx.compose.ui.unit.Dp = 56.dp) {
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus(); kb?.show() } }
    Box(
        modifier.heightIn(min = minHeight).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (tfv.text.isEmpty()) {
            Text(if (Modifiers.any) "Type a key for ${Modifiers.active().joinToString("+")}…" else "Tap here and type — it goes to the PC live",
                color = Muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = tfv,
            onValueChange = { nv ->
                val edit = TypingDiff.diff(tfv.text, nv.text)
                if (!edit.isEmpty) RemoteInput.typed(edit)
                // Start fresh after Enter, or when the buffer gets long and nothing is mid-composition.
                tfv = if (nv.text.endsWith("\n") || Modifiers.any || (nv.text.length > 400 && nv.composition == null))
                    TextFieldValue("", TextRange.Zero) else nv
            },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text, imeAction = ImeAction.None,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                // Backspace on an empty field still deletes on the PC.
                if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && tfv.text.isEmpty()) {
                    RemoteInput.key("backspace"); true
                } else if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && tfv.text.isEmpty()) {
                    RemoteInput.key("enter"); true
                } else false
            },
        )
    }
}

@Composable
fun ModifierKeys(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 48.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in Modifiers.Mod.entries) {
            val latch = Modifiers.state[m] ?: Modifiers.Latch.OFF
            KeyCap(
                label = if (m == Modifiers.Mod.WIN) "Win" else m.label,
                onClick = { Modifiers.tap(m) },
                modifier = Modifier.weight(1f),
                selected = latch == Modifiers.Latch.ONCE,
                locked = latch == Modifiers.Latch.LOCKED,
                height = height,
            )
        }
    }
}

@Composable
fun KeysPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TypeField(Modifier.fillMaxWidth())
        ModifierKeys(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("Esc", { RemoteInput.key("esc") }, Modifier.weight(1f))
            KeyCap("Tab", { RemoteInput.key("tab") }, Modifier.weight(1f))
            KeyCap("Del", { RemoteInput.key("delete") }, Modifier.weight(1f))
            KeyCap("Bksp", { RemoteInput.key("backspace") }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Backspace)
            KeyCap("Enter", { RemoteInput.key("enter") }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.KeyboardReturn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("Home", { RemoteInput.key("home") }, Modifier.weight(1f))
            KeyCap("End", { RemoteInput.key("end") }, Modifier.weight(1f))
            KeyCap("PgUp", { RemoteInput.key("pageup") }, Modifier.weight(1f))
            KeyCap("PgDn", { RemoteInput.key("pagedown") }, Modifier.weight(1f))
        }
        ArrowKeys(Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..12) KeyCap("F$i", { RemoteInput.key("f$i") }, Modifier.width(56.dp))
        }
        SectionTitle("Shortcuts")
        val shortcuts = listOf(
            Triple("Copy", Icons.Rounded.ContentCopy, "copy"), Triple("Paste", Icons.Rounded.ContentPaste, "paste"),
            Triple("Undo", Icons.AutoMirrored.Rounded.Undo, "undo"), Triple("Redo", Icons.AutoMirrored.Rounded.Redo, "redo"),
            Triple("Alt-Tab", Icons.Rounded.SwapHoriz, "alt_tab"), Triple("Snip", Icons.Rounded.Screenshot, "screenshot"),
            Triple("Desktop", Icons.Rounded.DesktopWindows, "show_desktop"), Triple("Tasks", Icons.AutoMirrored.Rounded.ViewQuilt, "task_view"),
        )
        for (row in shortcuts.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, icon, name) in row) ShortcutTile(label, icon, Modifier.weight(1f)) { RemoteInput.shortcut(name) }
            }
        }
        SectionTitle("Clipboard")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShortcutTile("Phone → PC", Icons.Rounded.PhoneAndroid, Modifier.weight(1f)) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrEmpty()) Hub.toastLocal("Phone clipboard is empty")
                else scope.launch { if (Hub.pushClipboard(text)) Hub.toastLocal("Sent to PC clipboard") }
            }
            ShortcutTile("PC → Phone", Icons.Rounded.Monitor, Modifier.weight(1f)) { Hub.send("clipboard_pull") }
        }
        VSpace(8.dp)
    }
}

@Composable
fun ArrowKeys(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 48.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyCap("Left", { RemoteInput.key("left") }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.ArrowBack, height = height)
        KeyCap("Up", { RemoteInput.key("up") }, Modifier.weight(1f), icon = Icons.Rounded.ArrowUpward, height = height)
        KeyCap("Down", { RemoteInput.key("down") }, Modifier.weight(1f), icon = Icons.Rounded.ArrowDownward, height = height)
        KeyCap("Right", { RemoteInput.key("right") }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.ArrowForward, height = height)
    }
}

@Composable
fun ShortcutTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = { Haptics.tick(); onClick() }, modifier = modifier.height(72.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// --- Media ------------------------------------------------------------------------------------
@Composable
fun NowPlayingText(modifier: Modifier = Modifier, center: Boolean = true, big: Boolean = true) {
    val pc by Hub.pc.collectAsStateWithLifecycle()
    val np = pc.nowPlaying
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        AnimatedContent(np?.title ?: "Nothing playing", label = "title") { t ->
            Text(t, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (center) TextAlign.Center else TextAlign.Start)
        }
        Text(listOfNotNull(np?.artist?.ifEmpty { null }, np?.app?.substringAfterLast('.')?.substringBefore('!')?.ifEmpty { null }
            ?.takeIf { np.artist.isEmpty() }).joinToString(" · ").ifEmpty { if (np == null) "Start something on the PC" else "" },
            color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (center) TextAlign.Center else TextAlign.Start)
    }
}

@Composable
fun TransportControls(modifier: Modifier = Modifier, big: androidx.compose.ui.unit.Dp = 88.dp, small: androidx.compose.ui.unit.Dp = 64.dp) {
    val pc by Hub.pc.collectAsStateWithLifecycle()
    val playing = pc.nowPlaying?.playing == true
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        BigButton(Icons.Rounded.SkipPrevious, "Previous", { RemoteInput.media("prev") }, size = small, haptic = false)
        BigButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
            { RemoteInput.media("play_pause") }, size = big, accent = true, haptic = false)
        BigButton(Icons.Rounded.SkipNext, "Next", { RemoteInput.media("next") }, size = small, haptic = false)
    }
}

@Composable
fun VolumeRow(modifier: Modifier = Modifier, compact: Boolean = false) {
    val pc by Hub.pc.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastSent by remember { mutableLongStateOf(0L) }
    val shown = dragging ?: pc.volume
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BigButton(if (pc.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeDown,
            if (pc.muted) "Unmute" else "Mute", { Hub.send("mute") }, size = if (compact) 44.dp else 52.dp,
            tint = if (pc.muted) Danger else null)
        Slider(
            value = shown,
            onValueChange = { v ->
                dragging = v
                val now = System.currentTimeMillis()
                if (now - lastSent > 40) { // ~25 updates/s while dragging
                    lastSent = now
                    Hub.send("volume_set", "level" to v.toDouble())
                }
            },
            onValueChangeFinished = {
                dragging?.let { Hub.send("volume_set", "level" to it.toDouble()) }
                dragging = null
                Haptics.tick()
            },
            colors = SliderDefaults.colors(activeTrackColor = if (pc.muted) Muted else MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
        )
        if (!compact) Text("${(shown * 100).toInt()}", color = Muted, fontSize = 13.sp, modifier = Modifier.width(30.dp),
            textAlign = TextAlign.End)
        BigButton(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up", { RemoteInput.volumeStep(0.05) }, size = if (compact) 44.dp else 52.dp)
    }
}

@Composable
fun MediaPane(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        VSpace(8.dp)
        Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f).clip(RoundedCornerShape(32.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Accent.copy(alpha = 0.35f), AccentGreen.copy(alpha = 0.18f)))),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayCircle, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), modifier = Modifier.size(72.dp))
        }
        NowPlayingText(Modifier.fillMaxWidth())
        TransportControls(Modifier.fillMaxWidth())
        VolumeRow(Modifier.fillMaxWidth())
        VSpace(8.dp)
    }
}

// --- Launchers & power ------------------------------------------------------------------------
private fun launcherIcon(l: Launcher): ImageVector = when (l.icon) {
    "web" -> Icons.Rounded.Language
    "play" -> Icons.Rounded.PlayCircle
    "folder" -> Icons.Rounded.Folder
    "note" -> Icons.Rounded.Description
    "calc" -> Icons.Rounded.Calculate
    "monitor" -> Icons.Rounded.Monitor
    "settings" -> Icons.Rounded.Settings
    "terminal" -> Icons.Rounded.Terminal
    else -> if (l.type == "url") Icons.Rounded.Language else Icons.Rounded.Apps
}

@Composable
fun LaunchPane(modifier: Modifier = Modifier) {
    val pc by Hub.pc.collectAsStateWithLifecycle()
    val transfer by Hub.transfer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = Hub.sendFiles(context, uris)
            Hub.toastLocal(if (n == uris.size) "Saved $n file(s) to PC Downloads" else "Sent $n of ${uris.size} files")
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp), modifier = modifier,
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Power") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerTile("Lock", Icons.Rounded.Lock, Modifier.weight(1f)) { Hub.send("power", "action" to "lock"); Haptics.heavy() }
                PowerTile("Sleep", Icons.Rounded.Bedtime, Modifier.weight(1f)) { confirm = "sleep" }
                PowerTile("Restart", Icons.Rounded.RestartAlt, Modifier.weight(1f)) { confirm = "restart" }
                PowerTile("Shut down", Icons.Rounded.PowerSettingsNew, Modifier.weight(1f), danger = true) { confirm = "shutdown" }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortcutTile(transfer ?: "Send files to PC", Icons.Rounded.UploadFile, Modifier.weight(1f)) { pick.launch("*/*") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Launchers  ·  edit launchers.json from the PC tray") }
        if (pc.launchers.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("No launchers configured", color = Muted) }
        }
        items(pc.launchers.distinctBy { it.id }, key = { it.id }) { l ->
            ShortcutTile(l.name, launcherIcon(l), Modifier.fillMaxWidth()) { Hub.send("launch", "id" to l.id) }
        }
    }
    confirm?.let { action ->
        val title = mapOf("sleep" to "Put the PC to sleep?", "restart" to "Restart the PC?", "shutdown" to "Shut down the PC?")[action]!!
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("Unsaved work on the PC may be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    Hub.send("power", "action" to action, "confirm" to true)
                    Haptics.heavy()
                    confirm = null
                }) { Text(action.replaceFirstChar { it.uppercase() }.replace("Shutdown", "Shut down"), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PowerTile(label: String, icon: ImageVector, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = { Haptics.tick(); onClick() }, modifier = modifier.height(84.dp), shape = RoundedCornerShape(18.dp),
        color = if (danger) Danger.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (danger) Danger else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
            Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        }
    }
}
