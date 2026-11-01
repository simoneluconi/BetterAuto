package com.simoneluconi.betterauto.map.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class TripPaceTest {
    /** 0.1 degrees north, about 11 km, which Valhalla gives 1100 s (36 km/h). */
    private val route = NavRoute(doubleArrayOf(45.0, 9.0, 45.1, 9.0), emptyList(), 11_000.0, 1_100.0)
    private val length = route.cumulative[1]

    /** Drives [route] from [fromM] to [toM] in [seconds], a fix a second; returns the clock at the end. */
    private fun TripPace.drive(fromM: Double, toM: Double, seconds: Int, startMs: Long): Long {
        for (s in 1..seconds) onRoute(route, fromM + (toM - fromM) * s / seconds, startMs + s * 1000L)
        return startMs + seconds * 1000L
    }

    @Test
    fun valhallasTimeUntilACoupleOfKmAreDriven() {
        val pace = TripPace()
        pace.newRoute(0)
        assertEquals(1_100.0, pace.remainingS(route, 0.0), 1e-6)
        // 1 km in 60 s, twice Valhalla's pace: too little to tell yet
        pace.drive(0.0, 1_000.0, 60, 0)
        assertEquals(1.0, pace.ratio, 1e-9)
    }

    @Test
    fun theRestAtTheDriversPace() {
        val pace = TripPace()
        pace.newRoute(0)
        // 3 km in 180 s where Valhalla says about 300 s
        pace.drive(0.0, 3_000.0, 180, 0)
        val expected = 180 / route.secondsTo(3_000.0)
        assertEquals(expected, pace.ratio, 1e-6)
        val left = route.secondsTo(length) - route.secondsTo(3_000.0)
        assertEquals(left * expected, pace.remainingS(route, 3_000.0), 1e-3)
    }

    @Test
    fun aRedLightCountsAndTheGpsWanderingBackDoesNot() {
        val pace = TripPace()
        pace.newRoute(0)
        val now = pace.drive(0.0, 3_000.0, 300, 0)
        // 60 s standing, the fix a few metres back and forth
        for (s in 1..60) pace.onRoute(route, 3_000.0 - (s % 2) * 5, now + s * 1000L)
        assertEquals(360.0 / 300.0, pace.ratio, 0.02)
    }

    @Test
    fun theRatioIsKeptAcrossANewRouteAndStaysWithinBounds() {
        val pace = TripPace()
        pace.newRoute(0)
        var now = pace.drive(0.0, 3_000.0, 60, 0)
        // 3 km in a minute against 5 Valhalla minutes: held at the lower bound
        assertEquals(TripPace.MIN_RATIO, pace.ratio, 1e-9)
        // A reroute: the time spent off the route is not counted, the pace stays
        now += 120_000
        pace.newRoute(now)
        assertEquals(TripPace.MIN_RATIO, pace.ratio, 1e-9)
        // A new trip knows nothing
        pace.reset()
        assertEquals(1.0, pace.ratio, 1e-9)
    }
}
