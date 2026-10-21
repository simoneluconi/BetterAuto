package com.simoneluconi.betterauto.exlap

import com.simoneluconi.betterauto.car.FieldValue
import com.simoneluconi.betterauto.car.Gear
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.HeadLights
import com.simoneluconi.betterauto.car.TurnSignal
import com.simoneluconi.betterauto.car.VehicleDemand
import com.simoneluconi.betterauto.car.VehicleField
import java.util.Locale
import kotlin.math.roundToInt

/** Canonical fields found in Exlap values, and which raw values ("url/name") they came from. */
data class ExlapMapped(val fields: List<FieldValue<*>>, val used: Set<String>)

/**
 * Exlap data objects to canonical [VehicleField]s, with their units converted. Object names come from
 * the published EXLAP 1.3 specification (Volkswagen, CC BY-SA 3.0: value types, nodata state), the
 * EXLAP MIB2 object atlas (CC BY 4.0: which objects exist) and the names other open projects read
 * (member names such as tyrePressures.pressureFrontLeft, totalDistance.distanceValue). Where a member
 * name or format is not published the match is loose (by words in the name, or by the unit the car
 * declares in its Interface answer) and anything not understood is simply left as a raw value.
 */
object ExlapMapping {
    /** Followed whatever is on screen: who is aboard drives the notification privacy, the clock widget shows the outside temperature. */
    private val ALWAYS = setOf("Comfort_CarSeatsStatus", "outsideTemperature")

    /** What the vehicle overview shows. */
    private val OVERVIEW = setOf(
        "tankLevelPrimary", "fuelWarningPrimaryTank", "totalDistance", "dayMilage",
        "tyrePressures", "doorState", "lightState_front", "blinkingState", "currentGear", "reverseGear", "parkingBrake",
    )

    /** Only on the full list. */
    private val DETAIL = setOf(
        "oilTemperature", "coolantTemperature", "batteryVoltage", "vehicleSpeed", "engineSpeed", "wheelAngle",
        "shortTermConsumptionPrimary", "longTermConsumptionPrimary", "serviceOil", "serviceInspection",
    )

    fun isMapped(url: String) = url in ALWAYS || url in OVERVIEW || url in DETAIL

    /** The least demand at which [url] is worth following; objects we don't map only for the full list. */
    fun neededAt(url: String): VehicleDemand = when (url) {
        in ALWAYS -> VehicleDemand.NONE
        in OVERVIEW -> VehicleDemand.OVERVIEW
        else -> VehicleDemand.DETAIL
    }

    /** Objects that may carry personal data (places, VIN, phone, media): their values are never logged. */
    fun isPrivate(url: String) = PRIVATE.any { url.startsWith(it, ignoreCase = true) }

    private val PRIVATE = listOf(
        "Nav_", "navPosition", "vehicleIdent", "ExAc_", "Phone_", "SPI_", "Media", "WebRadio", "Radio_", "Car_vehicleInformation",
    )

    fun map(values: Collection<ExlapValue>): ExlapMapped {
        val byUrl = values.groupBy { it.url }
        val used = HashSet<String>()
        val out = ArrayList<FieldValue<*>>()
        fun obj(url: String) = byUrl[url]?.let { Obj(it, used) }

        obj("outsideTemperature")?.let { o -> out += VehicleField.OutsideTemperature of o.main()?.let { o.temperature(it) } }
        obj("oilTemperature")?.let { o -> out += VehicleField.OilTemperature of o.main()?.let { o.temperature(it) } }
        obj("coolantTemperature")?.let { o -> out += VehicleField.CoolantTemperature of o.main()?.let { o.temperature(it) } }
        obj("batteryVoltage")?.let { o ->
            val m = o.main() ?: o.member("main")
            out += VehicleField.BatteryVoltage of m?.let { o.number(it) }?.let { v -> if (o.unit(m)?.lowercase() == "mv") v / 1000 else v }
        }
        obj("tankLevelPrimary")?.let { o ->
            val m = o.main()
            // A Relative goes from 0 to 1; some cars may send a percentage instead
            out += VehicleField.FuelLevel of m?.let { o.number(it) }?.let { v ->
                val percent = if (o.unit(m)?.contains('%') == true || v > 1.0) v else v * 100
                percent.roundToInt().coerceIn(0, 100)
            }
        }
        obj("fuelWarningPrimaryTank")?.let { o -> out += VehicleField.FuelLow of o.firstFlag(::warningFlag) }
        obj("totalDistance")?.let { o -> out += VehicleField.Odometer of (o.member("distanceValue") ?: o.main())?.let { o.distance(it) } }
        // "Tageskilometer": the trip counter
        obj("dayMilage")?.let { o -> out += VehicleField.Trip of (o.member("distanceValue") ?: o.main())?.let { o.distance(it) } }
        obj("tyrePressures")?.let { o -> out += VehicleField.TirePressures of tirePressures(o) }
        obj("doorState")?.let { o -> out += doors(o) }
        obj("lightState_front")?.let { o -> out += VehicleField.Headlights of headlights(o) }
        obj("blinkingState")?.let { o -> out += blinking(o) }
        out += VehicleField.CurrentGear of gear(obj("currentGear"), obj("reverseGear"))
        obj("parkingBrake")?.let { o -> out += VehicleField.ParkingBrake of (o.member("engaged") ?: o.main())?.let { o.flag(it) } }
        obj("vehicleSpeed")?.let { o -> out += VehicleField.Speed of o.main()?.let { o.speed(it) } }
        obj("engineSpeed")?.let { o -> out += VehicleField.Rpm of o.main()?.let { o.number(it) }?.roundToInt() }
        obj("wheelAngle")?.let { o ->
            out += VehicleField.SteeringAngle of o.main()?.let { m -> o.number(m)?.let { if (o.unit(m)?.lowercase() == "rad") Math.toDegrees(it) else it } }
        }
        obj("shortTermConsumptionPrimary")?.let { o -> out += VehicleField.ConsumptionShortTerm of o.main()?.let { o.consumption(it) } }
        obj("longTermConsumptionPrimary")?.let { o -> out += VehicleField.ConsumptionLongTerm of o.main()?.let { o.consumption(it) } }
        obj("serviceOil")?.let { o -> out += service(o, VehicleField.ServiceOilDistance, VehicleField.ServiceOilDays) }
        obj("serviceInspection")?.let { o -> out += service(o, VehicleField.ServiceInspectionDistance, VehicleField.ServiceInspectionDays) }
        obj("Comfort_CarSeatsStatus")?.let { o -> out += seats(o) }
        return ExlapMapped(out.filter { it.value != null }, used)
    }

