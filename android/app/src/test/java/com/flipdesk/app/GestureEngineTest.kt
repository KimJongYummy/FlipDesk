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

    @Test fun longPressIsNotAClickWhenHoldToDragIsOff() {
        e.holdToDrag = false
        e.down(1, 100f, 100f, 0); fire(500); e.up(1, 600); fire(2000)
        assertTrue(events.isEmpty())
    }

    @Test fun holdStillThenMoveDragsAWindow() {
        e.down(1, 100f, 100f, 0)
        fire(GestureEngine.LONG_PRESS_MS - 1)
        assertTrue("not yet", events.isEmpty())
        fire(GestureEngine.LONG_PRESS_MS)
        assertEquals("grabbed", listOf("down:left"), events)
        drag(1, 100f, 100f, 250f, 160f, 450)
        assertTrue(moved.first > 100 && moved.second > 30)
        e.up(1, 560)
        assertEquals("still held just after the lift (drag lock)", listOf("down:left"), events)
        fire(560 + GestureEngine.DRAG_LOCK_MS)
        assertEquals(listOf("down:left", "up:left"), events)
        assertEquals(null, e.nextTimerAt())
    }

    @Test fun movingBeforeTheHoldIsJustAPointerMove() {
        e.down(1, 100f, 100f, 0)
        drag(1, 100f, 100f, 200f, 100f, 0, dur = 300)
        fire(1000)
        e.up(1, 1100); fire(3000)
        assertTrue(events.isEmpty())
        assertTrue(moved.first > 50)
    }

    @Test fun fingerSlidOffBeforeItsFrameDoesNotGrab() {
        e.down(1, 100f, 100f, 0)
        e.move(1, 140f, 100f)                         // moved, but frame() not called yet
        fire(GestureEngine.LONG_PRESS_MS)
        assertTrue(events.isEmpty())
        assertEquals("no busy timer left behind", null, e.nextTimerAt())
    }

    @Test fun dragLockLetsTheFingerBePutBackDown() {
        e.down(1, 100f, 100f, 0); fire(GestureEngine.LONG_PRESS_MS)
        drag(1, 100f, 100f, 300f, 100f, 450)
        e.up(1, 560)
        e.down(2, 40f, 100f, 800)                     // re-placed within the lock time
        assertEquals(null, e.nextTimerAt())
        drag(2, 40f, 100f, 240f, 100f, 810)
        val firstStroke = moved.first
        e.up(2, 920); fire(920 + GestureEngine.DRAG_LOCK_MS)
        assertEquals("one grab, one drop", listOf("down:left", "up:left"), events)
        assertTrue(firstStroke > 300)
    }

    @Test fun tapDuringDragLockDropsAtOnce() {
        e.down(1, 100f, 100f, 0); fire(GestureEngine.LONG_PRESS_MS)
        drag(1, 100f, 100f, 300f, 100f, 450)
        e.up(1, 560)
        e.down(2, 120f, 100f, 700); e.up(2, 760)
        assertEquals(listOf("down:left", "up:left"), events)
        fire(3000)
        assertEquals("the tap is not also a click", listOf("down:left", "up:left"), events)
    }

    @Test fun cancelReleasesALockedDrag() {
        e.down(1, 100f, 100f, 0); fire(GestureEngine.LONG_PRESS_MS)
        e.up(1, 500)
        e.cancel()
        assertEquals(listOf("down:left", "up:left"), events)
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
        e.up(1, 450); fire(450 + GestureEngine.DRAG_LOCK_MS)
        assertEquals(listOf("down:left", "up:left"), events)
        assertTrue(moved.second > 100)
    }

    @Test fun tapThenTouchAndMoveDragsImmediately() {
        e.down(1, 100f, 100f, 0); e.up(1, 60)
        e.down(1, 100f, 100f, 120)
        drag(1, 100f, 100f, 160f, 100f, 125, steps = 3, dur = 30)
        assertEquals(listOf("down:left"), events)
        e.up(1, 200); fire(200 + GestureEngine.DRAG_LOCK_MS)
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

    /** Two fingers flick down fast; returns the time of the last frame. */
    private fun flick(): Long {
        e.down(1, 100f, 100f, 0); e.down(2, 160f, 100f, 2)
        for (i in 1..10) { e.move(1, 100f, 100f + i * 16); e.move(2, 160f, 100f + i * 16); e.frame(10L + i * 8) }
        return 90L
    }

    /** Runs the engine's timers from [from] until they stop, as the UI would. */
    private fun runTimers(from: Long) {
        var guard = 0
        var t = from
        while (true) {
            t = maxOf(t, e.nextTimerAt() ?: return)
            e.onTimer(t)
            assertTrue("glide never ended", ++guard < 2000)
        }
    }

    @Test fun flickGlidesOnThenStops() {
        val t = flick()
        val atLift = scrolled.second
        e.up(1, t + 2); e.up(2, t + 2)
        runTimers(t + 2)
        assertTrue("kept scrolling after the lift", scrolled.second > atLift + 1f)
        assertEquals("the glide ended", null, e.nextTimerAt())
    }

    @Test fun glideIsTheSameLengthWhateverTheTickRate() {
        val t = flick()
        val atLift = scrolled.second
        e.up(1, t + 2); e.up(2, t + 2)
        runTimers(t + 2)
        val fine = scrolled.second - atLift

        setUp()
        val t2 = flick()
        val atLift2 = scrolled.second
        e.up(1, t2 + 2); e.up(2, t2 + 2)
        var at = t2 + 2
        while (e.nextTimerAt() != null) { at += 30; e.onTimer(at) }
        assertEquals(fine, scrolled.second - atLift2, fine * 0.1f)
    }

    @Test fun touchingStopsAGlide() {
        val t = flick()
        e.up(1, t + 2); e.up(2, t + 2)
        e.onTimer(t + 12)
        val caught = scrolled.second
        e.down(3, 100f, 100f, t + 20)
        assertEquals(null, e.nextTimerAt())
        e.onTimer(t + 40)
        assertEquals(caught, scrolled.second)
        e.onTimer(t + 20 + GestureEngine.LONG_PRESS_MS)
        assertTrue("the finger that caught the glide doesn't grab", events.isEmpty())
    }

    @Test fun fingerRestingBeforeLiftDoesNotGlide() {
        val t = flick()
        e.up(1, t + 200); e.up(2, t + 200)
        assertEquals(null, e.nextTimerAt())
    }

    @Test fun slowDragDoesNotGlide() {
        e.down(1, 100f, 100f, 0); e.down(2, 160f, 100f, 2)
        for (i in 1..30) { e.move(1, 100f, 100f + i); e.move(2, 160f, 100f + i); e.frame(10L + i * 16) }
        e.up(1, 490); e.up(2, 490)
        assertEquals(null, e.nextTimerAt())
    }

    @Test fun scrollStripScrollsWithOneFingerAndNothingElse() {
        e = GestureEngine(sink, scrollOnly = true)
        e.down(1, 20f, 100f, 0)
        for (i in 1..10) { e.move(1, 20f + i * 3, 100f + i * 10); e.frame(i * 16L) }
        e.down(2, 20f, 400f, 170)                    // a stray second finger changes nothing
        e.move(2, 20f, 500f); e.frame(176)
        e.up(2, 180)
        val before = scrolled.second
        e.up(1, 600); fire(2000)
        assertTrue("fingers down = scroll up", before > 2f)
        assertEquals("100 dp is about three notches", 100f / GestureEngine.NOTCH_DP, before, 0.01f)
        assertEquals("sideways drift is not a scroll", 0f, scrolled.first)
        assertEquals("the pointer stays put", 0 to 0, moved)
        assertTrue(events.isEmpty())
    }

    @Test fun tappingTheScrollStripDoesNotClick() {
        e = GestureEngine(sink, scrollOnly = true)
        e.down(1, 20f, 100f, 0); e.up(1, 60); fire(1000)
        assertTrue(events.isEmpty())
    }

    @Test fun scrollStripFlickGlides() {
        e = GestureEngine(sink, scrollOnly = true)
        e.down(1, 20f, 100f, 0)
        for (i in 1..10) { e.move(1, 20f, 100f + i * 16); e.frame(i * 8L) }
        val atLift = scrolled.second
        e.up(1, 82)
        runTimers(82)
        assertTrue(scrolled.second > atLift + 1f)
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
