package com.simoneluconi.betterauto.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacingTest {
    // 48 kHz, 16-bit stereo
    private val bytesPerMs = 192.0
    private fun ms(n: Int) = (n * bytesPerMs).toInt()

    @Test
    fun audioAheadStaysWithinTheAllowanceWhateverTheMessageSize() {
        val pace = AudioPacing(bytesPerMs)
        assertEquals(Int.MAX_VALUE, pace.roomBytes())
        // Larger messages after a slow ack fill the allowance sooner instead of adding delay
        pace.sent(ms(150))
        pace.sent(ms(150))
        assertTrue(pace.roomBytes() <= 0)
        pace.acked(1)
        assertEquals(ms(150), pace.roomBytes())
        assertEquals(150, pace.inFlightMs)
    }

    @Test
    fun waitingAudioBeyondTheLimitIsTheDelayToDrop() {
        val pace = AudioPacing(bytesPerMs)
        assertEquals(ms(AudioPacing.BASE_MS + AudioPacing.MARGIN_MS), pace.latencyLimitBytes())
    }

    @Test
    fun aNewStreamAndStrayAcksStartFromNothing() {
        val pace = AudioPacing(bytesPerMs)
        pace.sent(ms(100))
        pace.reset()
        pace.acked(3)
        assertEquals(0, pace.inFlightBytes)
        pace.sent(ms(20))
        pace.acked(2)
        assertEquals(0, pace.inFlightBytes)
    }

    @Test
    fun aCarThatNeedsMoreGetsItStepByStepThenCalmTakesItBack() {
        val pace = AudioPacing(bytesPerMs)
        assertTrue(pace.loosen(1_000))
        assertEquals(AudioPacing.BASE_MS + AudioPacing.STEP_MS, pace.allowanceMs)
        // Not again right away: one hiccup is one step
        assertFalse(pace.loosen(1_500))
        var now = 1_500L
        repeat(20) {
            now += AudioPacing.LOOSEN_GAP_MS
            pace.loosen(now)
        }
        assertEquals(AudioPacing.MAX_MS, pace.allowanceMs)

        assertFalse(pace.tighten(now + AudioPacing.CALM_MS - 1))
        now += AudioPacing.CALM_MS
        assertTrue(pace.tighten(now))
        assertEquals(AudioPacing.MAX_MS - AudioPacing.STEP_MS, pace.allowanceMs)
        repeat(20) {
            now += AudioPacing.CALM_MS
            pace.tighten(now)
        }
        assertEquals(AudioPacing.BASE_MS, pace.allowanceMs)
        assertFalse(pace.tighten(now + AudioPacing.CALM_MS))
    }
}
