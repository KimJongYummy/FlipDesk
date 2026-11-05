package com.flipdesk.app.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Frame
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.StreamRegion
import com.flipdesk.app.util.Haptics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val ZOOM_ANIM_MS = 450L

/** Lets the layout ask the Flex-mode view to zoom back out before it goes away. */
object LaptopZoom {
    internal var attached = false
    internal var exitRequest: CompletableDeferred<Unit>? = null

    /** Animate back to the full desktop; returns when done (or immediately if not shown). */
    suspend fun animateOut() {
        if (!attached) return
        val d = CompletableDeferred<Unit>()
        exitRequest = d
        withTimeoutOrNull(ZOOM_ANIM_MS + 300) { d.await() }
        exitRequest = null
    }
}

private class ZoomAnim(val from: Viewport, val to: Viewport, val startNanos: Long, val exit: Boolean)

/** Everything the frame loop, gestures and drawing share. Compose-state fields drive redraws. */
private class ZoomState {
    var vp by mutableStateOf<Viewport?>(null)
    var indicatorAlpha by mutableFloatStateOf(0f)
    var miniAlpha by mutableFloatStateOf(0f)
    var prevRegion by mutableStateOf<Frame?>(null)
    var curRegion by mutableStateOf<Frame?>(null)
    var box = IntSize.Zero
    var anim: ZoomAnim? = null
    var entered = false
    var monitor = -1
    var gestureActive = false
    var followPausedAt: Pair<Int, Int>? = null
    var followTarget: Viewport? = null
    var region: Pair<StreamRegion, Pair<Int, Int>>? = null
    var lastRegionAt = 0L
    var shownZoom = 1f
    var zoomChangedAt = 0L
    var lastTapAt = 0L
    var lastTapPos = Offset.Zero

    fun geometry(f: Frame?): ZoomGeometry? {
        val h = f?.header ?: return null
        if (box.width <= 0 || box.height <= 0 || h.monW <= 0 || h.monH <= 0) return null
        return ZoomGeometry(box.width.toFloat(), box.height.toFloat(), h.monW.toFloat(), h.monH.toFloat())
    }

    fun start(from: Viewport, to: Viewport, now: Long, exit: Boolean = false) {
        anim = ZoomAnim(from, to, now, exit)
        followTarget = null
    }
}

/**
 * Laptop (Flex) mode screen view: animates into a zoom centered on the cursor, follows the
 * cursor with eased motion, pinch / double-tap to zoom, and streams only the visible region
 * at native resolution while zoomed. Animation runs every display frame, independent of video fps.
 */
