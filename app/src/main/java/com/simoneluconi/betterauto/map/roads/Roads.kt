package com.simoneluconi.betterauto.map.roads

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A speed limit as the road data states it, in its own unit. */
data class SpeedLimit(val value: Int, val mph: Boolean) {
    val kmh: Double get() = if (mph) value * KM_PER_MILE else value.toDouble()

    /** The same limit for a driver reading [inMph]: as the sign says when units match, else converted and rounded to 5. */
    fun shown(inMph: Boolean): Int = when {
        inMph == mph -> value
        inMph -> ((kmh / KM_PER_MILE) / 5).roundToInt() * 5
        else -> (kmh / 5).roundToInt() * 5
    }

    companion object {
        const val KM_PER_MILE = 1.609344
    }
}

/** Which way along a road's drawing the car goes. */
enum class TravelDirection { FORWARD, BACKWARD }

/** One limit of a road: along which part of it (fractions of its length) and which way, null for both. */
data class LimitRule(val limit: SpeedLimit, val from: Double = 0.0, val to: Double = 1.0, val direction: TravelDirection? = null)

/** A name for part of a road ("Viale ..." that ends as "Piazzale ..."). */
data class NameRule(val name: String, val from: Double, val to: Double)

/** A road number and the network it belongs to, as the data has them ("A1", "IT:A-road"; "E 35", "e-road"). */
data class RouteRef(val ref: String, val network: String?) {
    /** How the road sign shows this number, by country and kind of road. */
    val shield: ShieldKind get() = shieldKind(ref, network)
}

/** The colours of a road-number sign. NEUTRAL where the country's signs are not known here: better than a wrong colour. */
enum class ShieldKind { GREEN, BLUE, RED, YELLOW, UK_PRIMARY, WHITE, NEUTRAL }

/**
 * The sign colours of a road number, from its network ("IT:A-road", "DE:BAB", "FR:N-road", "e-road", or a
 * province "IT:FI") or, within a known country, its letters. European routes are green; in Italy motorways
 * green, every other numbered road blue; elsewhere as each country signs them.
 */
fun shieldKind(ref: String, network: String?): ShieldKind {
    val net = network?.lowercase()
    val country = net?.takeIf { ':' in it }?.substringBefore(':')?.uppercase()
    val prefix = ref.takeWhile { it.isLetter() }.uppercase()
    if (net == "e-road" || (prefix == "E" && country == null)) return ShieldKind.GREEN
    return when (country) {
        // Motorways (and the link roads run as motorways) green; state, regional and provincial roads blue
        "IT" -> if (prefix == "A" || net == "it:a-road") ShieldKind.GREEN else ShieldKind.BLUE
        "DE" -> when (prefix) {
            "A" -> ShieldKind.BLUE
            "B" -> ShieldKind.YELLOW
            else -> ShieldKind.NEUTRAL
        }
        "FR" -> when (prefix) {
            "A", "N" -> ShieldKind.RED
            "D", "M" -> ShieldKind.YELLOW
            else -> ShieldKind.NEUTRAL
        }
        "CH" -> if (prefix == "A") ShieldKind.RED else ShieldKind.BLUE
        "ES" -> when (prefix) {
            "A", "AP" -> ShieldKind.BLUE
            "N" -> ShieldKind.RED
            else -> ShieldKind.NEUTRAL
        }
        "NL" -> when (prefix) {
            "A" -> ShieldKind.RED
            "N" -> ShieldKind.YELLOW
            else -> ShieldKind.NEUTRAL
        }
        "GB" -> when (prefix) {
            "M" -> ShieldKind.BLUE
            "A" -> ShieldKind.UK_PRIMARY
            "B" -> ShieldKind.WHITE
            else -> ShieldKind.NEUTRAL
        }
        else -> ShieldKind.NEUTRAL
    }
}

/**
 * One stretch of road, between two junctions, as a source draws it. [partial]: the source had only a piece
 * of it (cut at a tile's edge), so fractions along it are not the road's own and rules tied to a part of the
 * road are not trusted.
 */
