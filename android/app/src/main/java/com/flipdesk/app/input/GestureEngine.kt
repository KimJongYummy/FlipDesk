package com.flipdesk.app.input

import kotlin.math.abs
import kotlin.math.hypot

enum class MouseButton(val wire: String) { LEFT("left"), RIGHT("right"), MIDDLE("middle") }
enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

/** Where trackpad gestures go. All coordinates handed to the engine are in dp. */
interface TrackpadSink {
    fun move(dx: Int, dy: Int)
    fun click(button: MouseButton)
    fun doubleClick()
    fun buttonDown(button: MouseButton)
    fun buttonUp(button: MouseButton)
    /** Wheel notches (fractional = smooth). +dy scrolls content up, +dx scrolls right. */
    fun scroll(dx: Float, dy: Float)
    fun swipe3(dir: SwipeDir)
    /** Two-finger pinch; [factor] is the span ratio since the previous call. */
    fun pinch(factor: Float, ended: Boolean) {}
}

/**
 * Laptop-trackpad gesture recognizer, independent of Android so it can be unit-tested.
 *
 * - 1 finger move: pointer motion with acceleration
 * - tap: left click (sent after a short wait so a double-tap-drag can claim it)
 * - press and hold still ([holdToDrag]), or double-tap-and-hold (or tap then touch & move):
 *   drag with the left button held
 * - drag lock: lifting mid-drag keeps the button held for [DRAG_LOCK_MS], so the finger can be
 *   put back down to carry on; a tap in that time drops at once
 * - quick double tap: double click
 * - 2-finger tap: right click; 2-finger drag: smooth scroll; 2-finger pinch: [TrackpadSink.pinch]
 * - 3-finger swipe: [TrackpadSink.swipe3]; 3-finger tap: middle click
 * - a flicked scroll glides on after the fingers lift; any new touch stops it
 *
 * With [scrollOnly], the surface is a scroll strip instead: its first finger scrolls vertically
 * and nothing else is recognized.
 */
