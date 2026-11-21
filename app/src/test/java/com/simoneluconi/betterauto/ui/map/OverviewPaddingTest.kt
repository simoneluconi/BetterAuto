package com.simoneluconi.betterauto.ui.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routes' overview keeps room to frame them, whatever the panels cover. */
class OverviewPaddingTest {
    @Test
    fun roomyScreenKeepsTheInsets() {
        // A wide screen: the panel on the left, the buttons on the right, a margin all round
        assertArrayEquals(intArrayOf(770, 50, 190, 50), CarMapState.overviewPadding(listOf(720, 0, 140, 0), 50, 1630, 900))
    }

    @Test
    fun aSmallScreenStillFramesTheRoutes() {
        // The panel above, covering nearly all the height of a small screen: cut down to leave 30% of it
        val padding = CarMapState.overviewPadding(listOf(0, 400, 60, 0), 40, 760, 420)
        assertTrue(padding[1] + padding[3] <= 420 * 0.7)
        assertTrue(padding[0] + padding[2] <= 760 * 0.7)
        // Still more at the top, where the panel is, than at the bottom
        assertTrue(padding[1] > padding[3])
    }

    @Test
    fun anUnknownSizeLeavesThePaddingAsAsked() {
        assertArrayEquals(intArrayOf(10, 10, 10, 10), CarMapState.overviewPadding(listOf(0, 0, 0, 0), 10, 0, 0))
    }
}