    /** One object's members; whatever is read is marked as used. */
    private class Obj(val members: List<ExlapValue>, private val used: MutableSet<String>) {
        val url: String get() = members.first().url

        /** The value of a one-value object: the member named like the object, or its only real member. */
        fun main(): ExlapValue? =
            members.firstOrNull { it.name.equals(url, ignoreCase = true) } ?: members.filter { it.name !in DESCRIPTIVE }.singleOrNull()

        fun member(name: String) = members.firstOrNull { it.name.equals(name, ignoreCase = true) }

        fun real() = members.filter { it.name !in DESCRIPTIVE && it.value != null }

        /** The unit the car declared for [m], or the one it sends next to it ("unit", "distanceUnit"). */
        fun unit(m: ExlapValue): String? =
            m.unit ?: (member(m.name.removeSuffix("Value") + "Unit") ?: member("unit"))?.also(::use)?.value

        fun use(m: ExlapValue) {
            used += "${m.url}/${m.name}"
        }

        fun number(m: ExlapValue): Double? = m.value?.trim()?.replace(',', '.')?.toDoubleOrNull()?.also { use(m) }

        fun flag(m: ExlapValue, read: (String) -> Boolean? = ::flagOf): Boolean? = m.value?.let(read)?.also { use(m) }

        fun firstFlag(read: (String) -> Boolean?): Boolean? = real().firstNotNullOfOrNull { flag(it, read) }

        fun temperature(m: ExlapValue): Double? {
            val v = number(m) ?: return null
            return when (normalized(unit(m))) {
                "f", "fahrenheit" -> (v - 32) * 5 / 9
                "k", "kelvin" -> v - 273.15
                else -> v
            }
        }

        fun distance(m: ExlapValue): Double? {
            val v = number(m) ?: return null
            return when (normalized(unit(m))) {
                "m" -> v / 1000
                "mi", "mile", "miles" -> v * KM_PER_MILE
                else -> v
            }
        }

        fun speed(m: ExlapValue): Double? {
            val v = number(m) ?: return null
            return when (normalized(unit(m))) {
                "mph" -> v * KM_PER_MILE
                "m/s" -> v * 3.6
                else -> v
            }
        }

        /** Litres per 100 km; other ways of counting (mpg, kWh) are left raw. */
        fun consumption(m: ExlapValue): Double? {
            val unit = normalized(unit(m))
            val v = m.value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
            val l100 = when (unit) {
                "l/100km", "" -> v
                "km/l" -> if (v > 0) 100 / v else return null
                else -> return null
            }
            use(m)
            return l100
        }
    }

    /** Front left, front right, rear left, rear right, only when all four are there. */
    private fun tirePressures(o: Obj): List<Double>? {
        val corners = CORNERS.map { corner -> o.real().firstOrNull { corner.matches(it.name) } }
        if (corners.any { it == null }) return null
        return corners.map { m ->
            val v = o.number(m!!) ?: return null
            when (normalized(o.unit(m))) {
                "bar" -> v
                "kpa" -> v / 100
                "mbar", "hpa" -> v / 1000
                "psi" -> v / PSI_PER_BAR
                // No unit declared: other readers take the raw value as tenths of a bar
                "" -> if (v > 10) v / 10 else v
                else -> return null
            }
        }
    }

