package com.simoneluconi.betterauto.car

/**
 * Whether the car's GPS position can be believed, for everything that follows the car (the map, the
 * smart-home arrival buttons): the car's own GPS when it is live and sees satellites, the phone's otherwise.
 * One gate per follower: it remembers when the car's first position came.
 */
class CarGpsGate {
    enum class Verdict {
        /** Live and seeing satellites: use it, the phone's GPS can rest. */
        USE,
        /** A first position, its satellite count not here yet: neither use it nor give up on it. */
        WAIT,
        /** No satellites (a garage): the car keeps sending its last fix, kilometres off. The phone counts. */
        BLIND,
        /** Too old: the car stopped sending. The phone counts. */
        STALE,
    }

    private var firstPositionAt = 0L

    /** The position the car kept sending while blind: still the old one, it is not a fix yet. */
    private var frozen: Pair<Double, Double>? = null

    fun judge(position: Timed<Position>, satellites: Timed<Satellites>?, now: Long = System.currentTimeMillis()): Verdict {
        if (now - position.at > FRESH_MS) return Verdict.STALE
        if (firstPositionAt == 0L) firstPositionAt = now
        // The satellites come a moment after the first position: until then (or a while, for a car that never
        // sends them) the position is not trusted
        if (satellites == null && now - firstPositionAt < SATELLITES_WAIT_MS) return Verdict.WAIT
        val at = position.value.latitude to position.value.longitude
        val blind = satellites != null && now - satellites.at < FRESH_MS && satellites.value.inUse == 0
        if (blind) {
            frozen = at
            return Verdict.BLIND
        }
        // Out of a garage the satellites come back long before the position does (an Audi: a minute, 3 km
        // off, until it moved): blind until the position changes
        if (at == frozen) return Verdict.BLIND
        frozen = null
        return Verdict.USE
    }

    companion object {
        /** A car position older than this is not live: the phone takes over. */
        const val FRESH_MS = 20_000L

        /** How long a car's first position waits for its satellite count. */
        const val SATELLITES_WAIT_MS = 5_000L
    }
}
