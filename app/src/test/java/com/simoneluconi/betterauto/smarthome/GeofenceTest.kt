package com.simoneluconi.betterauto.smarthome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceTest {
    private val home = Fence(45.4642, 9.19, radiusM = 100.0, offsetM = 300.0)

    private fun at(distanceM: Double, bearing: Double = 0.0) = Geo.offset(home.latitude, home.longitude, distanceM, bearing)

    @Test
    fun distanceMatchesAKnownValue() {
        // Milan Duomo to Milano Centrale: about 2.5 km
        val d = Geo.distanceM(45.4642, 9.1900, 45.4859, 9.2035)
        assertEquals(2650.0, d, 100.0)
    }

    @Test
    fun offsetGoesThatFar() {
        for (bearing in listOf(0.0, 90.0, 200.0)) {
            val (lat, lon) = at(1234.0, bearing)
            assertEquals(1234.0, home.distanceM(lat, lon), 0.5)
        }
    }

    @Test
    fun entersAtRadiusPlusOffsetAndLeavesOnlyPastTheHysteresis() {
        val t = FenceTracker(home)
        assertNull(t.update(at(2000.0).first, at(2000.0).second))
        assertEquals(false, t.inside)
        assertNull(t.update(at(420.0).first, at(420.0).second))
        assertEquals(FenceTransition.ENTER, t.update(at(390.0).first, at(390.0).second))
        // Jitter around the edge changes nothing
        assertNull(t.update(at(450.0).first, at(450.0).second))
        assertNull(t.update(at(540.0).first, at(540.0).second))
        assertEquals(FenceTransition.EXIT, t.update(at(560.0).first, at(560.0).second))
        assertEquals(false, t.inside)
    }

    @Test
    fun startingInsideCountsAsArriving() {
        val t = FenceTracker(home)
        assertEquals(FenceTransition.ENTER, t.update(at(20.0).first, at(20.0).second))
        assertTrue(t.inside == true)
    }

    @Test
    fun vagueFixesAreIgnored() {
        val t = FenceTracker(home)
        assertNull(t.update(at(10.0).first, at(10.0).second, accuracyM = 800.0))
        assertNull(t.inside)
    }

    @Test
    fun nextFixWaitsAsLongAsTheCarNeedsToReachTheEdge() {
        // 4 km from the edge at 40 m/s: 100 s
        assertEquals(100_000L, Geo.nextFixMs(4000.0, null))
        // A known 20 m/s counts 30 m/s
        assertEquals(100_000L, Geo.nextFixMs(3000.0, 20.0))
        // Standing still counts a minimum speed, and the interval stays within its bounds
        assertEquals(Geo.MAX_INTERVAL_MS, Geo.nextFixMs(100_000.0, 0.0))
        assertEquals(Geo.MIN_INTERVAL_MS, Geo.nextFixMs(10.0, null))
    }

    @Test
    fun edgeDistanceIsTheOneThatMatters() {
        assertEquals(600.0, Geo.edgeDistanceM(home, 1000.0, inside = false), 0.01)
        assertEquals(550.0, Geo.edgeDistanceM(home, 0.0, inside = true), 0.01)
        assertEquals(0.0, Geo.edgeDistanceM(home, 300.0, inside = false), 0.01)
    }

    @Test
    fun aDriveComesInAndGoesOut() {
        val drive = FakeRoute.Drive(startM = 1015.0, speedMs = 20.0, startedAtMs = 0L)
        val t = FenceTracker(home)
        val transitions = (0..120 step 2).mapNotNull { s ->
            val (lat, lon) = drive.at(s * 1000L, home)!!
            t.update(lat, lon)?.let { s to it }
        }
        // In at 400 m from the centre (after 30.75 s), out past 550 m on the other side (after 78.25 s)
        assertEquals(listOf(32 to FenceTransition.ENTER, 80 to FenceTransition.EXIT), transitions)
    }

    @Test
    fun placeRelativeRoutesNeedAFence() {
        assertNull(FakeRoute.FromPlace(100.0).at(0, null))
        val (lat, lon) = FakeRoute.FromPlace(100.0, 90.0).at(0, home)!!
        assertEquals(100.0, home.distanceM(lat, lon), 0.5)
    }
}
