package com.simoneluconi.betterauto.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A value from the car with the wall-clock time it arrived, to tell live data from the last known. */
data class Timed<T>(val value: T, val at: Long = System.currentTimeMillis())

/** What the car says about itself at connection time. */
data class VehicleInfo(
    val make: String? = null,
    val model: String? = null,
    val year: String? = null,
    val headUnit: String? = null,
    val headUnitSoftware: String? = null,
    val fuelTypes: List<String> = emptyList(),
    val evConnectors: List<String> = emptyList(),
) {
    val name: String? get() = listOfNotNull(make, model).joinToString(" ").ifBlank { null }
}

data class Fuel(val levelPercent: Int?, val rangeKm: Int?, val low: Boolean?)

data class Odometer(val totalKm: Double, val tripKm: Double?)

/** Tire pressures in bar, in the car's order (usually front left, front right, rear left, rear right). */
data class Tires(val bar: List<Double>)

data class Environment(val temperatureC: Double?, val pressureKpa: Double?, val rain: Int?)

data class Hvac(val targetC: Double?, val currentC: Double?)

data class Doors(val hoodOpen: Boolean?, val trunkOpen: Boolean?, val doorsOpen: List<Boolean>)

enum class HeadLights { OFF, ON, HIGH }

enum class TurnSignal { NONE, LEFT, RIGHT }

data class Lights(val head: HeadLights?, val turn: TurnSignal?, val hazard: Boolean?)

data class Position(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedKmh: Double?,
    val bearing: Double?,
)

data class Satellites(val inUse: Int, val inView: Int?)

enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, MANUAL }

data class Gear(val position: GearPosition, val manualGear: Int?)

data class Speed(val kmh: Double, val cruiseOn: Boolean?, val cruiseSetKmh: Int?)

data class Motion(
    val compassBearing: Double? = null,
    val acceleration: Triple<Double, Double, Double>? = null,
    val rotation: Triple<Double, Double, Double>? = null,
    val steeringAngle: Double? = null,
)

/** Everything the car has shared during this run of the app; null fields were never sent. */
data class VehicleSnapshot(
    val info: VehicleInfo = VehicleInfo(),
    /** Sensor names the car offers, as it announced them. */
    val offered: List<String> = emptyList(),
    val connected: Boolean = false,
    val fuel: Timed<Fuel>? = null,
    val odometer: Timed<Odometer>? = null,
    val tires: Timed<Tires>? = null,
    val environment: Timed<Environment>? = null,
    val hvac: Timed<Hvac>? = null,
    val doors: Timed<Doors>? = null,
    val lights: Timed<Lights>? = null,
    val diagnostics: Timed<List<String>>? = null,
    val position: Timed<Position>? = null,
    val satellites: Timed<Satellites>? = null,
    val gear: Timed<Gear>? = null,
    val parkingBrake: Timed<Boolean>? = null,
    /** Driving restrictions bitmask from the car: 0 = none (usually standing still). */
    val drivingStatus: Timed<Int>? = null,
    val speed: Timed<Speed>? = null,
    val rpm: Timed<Int>? = null,
    val motion: Timed<Motion>? = null,
    val passengerPresent: Timed<Boolean>? = null,
    val tollCardPresent: Timed<Boolean>? = null,
) {
    /** True once the car sent anything beyond its own description. */
    val hasData: Boolean
        get() = listOf(
            fuel, odometer, tires, environment, hvac, doors, lights, diagnostics, position, satellites,
            gear, parkingBrake, drivingStatus, speed, rpm, motion, passengerPresent, tollCardPresent,
        ).any { it != null }
}

/**
 * Data about the vehicle, from the car's sensors. Generic: each car shares a different subset and
 * the UI shows only what arrived. Values are kept after a disconnection (shown as last known),
 * and dropped when a different car connects.
 */
object VehicleData {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot

    /** Fault codes reported by the car right now, empty when none. */
    val faultCodes: List<String> get() = _snapshot.value.diagnostics?.value.orEmpty()

    fun onConnected(info: VehicleInfo, offered: List<String>) = _snapshot.update { current ->
        val sameCar = current.info.make == info.make && current.info.model == info.model && current.info.year == info.year
        (if (sameCar) current else VehicleSnapshot()).copy(info = info, offered = offered, connected = true)
    }

    fun onDisconnected() = _snapshot.update { it.copy(connected = false) }

    fun update(change: (VehicleSnapshot) -> VehicleSnapshot) = _snapshot.update(change)

    /** Development: a plausible car with every kind of data, to see the vehicle screen on the bench. */
    fun simulate(codes: List<String> = listOf("P0171", "P0420")) {
        _snapshot.value = VehicleSnapshot(
            info = VehicleInfo("Demo", "Wagon", "2019", "Demo head unit", "1.0", listOf("Diesel")),
            offered = listOf("all (simulated)"),
            connected = true,
            fuel = Timed(Fuel(38, 412, false)),
            odometer = Timed(Odometer(86_420.5, 23.4)),
            tires = Timed(Tires(listOf(2.4, 2.4, 2.1, 2.3))),
            environment = Timed(Environment(12.5, 101.3, 0)),
            hvac = Timed(Hvac(21.0, 19.5)),
            doors = Timed(Doors(false, false, listOf(false, false, false, false))),
            lights = Timed(Lights(HeadLights.ON, TurnSignal.NONE, false)),
            diagnostics = Timed(codes),
            position = Timed(Position(45.4642, 9.19, 4.0, 122.0, 0.0, 90.0)),
            satellites = Timed(Satellites(9, 14)),
            gear = Timed(Gear(GearPosition.PARK, null)),
            parkingBrake = Timed(true),
            drivingStatus = Timed(0),
            speed = Timed(Speed(0.0, false, null)),
            rpm = Timed(820),
            motion = Timed(Motion(compassBearing = 90.0, steeringAngle = 0.0)),
            passengerPresent = Timed(false),
            tollCardPresent = Timed(true),
        )
    }

    fun clear() {
        _snapshot.value = VehicleSnapshot()
    }
}