class RoadSegment(
    val id: String,
    val name: String?,
    /** Road numbers, as on the signs: the country's own first ("A1"), then a European route ("E 35"). */
    val refs: List<RouteRef>,
    val roadClass: String,
    /** Latitude, longitude pairs. */
    val points: DoubleArray,
    val limits: List<LimitRule>,
    val partial: Boolean = false,
    /** Names for parts of it, when it changes name along the way; [name] otherwise. */
    val nameRules: List<NameRule> = emptyList(),
) {
    /** The name at [fraction] along the road. */
    fun nameAt(fraction: Double): String? {
        if (partial || nameRules.isEmpty()) return name
        return nameRules.firstOrNull { fraction >= it.from - EDGE && fraction <= it.to + EDGE }?.name ?: name
    }

    val size: Int get() = points.size / 2
    fun lat(i: Int) = points[2 * i]
    fun lon(i: Int) = points[2 * i + 1]

    /** The point of the road nearest to [latitude], [longitude], how far it is, and the road's direction there. */
    fun nearestPoint(latitude: Double, longitude: Double): RoadPoint? {
        val cosLat = cos(Math.toRadians(latitude))
        var best: RoadPoint? = null
        for (i in 0 until size - 1) {
            val ax = Math.toRadians(lon(i) - longitude) * cosLat * EARTH_RADIUS_M
            val ay = Math.toRadians(lat(i) - latitude) * EARTH_RADIUS_M
            val dx = Math.toRadians(lon(i + 1) - lon(i)) * cosLat * EARTH_RADIUS_M
            val dy = Math.toRadians(lat(i + 1) - lat(i)) * EARTH_RADIUS_M
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            val t = ((-ax) * dx + (-ay) * dy).div(len2).coerceIn(0.0, 1.0)
            val d = sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
            if (best == null || d < best.distanceM) {
                best = RoadPoint(
                    lat(i) + (lat(i + 1) - lat(i)) * t,
                    lon(i) + (lon(i + 1) - lon(i)) * t,
                    d,
                    (Math.toDegrees(atan2(dx, dy)) + 360) % 360,
                )
            }
        }
        return best
    }

    /** The limit for a car at [fraction] of the road going [direction]; null when the data says nothing sure. */
    fun limitAt(fraction: Double, direction: TravelDirection?): SpeedLimit? {
        val applying = limits.filter { r -> r.direction == null || direction == null || r.direction == direction }
        // On a piece of the road where along it the car is is not known: only a rule for the whole road holds
        val here = if (partial) applying.filter { it.from <= EDGE && it.to >= 1 - EDGE }
        else applying.filter { fraction >= it.from - EDGE && fraction <= it.to + EDGE }
        // Two different limits here (the way unknown, rules overlapping): nothing sure to say
        return here.map { it.limit }.toSet().singleOrNull()
    }

    private companion object {
        const val EDGE = 0.001
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/** A point on a road's line, [distanceM] from where it was asked for, with the line's [bearing] (drawing order). */
data class RoadPoint(val latitude: Double, val longitude: Double, val distanceM: Double, val bearing: Double)

/** Where the car is on a road. */
data class RoadMatch(val segment: RoadSegment, val fraction: Double, val direction: TravelDirection?, val distanceM: Double)

/**
 * Picks the road the car is on from the roads around it: the nearest one, weighed by how well its direction
 * agrees with the car's heading (a side street at a junction, or a parallel road, runs another way), and
 * sticking to the road it was on unless another is clearly better, so a junction doesn't flicker the name.
 */
class RoadMatcher {
    private var current: String? = null

    fun reset() {
        current = null
    }

    fun match(roads: List<RoadSegment>, latitude: Double, longitude: Double, bearing: Float?, accuracyM: Float? = null): RoadMatch? {
        val maxDistance = MAX_DISTANCE_M + (accuracyM?.coerceAtMost(MAX_EXTRA_M.toFloat())?.toDouble() ?: 0.0)
        var best: Scored? = null
        var stay: Scored? = null
        for (road in roads) {
            if (road.size < 2) continue
            val s = score(road, latitude, longitude, bearing) ?: continue
            if (s.match.distanceM > maxDistance) continue
            if (best == null || s.score < best.score) best = s
            if (road.id == current && (stay == null || s.score < stay.score)) stay = s
        }
        val chosen = if (stay != null && best != null && stay.score <= best.score + STICK_M) stay else best
        current = chosen?.match?.segment?.id
        return chosen?.match
    }

    private class Scored(val match: RoadMatch, val score: Double)

    private fun score(road: RoadSegment, latitude: Double, longitude: Double, bearing: Float?): Scored? {
        val cosLat = cos(Math.toRadians(latitude))
        fun x(i: Int) = Math.toRadians(road.lon(i) - longitude) * cosLat * EARTH_RADIUS_M
        fun y(i: Int) = Math.toRadians(road.lat(i) - latitude) * EARTH_RADIUS_M

        var total = 0.0
        for (i in 0 until road.size - 1) total += hypot(x(i + 1) - x(i), y(i + 1) - y(i))
        if (total <= 0.0) return null

        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestEdgeBearing = 0.0
        var along = 0.0
        for (i in 0 until road.size - 1) {
            val ax = x(i)
            val ay = y(i)
            val dx = x(i + 1) - ax
            val dy = y(i + 1) - ay
            val len2 = dx * dx + dy * dy
            val len = sqrt(len2)
            if (len2 == 0.0) continue
            // The car is at the origin: the nearest point of this edge to it
            val t = ((-ax) * dx + (-ay) * dy).div(len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestD) {
                bestD = d
                bestAlong = along + t * len
                bestEdgeBearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            }
            along += len
        }
        var direction: TravelDirection? = null
        var penalty = 0.0
        if (bearing != null) {
            val diff = angleDiff(bearing.toDouble(), bestEdgeBearing)
            direction = if (diff <= 90) TravelDirection.FORWARD else TravelDirection.BACKWARD
            // How far the road's line is from the car's, either way along it
            penalty = minOf(diff, 180 - diff) / 90 * HEADING_WEIGHT_M
        }
        return Scored(RoadMatch(road, bestAlong / total, direction, bestD), bestD + penalty)
    }

    companion object {
        /** Farther than this from every road: not on any road the data knows (a car park, a field). */
        const val MAX_DISTANCE_M = 25.0
        private const val MAX_EXTRA_M = 25.0
        /** A road at right angles to the car counts as this much farther away. */
        private const val HEADING_WEIGHT_M = 25.0
        /** How much better another road must be to leave the current one. */
        private const val STICK_M = 8.0
        private const val EARTH_RADIUS_M = 6_371_000.0

        private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

        /** 0..180 degrees between two bearings. */
        fun angleDiff(a: Double, b: Double): Double {
            val d = abs((a - b) % 360)
            return if (d > 180) 360 - d else d
        }
    }
}