@Composable
fun ZoomStreamView(modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    val state by Hub.state.collectAsStateWithLifecycle()
    val base by Hub.fullFrame.collectAsStateWithLifecycle()
    val cursor by Hub.cursor.collectAsStateWithLifecycle()
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val pc by Hub.pc.collectAsStateWithLifecycle()
    val st = remember { ZoomState() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val connected = state is ConnState.Connected

    DisposableEffect(connected, boxSize.width > 0) {
        if (connected && boxSize.width > 0) {
            Hub.acquireStream(boxSize.width, boxSize.height)
            onDispose {
                Hub.setRegion(null)
                Hub.releaseStream()
            }
        } else onDispose {}
    }
    DisposableEffect(Unit) {
        LaptopZoom.attached = true
        onDispose {
            LaptopZoom.attached = false
            LaptopZoom.exitRequest?.complete(Unit)
        }
    }
    // Keep the last two region frames: the newest on top, the previous one filling in while a new region loads.
    LaunchedEffect(Unit) {
        Hub.frame.collect { f ->
            if (f?.header?.region != null && f !== st.curRegion) {
                st.prevRegion = st.curRegion
                st.curRegion = f
            }
        }
    }

    // The per-display-frame loop: animations, follow-cursor, indicator fades, region requests.
    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                tick(st, Hub.fullFrame.value ?: st.curRegion, Hub.cursor.value, now, dt)
            }
        }
    }

    val zoomLabel by remember { derivedStateOf { st.vp?.let { "%.1f×".format((it.zoom * 10).roundToInt() / 10f) } ?: "" } }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Color.Black)
            .onSizeChanged { boxSize = it; st.box = it },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val b = base ?: st.curRegion ?: return@Canvas
            val g = st.geometry(b) ?: return@Canvas
            val v = st.vp ?: g.center
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                fun draw(f: Frame) {
                    val r = f.header.region ?: StreamRegion(0, 0, f.header.monW, f.header.monH)
                    nc.drawBitmap(f.bitmap, null, RectF(g.toScreenX(r.x.toFloat(), v), g.toScreenY(r.y.toFloat(), v),
                        g.toScreenX((r.x + r.w).toFloat(), v), g.toScreenY((r.y + r.h).toFloat(), v)), paint)
                }
                base?.let(::draw)
                // Region layers only if they are newer than the whole-monitor frame underneath.
                for (layer in listOf(st.prevRegion, st.curRegion)) {
                    if (layer != null && layer.header.monitor == b.header.monitor &&
                        (base == null || layer.header.seq > base!!.header.seq)) draw(layer)
                }
            }
            val cur = cursor
            if (cur != null && cur.x >= 0) {
                val px = g.toScreenX(cur.x.toFloat(), v)
                val py = g.toScreenY(cur.y.toFloat(), v)
                val u = 1.dp.toPx() * (1f + (v.zoom - 1f) * 0.15f)
                val arrow = Path().apply {
                    moveTo(px, py); lineTo(px, py + 17 * u); lineTo(px + 4.5f * u, py + 12.5f * u)
                    lineTo(px + 11.5f * u, py + 12.5f * u); close()
                }
                drawPath(arrow, Color.White)
                drawPath(arrow, Color.Black, style = Stroke(width = 1.2f * u))
            }
            // Mini-map: the whole desktop with the visible rectangle outlined.
            val ma = st.miniAlpha
            val full = base
            if (ma > 0.01f && full != null) {
                val mw = size.width * 0.28f
                val mh = mw * g.monH / g.monW
                val pad = 10.dp.toPx()
                val left = pad
                val top = size.height - mh - pad
                drawRect(Color.Black.copy(alpha = 0.6f * ma), Offset(left - 2, top - 2),
                    androidx.compose.ui.geometry.Size(mw + 4, mh + 4))
                drawIntoCanvas { c ->
                    paint.alpha = (255 * ma).toInt()
                    c.nativeCanvas.drawBitmap(full.bitmap, null, RectF(left, top, left + mw, top + mh), paint)
                    paint.alpha = 255
                }
                val vis = g.visible(v)
                val k = mw / g.monW
                drawRect(Accent.copy(alpha = ma), Offset(left + vis[0] * k, top + vis[1] * k),
                    androidx.compose.ui.geometry.Size((vis[2] - vis[0]) * k, (vis[3] - vis[1]) * k), style = Stroke(1.5.dp.toPx()))
            }
        }
        if (base == null && st.curRegion == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (connected) CircularProgressIndicator(strokeWidth = 3.dp) else Text(state.label(), color = Muted, fontSize = 13.sp)
            }
        }
        // Zoom indicator: appears on change, fades after a second (alpha read in the layer, not composition).
        Text(zoomLabel, color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .graphicsLayer { alpha = st.indicatorAlpha }
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp))
        // Gestures on their own layer under the buttons, so a button tap isn't also a zoom/pan/double-tap.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { zoomGestures(st) })
        if (connected) {
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(onClick = { Haptics.tick(); Prefs.update { it.copy(miniMap = !it.miniMap) } }) {
                    Icon(Icons.Rounded.Map, "Mini-map", tint = if (settings.miniMap) Accent else Color.White.copy(alpha = 0.7f))
                }
                if (pc.monitors.size > 1) {
                    IconButton(onClick = {
                        Haptics.tick()
                        val curMon = base?.header?.monitor ?: 0
                        Prefs.update { it.copy(monitor = (curMon + 1) % pc.monitors.size) }
                        st.entered = false // zoom back in on the new monitor's cursor
                        st.region = null
                        Hub.restartStream()
                    }) { Icon(Icons.Rounded.DesktopWindows, "Next monitor", tint = Color.White.copy(alpha = 0.7f)) }
                }
            }
        }
    }
}

