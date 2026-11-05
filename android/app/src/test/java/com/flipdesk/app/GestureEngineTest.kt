package com.flipdesk.app

import com.flipdesk.app.input.GestureEngine
import com.flipdesk.app.input.MouseButton
import com.flipdesk.app.input.SwipeDir
import com.flipdesk.app.input.TrackpadSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureEngineTest {
    private val events = mutableListOf<String>()
    private var moved = 0 to 0
    private var scrolled = 0f to 0f
    private lateinit var e: GestureEngine

    private val sink = object : TrackpadSink {
        override fun move(dx: Int, dy: Int) { moved = (moved.first + dx) to (moved.second + dy) }
        override fun click(button: MouseButton) { events += "click:${button.wire}" }
        override fun doubleClick() { events += "double" }
        override fun buttonDown(button: MouseButton) { events += "down:${button.wire}" }
        override fun buttonUp(button: MouseButton) { events += "up:${button.wire}" }
        override fun scroll(dx: Float, dy: Float) { scrolled = (scrolled.first + dx) to (scrolled.second + dy) }
        override fun swipe3(dir: SwipeDir) { events += "swipe:$dir" }
        override fun pinch(factor: Float, ended: Boolean) { if (ended) events += "pinch-end" }
    }

    @Before
    fun setUp() {
        events.clear(); moved = 0 to 0; scrolled = 0f to 0f
        e = GestureEngine(sink)
    }

    private fun drag(id: Long, x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, steps: Int = 10, dur: Long = 100) {
        for (i in 1..steps) {
            e.move(id, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
            e.frame(t0 + dur * i / steps)
        }
    }

    private fun fire(t: Long) { e.nextTimerAt()?.let { if (it <= t) e.onTimer(t) } }

    @Test fun tapClicksAfterDoubleTapWindow() {
        e.down(1, 100f, 100f, 0); e.up(1, 80)
        assertTrue(events.isEmpty())                 // waits to see if a drag follows
        fire(80 + GestureEngine.DOUBLE_TAP_MS)
        assertEquals(listOf("click:left"), events)
    }

    @Test fun longPressIsNotAClick() {
        e.down(1, 100f, 100f, 0); e.up(1, 600); fire(2000)
        assertTrue(events.isEmpty())
    }

    @Test fun oneFingerMovesPointerRight() {
        e.down(1, 100f, 100f, 0)
        drag(1, 100f, 100f, 200f, 100f, 0)
        e.up(1, 120); fire(1000)
        assertTrue(moved.first > 100 && moved.second == 0)
        assertTrue("moving must not click", events.isEmpty())
    }

    @Test fun doubleTapAndHoldDrags() {
        e.down(1, 100f, 100f, 0); e.up(1, 60)
        e.down(1, 102f, 101f, 150)
        fire(150 + GestureEngine.HOLD_MS)            // held -> button goes down
        assertEquals(listOf("down:left"), events)
        drag(1, 102f, 101f, 102f, 300f, 300)
        e.up(1, 450)
        assertEquals(listOf("down:left", "up:left"), events)
        assertTrue(moved.second > 100)
    }

    @Test fun tapThenTouchAndMoveDragsImmediately() {
        e.down(1, 100f, 100f, 0); e.up(1, 60)
        e.down(1, 100f, 100f, 120)
        drag(1, 100f, 100f, 160f, 100f, 125, steps = 3, dur = 30)
        assertEquals(listOf("down:left"), events)
        e.up(1, 200)
        assertEquals("up:left", events.last())
    }

    @Test fun quickDoubleTapIsDoubleClick() {
        e.down(1, 100f, 100f, 0); e.up(1, 60)
        e.down(1, 100f, 100f, 120); e.up(1, 170)
        fire(1000)
        assertEquals(listOf("double"), events)
    }

    @Test fun twoFingerTapRightClicks() {
        e.down(1, 100f, 100f, 0); e.down(2, 160f, 100f, 15)
        e.up(1, 120); e.up(2, 130); fire(1000)
        assertEquals(listOf("click:right"), events)
    }

    @Test fun twoFingerDragScrollsBothAxesWithoutMovingPointer() {
        e.down(1, 100f, 100f, 0); e.down(2, 160f, 100f, 5)
        for (i in 1..10) {
            e.move(1, 100f + i * 4, 100f + i * 10); e.move(2, 160f + i * 4, 100f + i * 10); e.frame(10L + i * 16)
        }
        e.up(1, 300); e.up(2, 310); fire(1000)
        assertEquals(0 to 0, moved)
        assertTrue("natural scroll: fingers down = scroll up", scrolled.second > 2f)
        assertTrue("fingers right = content right = scroll left", scrolled.first < 0f)
        assertTrue(events.isEmpty())
    }

    @Test fun reverseScrollSetting() {
        e.naturalScroll = false
        e.down(1, 100f, 100f, 0); e.down(2, 160f, 100f, 5)
        for (i in 1..10) { e.move(1, 100f, 100f + i * 10); e.move(2, 160f, 100f + i * 10); e.frame(10L + i * 16) }
        assertTrue(scrolled.second < -2f)
    }

    @Test fun pinchIsNotScroll() {
        e.down(1, 100f, 100f, 0); e.down(2, 200f, 100f, 5)
        for (i in 1..10) { e.move(1, 100f - i * 6, 100f); e.move(2, 200f + i * 6, 100f); e.frame(10L + i * 16) }
        e.up(1, 300); e.up(2, 300)
        assertEquals(0f to 0f, scrolled)
        assertEquals(listOf("pinch-end"), events)
    }

    @Test fun threeFingerSwipes() {
        for ((dx, dy, dir) in listOf(Triple(0f, -90f, SwipeDir.UP), Triple(-90f, 0f, SwipeDir.LEFT), Triple(90f, 0f, SwipeDir.RIGHT))) {
            events.clear()
            e.down(1, 100f, 200f, 0); e.down(2, 150f, 200f, 5); e.down(3, 200f, 200f, 10)
            for (i in 1..10) {
                e.move(1, 100f + dx * i / 10, 200f + dy * i / 10)
                e.move(2, 150f + dx * i / 10, 200f + dy * i / 10)
                e.move(3, 200f + dx * i / 10, 200f + dy * i / 10)
                e.frame(20L + i * 16)
            }
            e.up(1, 400); e.up(2, 400); e.up(3, 400); fire(2000)
            assertEquals(listOf("swipe:$dir"), events)
        }
    }

    @Test fun liftingOneFingerAfterScrollDoesNotJumpPointer() {
        e.down(1, 100f, 100f, 0); e.down(2, 160f, 100f, 5)
        for (i in 1..5) { e.move(1, 100f, 100f + i * 10); e.move(2, 160f, 100f + i * 10); e.frame(10L + i * 16) }
        e.up(2, 200)
        drag(1, 100f, 150f, 300f, 150f, 210)
        assertEquals(0 to 0, moved)
    }
}
