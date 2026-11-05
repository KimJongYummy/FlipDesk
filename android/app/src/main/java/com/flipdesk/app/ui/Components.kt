package com.flipdesk.app.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.input.GestureEngine
import com.flipdesk.app.input.PcTrackpadSink
import com.flipdesk.app.input.TrackpadSink
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.util.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled && Prefs.settings.value.keepAwake
        onDispose { view.keepScreenOn = false }
    }
}

/** A big, round, thumb-friendly button with a springy press and a haptic tick. */
@Composable
fun BigButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    accent: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
    haptic: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = 900f), label = "press")
    val bg = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = tint ?: if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button) {
                if (haptic) Haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) fg else fg.copy(alpha = 0.4f), modifier = Modifier.size(size * 0.45f))
    }
}

/** Rounded-rect key used in the special-key bar and laptop row. */
@Composable
fun KeyCap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    locked: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            locked -> MaterialTheme.colorScheme.primary
            selected -> MaterialTheme.colorScheme.primaryContainer
            pressed -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }, label = "key",
    )
    val fg = if (locked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(interaction, indication = androidx.compose.material3.ripple(), role = Role.Button) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
        else Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun StatusDot(state: ConnState, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        when (state) {
            is ConnState.Connected -> AccentGreen
            is ConnState.Connecting, is ConnState.Waiting -> Warn
            is ConnState.AuthFailed -> Danger
            else -> Muted
        }, label = "dot",
    )
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

fun ConnState.label(): String = when (this) {
    is ConnState.Connected -> serverName
    is ConnState.Connecting -> "Connecting…"
    is ConnState.Waiting -> "$reason · retrying…"
    ConnState.Away -> "Not on Wi-Fi · reconnects when you're home"
    is ConnState.AuthFailed -> "Pairing lost"
    ConnState.Offline -> "Disconnected"
    ConnState.Unpaired -> "Not paired"
}

/**
 * The trackpad surface. Feeds raw touches (in dp) into [GestureEngine]; clicks, drags,
 * scrolling and 3-finger swipes come out of [sink].
 */
@Composable
fun Trackpad(
    modifier: Modifier = Modifier,
    sink: TrackpadSink = remember { PcTrackpadSink() },
    hint: String? = "1 finger move · tap click · 2 finger scroll / right-click · 3 finger swipe",
    corner: Dp = 26.dp,
) {
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val engine = remember(sink) { GestureEngine(sink) }
    LaunchedEffect(settings) {
        engine.accel.sensitivity = settings.sensitivity
        engine.accel.acceleration = settings.acceleration
        engine.naturalScroll = settings.naturalScroll
        engine.scrollSpeed = settings.scrollSpeed
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Pad)
            .border(1.dp, PadEdge, RoundedCornerShape(corner))
            .drawBehind {
                // Subtle dot grid so the surface reads as a trackpad.
                val step = 28.dp.toPx()
                val r = 1.2.dp.toPx()
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(Color.White.copy(alpha = 0.05f), r, Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            .trackpadInput(engine),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (hint != null) {
            Text(hint, color = Muted.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(36.dp, 4.dp).clip(CircleShape)
            .background(accent.copy(alpha = 0.25f)))
    }
}

/** Left / right mouse buttons under small trackpads (cover screen, laptop row). */
@Composable
fun MouseButtonsRow(modifier: Modifier = Modifier, height: Dp = 48.dp) {
    val sink = remember { PcTrackpadSink() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyCap("Left", { sink.click(com.flipdesk.app.input.MouseButton.LEFT) }, Modifier.weight(1f), height = height)
        KeyCap("Right", { sink.click(com.flipdesk.app.input.MouseButton.RIGHT) }, Modifier.weight(1f), height = height)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Muted, modifier = modifier.padding(vertical = 6.dp))
}

@Composable
fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Feeds raw touches (converted to dp) into a [GestureEngine], driving its timers too. */
fun Modifier.trackpadInput(engine: GestureEngine): Modifier = pointerInput(engine) {
    val d = density
    coroutineScope {
        var timer: Job? = null
        fun schedule() {
            timer?.cancel()
            val at = engine.nextTimerAt() ?: return
            timer = launch {
                delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
                engine.onTimer(SystemClock.uptimeMillis())
                schedule()
            }
        }
        try {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Exit || ev.type == PointerEventType.Enter) continue
                    val t = ev.changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()
                    for (c in ev.changes) {
                        val x = c.position.x / d
                        val y = c.position.y / d
                        when {
                            c.pressed && !c.previousPressed -> engine.down(c.id.value, x, y, c.uptimeMillis)
                            c.pressed -> engine.move(c.id.value, x, y)
                        }
                    }
                    engine.frame(t)
                    for (c in ev.changes) if (!c.pressed && c.previousPressed) engine.up(c.id.value, c.uptimeMillis)
                    ev.changes.forEach { it.consume() }
                    schedule()
                }
            }
        } finally {
            engine.cancel()
        }
    }
}
