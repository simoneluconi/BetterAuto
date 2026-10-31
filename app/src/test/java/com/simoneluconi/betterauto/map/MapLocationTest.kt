package com.simoneluconi.betterauto.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which way the car's arrow points, and where it goes without a GPS. */
class MapLocationTest {
    private fun fix(lat: Double, lon: Double, bearing: Float?) = MapFix(lat, lon, bearing, null, null, MapSource.CAR, at = 0)

    @Test
    fun movingUsesTheGpsBearing() {
        assertEquals(90f, MapLocation.heading(fix(45.0, 9.0, 10f), 45.0, 9.001, 90f, 10f)!!, 0f)
    }

    @Test
    fun standingKeepsTheLastBearing() {
        // Standing, the GPS bearing wanders: the arrow keeps pointing where the car went
        assertEquals(10f, MapLocation.heading(fix(45.0, 9.0, 10f), 45.0, 9.0, 200f, 0.2f)!!, 0f)
    }

    @Test
    fun withoutBearingTheMoveGivesIt() {
        // 0.001 degrees of longitude east is about 79 m here
        assertEquals(90f, MapLocation.heading(fix(45.0, 9.0, null), 45.0, 9.001, null, null)!!, 0.5f)
    }

    @Test
    fun aTinyMoveSaysNothing() {
        assertNull(MapLocation.heading(fix(45.0, 9.0, null), 45.00001, 9.0, null, null))
    }

    @Test
    fun bearingAndDistance() {
        assertEquals(0f, bearingDeg(45.0, 9.0, 45.01, 9.0), 0.01f)
        assertEquals(1112.0, distanceM(45.0, 9.0, 45.01, 9.0), 1.0)
    }

    @Test
    fun advanceGoesAlongTheBearingAndBackInReverse() {
        val (lat, lon) = advance(45.0, 9.0, 90.0, 100.0)
        assertEquals(45.0, lat, 1e-9)
        assertEquals(100.0, distanceM(45.0, 9.0, lat, lon), 0.5)
        assertEquals(90f, bearingDeg(45.0, 9.0, lat, lon), 0.1f)
        val (backLat, _) = advance(45.0, 9.0, 0.0, -100.0)
        assertEquals(-100.0, (backLat - 45.0) * 111_195.0, 0.5)
    }

    @Test
    fun headingPrefersTheCarsCompass() {
        assertEquals(270f, carHeading(10f, -90.0, 0.5, 1, 1.0)!!, 0f)
    }

    @Test
    fun headingWithoutAnythingStaysStraight() {
        assertEquals(10f, carHeading(10f, null, null, 0, 1.0)!!, 0f)
        // A gyroscope whose sense is not known yet is not used
        assertEquals(10f, carHeading(10f, null, 0.5, 0, 1.0)!!, 0f)
        assertNull(carHeading(null, null, 0.5, 1, 1.0))
    }

    @Test
    fun gyroscopeTurnsTheHeadingOnceItsSenseIsKnown() {
        // 0.1 rad/s for 1 s is about 5.7 degrees
        assertEquals(15.73f, carHeading(10f, null, 0.1, 1, 1.0)!!, 0.01f)
        assertEquals(4.27f, carHeading(10f, null, 0.1, -1, 1.0)!!, 0.01f)
        assertEquals(354.27f, carHeading(0f, null, 0.1, -1, 1.0)!!, 0.01f)
    }

    @Test
    fun gyroSenseIsLearnedFromGpsTurns() {
        val gyro = GyroSense()
        assertEquals(0, gyro.sense)
        // Turning right (bearing up) while the car reports a negative rate: negative means right for this car
        repeat(3) { gyro.learn(0f, 10f, 1.0, 10f, -0.17) }
        assertEquals(-1, gyro.sense)
        // Small turns and slow speeds say nothing
        val other = GyroSense()
        repeat(5) { other.learn(0f, 1f, 1.0, 10f, 0.17) }
        repeat(5) { other.learn(0f, 10f, 1.0, 1f, 0.17) }
        assertEquals(0, other.sense)
    }

    @Test
    fun turnsWrapAroundNorth() {
        assertEquals(20.0, turnDeg(350f, 10f), 1e-9)
        assertEquals(-20.0, turnDeg(10f, 350f), 1e-9)
    }
}
