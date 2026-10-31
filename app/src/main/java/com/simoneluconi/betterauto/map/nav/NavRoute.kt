package com.simoneluconi.betterauto.map.nav

import com.simoneluconi.betterauto.map.distanceM
import org.json.JSONObject

/** What a maneuver looks like on its sign: the arrow shown in the next-turn card. */
enum class ManeuverKind { STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, U_TURN, ROUNDABOUT, ARRIVE }

/** One step of the route: where it starts along the shape and what to say about it. */
data class Maneuver(
    val kind: ManeuverKind,
    /** Shown on the card ("Svolta a destra su Via Roma."). */
    val instruction: String,
    /** The street it leads onto, when it has one. */
    val street: String?,
    /** Spoken just before it. */
    val spokenNow: String,
    /** Spoken a while before it, without the distance ("Svolta a destra su Via Roma"). */
    val spokenSoon: String,
    /** Index into the route's points where it happens. */
    val beginIndex: Int,
    /** Roundabout exit to take, when it is one. */
    val roundaboutExit: Int? = null,
    /** The arrival at a stop on the way: which one (0 the first); null for every other maneuver and the destination. */
    val stop: Int? = null,
    /** Where a roundabout is left (index into the route's points), for the arrow drawn on the map. */
    val exitIndex: Int? = null,
)

/**
 * A route to follow: its line (latitude, longitude pairs), the maneuvers along it, length and time. With stops it
 * is one leg per stop and one to the destination, joined: [legEnds] holds the last point of each.
 */
class NavRoute(
    val points: DoubleArray,
    val maneuvers: List<Maneuver>,
    val lengthM: Double,
    val durationS: Double,
    val legEnds: IntArray = intArrayOf(points.size / 2 - 1),
) {
    val size: Int get() = points.size / 2
    fun lat(i: Int) = points[2 * i]
    fun lon(i: Int) = points[2 * i + 1]

    /** How far along the route each point is, in metres (the first at 0). */
    val cumulative: DoubleArray by lazy {
        val out = DoubleArray(size)
        for (i in 1 until size) out[i] = out[i - 1] + distanceM(lat(i - 1), lon(i - 1), lat(i), lon(i))
        out
    }

    /**
     * The piece of the route's line from [fromM] to [toM] along it (latitude, longitude pairs), cut where those
     * distances fall between points; empty when it has no length.
     */
    fun slice(fromM: Double, toM: Double): DoubleArray {
        val c = cumulative
        val from = fromM.coerceIn(0.0, c.last())
        val to = toM.coerceIn(0.0, c.last())
        if (to <= from) return DoubleArray(0)
        val out = ArrayList<Double>()
        fun at(m: Double) {
            val i = (c.indexOfLast { it <= m }).coerceIn(0, size - 2)
            val length = c[i + 1] - c[i]
            val t = if (length <= 0) 0.0 else ((m - c[i]) / length).coerceIn(0.0, 1.0)
            out += lat(i) + (lat(i + 1) - lat(i)) * t
            out += lon(i) + (lon(i + 1) - lon(i)) * t
        }
        at(from)
        for (i in 0 until size) if (c[i] > from && c[i] < to) {
            out += lat(i)
            out += lon(i)
        }
        at(to)
        return out.toDoubleArray()
    }

    companion object {
        /**
         * A route from Valhalla's `/route` answer (https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/):
         * every leg's shape (polyline, 6 decimals) and maneuvers, joined, already in the language asked for.
         */
        fun fromValhalla(json: String): NavRoute {
            val trip = JSONObject(json).getJSONObject("trip")
            val legs = trip.getJSONArray("legs")
            val points = ArrayList<Double>()
            val maneuvers = ArrayList<Maneuver>()
            val legEnds = IntArray(legs.length())
            for (l in 0 until legs.length()) {
                val leg = legs.getJSONObject(l)
                // The legs' maneuvers count their points from the start of the leg
                val offset = points.size / 2
                decodePolyline6(leg.getString("shape")).forEach { points += it }
                legEnds[l] = points.size / 2 - 1
                val list = leg.getJSONArray("maneuvers")
                for (i in 0 until list.length()) {
                    val m = list.getJSONObject(i)
                    // The way out of a roundabout is already in its entry ("take the 2nd exit"): shown twice, the
                    // card and the cluster would show a second roundabout right after the first
                    if (m.optInt("type") == ROUNDABOUT_EXIT) {
                        // Where the arrow over the roundabout ends
                        val last = maneuvers.lastOrNull()
                        if (last?.kind == ManeuverKind.ROUNDABOUT) maneuvers[maneuvers.size - 1] = last.copy(exitIndex = offset + m.optInt("begin_shape_index"))
                        continue
                    }
                    val instruction = m.optString("instruction")
                    val kind = kindOf(m.optInt("type"))
                    maneuvers += Maneuver(
                        kind = kind,
                        instruction = instruction,
                        street = m.optJSONArray("street_names")?.optString(0)?.takeIf { it.isNotBlank() },
                        spokenNow = m.optString("verbal_pre_transition_instruction").ifBlank { instruction },
                        spokenSoon = m.optString("verbal_succinct_transition_instruction").ifBlank { m.optString("verbal_transition_alert_instruction") }
                            .ifBlank { instruction },
                        beginIndex = offset + m.optInt("begin_shape_index"),
                        roundaboutExit = m.optInt("roundabout_exit_count").takeIf { it > 0 },
                        stop = l.takeIf { kind == ManeuverKind.ARRIVE && l < legs.length() - 1 },
                    )
                }
            }
            val summary = trip.getJSONObject("summary")
            return NavRoute(points.toDoubleArray(), maneuvers, summary.optDouble("length") * 1000, summary.optDouble("time"), legEnds)
        }

        /** Valhalla's maneuver types, by the arrow a driver expects. */
        internal fun kindOf(type: Int): ManeuverKind = when (type) {
            9, 18, 20, 23, 37 -> ManeuverKind.SLIGHT_RIGHT
            10 -> ManeuverKind.RIGHT
            11 -> ManeuverKind.SHARP_RIGHT
            16, 19, 21, 24, 38 -> ManeuverKind.SLIGHT_LEFT
            15 -> ManeuverKind.LEFT
            14 -> ManeuverKind.SHARP_LEFT
            12, 13 -> ManeuverKind.U_TURN
            26, 27 -> ManeuverKind.ROUNDABOUT
            4, 5, 6 -> ManeuverKind.ARRIVE
            else -> ManeuverKind.STRAIGHT
        }

        /** Valhalla's "exit the roundabout" maneuver. */
        private const val ROUNDABOUT_EXIT = 27

        /** An encoded polyline with 6 decimals (Valhalla's) into latitude, longitude pairs. */
        internal fun decodePolyline6(encoded: String): DoubleArray {
            val out = ArrayList<Double>()
            var i = 0
            var lat = 0L
            var lon = 0L
            while (i < encoded.length) {
                for (axis in 0..1) {
                    var result = 0L
                    var shift = 0
                    while (true) {
                        val b = encoded[i++].code - 63
                        result = result or ((b and 0x1F).toLong() shl shift)
                        shift += 5
                        if (b < 0x20) break
                    }
                    val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                    if (axis == 0) lat += delta else lon += delta
                }
                out += lat / 1e6
                out += lon / 1e6
            }
            return out.toDoubleArray()
        }
    }
}
