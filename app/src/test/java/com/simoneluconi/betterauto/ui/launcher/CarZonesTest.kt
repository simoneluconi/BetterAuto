package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The trio home: music on the left, clock above notifications on the right. */
class CarZonesTest {
    private val music = Rect(0f, 0f, 600f, 600f)
    private val clock = Rect(620f, 0f, 1000f, 360f)
    private val notifications = Rect(620f, 380f, 1000f, 600f)
    private val zones = listOf(music, clock, notifications)

    private fun pick(from: Rect, direction: FocusDirection) = CarZones.pick(from, zones.filter { it != from }, direction)?.let { zones.filter { z -> z != from }[it] }

    @Test
    fun rightFromTheMusicGoesToTheWidgetBesideIt() {
        assertEquals(clock, pick(music, FocusDirection.Right))
    }

    @Test
    fun leftFromEitherSmallWidgetGoesBackToTheMusic() {
        assertEquals(music, pick(clock, FocusDirection.Left))
        assertEquals(music, pick(notifications, FocusDirection.Left))
    }

    @Test
    fun upAndDownMoveBetweenStackedWidgets() {
        assertEquals(notifications, pick(clock, FocusDirection.Down))
        assertEquals(clock, pick(notifications, FocusDirection.Up))
    }

    @Test
    fun nothingPastTheEdge() {
        assertNull(pick(music, FocusDirection.Left))
        assertNull(pick(clock, FocusDirection.Right))
        assertNull(pick(music, FocusDirection.Up))
    }

    @Test
    fun theFartherZoneComesNextWhenTheNearestHasNothingToFocus() {
        val others = listOf(clock, notifications)
        assertEquals(listOf(0, 1), CarZones.order(music, others, FocusDirection.Right))
    }
}
