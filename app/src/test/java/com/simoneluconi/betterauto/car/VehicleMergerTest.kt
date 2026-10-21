package com.simoneluconi.betterauto.car

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VehicleMergerTest {
    private val extra = VehicleSource("extra", 0, priority = 10)
    private val car = VehicleSource.CAR

    private fun state(source: VehicleSource, live: Boolean, vararg values: Pair<VehicleField<*>, Timed<Any>>) =
        SourceState(source, values.toMap(), live)

    @Before
    fun clean() = VehicleData.clear()

    @After
    fun cleanAfter() = VehicleData.clear()

    @Test
    fun theStandardSensorsWinWhenTheyHaveTheValue() {
        val states = listOf(
            state(extra, true, VehicleField.OutsideTemperature to Timed(12.0, 2_000)),
            state(car, true, VehicleField.OutsideTemperature to Timed(10.0, 1_000)),
        )
        val pick = VehicleMerger.pick(VehicleField.OutsideTemperature, states)!!
        assertEquals(10.0, pick.value.value)
        assertSame(car, pick.source)
    }

    @Test
    fun anotherSourceFillsWhatTheStandardSensorsLack() {
        val states = listOf(
            state(car, true, VehicleField.FuelLevel to Timed(40, 1_000)),
            state(extra, true, VehicleField.FuelRange to Timed(520, 1_200), VehicleField.FuelLevel to Timed(41, 1_500)),
        )
        val fuel = VehicleMerger.snapshot(VehicleSnapshot(), states).fuel!!
        assertEquals(Fuel(40, 520, null), fuel.value)
        // The group is as new as the newest value it shows
        assertEquals(1_200L, fuel.at)
    }

    @Test
    fun aLiveSourceBeatsOneThatWentQuiet() {
        val states = listOf(
            state(car, false, VehicleField.OutsideTemperature to Timed(10.0, 5_000)),
            state(extra, true, VehicleField.OutsideTemperature to Timed(12.0, 1_000)),
        )
        assertSame(extra, VehicleMerger.pick(VehicleField.OutsideTemperature, states)!!.source)
    }

    @Test
    fun withNobodyTalkingTheNewestLastKnownValueStays() {
        val states = listOf(
            state(car, false, VehicleField.Odometer to Timed(1_000.0, 1_000)),
            state(extra, false, VehicleField.Odometer to Timed(1_010.0, 2_000)),
        )
        assertEquals(1_010.0, VehicleMerger.pick(VehicleField.Odometer, states)!!.value.value)
    }

    @Test
    fun othersOnBoardComesOnlyFromLiveSources() {
        assertNull(VehicleMerger.othersOnBoard(emptyList()))
        // Known last session, unknown now: Auto must not follow a stale value
        assertNull(VehicleMerger.othersOnBoard(listOf(state(car, false, VehicleField.PassengerPresent to Timed(true, 1)))))
        assertEquals(false, VehicleMerger.othersOnBoard(listOf(state(car, true, VehicleField.PassengerPresent to Timed(false, 1)))))
        assertEquals(
            true,
            VehicleMerger.othersOnBoard(
                listOf(
                    state(car, true, VehicleField.PassengerPresent to Timed(false, 1)),
                    state(extra, true, VehicleField.RearOccupied to Timed(true, 1)),
                )
            ),
        )
    }

    @Test
    fun aReplacedValueThatDidNotChangeKeepsItsTime() {
        VehicleData.replace(extra, listOf(VehicleField.OutsideTemperature of 11.0))
        val first = VehicleData.snapshot.value.environment!!.at
        Thread.sleep(5)
        VehicleData.replace(extra, listOf(VehicleField.OutsideTemperature of 11.0, VehicleField.Trip of 3.0))
        assertEquals(first, VehicleData.snapshot.value.environment!!.at)
        // A trip counter alone is not an odometer
        assertNull(VehicleData.snapshot.value.odometer)
    }

    @Test
    fun occupancyFollowsTheSourcesAsTheyChange() {
        VehicleData.publish(car, VehicleField.PassengerPresent of false)
        assertEquals(false, VehicleData.othersOnBoard.value)
        VehicleData.publish(car, VehicleField.PassengerPresent of true)
        assertEquals(true, VehicleData.othersOnBoard.value)
        VehicleData.onDisconnected()
        assertNull(VehicleData.othersOnBoard.value)
        // Shown as last known on the vehicle screen
        assertEquals(true, VehicleData.snapshot.value.passengerPresent?.value)
    }

    @Test
    fun theMostDemandingScreenWins() {
        val overview = VehicleData.request(VehicleDemand.OVERVIEW)
        val detail = VehicleData.request(VehicleDemand.DETAIL)
        assertEquals(VehicleDemand.DETAIL, VehicleData.demand.value)
        detail()
        detail()
        assertEquals(VehicleDemand.OVERVIEW, VehicleData.demand.value)
        overview()
        assertEquals(VehicleDemand.NONE, VehicleData.demand.value)
    }

    @Test
    fun everyFieldIsListedOnce() {
        assertTrue(VehicleField.all.size == VehicleField.all.map { it.key }.toSet().size)
    }
}
