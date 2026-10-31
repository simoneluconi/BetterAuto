package com.simoneluconi.betterauto.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarGpsGateTest {
    private val position = Position(45.0, 9.0, 5.0, null, 50.0, 90.0)

    @Test
    fun liveWithSatellitesIsUsed() {
        val gate = CarGpsGate()
        assertEquals(CarGpsGate.Verdict.USE, gate.judge(Timed(position, 1_000), Timed(Satellites(8, 10), 1_000), now = 1_500))
    }

    @Test
    fun oldPositionIsStale() {
        val gate = CarGpsGate()
        assertEquals(CarGpsGate.Verdict.STALE, gate.judge(Timed(position, 0), Timed(Satellites(8, 10), 0), now = CarGpsGate.FRESH_MS + 1))
    }

    @Test
    fun firstPositionWaitsForSatellitesThenIsTrustedAnyway() {
        val gate = CarGpsGate()
        assertEquals(CarGpsGate.Verdict.WAIT, gate.judge(Timed(position, 1_000), null, now = 1_000))
        // A car that never sends its satellites: after a while its position counts
        val later = 1_000 + CarGpsGate.SATELLITES_WAIT_MS
        assertEquals(CarGpsGate.Verdict.USE, gate.judge(Timed(position, later), null, now = later))
    }

    @Test
    fun noSatellitesIsBlind() {
        val gate = CarGpsGate()
        assertEquals(CarGpsGate.Verdict.BLIND, gate.judge(Timed(position, 1_000), Timed(Satellites(0, 4), 1_000), now = 1_000))
    }

    @Test
    fun satellitesBackButTheSamePositionStaysBlindUntilItMoves() {
        val gate = CarGpsGate()
        assertEquals(CarGpsGate.Verdict.BLIND, gate.judge(Timed(position, 1_000), Timed(Satellites(0, 16), 1_000), now = 1_000))
        // Satellites in use again, the position still the one kept from before the garage
        assertEquals(CarGpsGate.Verdict.BLIND, gate.judge(Timed(position, 20_000), Timed(Satellites(7, 12), 20_000), now = 20_000))
        val fix = position.copy(latitude = 45.03, accuracyM = 20.0)
        assertEquals(CarGpsGate.Verdict.USE, gate.judge(Timed(fix, 21_000), Timed(Satellites(8, 12), 21_000), now = 21_000))
        // Standing still with a real fix afterwards stays trusted
        assertEquals(CarGpsGate.Verdict.USE, gate.judge(Timed(fix, 22_000), Timed(Satellites(8, 12), 22_000), now = 22_000))
    }

    @Test
    fun oldSatelliteCountDoesNotBlind() {
        val gate = CarGpsGate()
        val now = 2 * CarGpsGate.FRESH_MS
        assertEquals(CarGpsGate.Verdict.USE, gate.judge(Timed(position, now), Timed(Satellites(0, 4), 0), now = now))
    }
}
