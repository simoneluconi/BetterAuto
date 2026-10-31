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
    }
}