private fun tick(st: ZoomState, ref: Frame?, cursor: com.flipdesk.app.net.Cursor?, now: Long, dt: Float) {
    val g = st.geometry(ref) ?: return
    val h = ref!!.header
    val cur = cursor?.takeIf { it.x >= 0 && it.monW == h.monW }
    if (h.monitor != st.monitor) { // new monitor: start over from the full view
        st.monitor = h.monitor
        st.vp = g.center
        st.entered = false
        st.region = null
    }
    var v = st.vp ?: g.center
    val saved = Prefs.settings.value.laptopZoom.coerceIn(1.5f, ZoomGeometry.MAX_ZOOM)

    if (!st.entered) { // entering Flex mode: glide from the whole desktop into the cursor
        st.entered = true
        st.start(g.center, g.clamp(Viewport(saved, cur?.x?.toFloat() ?: g.monW / 2, cur?.y?.toFloat() ?: g.monH / 2)), now)
    }
    val exit = LaptopZoom.exitRequest
    if (exit != null && st.anim?.exit != true) {
        if (v.zoom <= 1.01f) exit.complete(Unit) else st.start(v, g.center, now, exit = true)
    }

    val a = st.anim
    if (a != null) {
        val t = (now - a.startNanos) / (ZOOM_ANIM_MS * 1_000_000f)
        v = if (t >= 1f) a.to else lerpViewport(a.from, a.to, t)
        if (t >= 1f) {
            st.anim = null
            if (a.exit) LaptopZoom.exitRequest?.complete(Unit)
        }
    } else if (!st.gestureActive && cur != null && v.zoom > 1.01f) {
        st.followPausedAt?.let { p -> if (hypot((cur.x - p.first).toFloat(), (cur.y - p.second).toFloat()) > 6f) st.followPausedAt = null }
        if (st.followPausedAt == null) {
            val target = g.followTarget(v, cur.x.toFloat(), cur.y.toFloat())
            val ft = st.followTarget
            // Tiny target changes are jitter; ignore them.
            if (ft == null || abs(target.cx - ft.cx) > 2f || abs(target.cy - ft.cy) > 2f || abs(target.zoom - ft.zoom) > 0.01f) {
                st.followTarget = target
            }
            val tg = st.followTarget!!
            val k = smoothing(dt, 0.12f)
            v = if (abs(tg.cx - v.cx) < 0.05f && abs(tg.cy - v.cy) < 0.05f) Viewport(v.zoom, tg.cx, tg.cy) // settled: stop redrawing
            else Viewport(v.zoom, v.cx + (tg.cx - v.cx) * k, v.cy + (tg.cy - v.cy) * k)
        }
    }
    v = g.clamp(v)
    if (v != st.vp) st.vp = v

    // Zoom indicator: full for 1 s after the last change, then a 300 ms fade.
    if (abs(v.zoom - st.shownZoom) > 0.01f) {
        st.shownZoom = v.zoom
        st.zoomChangedAt = now
    }
    val since = (now - st.zoomChangedAt) / 1e6f
    val ia = if (st.zoomChangedAt == 0L) 0f else (1f - ((since - 1000f) / 300f)).coerceIn(0f, 1f)
    if (ia != st.indicatorAlpha) st.indicatorAlpha = ia
    val miniTarget = if (Prefs.settings.value.miniMap && v.zoom > 1.05f) 1f else 0f
    val ma = st.miniAlpha + (miniTarget - st.miniAlpha) * smoothing(dt, 0.15f)
    if (abs(ma - st.miniAlpha) > 0.002f || (miniTarget == 0f && ma < 0.01f && st.miniAlpha != 0f)) {
        st.miniAlpha = if (ma < 0.01f && miniTarget == 0f) 0f else ma
    }

    // Region streaming: ask for where the view is going (the animation's end), not where it is.
    val want = st.anim?.to ?: v
    if (want.zoom > 1.15f) {
        if (g.needsRegion(st.region, want) && now - st.lastRegionAt > 70_000_000L) {
            val r = g.regionFor(want)
            st.region = r
            st.lastRegionAt = now
            Hub.setRegion(r.first, r.second.first, r.second.second)
        }
    } else if (st.region != null) {
        st.region = null
        Hub.setRegion(null)
    }
}

