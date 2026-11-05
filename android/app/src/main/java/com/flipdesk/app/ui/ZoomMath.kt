package com.flipdesk.app.ui

import com.flipdesk.app.net.StreamRegion
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** What the zoomed view shows: a zoom factor and the monitor pixel at the view's center. */
data class Viewport(val zoom: Float, val cx: Float, val cy: Float)

/**
 * Geometry of a monitor (monW × monH px) shown in a box (boxW × boxH screen px).
 * At zoom 1 the whole monitor fits the box; zoom z magnifies that by z around (cx, cy).
 */
class ZoomGeometry(val boxW: Float, val boxH: Float, val monW: Float, val monH: Float) {
    /** Screen px per monitor px at zoom 1. */
    val s0 = min(boxW / monW, boxH / monH)

    fun scale(z: Float) = s0 * z
    fun halfW(z: Float) = boxW / (2 * scale(z))
    fun halfH(z: Float) = boxH / (2 * scale(z))

    val center get() = Viewport(1f, monW / 2, monH / 2)

    /** Keep the zoom in range and never show past the monitor's edges. */
    fun clamp(v: Viewport): Viewport {
        val z = v.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return Viewport(z, clampAxis(v.cx, halfW(z), monW), clampAxis(v.cy, halfH(z), monH))
    }

    private fun clampAxis(c: Float, half: Float, size: Float) =
        if (half * 2 >= size) size / 2 else c.coerceIn(half, size - half)

    fun toScreenX(mx: Float, v: Viewport) = boxW / 2 + (mx - v.cx) * scale(v.zoom)
    fun toScreenY(my: Float, v: Viewport) = boxH / 2 + (my - v.cy) * scale(v.zoom)

    /** Visible part of the monitor: x0, y0, x1, y1 in monitor px. */
    fun visible(v: Viewport): FloatArray {
        val hw = halfW(v.zoom)
        val hh = halfH(v.zoom)
        return floatArrayOf(max(0f, v.cx - hw), max(0f, v.cy - hh), min(monW, v.cx + hw), min(monH, v.cy + hh))
    }

    /**
     * Follow-cursor target. The cursor moves freely inside a central zone ([zone] of the half
     * extent on each axis); once it leaves, the view re-centers just enough to bring it back to
     * [settle] (hysteresis, so it doesn't oscillate at the zone edge).
     */
    fun followTarget(v: Viewport, curX: Float, curY: Float, zone: Float = FOLLOW_ZONE, settle: Float = FOLLOW_SETTLE): Viewport {
        fun axis(c: Float, cur: Float, half: Float): Float {
            val d = cur - c
            return if (abs(d) <= half * zone) c else cur - sign(d) * half * settle
        }
        return clamp(Viewport(v.zoom, axis(v.cx, curX, halfW(v.zoom)), axis(v.cy, curY, halfH(v.zoom))))
    }

    /** Pinch around a screen-space focal point, plus a two-finger pan (screen px). */
    fun pinch(v: Viewport, factor: Float, focusX: Float, focusY: Float, panX: Float = 0f, panY: Float = 0f): Viewport {
        val z = (v.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val fx = v.cx + (focusX - boxW / 2) / scale(v.zoom) // monitor px under the fingers
        val fy = v.cy + (focusY - boxH / 2) / scale(v.zoom)
        return clamp(Viewport(z, fx - (focusX - boxW / 2) / scale(z) - panX / scale(z),
            fy - (focusY - boxH / 2) / scale(z) - panY / scale(z)))
    }

    fun panBy(v: Viewport, dxScreen: Float, dyScreen: Float) =
        clamp(Viewport(v.zoom, v.cx - dxScreen / scale(v.zoom), v.cy - dyScreen / scale(v.zoom)))

    /** Region to request: the visible area plus [margin] on each side, and its pixel budget. */
    fun regionFor(v: Viewport, margin: Float = REGION_MARGIN): Pair<StreamRegion, Pair<Int, Int>> {
        val (x0, y0, x1, y1) = visible(v).toList()
        val mx = (x1 - x0) * margin
        val my = (y1 - y0) * margin
        val rx0 = max(0f, x0 - mx).toInt()
        val ry0 = max(0f, y0 - my).toInt()
        val rx1 = min(monW, x1 + mx).roundToInt()
        val ry1 = min(monH, y1 + my).roundToInt()
        val r = StreamRegion(rx0, ry0, max(16, rx1 - rx0), max(16, ry1 - ry0))
        // Ask for as many phone pixels as the region will cover on screen (the PC never upscales).
        val s = scale(v.zoom)
        return r to (min(MAX_REGION_PX, (r.w * s).roundToInt()) to min(MAX_REGION_PX, (r.h * s).roundToInt()))
    }

    /** Do we need a new region for [v], given the one we have (and its pixel budget)? */
    fun needsRegion(current: Pair<StreamRegion, Pair<Int, Int>>?, v: Viewport): Boolean {
        if (current == null) return true
        val (r, budget) = current
        val (x0, y0, x1, y1) = visible(v).toList()
        if (!r.contains(x0, y0, x1, y1)) return true
        // Phone px per monitor px the current region delivers (capped at native).
        val have = min(1f, min(budget.first.toFloat() / r.w, budget.second.toFloat() / r.h))
        val want = min(1f, scale(v.zoom))
        return want > have * 1.2f || (r.w * r.h) > (x1 - x0) * (y1 - y0) * 4f // much too big = wasted pixels
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val FOLLOW_ZONE = 0.5f
        const val FOLLOW_SETTLE = 0.3f
        const val REGION_MARGIN = 0.25f
        const val MAX_REGION_PX = 2048
    }
}

/** Interpolation used for the Flex-mode zoom in/out: log-space zoom, eased center. */
fun lerpViewport(a: Viewport, b: Viewport, t: Float): Viewport {
    val e = easeInOutCubic(t.coerceIn(0f, 1f))
    val z = exp(ln(a.zoom) + (ln(b.zoom) - ln(a.zoom)) * e)
    return Viewport(z, a.cx + (b.cx - a.cx) * e, a.cy + (b.cy - a.cy) * e)
}

fun easeInOutCubic(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2

/** Frame-rate independent exponential smoothing factor for time constant [tau] seconds. */
fun smoothing(dt: Float, tau: Float) = 1f - exp(-dt / tau)
