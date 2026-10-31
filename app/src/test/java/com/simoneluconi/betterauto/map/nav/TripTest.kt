package com.simoneluconi.betterauto.map.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a new stop goes in the trip, and the trip reordered. */
class TripTest {
    // A road north: the car at 45.00, a stop at 45.05, the destination at 45.10
    private val stop = Destination("Stop", null, 45.05, 9.0)
    private val destination = Destination("Destination", null, 45.10, 9.0)
    private val trip = listOf(stop, destination)

    private fun names(list: List<Destination>) = list.map { it.name }

    @Test
    fun aStopGoesWhereTheDetourIsShortest() {
        val near = Destination("Near", null, 45.02, 9.0)
        assertEquals(listOf("Near", "Stop", "Destination"), names(Trip.withStop(trip, near, 45.0, 9.0)))
        val after = Destination("After", null, 45.07, 9.001)
        assertEquals(listOf("Stop", "After", "Destination"), names(Trip.withStop(trip, after, 45.0, 9.0)))
        // Past the destination: still before it, the destination stays last
        val beyond = Destination("Beyond", null, 45.20, 9.0)
        assertEquals(listOf("Stop", "Beyond", "Destination"), names(Trip.withStop(trip, beyond, 45.0, 9.0)))
    }

    @Test
    fun withoutThePositionNeverBeforeTheFirstPlace() {
        val near = Destination("Near", null, 45.02, 9.0)
        assertEquals(listOf("Stop", "Near", "Destination"), names(Trip.withStop(trip, near, null, null)))
        // A trip of just the destination: the stop comes before it
        assertEquals(listOf("Near", "Destination"), names(Trip.withStop(listOf(destination), near, null, null)))
    }

    @Test
    fun onlyARealBreakDropsTheStopsReached() {
        val atStop = 45.0502 to 9.0
        val long = Trip.MIN_BREAK_MS + 60_000
        // A screen rebuilt, a link dropped for a moment: nothing reached, even next to the stop
        assertEquals(0, Trip.reachedDuringBreak(trip, 20_000, atStop.first, atStop.second, atStop.first, atStop.second))
        // A real break by the stop: the stop is done, the destination is not
        assertEquals(1, Trip.reachedDuringBreak(trip, long, atStop.first, atStop.second, atStop.first, atStop.second))
        // Near the stop now, but the break was elsewhere (the car stopped before it): not reached
        assertEquals(0, Trip.reachedDuringBreak(trip, long, 45.0, 9.0, atStop.first, atStop.second))
        // Where the car stood not known (an older save): where it is now decides
        assertEquals(1, Trip.reachedDuringBreak(trip, long, null, null, atStop.first, atStop.second))
        // Far from everything: nothing
        assertEquals(0, Trip.reachedDuringBreak(trip, long, 45.0, 9.0, 45.0, 9.0))
        // By the destination with the stop already behind: both
        val atDestination = 45.1001 to 9.0
        val both = listOf(Destination("Stop", null, 45.1, 9.001), destination)
        assertEquals(2, Trip.reachedDuringBreak(both, long, atDestination.first, atDestination.second, atDestination.first, atDestination.second))
    }

    @Test
    fun movingPlacesUpAndDown() {
        val three = listOf(Destination("A", null, 1.0, 1.0), stop, destination)
        assertEquals(listOf("Stop", "A", "Destination"), names(Trip.moved(three, 0, 1)!!))
        // Moved last, a stop becomes the destination
        assertEquals(listOf("A", "Destination", "Stop"), names(Trip.moved(three, 1, 1)!!))
        assertNull(Trip.moved(three, 0, -1))
        assertNull(Trip.moved(three, 2, 1))
    }
}