/** Top-half gestures in Flex mode: pinch = zoom, drag = pan, double-tap = toggle zoom. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.zoomGestures(st: ZoomState) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        st.gestureActive = true
        var moved = false
        var multi = false
        var upTime = down.uptimeMillis
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.count { it.pressed }
            val g = st.geometry(Hub.fullFrame.value ?: st.curRegion)
            val v = st.vp
            if (g != null && v != null) {
                if (pressed >= 2) {
                    multi = true
                    val c = ev.calculateCentroid()
                    val p = ev.calculatePan()
                    st.anim = null
                    st.vp = g.pinch(v, ev.calculateZoom(), c.x, c.y, p.x, p.y)
                    pauseFollow(st)
                } else if (pressed == 1 && !multi) {
                    val c = ev.changes.first { it.pressed }
                    if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                    if (moved) {
                        val d = c.positionChange()
                        st.anim = null
                        st.vp = g.panBy(v, d.x, d.y)
                        pauseFollow(st)
                    }
                }
            }
            ev.changes.forEach { it.consume() }
            if (ev.changes.none { it.pressed }) {
                upTime = ev.changes.maxOf { it.uptimeMillis }
                break
            }
        }
        st.gestureActive = false
        val v = st.vp
        if (multi && v != null && v.zoom > 1.05f) Prefs.update { it.copy(laptopZoom = v.zoom) }
        if (!moved && !multi && upTime - down.uptimeMillis < 250) {
            if (upTime - st.lastTapAt < 320 && (down.position - st.lastTapPos).getDistance() < 60.dp.toPx()) {
                st.lastTapAt = 0L
                doubleTap(st, down.position)
            } else {
                st.lastTapAt = upTime
                st.lastTapPos = down.position
            }
        }
    }
}

private fun pauseFollow(st: ZoomState) {
    // Manual pan/zoom wins until the PC cursor actually moves again.
    val c = Hub.cursor.value
    st.followPausedAt = if (c != null && c.x >= 0) c.x to c.y else -9999 to -9999
    st.followTarget = null
}

private fun doubleTap(st: ZoomState, at: Offset) {
    val g = st.geometry(Hub.fullFrame.value ?: st.curRegion) ?: return
    val v = st.vp ?: return
    val now = System.nanoTime()
    Haptics.tick()
    if (v.zoom > 1.05f) {
        Prefs.update { it.copy(laptopZoom = v.zoom) }
        st.start(v, g.center, now)
    } else {
        val c = Hub.cursor.value?.takeIf { it.x >= 0 }
        val tx = c?.x?.toFloat() ?: (v.cx + (at.x - g.boxW / 2) / g.scale(v.zoom))
        val ty = c?.y?.toFloat() ?: (v.cy + (at.y - g.boxH / 2) / g.scale(v.zoom))
        st.start(v, g.clamp(Viewport(Prefs.settings.value.laptopZoom.coerceIn(1.5f, ZoomGeometry.MAX_ZOOM), tx, ty)), now)
    }
    st.followPausedAt = null
}
