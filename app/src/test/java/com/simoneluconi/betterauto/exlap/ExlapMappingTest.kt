package com.simoneluconi.betterauto.exlap

import com.simoneluconi.betterauto.car.Gear
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.TurnSignal
import com.simoneluconi.betterauto.car.VehicleDemand
import com.simoneluconi.betterauto.car.VehicleField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExlapMappingTest {
    private fun v(url: String, name: String, value: String?, unit: String? = null, type: String? = null) =
        ExlapValue(url, name, value, unit, null, type)

    private fun <T : Any> ExlapMapped.get(field: VehicleField<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return fields.firstOrNull { it.field == field }?.value as T?
    }

    @Test
    fun temperaturesInTheCarsUnitBecomeCelsius() {
        val mapped = ExlapMapping.map(
            listOf(
                v("outsideTemperature", "outsideTemperature", "50", unit = "°F"),
                v("oilTemperature", "oilTemperature", "96.5", unit = "°C"),
                v("coolantTemperature", "coolantTemperature", "90"),
            )
        )
        assertEquals(10.0, mapped.get(VehicleField.OutsideTemperature)!!, 0.01)
        assertEquals(96.5, mapped.get(VehicleField.OilTemperature)!!, 0.01)
        assertEquals(90.0, mapped.get(VehicleField.CoolantTemperature)!!, 0.01)
    }

    @Test
    fun aRelativeTankLevelIsAPercentage() {
        assertEquals(35, ExlapMapping.map(listOf(v("tankLevelPrimary", "tankLevelPrimary", "0.35", type = "Relative"))).get(VehicleField.FuelLevel))
        assertEquals(42, ExlapMapping.map(listOf(v("tankLevelPrimary", "tankLevelPrimary", "42", unit = "%"))).get(VehicleField.FuelLevel))
    }

    @Test
    fun distancesFollowTheirUnitMember() {
        val mapped = ExlapMapping.map(
            listOf(
                v("totalDistance", "distanceValue", "1000"),
                v("totalDistance", "distanceUnit", "mi"),
                v("dayMilage", "dayMilage", "12.5", unit = "km"),
            )
        )
        assertEquals(1609.344, mapped.get(VehicleField.Odometer)!!, 0.001)
        assertEquals(12.5, mapped.get(VehicleField.Trip)!!, 0.001)
        // Both members of totalDistance are used: none of them is left as a raw row
        assertTrue("totalDistance/distanceUnit" in mapped.used && "totalDistance/distanceValue" in mapped.used)
    }

    @Test
    fun tirePressuresNeedAllFourWheels() {
        val four = listOf("FrontLeft" to "2.4", "FrontRight" to "2.5", "RearLeft" to "2.2", "RearRight" to "2.3")
        val bar = ExlapMapping.map(four.map { (w, p) -> v("tyrePressures", "pressure$w", p, unit = "bar") })
        assertEquals(listOf(2.4, 2.5, 2.2, 2.3), bar.get(VehicleField.TirePressures))
        // No unit declared: tenths of a bar
        val raw = ExlapMapping.map(four.map { (w, p) -> v("tyrePressures", "pressure$w", (p.toDouble() * 10).toString()) })
        assertEquals(2.4, raw.get(VehicleField.TirePressures)!![0], 0.001)
        assertNull(ExlapMapping.map(four.take(3).map { (w, p) -> v("tyrePressures", "pressure$w", p) }).get(VehicleField.TirePressures))
    }

    @Test
    fun doorsHoodAndTrunk() {
        val mapped = ExlapMapping.map(
            listOf(
                v("doorState", "frontLeft", "open"), v("doorState", "frontRight", "closed"),
                v("doorState", "rearLeft", "closed"), v("doorState", "rearRight", "closed"),
                v("doorState", "trunk", "open"), v("doorState", "hood", "closed"),
            )
        )
        assertEquals(listOf(true, false, false, false), mapped.get(VehicleField.DoorsOpen))
        assertEquals(true, mapped.get(VehicleField.TrunkOpen))
        assertEquals(false, mapped.get(VehicleField.HoodOpen))
    }

    @Test
    fun gearFromTheGearboxAndTheReverseSwitch() {
        assertEquals(Gear(GearPosition.MANUAL, 3), ExlapMapping.map(listOf(v("currentGear", "currentGear", "Gear3"))).get(VehicleField.CurrentGear))
        val reverse = ExlapMapping.map(listOf(v("currentGear", "currentGear", "0"), v("reverseGear", "engaged", "true")))
        assertEquals(Gear(GearPosition.REVERSE, null), reverse.get(VehicleField.CurrentGear))
        assertNull(ExlapMapping.map(listOf(v("currentGear", "currentGear", "0"))).get(VehicleField.CurrentGear))
        assertEquals(true, ExlapMapping.map(listOf(v("parkingBrake", "engaged", "true"))).get(VehicleField.ParkingBrake))
    }

    @Test
    fun blinkersAndHazards() {
        val left = ExlapMapping.map(listOf(v("blinkingState", "blinkingState", "left")))
        assertEquals(TurnSignal.LEFT, left.get(VehicleField.Turn))
        assertEquals(false, left.get(VehicleField.Hazard))
        val hazard = ExlapMapping.map(listOf(v("blinkingState", "left", "true"), v("blinkingState", "right", "true")))
        assertEquals(true, hazard.get(VehicleField.Hazard))
    }

    @Test
    fun consumptionOnlyInUnitsItCanConvert() {
        assertEquals(5.0, ExlapMapping.map(listOf(v("shortTermConsumptionPrimary", "shortTermConsumptionPrimary", "20"), v("shortTermConsumptionPrimary", "unit", "km/l"))).get(VehicleField.ConsumptionShortTerm)!!, 0.001)
        assertNull(ExlapMapping.map(listOf(v("longTermConsumptionPrimary", "longTermConsumptionPrimary", "40", unit = "mpg"))).get(VehicleField.ConsumptionLongTerm))
    }

    @Test
    fun serviceDistanceAndDaysByUnit() {
        val mapped = ExlapMapping.map(listOf(v("serviceOil", "distance", "8200", unit = "km"), v("serviceOil", "time", "240", unit = "d")))
        assertEquals(8200.0, mapped.get(VehicleField.ServiceOilDistance)!!, 0.001)
        assertEquals(240, mapped.get(VehicleField.ServiceOilDays))
    }

    @Test
    fun seatsArePlacedByTheirPosition() {
        val mapped = ExlapMapping.map(
            listOf(
                v("Comfort_CarSeatsStatus", "seats[0].position", "driver"),
                v("Comfort_CarSeatsStatus", "seats[0].occupied", "true"),
                v("Comfort_CarSeatsStatus", "seats[1].position", "passenger"),
                v("Comfort_CarSeatsStatus", "seats[1].occupied", "false"),
                v("Comfort_CarSeatsStatus", "seats[2].position", "rearLeft"),
                v("Comfort_CarSeatsStatus", "seats[2].occupied", "occupied"),
            )
        )
        assertEquals(false, mapped.get(VehicleField.PassengerPresent))
        assertEquals(true, mapped.get(VehicleField.RearOccupied))
        // A seat we can't place says nothing
        val unknown = ExlapMapping.map(listOf(v("Comfort_CarSeatsStatus", "zone5Occupied", "true")))
        assertNull(unknown.get(VehicleField.PassengerPresent))
    }

    @Test
    fun missingValuesGiveNoField() {
        val mapped = ExlapMapping.map(listOf(v("outsideTemperature", "outsideTemperature", null)))
        assertTrue(mapped.fields.isEmpty())
    }

    @Test
    fun whatIsFollowedWhen() {
        assertEquals(VehicleDemand.NONE, ExlapMapping.neededAt("Comfort_CarSeatsStatus"))
        assertEquals(VehicleDemand.OVERVIEW, ExlapMapping.neededAt("doorState"))
        assertEquals(VehicleDemand.DETAIL, ExlapMapping.neededAt("engineSpeed"))
        assertEquals(VehicleDemand.DETAIL, ExlapMapping.neededAt("somethingNew"))
        assertTrue(ExlapMapping.isPrivate("Nav_GeoPosition"))
        assertTrue(ExlapMapping.isPrivate("vehicleIdenticationNumber"))
        assertFalse(ExlapMapping.isPrivate("outsideTemperature"))
    }
}