    private fun doors(o: Obj): List<FieldValue<*>> {
        val out = ArrayList<FieldValue<*>>()
        val corners = arrayOfNulls<Boolean>(4)
        for (m in o.real()) {
            val name = m.name.lowercase(Locale.ROOT)
            val open = o.flag(m, ::openFlag) ?: continue
            when {
                HOOD.any { it in name } -> out += VehicleField.HoodOpen of open
                TRUNK.any { it in name } -> out += VehicleField.TrunkOpen of open
                else -> CORNERS.indexOfFirst { it.matches(m.name) }.takeIf { it >= 0 }?.let { corners[it] = open }
            }
        }
        // At least the front doors, so a two-door car still counts; a missing rear door counts as closed
        if (corners[0] != null && corners[1] != null) out += VehicleField.DoorsOpen of corners.map { it ?: false }
        return out
    }

    private fun headlights(o: Obj): HeadLights? {
        var known = false
        var high = false
        var low = false
        for (m in o.real()) {
            val name = m.name.lowercase(Locale.ROOT)
            val value = m.value!!.lowercase(Locale.ROOT)
            val on = flagOf(value)
            when {
                on != null && HIGH_BEAM.any { it in name } -> { known = true; high = high || on; o.use(m) }
                on != null && LOW_BEAM.any { it in name } -> { known = true; low = low || on; o.use(m) }
                // An enumeration of the light switch position
                on == null && HIGH_BEAM.any { it in value } -> { known = true; high = true; o.use(m) }
                on == null && LOW_BEAM.any { it in value } -> { known = true; low = true; o.use(m) }
                on == null && value == "off" -> { known = true; o.use(m) }
            }
        }
        return when {
            !known -> null
            high -> HeadLights.HIGH
            low -> HeadLights.ON
            else -> HeadLights.OFF
        }
    }

    private fun blinking(o: Obj): List<FieldValue<*>> {
        var known = false
        var left = false
        var right = false
        var hazard = false
        for (m in o.real()) {
            val name = m.name.lowercase(Locale.ROOT)
            val value = m.value!!.lowercase(Locale.ROOT)
            val on = flagOf(value)
            if (on != null) {
                when {
                    "hazard" in name || "warn" in name -> hazard = hazard || on
                    "left" in name -> left = left || on
                    "right" in name -> right = right || on
                    else -> continue
                }
            } else {
                when {
                    "hazard" in value || "warn" in value || "both" in value -> hazard = true
                    "left" in value -> left = true
                    "right" in value -> right = true
                    value in setOf("none", "inactive", "noblinking", "idle") -> Unit
                    else -> continue
                }
            }
            known = true
            o.use(m)
        }
        if (!known) return emptyList()
        val turn = when {
            hazard || (left && right) -> TurnSignal.NONE
            left -> TurnSignal.LEFT
            right -> TurnSignal.RIGHT
            else -> TurnSignal.NONE
        }
        return listOf(VehicleField.Turn of turn, VehicleField.Hazard of (hazard || (left && right)))
    }

    /** Reverse engaged wins; otherwise "Gear1".."Gear8" or a selector position. */
    private fun gear(current: Obj?, reverse: Obj?): Gear? {
        val reverseOn = reverse?.let { r -> (r.member("engaged") ?: r.main())?.let { r.flag(it) } }
        if (reverseOn == true) return Gear(GearPosition.REVERSE, null)
        val c = current ?: return null
        val m = c.main() ?: return null
        val value = m.value?.lowercase(Locale.ROOT) ?: return null
        val gear = when {
            value.startsWith("gear") -> value.removePrefix("gear").toIntOrNull()?.takeIf { it > 0 }?.let { Gear(GearPosition.MANUAL, it) }
            value.startsWith("rev") -> Gear(GearPosition.REVERSE, null)
            value.startsWith("park") -> Gear(GearPosition.PARK, null)
            value.startsWith("neutral") -> Gear(GearPosition.NEUTRAL, null)
            value.startsWith("drive") -> Gear(GearPosition.DRIVE, null)
            else -> null
        }
        if (gear != null) c.use(m)
        return gear
    }

    /** Distance and days left, told apart by the declared unit (or the member's name when there is none). */
    private fun service(o: Obj, distance: VehicleField<Double>, days: VehicleField<Int>): List<FieldValue<*>> {
        val out = ArrayList<FieldValue<*>>()
        for (m in o.real()) {
            val unit = normalized(o.unit(m))
            val name = m.name.lowercase(Locale.ROOT)
            when {
                unit in DISTANCE_UNITS || (unit.isEmpty() && ("distance" in name || "km" in name)) -> o.distance(m)?.let { out += distance of it }
                unit in DAY_UNITS || (unit.isEmpty() && ("day" in name || "time" in name)) -> o.number(m)?.let { out += days of it.roundToInt() }
            }
        }
        return out
    }

