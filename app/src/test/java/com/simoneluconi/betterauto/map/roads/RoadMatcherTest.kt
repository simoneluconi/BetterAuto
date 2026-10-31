package com.simoneluconi.betterauto.map.roads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which road the car is on, in a small made-up town around 45 N 9 E. */
class RoadMatcherTest {
    // About 11 m per 0.0001 degrees of latitude, 7.9 m per 0.0001 of longitude here
    private val mainStreet = road("main", "Via Roma", 45.0, 8.998, 45.0, 9.002, limit = 50)
    private val sideStreet = road("side", "Via Verdi", 44.998, 9.0, 45.002, 9.0, limit = 30)
    private val parallel = road("parallel", "Viale Europa", 45.0002, 8.998, 45.0002, 9.002, limit = 70)

    @Test
    fun theNearestRoadGoingTheCarsWayWins() {
        val m = RoadMatcher().match(listOf(mainStreet, sideStreet), 45.00003, 8.9995, bearing = 90f)!!
        assertEquals("main", m.segment.id)
        assertEquals(TravelDirection.FORWARD, m.direction)
        assertEquals(0.375, m.fraction, 0.01)
    }

    @Test
    fun atAJunctionTheHeadingDecides() {
        // Right on the crossing: driving north means the side street
        val m = RoadMatcher().match(listOf(mainStreet, sideStreet), 45.0, 9.0, bearing = 0f)!!
        assertEquals("side", m.segment.id)
    }

    @Test
    fun drivingBackwardsAlongTheDrawing() {
        val m = RoadMatcher().match(listOf(mainStreet), 45.0, 9.001, bearing = 270f)!!
        assertEquals(TravelDirection.BACKWARD, m.direction)
    }

    @Test
    fun aParallelRoadDoesNotStealTheCarWithoutReason() {
        val matcher = RoadMatcher()
        assertEquals("main", matcher.match(listOf(mainStreet, parallel), 45.00002, 9.0, 90f)!!.segment.id)
        // GPS drift to slightly nearer the parallel road: the car stays on its road
        assertEquals("main", matcher.match(listOf(mainStreet, parallel), 45.00012, 9.0, 90f)!!.segment.id)
        // Clearly on the other one now
        assertEquals("parallel", matcher.match(listOf(mainStreet, parallel), 45.0002, 9.0, 90f)!!.segment.id)
    }

    @Test
    fun farFromEveryRoadIsNoRoad() {
        assertNull(RoadMatcher().match(listOf(mainStreet), 45.001, 9.0, 90f))
    }

    @Test
    fun limitsByPartAndByDirection() {
        val road = RoadSegment(
            "r", "Via Lunga", emptyList(), "secondary", doubleArrayOf(45.0, 9.0, 45.0, 9.01),
            listOf(
                LimitRule(SpeedLimit(50, false), 0.0, 0.5),
                LimitRule(SpeedLimit(70, false), 0.5, 1.0, TravelDirection.FORWARD),
                LimitRule(SpeedLimit(90, false), 0.5, 1.0, TravelDirection.BACKWARD),
            ),
        )
        assertEquals(50, road.limitAt(0.2, TravelDirection.FORWARD)?.value)
        assertEquals(70, road.limitAt(0.8, TravelDirection.FORWARD)?.value)
        assertEquals(90, road.limitAt(0.8, TravelDirection.BACKWARD)?.value)
        // Which way unknown and the ways differ: nothing sure
        assertNull(road.limitAt(0.8, null))
        // Only a piece of the road: part-of-road rules can't be placed
        val piece = RoadSegment("r", "Via Lunga", emptyList(), "secondary", road.points, road.limits, partial = true)
        assertNull(piece.limitAt(0.2, TravelDirection.FORWARD))
        val wholePiece = RoadSegment("w", null, emptyList(), "primary", road.points, listOf(LimitRule(SpeedLimit(50, false))), partial = true)
        assertEquals(50, wholePiece.limitAt(0.3, null)?.value)
    }

    @Test
    fun limitsShownInTheDriversUnits() {
        assertEquals(50, SpeedLimit(50, mph = false).shown(inMph = false))
        assertEquals(30, SpeedLimit(50, mph = false).shown(inMph = true))
        assertEquals(30, SpeedLimit(30, mph = true).shown(inMph = true))
        assertEquals(50, SpeedLimit(30, mph = true).shown(inMph = false))
    }

    private fun road(id: String, name: String, lat1: Double, lon1: Double, lat2: Double, lon2: Double, limit: Int) =
        RoadSegment(id, name, emptyList(), "secondary", doubleArrayOf(lat1, lon1, lat2, lon2), listOf(LimitRule(SpeedLimit(limit, false))))
}
