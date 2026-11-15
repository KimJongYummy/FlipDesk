package com.flipdesk.app.input

import kotlin.math.exp
import kotlin.math.hypot

/**
 * Keeps a scroll going after the finger lifts, like a laptop trackpad: a flick glides on and
 * slows to a stop, while a slow drag or a finger that rested before lifting just stops.
 * Deltas are wheel notches and speeds are notches per second.
 */
class ScrollMomentum(private val emit: (dx: Float, dy: Float) -> Unit) {
    private class Sample(val t: Long, val dx: Float, val dy: Float)

    private val samples = ArrayDeque<Sample>()
    private var vx = 0f
    private var vy = 0f
    private var lastStep = 0L
    private var gliding = false

    /** When [step] should run next, or null if nothing is gliding. */
    val nextAt: Long? get() = if (gliding) lastStep + TICK_MS else null

    /** Record a delta the finger just scrolled. */
    fun track(t: Long, dx: Float, dy: Float) {
        samples.addLast(Sample(t, dx, dy))
        while (samples.first().t < t - WINDOW_MS) samples.removeFirst()
    }

    /** The finger lifted: start gliding if it was still moving fast enough. */
    fun release(t: Long) {
        val recent = samples.filter { it.t >= t - WINDOW_MS }
        samples.clear()
        if (recent.size < 2 || t - recent.last().t > REST_MS) return
        // The first sample's delta happened before its timestamp, so only the later ones count.
        val seconds = (recent.last().t - recent.first().t).coerceAtLeast(1) / 1000f
        val sx = recent.drop(1).sumOf { it.dx.toDouble() }.toFloat() / seconds
        val sy = recent.drop(1).sumOf { it.dy.toDouble() }.toFloat() / seconds
        val speed = hypot(sx, sy)
        if (speed < MIN_START_SPEED) return
        val cap = if (speed > MAX_SPEED) MAX_SPEED / speed else 1f
        vx = sx * cap
        vy = sy * cap
        lastStep = t
        gliding = true
    }

    fun step(t: Long) {
        if (!gliding || t < lastStep + TICK_MS) return
        // Integrating the decay exactly keeps the glide the same length however late a tick runs.
        val dt = (t - lastStep).coerceAtMost(MAX_STEP_MS) / 1000f
        lastStep = t
        val decay = exp(-dt / DECAY_SECONDS)
        val moved = DECAY_SECONDS * (1 - decay)
        emit(vx * moved, vy * moved)
        vx *= decay
        vy *= decay
        if (hypot(vx, vy) < MIN_GLIDE_SPEED) gliding = false
    }

    fun stop() {
        gliding = false
        samples.clear()
    }

    companion object {
        const val TICK_MS = 8L
        const val WINDOW_MS = 80L
        const val REST_MS = 50L
        const val MAX_STEP_MS = 50L
        const val DECAY_SECONDS = 0.35f
        const val MIN_START_SPEED = 3f
        const val MIN_GLIDE_SPEED = 0.4f
        const val MAX_SPEED = 60f
    }
}
