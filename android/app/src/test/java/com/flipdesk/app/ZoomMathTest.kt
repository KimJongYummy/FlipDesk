package com.flipdesk.app

import com.flipdesk.app.net.StreamRegion
import com.flipdesk.app.net.VideoHeader
import com.flipdesk.app.ui.Viewport
import com.flipdesk.app.ui.ZoomGeometry
import com.flipdesk.app.ui.lerpViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ZoomMathTest {
    // Flip8 top half in Flex mode ≈ 1080 × 1150 px showing a 1920 × 1080 monitor.
    private val g = ZoomGeometry(1080f, 1150f, 1920f, 1080f)

    @Test fun zoomOneShowsWholeMonitorCentered() {
        val v = g.clamp(Viewport(1f, 100f, 900f))
        assertEquals(960f, v.cx, 0.01f)
        assertEquals(540f, v.cy, 0.01f)
        val vis = g.visible(v)
        assertEquals(0f, vis[0], 0.01f); assertEquals(1920f, vis[2], 0.01f)
        // Letterboxed: monitor occupies the full width, centered vertically.
        assertEquals(0f, g.toScreenX(0f, v), 0.01f)
        assertEquals(1080f, g.toScreenX(1920f, v), 0.01f)
    }

    @Test fun clampKeepsViewInsideMonitorAndZoomInRange() {
        val v = g.clamp(Viewport(9f, 0f, 0f))
        assertEquals(4f, v.zoom, 0f)
        val vis = g.visible(v)
        assertEquals(0f, vis[0], 0.01f)
        assertTrue(vis[2] < 1920f)
        assertEquals(1f, g.clamp(Viewport(0.2f, 0f, 0f)).zoom, 0f)
    }

    @Test fun followHasDeadzoneThenPansWithHysteresis() {
        val v = g.clamp(Viewport(2.5f, 960f, 540f))
        val hw = g.halfW(2.5f)
        // Inside the central zone: no movement.
        assertEquals(v, g.followTarget(v, 960f + hw * 0.4f, 540f))
        // Beyond it: re-center so the cursor sits at the settle point, not at dead center.
        val t = g.followTarget(v, 960f + hw * 0.8f, 540f)
        val curX = 960f + hw * 0.8f
        assertEquals(curX - hw * 0.3f, t.cx, 0.5f)
        // And the new target is stable for the same cursor (no oscillation).
        assertEquals(t, g.followTarget(t, curX, 540f))
    }

    @Test fun pinchKeepsFocalPointUnderFingers() {
        val v = g.clamp(Viewport(2f, 960f, 540f))
        val fx = 300f
        val fy = 500f
        val before = Pair(v.cx + (fx - 540f) / g.scale(2f), v.cy + (fy - 575f) / g.scale(2f))
        val p = g.pinch(v, 1.5f, fx, fy)
        assertEquals(3f, p.zoom, 0.001f)
        val after = Pair(p.cx + (fx - 540f) / g.scale(3f), p.cy + (fy - 575f) / g.scale(3f))
        assertEquals(before.first, after.first, 0.5f)
        assertEquals(before.second, after.second, 0.5f)
    }

    @Test fun regionCoversVisiblePlusMarginAtScreenResolution() {
        val v = g.clamp(Viewport(2.5f, 900f, 500f))
        val (r, budget) = g.regionFor(v)
        val vis = g.visible(v)
        assertTrue(r.contains(vis[0], vis[1], vis[2], vis[3]))
        assertTrue(r.w > vis[2] - vis[0])
        // At 2.5x, one monitor px is ~1.4 phone px: budget ~= region size * scale.
        assertEquals((r.w * g.scale(2.5f)).toInt().toFloat(), budget.first.toFloat(), 2f)
        assertFalse(g.needsRegion(r to budget, v))
        // Small pan stays inside the margin: no refetch.
        assertFalse(g.needsRegion(r to budget, g.panBy(v, 40f, 0f)))
        // Big pan or zooming in further does need a new region.
        assertTrue(g.needsRegion(r to budget, g.panBy(v, -600f, 0f)))
        assertTrue(g.needsRegion(r to budget, g.clamp(Viewport(3.8f, v.cx, v.cy))))
        assertTrue(g.needsRegion(null, v))
    }

    @Test fun zoomAnimationIsMonotonicAndEndsExactly() {
        val a = Viewport(1f, 960f, 540f)
        val b = Viewport(2.5f, 1400f, 300f)
        var last = 0f
        for (i in 0..20) {
            val z = lerpViewport(a, b, i / 20f).zoom
            assertTrue(z >= last)
            last = z
        }
        assertEquals(2.5f, lerpViewport(a, b, 1f).zoom, 0.001f)
        assertEquals(1400f, lerpViewport(a, b, 1f).cx, 0.001f)
    }

    @Test fun parsesRegionHeader() {
        val b = ByteBuffer.allocate(26)
        b.put(1).putInt(9).putShort(1920.toShort()).putShort(1080.toShort()).putShort(10).putShort(20)
            .put(0).put(80).put(1)
            .putShort(100).putShort(200).putShort(640).putShort(360).put(byteArrayOf(-1, -40))
        val h = VideoHeader.parse(b.array())
        assertNotNull(h)
        assertEquals(StreamRegion(100, 200, 640, 360), h!!.region)
        assertEquals(24, h.jpegOffset)
    }
}
