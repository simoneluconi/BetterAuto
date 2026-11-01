package com.simoneluconi.betterauto.map.nav

import kotlin.math.max

/**
 * How the driver keeps to Valhalla's times on this trip: the real time for the part of the route driven, against
 * Valhalla's time for the same part, carried across new routes (a reroute, a stop reached). Valhalla's times are
 * slow (a road test: 32 and 43 min for trips of 19 and 27): scaled by this pace, the rest of the trip came within a
 * couple of minutes once a few km were behind. Off the route nothing is counted.
 */
internal class TripPace {
    private var realS = 0.0
    private var predictedS = 0.0
    private var drivenM = 0.0

    // Where the car was last seen on the current route
    private var lastAtMs = 0L
    private var lastAlongM = 0.0

    /** A new trip: nothing known of the driver yet. */
    fun reset() {
        realS = 0.0
        predictedS = 0.0
        drivenM = 0.0
        lastAtMs = 0L
    }

    /** A new route starts where the car is, at [nowMs]: what was learnt stays, the counting goes on from here. */
    fun newRoute(nowMs: Long) {
        lastAtMs = nowMs
        lastAlongM = 0.0
    }

    /** The car is [alongM] metres along [route] at [nowMs] ([android.os.SystemClock.elapsedRealtime]). */
    fun onRoute(route: NavRoute, alongM: Double, nowMs: Long) {
        // Only forward: a fix a little behind the last is the GPS wandering, not the car going back
        val along = max(alongM, lastAlongM)
        if (lastAtMs != 0L && nowMs > lastAtMs) {
            realS += (nowMs - lastAtMs) / 1000.0
            predictedS += route.secondsTo(along) - route.secondsTo(lastAlongM)
            drivenM += along - lastAlongM
        }
        lastAtMs = nowMs
        lastAlongM = along
    }

    /** Real time over Valhalla's, once enough is driven to tell (1 before), within [MIN_RATIO]..[MAX_RATIO]. */
    val ratio: Double
        get() = if (drivenM < MIN_DRIVEN_M || predictedS <= 0) 1.0 else (realS / predictedS).coerceIn(MIN_RATIO, MAX_RATIO)

    /** The rest of [route] from [alongM], in seconds, at this driver's pace. */
    fun remainingS(route: NavRoute, alongM: Double): Double =
        max(0.0, route.secondsTo(Double.MAX_VALUE) - route.secondsTo(alongM)) * ratio

    /** For the log at the arrival, gathering how far off Valhalla is (no places in it). */
    fun summary(): String? {
        if (drivenM <= 0 || predictedS <= 0 || realS <= 0) return null
        return "%.1f km on the route in %.1f min, Valhalla's time %.1f min (x%.2f), %.0f km/h".format(
            java.util.Locale.ROOT, drivenM / 1000, realS / 60, predictedS / 60, realS / predictedS, drivenM / realS * 3.6,
        )
    }

    companion object {
        /** A town start or a red light says little: the pace counts after a couple of km. */
        const val MIN_DRIVEN_M = 2_000.0
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 1.5
    }
}
