package com.simoneluconi.betterauto.car

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

data class Engine(val oilC: Double?, val coolantC: Double?, val batteryV: Double?)

/** Fuel used, in litres per 100 km: since the start of the trip, and over a long period. */
data class Consumption(val shortTermL100km: Double?, val longTermL100km: Double?)

/** Distance and days left before the next oil change and inspection. */
data class Service(val oilKm: Double?, val oilDays: Int?, val inspectionKm: Double?, val inspectionDays: Int?)

/** Values a source shares that are not (yet) canonical fields, as label / value rows, under the source's name. */
data class VehicleExtras(@param:StringRes val label: Int, val rows: List<Pair<String, String>>)

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
    val rearOccupied: Timed<Boolean>? = null,
    val tollCardPresent: Timed<Boolean>? = null,
    val engine: Timed<Engine>? = null,
    val consumption: Timed<Consumption>? = null,
    val service: Timed<Service>? = null,
    val extras: List<VehicleExtras> = emptyList(),
) {
    /** True once the car sent anything beyond its own description. */
    val hasData: Boolean
        get() = extras.isNotEmpty() || listOf(
            fuel, odometer, tires, environment, hvac, doors, lights, diagnostics, position, satellites,
            gear, parkingBrake, drivingStatus, speed, rpm, motion, passengerPresent, rearOccupied, tollCardPresent,
            engine, consumption, service,
        ).any { it != null }
}

/**
 * Data about the vehicle, from every [VehicleSource] (the Android Auto sensors, a car maker's protocol),
 * merged per field by [VehicleMerger]. Generic: each car shares a different subset and the UI shows only
 * what arrived. Values are kept after a disconnection (shown as last known), and dropped when a
 * different car connects.
 */
object VehicleData {
    private val sources = LinkedHashMap<VehicleSource, SourceState>()
    private var base = VehicleSnapshot()

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot

    private val _othersOnBoard = MutableStateFlow<Boolean?>(null)
    /** Anyone besides the driver aboard, as live sources say; null when none of them reports it. */
    val othersOnBoard: StateFlow<Boolean?> = _othersOnBoard

    private var occupancySources = emptySet<VehicleSource>()

    private val requests = ArrayList<VehicleDemand>()
    private val _demand = MutableStateFlow(VehicleDemand.NONE)
    /** The most any screen needs now ([request]); sources that can choose what to fetch follow it. */
    val demand: StateFlow<VehicleDemand> = _demand

    /** Fault codes reported by the car right now, empty when none. */
    val faultCodes: List<String> get() = _snapshot.value.diagnostics?.value.orEmpty()

    @Synchronized
    fun onConnected(info: VehicleInfo, offered: List<String>) {
        val sameCar = base.info.make == info.make && base.info.model == info.model && base.info.year == info.year
        if (!sameCar) sources.clear()
        base = VehicleSnapshot(info = info, offered = offered, connected = true)
        setLiveLocked(VehicleSource.CAR, true)
        rebuild()
    }

    @Synchronized
    fun onDisconnected() {
        base = base.copy(connected = false)
        for (source in sources.keys.toList()) {
            if (source != VehicleSource.DEMO) setLiveLocked(source, false)
        }
        rebuild()
    }

    /** New values from [source]; a null value removes that field from it. The source counts as live. */
    @Synchronized
    fun publish(source: VehicleSource, changes: List<FieldValue<*>>) {
        val state = sources[source] ?: SourceState(source)
        val values = LinkedHashMap(state.values)
        val now = System.currentTimeMillis()
        for (change in changes) {
            val value = change.value
            if (value == null) values.remove(change.field) else values[change.field] = Timed(value, now)
        }
        sources[source] = state.copy(values = values, live = true)
        rebuild()
    }

    fun publish(source: VehicleSource, vararg changes: FieldValue<*>) = publish(source, changes.toList())

    /**
     * Everything [source] knows now, replacing what it said before (fields it no longer has go). A field
     * whose value did not change keeps the time it arrived.
     */
    @Synchronized
    fun replace(source: VehicleSource, fields: List<FieldValue<*>>, extras: List<Pair<String, String>> = emptyList()) {
        val old = sources[source]?.values.orEmpty()
        val now = System.currentTimeMillis()
        val values = LinkedHashMap<VehicleField<*>, Timed<Any>>()
        for (f in fields) {
            val value: Any = f.value ?: continue
            values[f.field] = old[f.field]?.takeIf { it.value == value } ?: Timed(value, now)
        }
        sources[source] = SourceState(source, values, live = true, extras = extras)
        rebuild()
    }

