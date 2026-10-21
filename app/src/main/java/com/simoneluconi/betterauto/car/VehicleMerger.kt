package com.simoneluconi.betterauto.car

/** What one source has said: its fields, whether it is still talking, and raw extras for the "All data" page. */
data class SourceState(
    val source: VehicleSource,
    val values: Map<VehicleField<*>, Timed<Any>> = emptyMap(),
    val live: Boolean = false,
    val extras: List<Pair<String, String>> = emptyList(),
)

/**
 * Picks each field from the sources, without knowing any protocol. A live source (connected and
 * talking now) beats one that went quiet; among live ones the higher priority wins (the standard
 * Android Auto sensors over a car maker's protocol), then the newer value. With no live source the
 * newest last-known value is kept, so a parked car still shows what it said last.
 */
object VehicleMerger {
    data class Pick(val value: Timed<Any>, val source: VehicleSource)

    fun pick(field: VehicleField<*>, states: Collection<SourceState>): Pick? {
        var best: Pick? = null
        var bestLive = false
        for (state in states) {
            val value = state.values[field] ?: continue
            val better = when {
                best == null -> true
                state.live != bestLive -> state.live
                state.live && state.source.priority != best.source.priority -> state.source.priority > best.source.priority
                else -> value.at > best.value.at
            }
            if (better) {
                best = Pick(value, state.source)
                bestLive = state.live
            }
        }
        return best
    }

    fun merge(states: Collection<SourceState>): Map<VehicleField<*>, Pick> =
        VehicleField.all.mapNotNull { field -> pick(field, states)?.let { field to it } }.toMap()

    /**
     * Whether anyone besides the driver is aboard, from live sources only: true if any says so, false if
     * they all say nobody, null when no source in this session reports occupancy at all.
     */
    fun othersOnBoard(states: Collection<SourceState>): Boolean? {
        val live = states.filter { it.live }
        val known = VehicleField.Occupancy.mapNotNull { field -> pick(field, live)?.value?.value as Boolean? }
        return if (known.isEmpty()) null else known.any { it }
    }

    /** The sources that report occupancy right now, to log when one starts or stops. */
    fun occupancySources(states: Collection<SourceState>): Set<VehicleSource> =
        states.filter { s -> s.live && VehicleField.Occupancy.any { it in s.values } }.map { it.source }.toSet()

    /** The merged fields as the screens read them: values grouped by kind, each with its newest time. */
    fun snapshot(base: VehicleSnapshot, states: Collection<SourceState>): VehicleSnapshot {
        val picks = merge(states)
        fun <T : Any> get(field: VehicleField<T>): Timed<T>? {
            @Suppress("UNCHECKED_CAST")
            return picks[field]?.value as Timed<T>?
        }
        fun <T> group(vararg parts: Timed<*>?, build: () -> T): Timed<T>? {
            val at = parts.mapNotNull { it?.at }.maxOrNull() ?: return null
            return Timed(build(), at)
        }

        val level = get(VehicleField.FuelLevel)
        val range = get(VehicleField.FuelRange)
        val low = get(VehicleField.FuelLow)
        val odometer = get(VehicleField.Odometer)
        val trip = get(VehicleField.Trip)
        val outside = get(VehicleField.OutsideTemperature)
        val pressure = get(VehicleField.AirPressure)
        val rain = get(VehicleField.Rain)
        val target = get(VehicleField.ClimateTarget)
        val cabin = get(VehicleField.CabinTemperature)
        val hood = get(VehicleField.HoodOpen)
        val trunk = get(VehicleField.TrunkOpen)
        val doors = get(VehicleField.DoorsOpen)
        val head = get(VehicleField.Headlights)
        val turn = get(VehicleField.Turn)
        val hazard = get(VehicleField.Hazard)
        val speed = get(VehicleField.Speed)
        val cruiseOn = get(VehicleField.CruiseOn)
        val cruiseSet = get(VehicleField.CruiseSet)
        val compass = get(VehicleField.Compass)
        val acceleration = get(VehicleField.Acceleration)
        val rotation = get(VehicleField.Rotation)
        val steering = get(VehicleField.SteeringAngle)
        val oil = get(VehicleField.OilTemperature)
        val coolant = get(VehicleField.CoolantTemperature)
        val battery = get(VehicleField.BatteryVoltage)
        val shortTerm = get(VehicleField.ConsumptionShortTerm)
        val longTerm = get(VehicleField.ConsumptionLongTerm)
        val oilKm = get(VehicleField.ServiceOilDistance)
        val oilDays = get(VehicleField.ServiceOilDays)
        val inspectionKm = get(VehicleField.ServiceInspectionDistance)
        val inspectionDays = get(VehicleField.ServiceInspectionDays)

        return base.copy(
            fuel = group(level, range, low) { Fuel(level?.value, range?.value, low?.value) },
            // A trip counter alone is not an odometer
            odometer = odometer?.let { o -> group(o, trip) { Odometer(o.value, trip?.value) } },
            tires = get(VehicleField.TirePressures)?.let { Timed(Tires(it.value), it.at) },
            environment = group(outside, pressure, rain) { Environment(outside?.value, pressure?.value, rain?.value) },
            hvac = group(target, cabin) { Hvac(target?.value, cabin?.value) },
            doors = group(hood, trunk, doors) { Doors(hood?.value, trunk?.value, doors?.value.orEmpty()) },
            lights = group(head, turn, hazard) { Lights(head?.value, turn?.value, hazard?.value) },
            diagnostics = get(VehicleField.FaultCodes),
            position = get(VehicleField.Location),
            satellites = get(VehicleField.GpsSatellites),
            gear = get(VehicleField.CurrentGear),
            parkingBrake = get(VehicleField.ParkingBrake),
            drivingStatus = get(VehicleField.DrivingStatus),
            speed = speed?.let { s -> group(s, cruiseOn, cruiseSet) { Speed(s.value, cruiseOn?.value, cruiseSet?.value) } },
            rpm = get(VehicleField.Rpm),
            motion = group(compass, acceleration, rotation, steering) { Motion(compass?.value, acceleration?.value, rotation?.value, steering?.value) },
            passengerPresent = get(VehicleField.PassengerPresent),
            rearOccupied = get(VehicleField.RearOccupied),
            tollCardPresent = get(VehicleField.TollCard),
            engine = group(oil, coolant, battery) { Engine(oil?.value, coolant?.value, battery?.value) },
            consumption = group(shortTerm, longTerm) { Consumption(shortTerm?.value, longTerm?.value) },
            service = group(oilKm, oilDays, inspectionKm, inspectionDays) { Service(oilKm?.value, oilDays?.value, inspectionKm?.value, inspectionDays?.value) },
            extras = states.filter { it.extras.isNotEmpty() }.sortedByDescending { it.source.priority }.map { VehicleExtras(it.source.label, it.extras) },
        )
    }
}
