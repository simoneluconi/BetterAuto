package com.simoneluconi.betterauto.car

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.R

/**
 * One piece of vehicle data with a single meaning and unit, whatever protocol it comes from. Each
 * [VehicleSource] maps its own names to these fields, converting to [unit] (metric, as [Units] expects);
 * the screens read only these, through [VehicleData].
 */
class VehicleField<T : Any> private constructor(val key: String, val unit: String?) {
    override fun toString() = key

    /** This field with a value from a source; a null value means the source has none (any more). */
    infix fun of(value: T?) = FieldValue(this, value)

    companion object {
        private val registry = ArrayList<VehicleField<*>>()

        /** Every field, in declaration order. */
        val all: List<VehicleField<*>> get() = registry

        private fun <T : Any> field(key: String, unit: String? = null) = VehicleField<T>(key, unit).also { registry += it }

        val FuelLevel = field<Int>("fuelLevel", "%")
        val FuelRange = field<Int>("fuelRange", "km")
        val FuelLow = field<Boolean>("fuelLow")
        val Odometer = field<Double>("odometer", "km")
        val Trip = field<Double>("trip", "km")
        /** Front left, front right, rear left, rear right (or the car's own order when it has more). */
        val TirePressures = field<List<Double>>("tirePressures", "bar")
        val OutsideTemperature = field<Double>("outsideTemperature", "°C")
        val AirPressure = field<Double>("airPressure", "kPa")
        val Rain = field<Int>("rain")
        val ClimateTarget = field<Double>("climateTarget", "°C")
        val CabinTemperature = field<Double>("cabinTemperature", "°C")
        val HoodOpen = field<Boolean>("hoodOpen")
        val TrunkOpen = field<Boolean>("trunkOpen")
        /** Front left, front right, rear left, rear right. */
        val DoorsOpen = field<List<Boolean>>("doorsOpen")
        val Headlights = field<HeadLights>("headlights")
        val Turn = field<TurnSignal>("turnSignal")
        val Hazard = field<Boolean>("hazard")
        val FaultCodes = field<List<String>>("faultCodes")
        val Location = field<Position>("position")
        val GpsSatellites = field<Satellites>("satellites")
        val CurrentGear = field<Gear>("gear")
        val ParkingBrake = field<Boolean>("parkingBrake")
        /** Driving restrictions bitmask: 0 = none (usually standing still). */
        val DrivingStatus = field<Int>("drivingStatus")
        val Speed = field<Double>("speed", "km/h")
        val CruiseOn = field<Boolean>("cruiseOn")
        val CruiseSet = field<Int>("cruiseSet", "km/h")
        val Rpm = field<Int>("rpm", "1/min")
        val Compass = field<Double>("compass", "°")
        val Acceleration = field<Triple<Double, Double, Double>>("acceleration", "m/s²")
        val Rotation = field<Triple<Double, Double, Double>>("rotation", "rad/s")
        val SteeringAngle = field<Double>("steeringAngle", "°")
        /** Someone in the front passenger seat. */
        val PassengerPresent = field<Boolean>("passengerPresent")
        /** Someone in the rear seats. */
        val RearOccupied = field<Boolean>("rearOccupied")
        val TollCard = field<Boolean>("tollCard")
        val OilTemperature = field<Double>("oilTemperature", "°C")
        val CoolantTemperature = field<Double>("coolantTemperature", "°C")
        val BatteryVoltage = field<Double>("batteryVoltage", "V")
        val ConsumptionShortTerm = field<Double>("consumptionShortTerm", "l/100km")
        val ConsumptionLongTerm = field<Double>("consumptionLongTerm", "l/100km")
        val ServiceOilDistance = field<Double>("serviceOilDistance", "km")
        val ServiceOilDays = field<Int>("serviceOilDays", "d")
        val ServiceInspectionDistance = field<Double>("serviceInspectionDistance", "km")
        val ServiceInspectionDays = field<Int>("serviceInspectionDays", "d")

        /** Who else is in the car: what the notification privacy follows. */
        val Occupancy: List<VehicleField<Boolean>> = listOf(PassengerPresent, RearOccupied)
    }
}

data class FieldValue<T : Any>(val field: VehicleField<T>, val value: T?)

/**
 * Where vehicle data comes from. When several sources have the same field, a live one wins over one
 * that went quiet, and among live ones the higher [priority]; so the standard Android Auto sensors win
 * over a car maker's extra protocol, which fills in what they lack. Adding a protocol means one more
 * source that maps its own names to [VehicleField]s.
 */
class VehicleSource(val id: String, @param:StringRes val label: Int, val priority: Int) {
    override fun toString() = id

    companion object {
        /** Pretend data for the bench (debug broadcast): above everything. */
        val DEMO = VehicleSource("demo", R.string.vehicle_source_demo, 100)

        /** The Android Auto sensor channel. */
        val CAR = VehicleSource("car", R.string.vehicle_source_car, 50)
    }
}

/** How much vehicle data the screens need right now: sources that can choose fetch only that. */
enum class VehicleDemand { NONE, OVERVIEW, DETAIL }
