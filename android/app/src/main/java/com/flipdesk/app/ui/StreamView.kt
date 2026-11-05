package com.flipdesk.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.display.BigScreen
import com.flipdesk.app.input.GestureEngine
import com.flipdesk.app.input.PcTrackpadSink
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.util.Haptics
import kotlin.math.max
import kotlin.math.min

/** Zoom/pan of the stream inside its box; maps touches back to normalized PC coordinates. */
private class ViewXform {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    fun fitRect(box: Size, img: Size): Rect {
        if (img.width <= 0 || img.height <= 0) return Rect(Offset.Zero, box)
        val f = min(box.width / img.width, box.height / img.height)
        val s = Size(img.width * f, img.height * f)
        return Rect(Offset((box.width - s.width) / 2, (box.height - s.height) / 2), s)
    }

    fun toScreen(p: Offset, box: Size): Offset {
        val c = Offset(box.width / 2, box.height / 2)
        return c + pan + (p - c) * zoom
    }

    fun fromScreen(s: Offset, box: Size): Offset {
        val c = Offset(box.width / 2, box.height / 2)
        return c + (s - c - pan) / zoom
    }

    fun clamp(box: Size, fit: Rect) {
        zoom = zoom.coerceIn(1f, 6f)
        val mx = max(0f, (fit.width * zoom - box.width) / 2)
        val my = max(0f, (fit.height * zoom - box.height) / 2)
        pan = Offset(pan.x.coerceIn(-mx, mx), pan.y.coerceIn(-my, my))
    }

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

@Composable
fun StreamView(
    modifier: Modifier = Modifier,
    showToolbar: Boolean = true,
    corner: androidx.compose.ui.unit.Dp = 20.dp,
    /** This view is the full-screen one on an external display. */
    onBigScreen: Boolean = false,
    drawCursor: Boolean = true,
    touchInput: Boolean = true,
) {
    val bigShowing by BigScreen.showingOn.collectAsStateWithLifecycle()
    if (bigShowing != null && !onBigScreen) {
        // The PC is already full screen on the external display; don't stream it twice.
        Box(modifier.clip(RoundedCornerShape(corner)).background(Pad), contentAlignment = Alignment.Center) {
            Text("Showing on the big screen", color = Muted, fontSize = 13.sp)
        }
        return
    }
    val state by Hub.state.collectAsStateWithLifecycle()
    val frame by Hub.fullFrame.collectAsStateWithLifecycle()
    val cursor by Hub.cursor.collectAsStateWithLifecycle()
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val pc by Hub.pc.collectAsStateWithLifecycle()
    var box by remember { mutableStateOf(IntSize.Zero) }
    val xf = remember { ViewXform() }
    val connected = state is ConnState.Connected

    DisposableEffect(connected, box.width > 0) {
        if (connected && box.width > 0) {
            Hub.acquireStream(box.width, box.height, big = onBigScreen)
            onDispose { Hub.releaseStream(big = onBigScreen) }
        } else onDispose {}
    }

    val img = frame?.image
    val imgSize = img?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
    val boxSize = Size(box.width.toFloat(), box.height.toFloat())
    val fit = xf.fitRect(boxSize, imgSize)
    val fitState = rememberUpdatedState(fit)
    val monitorState = rememberUpdatedState(frame?.header?.monitor ?: 0)

    // Trackpad mode over the stream: pinch zooms the view instead of the PC.
    val engine = remember {
        GestureEngine(object : PcTrackpadSink() {
            override fun pinch(factor: Float, ended: Boolean) {
                if (!ended) {
                    xf.zoom *= factor
                    xf.clamp(boxSizeOf(box), fitState.value)
                }
            }
        })
    }
    LaunchedEffect(settings) {
        engine.accel.sensitivity = settings.sensitivity
        engine.accel.acceleration = settings.acceleration
        engine.naturalScroll = settings.naturalScroll
        engine.scrollSpeed = settings.scrollSpeed
    }

    // When zoomed in, keep the PC cursor in view.
    LaunchedEffect(cursor, xf.zoom) {
        val c = cursor ?: return@LaunchedEffect
        if (xf.zoom <= 1.01f || c.x < 0 || c.monW <= 0 || boxSize.width <= 0) return@LaunchedEffect
        val p = Offset(fit.left + fit.width * c.x / c.monW, fit.top + fit.height * c.y / c.monH)
        val s = xf.toScreen(p, boxSize)
        val margin = boxSize.minDimension * 0.15f
        var dx = 0f
        var dy = 0f
        if (s.x < margin) dx = margin - s.x else if (s.x > boxSize.width - margin) dx = boxSize.width - margin - s.x
        if (s.y < margin) dy = margin - s.y else if (s.y > boxSize.height - margin) dy = boxSize.height - margin - s.y
        if (dx != 0f || dy != 0f) {
            xf.pan += Offset(dx, dy)
            xf.clamp(boxSize, fit)
        }
    }

    fun sendAbs(screen: Offset): Boolean {
        val f = fitState.value
        val p = xf.fromScreen(screen, boxSizeOf(box))
        val nx = (p.x - f.left) / f.width
        val ny = (p.y - f.top) / f.height
        if (nx !in 0f..1f || ny !in 0f..1f) return false
        Hub.send("mouse_abs", "x" to nx.toDouble(), "y" to ny.toDouble(), "monitor" to monitorState.value)
        return true
    }

    val direct = settings.directTouch
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Color.Black)
            .onSizeChanged { box = it },
    ) {
        if (img != null) {
            Canvas(Modifier.fillMaxSize()) {
                val tl = xf.toScreen(fit.topLeft, size)
                val sz = Size(fit.width * xf.zoom, fit.height * xf.zoom)
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(tl.x.toInt(), tl.y.toInt()), dstSize = IntSize(sz.width.toInt(), sz.height.toInt()),
                    filterQuality = if (onBigScreen) FilterQuality.High else FilterQuality.Medium)
                val c = cursor
                if (drawCursor && c != null && c.x >= 0 && c.monW > 0) {
                    val p = xf.toScreen(Offset(fit.left + fit.width * c.x / c.monW, fit.top + fit.height * c.y / c.monH), size)
                    val u = 1.dp.toPx() * 1.1f
                    val arrow = Path().apply {
                        moveTo(p.x, p.y); lineTo(p.x, p.y + 17 * u); lineTo(p.x + 4.5f * u, p.y + 12.5f * u)
                        lineTo(p.x + 11.5f * u, p.y + 12.5f * u); close()
                    }
                    drawPath(arrow, Color.White)
                    drawPath(arrow, Color.Black, style = Stroke(width = 1.2f * u))
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (connected) CircularProgressIndicator(strokeWidth = 3.dp)
                else Text(state.label(), color = Muted, fontSize = 13.sp)
            }
        }
        // Touch input lives on its own layer under the toolbar: sibling layers don't share touches,
        // so tapping a toolbar chip never also clicks the PC screen behind it.
        Box(
            Modifier.fillMaxSize().then(
                if (!connected || img == null || !touchInput) Modifier
                else if (direct) Modifier.pointerInput(Unit) { directTouch(xf, { boxSizeOf(box) }, { fitState.value }, ::sendAbs) }
                else Modifier.trackpadInput(engine)
            ),
        )
        if (showToolbar && connected) {
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimatedVisibility(xf.zoom > 1.01f, enter = fadeIn(), exit = fadeOut()) {
                    ToolChip(Icons.Rounded.ZoomOutMap, "1×") { xf.reset() }
                }
                if (pc.monitors.size > 1) {
                    val cur = frame?.header?.monitor ?: 0
                    ToolChip(Icons.Rounded.DesktopWindows, "${cur + 1}/${pc.monitors.size}") {
                        Prefs.update { it.copy(monitor = (cur + 1) % pc.monitors.size) }
                        xf.reset()
                        Hub.restartStream()
                    }
                }
                ToolChip(if (direct) Icons.Rounded.TouchApp else Icons.Rounded.Mouse, if (direct) "Touch" else "Pad") {
                    Prefs.update { it.copy(directTouch = !it.directTouch) }
                }
            }
            pc.latencyMs?.let {
                Text("${it} ms", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

private fun boxSizeOf(s: IntSize) = Size(s.width.toFloat(), s.height.toFloat())

@Composable
private fun ToolChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = { Haptics.tick(); onClick() },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.padding(0.dp),
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Black.copy(alpha = 0.65f), contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Icon(icon, null, modifier = Modifier.padding(end = 4.dp))
        Text(label, fontSize = 12.sp)
    }
}

/**
 * Direct-touch input: tap = click there, drag = drag there, long-press = right-click,
 * two fingers = zoom/pan the view (local only).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.directTouch(
    xf: ViewXform,
    box: () -> Size,
    fit: () -> Rect,
    sendAbs: (Offset) -> Boolean,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val t0 = down.uptimeMillis
        var mode = 0 // 0 = pending, 1 = dragging, 2 = multi-touch view transform
        var longPressed = false
        var lastT = t0
        while (true) {
            val ev = if (mode == 0 && !longPressed) {
                // Timeout counts from the original touch, not from the latest jitter event.
                withTimeoutOrNull((LONG_PRESS_MS - (lastT - t0)).coerceAtLeast(1)) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (ev == null) {
                longPressed = true
                if (sendAbs(start)) {
                    Hub.send("mouse_button", "button" to "right", "action" to "click")
                    Haptics.heavy()
                }
                continue
            }
            lastT = ev.changes.maxOf { it.uptimeMillis }
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                if (mode == 1) Hub.send("mouse_button", "button" to "left", "action" to "up")
                mode = 2
                xf.zoom *= ev.calculateZoom()
                xf.pan += ev.calculatePan()
                xf.clamp(box(), fit())
                ev.changes.forEach { it.consume() }
                continue
            }
            if (mode == 2) {
                if (pressed.isEmpty()) break
                continue
            }
            val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
            if (!c.pressed) {
                if (mode == 1) {
                    Hub.send("mouse_button", "button" to "left", "action" to "up")
                    Haptics.tick()
                } else if (!longPressed && c.uptimeMillis - t0 < LONG_PRESS_MS) {
                    if (sendAbs(start)) {
                        Hub.send("mouse_button", "button" to "left", "action" to "click")
                        Haptics.click()
                    }
                }
                break
            }
            if (mode == 0 && !longPressed && (c.position - start).getDistance() > slop) {
                if (sendAbs(start)) {
                    Hub.send("mouse_button", "button" to "left", "action" to "down")
                    Haptics.tick()
                    mode = 1
                } else break
            }
            if (mode == 1) sendAbs(c.position)
            c.consume()
        }
    }
}

private const val LONG_PRESS_MS = 450L
