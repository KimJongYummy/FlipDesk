package com.flipdesk.app.input

import kotlin.math.hypot

/**
 * Velocity-dependent pointer gain: slow finger motion is precise (about 1:1 at low
 * sensitivity), and fast flicks cover a whole monitor. Input is in dp, output in PC pixels.
 */
class PointerAccel(
    var sensitivity: Float = 1f,   // 0.3 .. 3
    var acceleration: Float = 1f,  // 0 = linear .. 2 = strong
) {
    private var remX = 0f
    private var remY = 0f

    /** Returns PC-pixel deltas; keeps fractional remainders between calls. */
    fun apply(dxDp: Float, dyDp: Float, dtMs: Long): Pair<Float, Float> {
        val dt = dtMs.coerceIn(4, 100).toFloat()
        val speed = hypot(dxDp, dyDp) / dt // dp per ms
        // Linear below ~0.15 dp/ms, then a smoothstep ramp up to (1 + 3*accel)x by ~1.5 dp/ms.
        val r = ((speed - 0.15f) / 1.35f).coerceIn(0f, 1f)
        val ramp = r * r * (3f - 2f * r)
        val gain = BASE_GAIN * sensitivity * (1f + 3f * acceleration * ramp)
        return Pair(dxDp * gain, dyDp * gain)
    }

    /** Like [apply] but returns whole pixels, carrying sub-pixel remainders. */
    fun applyInt(dxDp: Float, dyDp: Float, dtMs: Long): Pair<Int, Int> {
        val (fx, fy) = apply(dxDp, dyDp, dtMs)
        remX += fx
        remY += fy
        val ix = remX.toInt()
        val iy = remY.toInt()
        remX -= ix
        remY -= iy
        return Pair(ix, iy)
    }

    fun reset() {
        remX = 0f
        remY = 0f
    }

    companion object {
        const val BASE_GAIN = 1.6f
    }
}