class GestureEngine(
    private val sink: TrackpadSink,
    val accel: PointerAccel = PointerAccel(),
    private val scrollOnly: Boolean = false,
) {
    var naturalScroll = true
    var scrollSpeed = 1f
    /** Press and hold one finger still for [LONG_PRESS_MS] to grab (left button down). */
    var holdToDrag = true

    private val momentum = ScrollMomentum { dx, dy -> sink.scroll(dx, dy) }

    private class Ptr(val sx: Float, val sy: Float) {
        var x = sx
        var y = sy
        var lx = sx
        var ly = sy
    }

    /** DRAG_LIFTED: no finger down, but the button is still held until [dropAt] (drag lock). */
    private enum class Mode { IDLE, ONE_PENDING, ONE_MOVING, DRAG_PENDING, DRAGGING, DRAG_LIFTED, TWO_PENDING, TWO_SCROLL, TWO_PINCH, THREE, DONE }

    private val ptrs = LinkedHashMap<Long, Ptr>()
    private var mode = Mode.IDLE
    private var gestureStart = 0L
    private var multiStart = 0L
    private var maxPointers = 0
    private var lastFrame = 0L
    private var pendingClickAt: Long? = null
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragDownAt = 0L
    private var dropAt: Long? = null
    /** The current touch picked a locked drag back up (so a quick tap means "drop"). */
    private var resumed = false
    /** This touch can still become a hold-to-drag grab. */
    private var holdArmed = false
    private var baseCx = 0f
    private var baseCy = 0f
    private var baseSpan = 0f
    private var lastSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    val isDragging get() = mode == Mode.DRAGGING || mode == Mode.DRAG_LIFTED

    fun down(id: Long, x: Float, y: Float, t: Long) {
        val caughtGlide = momentum.nextAt != null
        momentum.stop()
        if (scrollOnly && ptrs.isNotEmpty()) return // extra fingers on the strip are ignored
        ptrs[id] = Ptr(x, y)
        if (scrollOnly) {
            mode = Mode.TWO_SCROLL
            lastCx = x
            lastCy = y
            return
        }
        val n = ptrs.size
        if (n == 1) {
            gestureStart = t
            lastFrame = t
            maxPointers = 1
            accel.reset()
            holdArmed = holdToDrag && !caughtGlide // a finger that stopped a glide is just resting
            if (mode == Mode.DRAG_LIFTED) { // finger back down in time: carry on dragging
                dropAt = null
                mode = Mode.DRAGGING
                resumed = true
                return
            }
            val pending = pendingClickAt
            mode = if (pending != null && t - lastTapUp <= DOUBLE_TAP_MS &&
                hypot(x - lastTapX, y - lastTapY) < DOUBLE_TAP_SLOP
            ) {
                pendingClickAt = null
                dragDownAt = t
                Mode.DRAG_PENDING
            } else {
                flushClick()
                Mode.ONE_PENDING
            }
            return
        }
        maxPointers = maxOf(maxPointers, n)
        when (mode) {
            Mode.ONE_PENDING, Mode.ONE_MOVING, Mode.DRAG_PENDING, Mode.IDLE -> if (n == 2) {
                mode = Mode.TWO_PENDING
                multiStart = t
                resetMultiBaseline()
            }
            Mode.TWO_PENDING, Mode.TWO_SCROLL, Mode.TWO_PINCH -> if (n == 3) {
                if (mode == Mode.TWO_PINCH) sink.pinch(1f, ended = true)
                mode = Mode.THREE
                resetMultiBaseline()
            }
            else -> {}
        }
    }

    fun move(id: Long, x: Float, y: Float) {
        ptrs[id]?.let { it.x = x; it.y = y }
    }

    /** Call once after feeding all [move]s of one input event. */
    fun frame(t: Long) {
        val dt = (t - lastFrame).coerceAtLeast(1)
        lastFrame = t
        when (mode) {
            Mode.ONE_PENDING -> single()?.let { p ->
                if (hypot(p.x - p.sx, p.y - p.sy) > SLOP) {
                    mode = Mode.ONE_MOVING
                    emitMove(p, dt)
                }
            }
            Mode.ONE_MOVING, Mode.DRAGGING -> single()?.let { emitMove(it, dt) }
            Mode.DRAG_PENDING -> single()?.let { p ->
                if (hypot(p.x - p.sx, p.y - p.sy) > SLOP) {
                    startDrag()
                    emitMove(p, dt)
                }
            }
            Mode.TWO_PENDING -> {
                val (cx, cy) = centroid()
                val span = span()
                val moved = hypot(cx - baseCx, cy - baseCy)
                val spanDelta = abs(span - baseSpan)
                if (spanDelta > PINCH_SLOP && spanDelta > moved * 1.2f) {
                    mode = Mode.TWO_PINCH
                    lastSpan = span
                } else if (moved > SLOP) {
                    mode = Mode.TWO_SCROLL
                    lastCx = cx
                    lastCy = cy
                }
            }
            Mode.TWO_SCROLL -> {
                val (cx, cy) = centroid()
                val sign = if (naturalScroll) 1f else -1f
                val k = scrollSpeed / NOTCH_DP
                val sx = if (scrollOnly) 0f else -(cx - lastCx) * k * sign
                val sy = (cy - lastCy) * k * sign
                lastCx = cx
                lastCy = cy
                if (sx != 0f || sy != 0f) {
                    sink.scroll(sx, sy)
                    momentum.track(t, sx, sy)
                }
            }
            Mode.TWO_PINCH -> {
                val span = span()
                if (lastSpan > 1f && span > 1f && span != lastSpan) sink.pinch(span / lastSpan, ended = false)
                lastSpan = span
            }
            Mode.THREE -> {
                val (cx, cy) = centroid()
                val dx = cx - baseCx
                val dy = cy - baseCy
                if (hypot(dx, dy) > SWIPE_DP) {
                    sink.swipe3(
                        if (abs(dy) > abs(dx)) (if (dy < 0) SwipeDir.UP else SwipeDir.DOWN)
                        else (if (dx < 0) SwipeDir.LEFT else SwipeDir.RIGHT)
                    )
                    mode = Mode.DONE
                }
            }
            Mode.IDLE, Mode.DONE, Mode.DRAG_LIFTED -> {}
        }
        for (p in ptrs.values) {
            p.lx = p.x
            p.ly = p.y
        }
    }

    fun up(id: Long, t: Long) {
        val p = ptrs.remove(id) ?: return
        val left = ptrs.size
        when (mode) {
            Mode.ONE_PENDING -> if (left == 0) {
                if (t - gestureStart <= TAP_MS) {
                    pendingClickAt = t + DOUBLE_TAP_MS
                    lastTapUp = t
                    lastTapX = p.sx
                    lastTapY = p.sy
                }
                mode = Mode.IDLE
            }
            Mode.ONE_MOVING -> if (left == 0) mode = Mode.IDLE
            Mode.DRAG_PENDING -> if (left == 0) {
                sink.doubleClick()
                mode = Mode.IDLE
            }
            Mode.DRAGGING -> if (left == 0) {
                val tapped = t - gestureStart <= TAP_MS && hypot(p.x - p.sx, p.y - p.sy) <= SLOP
                if (resumed && tapped) drop()
                else {
                    mode = Mode.DRAG_LIFTED
                    dropAt = t + DRAG_LOCK_MS
                }
            }
            Mode.TWO_PENDING -> {
                if (maxPointers == 2 && t - multiStart <= TAP_MS * 3 / 2) sink.click(MouseButton.RIGHT)
                mode = if (left == 0) Mode.IDLE else Mode.DONE
            }
            Mode.TWO_SCROLL -> {
                momentum.release(t)
                mode = if (left == 0) Mode.IDLE else Mode.DONE
            }
            Mode.TWO_PINCH -> {
                sink.pinch(1f, ended = true)
                mode = if (left == 0) Mode.IDLE else Mode.DONE
            }
            Mode.THREE -> {
                if (t - multiStart <= TAP_MS * 3 / 2) sink.click(MouseButton.MIDDLE)
                mode = if (left == 0) Mode.IDLE else Mode.DONE
            }
            Mode.DONE, Mode.IDLE -> if (left == 0) mode = Mode.IDLE
            Mode.DRAG_LIFTED -> {} // no finger is down in this mode
        }
    }

    /** Touch stream was cancelled (e.g. the system took over the gesture). */
    fun cancel() {
        if (mode == Mode.DRAGGING || mode == Mode.DRAG_LIFTED) sink.buttonUp(MouseButton.LEFT)
        if (mode == Mode.TWO_PINCH) sink.pinch(1f, ended = true)
        momentum.stop()
        ptrs.clear()
        dropAt = null
        mode = Mode.IDLE
    }

    /** When the caller should invoke [onTimer] next, or null if no timer is pending. */
    fun nextTimerAt(): Long? {
        val a = pendingClickAt
        val b = if (mode == Mode.DRAG_PENDING) dragDownAt + HOLD_MS else null
        val c = if (holdArmed && mode == Mode.ONE_PENDING && ptrs.size == 1) gestureStart + LONG_PRESS_MS else null
        val d = if (mode == Mode.DRAG_LIFTED) dropAt else null
        return listOfNotNull(a, b, c, d, momentum.nextAt).minOrNull()
    }

    fun onTimer(t: Long) {
        momentum.step(t)
        pendingClickAt?.let { if (t >= it) flushClick() }
        if (mode == Mode.DRAG_PENDING && t >= dragDownAt + HOLD_MS) startDrag()
        if (holdArmed && mode == Mode.ONE_PENDING && t >= gestureStart + LONG_PRESS_MS) single()?.let { p ->
            // A finger that has already slid off is a pointer move whose frame hasn't arrived yet.
            if (hypot(p.x - p.sx, p.y - p.sy) <= SLOP) startDrag() else mode = Mode.ONE_MOVING
        }
        if (mode == Mode.DRAG_LIFTED && t >= (dropAt ?: Long.MAX_VALUE)) drop()
    }

    private fun drop() {
        sink.buttonUp(MouseButton.LEFT)
        dropAt = null
        resumed = false
        mode = Mode.IDLE
    }

    private fun flushClick() {
        if (pendingClickAt != null) {
            pendingClickAt = null
            sink.click(MouseButton.LEFT)
        }
    }

    private fun startDrag() {
        mode = Mode.DRAGGING
        resumed = false
        sink.buttonDown(MouseButton.LEFT)
    }

    private fun emitMove(p: Ptr, dt: Long) {
        val (dx, dy) = accel.applyInt(p.x - p.lx, p.y - p.ly, dt)
        if (dx != 0 || dy != 0) sink.move(dx, dy)
    }

    private fun single(): Ptr? = if (ptrs.size == 1) ptrs.values.first() else null

    private fun centroid(): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (p in ptrs.values) { x += p.x; y += p.y }
        val n = ptrs.size.coerceAtLeast(1)
        return Pair(x / n, y / n)
    }

    private fun span(): Float {
        val l = ptrs.values.toList()
        return if (l.size < 2) 0f else hypot(l[0].x - l[1].x, l[0].y - l[1].y)
    }

    private fun resetMultiBaseline() {
        val (cx, cy) = centroid()
        baseCx = cx; baseCy = cy
        lastCx = cx; lastCy = cy
        baseSpan = span()
        lastSpan = baseSpan
    }

    companion object {
        const val TAP_MS = 220L
        const val DOUBLE_TAP_MS = 230L
        const val HOLD_MS = 140L
        const val LONG_PRESS_MS = 400L
        const val DRAG_LOCK_MS = 500L
        const val SLOP = 6f
        const val DOUBLE_TAP_SLOP = 40f
        const val PINCH_SLOP = 18f
        const val SWIPE_DP = 56f
        const val NOTCH_DP = 32f
    }
}
