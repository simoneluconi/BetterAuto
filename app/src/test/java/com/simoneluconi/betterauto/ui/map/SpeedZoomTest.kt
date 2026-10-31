package com.simoneluconi.betterauto.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** The map's zoom with speed: steps at 65 and 105 km/h, left a little lower so a hovering speed doesn't pump it. */
class SpeedZoomTest {
    @Test
    fun bandsGoUpAtTheirThresholds() {
        assertEquals(0, CarMapState.speedBand(50.0, 0))
        assertEquals(1, CarMapState.speedBand(70.0, 0))
        assertEquals(2, CarMapState.speedBand(120.0, 0))
    }

    @Test
    fun bandsComeDownOnlyWellBelow() {
        // Slowing to 60 on a fast road keeps the farther view; at 50 it comes back
        assertEquals(1, CarMapState.speedBand(60.0, 1))
        assertEquals(0, CarMapState.speedBand(50.0, 1))
        assertEquals(2, CarMapState.speedBand(100.0, 2))
        assertEquals(1, CarMapState.speedBand(90.0, 2))
        // Going up the low threshold isn't enough from below
        assertEquals(0, CarMapState.speedBand(60.0, 0))
    }
}
