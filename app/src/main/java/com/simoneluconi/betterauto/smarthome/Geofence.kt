package com.simoneluconi.betterauto.smarthome

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The area where arrival buttons show: a place's radius plus the driver's offset. Shown from
 * [enterM] in, hidden only past [exitM] (a margin against GPS jitter at the edge).
 */
data class Fence(val latitude: Double, val longitude: Double, val radiusM: Double, val offsetM: Double, val hysteresisM: Double = HYSTERESIS_M) {
    val enterM: Double get() = radiusM + offsetM
    val exitM: Double get() = enterM + hysteresisM

    fun distanceM(latitude: Double, longitude: Double) = Geo.distanceM(this.latitude, this.longitude, latitude, longitude)

    companion object {
        const val HYSTERESIS_M = 150.0
    }
}

enum class FenceTransition { ENTER, EXIT }

/**
 * Follows position samples against a [Fence]: inside or not, with the hysteresis, and the first sample
 * already inside counts as arriving (the session starts in the garage: the buttons show).
 */
class FenceTracker(val fence: Fence) {
    /** Null until the first usable sample. */
    var inside: Boolean? = null
        private set

    /** A new position: the transition it causes, if any. Fixes vaguer than [MAX_ACCURACY_M] are ignored. */
    fun update(latitude: Double, longitude: Double, accuracyM: Double? = null): FenceTransition? {
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return null
        val d = fence.distanceM(latitude, longitude)
        val was = inside
        val now = when (was) {
            null, false -> d <= fence.enterM
            true -> d <= fence.exitM
        }
        inside = now
        return when {
            now && was != true -> FenceTransition.ENTER
            !now && was == true -> FenceTransition.EXIT
            else -> null
        }
    }

    companion object {
        const val MAX_ACCURACY_M = 250.0
    }
}

/** A pretend position (debug): where it is at a time, given the fence it is relative to. */
sealed interface FakeRoute {
    /** Metres per second it moves at, for the interval; null when it says nothing. */
    val speedMs: Double?

    /** Position at [nowMs]; null when it needs a fence and there is none yet. */
    fun at(nowMs: Long, fence: Fence?): Pair<Double, Double>?

    data class Fixed(val latitude: Double, val longitude: Double, override val speedMs: Double? = null) : FakeRoute {
        override fun at(nowMs: Long, fence: Fence?) = latitude to longitude
    }

    /** [distanceM] from the fence's centre on [bearingDeg]. */
    data class FromPlace(val distanceM: Double, val bearingDeg: Double = 0.0, override val speedMs: Double? = null) : FakeRoute {
        override fun at(nowMs: Long, fence: Fence?) = fence?.let { Geo.offset(it.latitude, it.longitude, distanceM, bearingDeg) }
    }

    /**
     * A drive in a straight line through the place: starts [startM] away on [bearingDeg], comes in at
     * [speedMs], goes past the centre and away on the other side. So arriving, the hysteresis and
     * leaving all happen in one run.
     */
    data class Drive(val startM: Double, override val speedMs: Double, val startedAtMs: Long, val bearingDeg: Double = 0.0) : FakeRoute {
        /** Signed distance from the centre along the line: positive before it, negative past it. */
        fun along(nowMs: Long) = startM - speedMs * (nowMs - startedAtMs) / 1000.0

        override fun at(nowMs: Long, fence: Fence?): Pair<Double, Double>? {
            fence ?: return null
            val d = along(nowMs)
            return Geo.offset(fence.latitude, fence.longitude, kotlin.math.abs(d), if (d >= 0) bearingDeg else bearingDeg + 180)
        }
    }
}

/** Distances and the phone GPS schedule: pure, so it is tested without Android. */
object Geo {
    private const val EARTH_M = 6_371_000.0

    /** Great-circle distance (haversine), metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** The point [distanceM] away from a place on [bearingDeg] (0 north, 90 east). */
    fun offset(latitude: Double, longitude: Double, distanceM: Double, bearingDeg: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(latitude)
        val l1 = Math.toRadians(longitude)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + kotlin.math.atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** How fast a car may go when nothing says otherwise: 144 km/h. */
    const val MAX_SPEED_MS = 40.0
    /** A known speed counts at least this much: a parked car may start any moment. */
    const val MIN_SPEED_MS = 8.0
    const val MIN_INTERVAL_MS = 5_000L
    const val MAX_INTERVAL_MS = 600_000L

    /** Metres to the nearest edge that would change something: the enter edge outside, the exit edge inside. */
    fun edgeDistanceM(fence: Fence, distanceM: Double, inside: Boolean): Double =
        if (inside) (fence.exitM - distanceM).coerceAtLeast(0.0) else (distanceM - fence.enterM).coerceAtLeast(0.0)

    /**
     * When to ask the phone for the next fix: the time the car needs, at most, to reach the nearest
     * edge. [speedMs] is the car's speed when known (taken ×1.5 for safety), otherwise [MAX_SPEED_MS].
     */
    fun nextFixMs(edgeM: Double, speedMs: Double?): Long {
        val speed = speedMs?.let { (it * 1.5).coerceIn(MIN_SPEED_MS, MAX_SPEED_MS) } ?: MAX_SPEED_MS
        return (edgeM / speed * 1000).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
