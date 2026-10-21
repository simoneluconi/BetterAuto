package com.simoneluconi.betterauto.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarMotionTest {
    private val now = 1_000_000L

    private fun speed(kmh: Double, ageMs: Long = 0) = Timed(Speed(kmh, null, null), now - ageMs)

    @Test
    fun aHeadUnitThatSharesNothingKeepsEverything() {
        assertEquals(CarMotion.UNKNOWN, CarMotionState.of(VehicleSnapshot(), now))
    }

    @Test
    fun automaticInParkStands() {
        val s = VehicleSnapshot(gear = Timed(Gear(GearPosition.PARK, null), now), drivingStatus = Timed(7, now))
        assertEquals(CarMotion.STANDING, CarMotionState.of(s, now))
    }

    @Test
    fun manualWithTheParkingBrakeStands() {
        val s = VehicleSnapshot(gear = Timed(Gear(GearPosition.NEUTRAL, null), now), parkingBrake = Timed(true, now))
        assertEquals(CarMotion.STANDING, CarMotionState.of(s, now))
    }

    @Test
    fun speedWinsOverEverythingElse() {
        val s = VehicleSnapshot(gear = Timed(Gear(GearPosition.PARK, null), now), parkingBrake = Timed(true, now), speed = speed(40.0))
        assertEquals(CarMotion.MOVING, CarMotionState.of(s, now))
        assertEquals(CarMotion.STANDING, CarMotionState.of(VehicleSnapshot(speed = speed(0.0), drivingStatus = Timed(7, now)), now))
    }

    @Test
    fun anOldSpeedSaysNothing() {
        assertEquals(CarMotion.UNKNOWN, CarMotionState.of(VehicleSnapshot(speed = speed(80.0, ageMs = 60_000)), now))
    }

    @Test
    fun withoutGearOrSpeedTheCarsRestrictionsDecide() {
        assertEquals(CarMotion.STANDING, CarMotionState.of(VehicleSnapshot(drivingStatus = Timed(0, now)), now))
        assertEquals(CarMotion.MOVING, CarMotionState.of(VehicleSnapshot(drivingStatus = Timed(7, now)), now))
    }
}
