package com.simoneluconi.betterauto.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    @Test
    fun fullFrameScalesTouchFromTheTouchscreenSpace() {
        val frame = Viewport.full(1920, 1080)
        assertTrue(frame.isFullFrame)
        assertEquals(960f to 540f, frame.touchToFrame(640f, 360f, 1280, 720))
        assertEquals(5f to 7f, frame.touchToFrame(5f, 7f, 0, 0))
    }

    @Test
    fun centeredAreaSitsInTheMiddleAndNeverExceedsTheFrame() {
        val area = Viewport.centered(1920, 1080, 1280, 720)
        assertEquals(Viewport(1920, 1080, 320, 180, 1280, 720), area)
        assertEquals(Viewport.full(1280, 720), Viewport.centered(1280, 720, 5000, 5000))
    }

    @Test
    fun aspectGivesTheLargestAreaOfThatShape() {
        val portrait = Viewport.aspect(1920, 1080, 9f / 16f)
        assertEquals(1080, portrait.height)
        assertEquals(608, portrait.width)
        val wide = Viewport.aspect(1920, 1080, 1920f / 720f)
        assertEquals(1920, wide.width)
        assertEquals(720, wide.height)
    }

    @Test
    fun touchInTheAreaSpaceIsOffsetIntoTheFrame() {
        val area = Viewport.centered(1920, 1080, 1280, 480)
        // A touchscreen the shape of the visible area reports in its own coordinates
        assertEquals(960f to 540f, area.touchToFrame(640f, 240f, 1280, 480))
        // One the shape of the frame reports frame coordinates
        assertEquals(960f to 540f, area.touchToFrame(960f, 540f, 1920, 1080))
    }
}
