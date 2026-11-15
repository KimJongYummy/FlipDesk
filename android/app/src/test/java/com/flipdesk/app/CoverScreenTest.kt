package com.flipdesk.app

import com.flipdesk.app.ui.isCoverPixels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverScreenTest {
    @Test fun flip8CoverScreenIsTheCover() {
        assertTrue(isCoverPixels(1048, 948))
        assertTrue(isCoverPixels(948, 1048))
    }

    @Test fun phoneScreensAndMonitorsAreNot() {
        assertFalse("Flip main screen", isCoverPixels(1080, 2640))
        assertFalse("Fold inner screen", isCoverPixels(2184, 1968))
        assertFalse("720p TV cast", isCoverPixels(1280, 720))
        assertFalse("1080p monitor", isCoverPixels(1920, 1080))
        assertFalse("no mode", isCoverPixels(0, 0))
    }
}
