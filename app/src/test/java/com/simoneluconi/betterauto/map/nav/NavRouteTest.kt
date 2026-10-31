package com.simoneluconi.betterauto.map.nav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRouteTest {
    @Test
    fun decodesAPolylineWithSixDecimals() {
        // The format's own example (5 decimals: 38.5,-120.2 ...), read with 6: the same digits a tenth as big
        val points = NavRoute.decodePolyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertArrayEquals(doubleArrayOf(3.85, -12.02, 4.07, -12.095, 4.3252, -12.6453), points, 1e-9)
    }

    @Test
    fun maneuverArrows() {
        assertEquals(ManeuverKind.RIGHT, NavRoute.kindOf(10))
        assertEquals(ManeuverKind.SLIGHT_LEFT, NavRoute.kindOf(21))
        assertEquals(ManeuverKind.ROUNDABOUT, NavRoute.kindOf(26))
        assertEquals(ManeuverKind.ARRIVE, NavRoute.kindOf(4))
        assertEquals(ManeuverKind.STRAIGHT, NavRoute.kindOf(8))
    }

    @Test
    fun legsThroughStopsAreJoined() {
        val shape = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val json = """{"trip":{"summary":{"length":12.5,"time":900},"legs":[
            {"shape":"$shape","maneuvers":[
                {"type":1,"instruction":"Head north","begin_shape_index":0},
                {"type":4,"instruction":"You have arrived","begin_shape_index":2}]},
            {"shape":"$shape","maneuvers":[
                {"type":1,"instruction":"Head south","begin_shape_index":0},
                {"type":10,"instruction":"Turn right","begin_shape_index":1},
                {"type":4,"instruction":"You have arrived","begin_shape_index":2}]}
        ]}}"""
        val route = NavRoute.fromValhalla(json)
        assertEquals(6, route.size)
        assertArrayEquals(intArrayOf(2, 5), route.legEnds)
        // The second leg's maneuvers count from where it starts in the joined line
        assertEquals(listOf(0, 2, 3, 4, 5), route.maneuvers.map { it.beginIndex })
        // Only the arrival at the stop is a stop; the last one is the destination
        assertEquals(listOf(null, 0, null, null, null), route.maneuvers.map { it.stop })
        assertEquals(12_500.0, route.lengthM, 0.1)
    }

    @Test
    fun progressAlongTheRouteAndOffIt() {
        // 0.01 degrees of latitude north, then east: about 1.1 km each way
        val route = NavRoute(doubleArrayOf(45.0, 9.0, 45.01, 9.0, 45.01, 9.014), emptyList(), 2200.0, 200.0)
        val halfway = RouteProgress.locate(route, 45.005, 9.0, 0)
        assertEquals(0, halfway.index)
        assertEquals(556.0, halfway.alongM, 2.0)
        assertTrue(halfway.offRouteM < 1)
        // 100 m to the side of the line: off the route
        val off = RouteProgress.locate(route, 45.005, 9.0013, 0)
        assertEquals(102.0, off.offRouteM, 5.0)
    }

    /** Out north on one carriageway, back south on the other, 24 m apart (a stop at the end of the road). */
    private val outAndBack = NavRoute(
        (0..10).flatMap { listOf(45.0 + it * 0.001, 9.0) }.plus((10 downTo 0).flatMap { listOf(45.0 + it * 0.001, 9.0003) }).toDoubleArray(),
        emptyList(), 2250.0, 200.0,
    )

    @Test
    fun theWayBackOnTheOtherCarriagewayIsNotWhereTheCarIs() {
        // Halfway out, the GPS a little toward the other carriageway
        val unlimited = RouteProgress.locate(outAndBack, 45.005, 9.00026, 4)
        assertTrue("without limits it jumps to the way back", unlimited.alongM > 1500)
        // No farther than the car can have driven since the last fix
        val limited = RouteProgress.locate(outAndBack, 45.005, 9.00026, 4, RouteProgress.aheadAllowance(14.0, 1.0))
        assertEquals(556.0, limited.alongM, 5.0)
        // Nor a part of the route going the other way from the car
        val heading = RouteProgress.locate(outAndBack, 45.005, 9.00026, 4, bearing = 0f)
        assertEquals(556.0, heading.alongM, 5.0)
    }

    @Test
    fun aUTurnOnTheRoutesOwnRoadShowsInTheHeading() {
        val route = NavRoute(doubleArrayOf(45.0, 9.0, 45.01, 9.0, 45.01, 9.014), emptyList(), 2200.0, 200.0)
        val allowance = RouteProgress.aheadAllowance(5.0, 1.0)
        // Going its way: on it, nothing against it
        val forward = RouteProgress.locate(route, 45.002, 9.00002, 0, allowance, bearing = 0f)
        assertTrue(forward.offRouteM < 5)
        assertEquals(Double.MAX_VALUE, forward.againstM, 0.0)
        // Turned round on the same road: right on the line, but only the way it came
        val back = RouteProgress.locate(route, 45.002, 9.00002, 0, allowance, bearing = 180f)
        assertTrue(back.againstM < 5)
        assertTrue(back.offRouteM > back.againstM + 10)
    }

    @Test
    fun theCarDrivesOffTheEndOfALongStraightSegment() {
        // 1.1 km north in one segment, then east: the car followed it fix by fix and is now just past the corner
        val route = NavRoute(doubleArrayOf(45.0, 9.0, 45.01, 9.0, 45.01, 9.014), emptyList(), 2200.0, 200.0)
        val allowance = RouteProgress.aheadAllowance(20.0, 1.0)
        val before = RouteProgress.locate(route, 45.0099, 9.0, 0, allowance)
        assertEquals(0, before.index)
        val after = RouteProgress.locate(route, 45.01, 9.0003, before.index, allowance, fromAlongM = before.alongM)
        assertEquals("on the second segment", 1, after.index)
        assertTrue(after.offRouteM < 5)
        // Counted from the start of the long segment instead, the corner was out of reach: "off the route"
        val fromSegmentStart = RouteProgress.locate(route, 45.01, 9.0003, before.index, allowance)
        assertTrue(fromSegmentStart.offRouteM > 20)
    }

    @Test
    fun theWayOutOfARoundaboutIsNotASecondRoundabout() {
        val shape = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val json = """{"trip":{"summary":{"length":1.0,"time":60},"legs":[
            {"shape":"$shape","maneuvers":[
                {"type":1,"instruction":"Head north","begin_shape_index":0},
                {"type":26,"instruction":"Enter the roundabout and take the 2nd exit","roundabout_exit_count":2,"begin_shape_index":1},
                {"type":27,"instruction":"Exit the roundabout","begin_shape_index":1},
                {"type":4,"instruction":"You have arrived","begin_shape_index":2}]}
        ]}}"""
        val route = NavRoute.fromValhalla(json)
        assertEquals(listOf(ManeuverKind.STRAIGHT, ManeuverKind.ROUNDABOUT, ManeuverKind.ARRIVE), route.maneuvers.map { it.kind })
        assertEquals(2, route.maneuvers[1].roundaboutExit)
        // Where it is left, for the arrow over it on the map
        assertEquals(1, route.maneuvers[1].exitIndex)
    }

    @Test
    fun aPieceOfTheRouteForTheManeuverArrow() {
        // 1.1 km north, then 1.1 km east: 40 m before the corner to 25 m after it
        val route = NavRoute(doubleArrayOf(45.0, 9.0, 45.01, 9.0, 45.01, 9.014), emptyList(), 2200.0, 200.0)
        val corner = route.cumulative[1]
        val piece = route.slice(corner - 40, corner + 25)
        assertEquals("start, corner, end", 6, piece.size)
        assertEquals(40.0, com.simoneluconi.betterauto.map.distanceM(piece[0], piece[1], 45.01, 9.0), 0.5)
        assertEquals(45.01, piece[2], 1e-9)
        assertEquals(25.0, com.simoneluconi.betterauto.map.distanceM(45.01, 9.0, piece[4], piece[5]), 0.5)
        // Past the route's end it stops there; nothing when it has no length
        assertEquals(9.014, route.slice(corner, corner * 10).last(), 1e-9)
        assertEquals(0, route.slice(100.0, 100.0).size)
    }
}