    /**
     * Who sits where, from the seats' occupancy (or, failing that, the belt) by the words in each seat's
     * name or position; seats that can't be placed are left out.
     */
    private fun seats(o: Obj): List<FieldValue<*>> {
        val passenger = ArrayList<Boolean>()
        val rear = ArrayList<Boolean>()
        val beltPassenger = ArrayList<Boolean>()
        val beltRear = ArrayList<Boolean>()
        for (m in o.real()) {
            val leaf = m.name.substringAfterLast('.').lowercase(Locale.ROOT)
            val occupancy = OCCUPIED.any { it in leaf }
            val belt = "belt" in leaf || "buckle" in leaf
            if (!occupancy && !belt) continue
            val value = o.flag(m, ::occupiedFlag) ?: continue
            // The seat is named in the member path or in a sibling of the same list element
            val prefix = m.name.substringBeforeLast('.', "")
            val where = (listOf(m.name) + o.members.filter { prefix.isNotEmpty() && it.name.startsWith("$prefix.") }.mapNotNull { it.value })
                .joinToString(" ").lowercase(Locale.ROOT)
            val target = when {
                PASSENGER_SEAT.any { it in where } -> if (occupancy) passenger else beltPassenger
                REAR_SEAT.any { it in where } -> if (occupancy) rear else beltRear
                else -> continue
            }
            target += value
        }
        fun any(list: List<Boolean>) = if (list.isEmpty()) null else list.any { it }
        return listOf(
            VehicleField.PassengerPresent of (any(passenger) ?: any(beltPassenger)),
            VehicleField.RearOccupied of (any(rear) ?: any(beltRear)),
        )
    }

    private class Corner(vararg val names: String) {
        fun matches(member: String): Boolean {
            val n = member.lowercase(Locale.ROOT).replace("_", "")
            return names.any { n.endsWith(it) || n.contains(it) }
        }
    }

    private val CORNERS = listOf(
        Corner("frontleft", "leftfront"), Corner("frontright", "rightfront"),
        Corner("rearleft", "leftrear", "backleft"), Corner("rearright", "rightrear", "backright"),
    )

    private fun normalized(unit: String?) = unit?.trim()?.lowercase(Locale.ROOT)?.replace("°", "")?.replace("deg", "")?.replace(" ", "").orEmpty()

    private fun flagOf(value: String): Boolean? = when (value.trim().lowercase(Locale.ROOT)) {
        "true", "1", "on", "active", "activated", "engaged", "yes" -> true
        "false", "0", "off", "inactive", "notactive", "deactivated", "disengaged", "no" -> false
        else -> null
    }

    private fun openFlag(value: String): Boolean? = when (value.trim().lowercase(Locale.ROOT)) {
        "open", "opened", "ajar", "notclosed" -> true
        "closed", "close", "locked", "notopen" -> false
        else -> flagOf(value)
    }

    private fun warningFlag(value: String): Boolean? = when (value.trim().lowercase(Locale.ROOT)) {
        "warning", "reserve", "low" -> true
        "nowarning", "no_warning", "normal", "ok", "none" -> false
        else -> flagOf(value)
    }

    private fun occupiedFlag(value: String): Boolean? = when (value.trim().lowercase(Locale.ROOT)) {
        "occupied", "present", "buckled", "fastened", "belted", "seated" -> true
        "free", "empty", "unoccupied", "notoccupied", "not_occupied", "notpresent", "unbuckled", "unfastened", "notbuckled" -> false
        else -> flagOf(value)
    }

    private val DESCRIPTIVE = setOf("unit", "state")
    private val HOOD = listOf("hood", "bonnet", "engine")
    private val TRUNK = listOf("trunk", "boot", "tailgate", "rearlid", "hatch", "luggage")
    private val HIGH_BEAM = listOf("highbeam", "high_beam", "mainbeam", "fullbeam")
    private val LOW_BEAM = listOf("lowbeam", "low_beam", "dippedbeam", "headlight")
    private val OCCUPIED = listOf("occup", "present", "seated", "person")
    private val PASSENGER_SEAT = listOf("passenger", "codriver", "co_driver", "beifahrer")
    private val REAR_SEAT = listOf("rear", "row2", "row3", "second", "back")
    private val DISTANCE_UNITS = setOf("km", "m", "mi", "mile", "miles")
    private val DAY_UNITS = setOf("d", "day", "days")
    private const val KM_PER_MILE = 1.609344
    private const val PSI_PER_BAR = 14.5037738
}