    /** [source] stopped talking: its values stay as last known, its raw extras go. */
    @Synchronized
    fun stop(source: VehicleSource) {
        val state = sources[source] ?: return
        sources[source] = state.copy(live = false, extras = emptyList())
        rebuild()
    }

    /** A screen needs [level] of detail until the returned function is called. */
    @Synchronized
    fun request(level: VehicleDemand): () -> Unit {
        requests += level
        _demand.value = requests.maxOrNull() ?: VehicleDemand.NONE
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    requests.remove(level)
                    _demand.value = requests.maxOrNull() ?: VehicleDemand.NONE
                }
            }
        }
    }

    private fun setLiveLocked(source: VehicleSource, live: Boolean) {
        val state = sources[source] ?: SourceState(source)
        sources[source] = state.copy(live = live)
    }

    private fun rebuild() {
        val states = sources.values
        _snapshot.value = VehicleMerger.snapshot(base, states)
        val providing = VehicleMerger.occupancySources(states)
        if (providing != occupancySources) {
            (providing - occupancySources).forEach { EventLog.log("Vehicle data: $it reports who is aboard") }
            (occupancySources - providing).forEach { EventLog.log("Vehicle data: $it no longer reports who is aboard") }
            occupancySources = providing
        }
        val others = VehicleMerger.othersOnBoard(states)
        if (others != _othersOnBoard.value) {
            EventLog.log("Vehicle data: others on board ${others ?: "unknown"}")
            _othersOnBoard.value = others
        }
    }

    /** Development: a plausible car with every kind of data, to see the vehicle screen on the bench. */
    @Synchronized
    fun simulate(codes: List<String> = listOf("P0171", "P0420")) {
        sources.clear()
        base = VehicleSnapshot(
            info = VehicleInfo("Demo", "Wagon", "2019", "Demo head unit", "1.0", listOf("Diesel")),
            offered = listOf("all (simulated)"),
            connected = true,
        )
        publish(
            VehicleSource.DEMO,
            VehicleField.FuelLevel of 38, VehicleField.FuelRange of 412, VehicleField.FuelLow of false,
            VehicleField.Odometer of 86_420.5, VehicleField.Trip of 23.4,
            VehicleField.TirePressures of listOf(2.4, 2.4, 2.1, 2.3),
            VehicleField.OutsideTemperature of 12.5, VehicleField.AirPressure of 101.3, VehicleField.Rain of 0,
            VehicleField.ClimateTarget of 21.0, VehicleField.CabinTemperature of 19.5,
            VehicleField.HoodOpen of false, VehicleField.TrunkOpen of false, VehicleField.DoorsOpen of listOf(false, false, false, false),
            VehicleField.Headlights of HeadLights.ON, VehicleField.Turn of TurnSignal.NONE, VehicleField.Hazard of false,
            VehicleField.FaultCodes of codes,
            VehicleField.Location of Position(45.4642, 9.19, 4.0, 122.0, 0.0, 90.0),
            VehicleField.GpsSatellites of Satellites(9, 14),
            VehicleField.CurrentGear of Gear(GearPosition.PARK, null), VehicleField.ParkingBrake of true,
            VehicleField.DrivingStatus of 0, VehicleField.Speed of 0.0, VehicleField.CruiseOn of false,
            VehicleField.Rpm of 820, VehicleField.Compass of 90.0, VehicleField.SteeringAngle of 0.0,
            VehicleField.PassengerPresent of false, VehicleField.TollCard of true,
            VehicleField.OilTemperature of 92.0, VehicleField.CoolantTemperature of 90.0, VehicleField.BatteryVoltage of 14.2,
            VehicleField.ConsumptionShortTerm of 6.4, VehicleField.ConsumptionLongTerm of 5.9,
            VehicleField.ServiceOilDistance of 8_200.0, VehicleField.ServiceOilDays of 240,
            VehicleField.ServiceInspectionDistance of 21_500.0, VehicleField.ServiceInspectionDays of 410,
        )
    }

    @Synchronized
    fun clear() {
        sources.clear()
        base = VehicleSnapshot()
        rebuild()
    }
}
