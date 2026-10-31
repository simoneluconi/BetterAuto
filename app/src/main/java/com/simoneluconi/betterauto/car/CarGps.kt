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

    fun judge(position: Timed<Position>, satellites: Timed<Satellites>?, now: Long = System.currentTimeMillis()): Verdict {
        if (now - position.at > FRESH_MS) return Verdict.STALE
        if (firstPositionAt == 0L) firstPositionAt = now
        // The satellites come a moment after the first position: until then (or a while, for a car that never
        // sends them) the position is not trusted
        if (satellites == null && now - firstPositionAt < SATELLITES_WAIT_MS) return Verdict.WAIT
        val blind = satellites != null && now - satellites.at < FRESH_MS && satellites.value.inUse == 0
        return if (blind) Verdict.BLIND else Verdict.USE
    }

    companion object {
        /** A car position older than this is not live: the phone takes over. */
        const val FRESH_MS = 20_000L

        /** How long a car's first position waits for its satellite count. */
        const val SATELLITES_WAIT_MS = 5_000L
    }
}
