package com.simoneluconi.betterauto.aap

/**
 * How much music may be on its way to the car: sent but not acknowledged yet (the car acks as it plays),
 * plus what waits on the phone. The car's window counts messages, and a message carries whatever was
 * captured while waiting: after one slow ack the messages grew and the delay stayed (an Audi MMX2 through a
 * wireless dongle went from 0.5 to 0.8 s and never came back), so a video in a phone app ran ahead of its
 * sound. Here the audio ahead is bounded in time: the sender waits while [roomBytes] is used up, and the
 * audio beyond [latencyLimitBytes] is dropped, which brings the delay back after a hiccup.
 *
 * A car that wants more ahead (no ack while we hold the allowance, or drops again and again) gets it, a
 * step at a time; a calm half minute takes a step back. Not thread-safe: callers hold one lock. Pure, for tests.
 */
internal class AudioPacing(private val bytesPerMs: Double) {
    private val sizes = ArrayDeque<Int>()

    /** Bytes sent and not acknowledged yet. */
    var inFlightBytes = 0
        private set

    /** Audio allowed on its way to the car, ms. */
    var allowanceMs = BASE_MS
        private set

    private var lastTroubleAt = 0L
    private var lastLoosenAt = Long.MIN_VALUE / 2

    val inFlightMs: Int get() = (inFlightBytes / bytesPerMs).toInt()

    /** Bytes that may still go out now; at least one message always may when nothing is out. */
    fun roomBytes(): Int = if (sizes.isEmpty()) Int.MAX_VALUE else (allowanceMs * bytesPerMs).toInt() - inFlightBytes

    /** Sent plus waiting above this: the oldest waiting audio goes. */
    fun latencyLimitBytes(): Int = ((allowanceMs + MARGIN_MS) * bytesPerMs).toInt()

    fun sent(bytes: Int) {
        sizes.addLast(bytes)
        inFlightBytes += bytes
    }

    fun acked(messages: Int) {
        repeat(messages) { inFlightBytes -= sizes.removeFirstOrNull() ?: return }
    }

    /** A new stream: the car dropped what it had. */
    fun reset() {
        sizes.clear()
        inFlightBytes = 0
    }

    /** The car showed it needs more ahead: one step more, at most once per [LOOSEN_GAP_MS]. Returns true if changed. */
    fun loosen(nowMs: Long): Boolean {
        lastTroubleAt = nowMs
        if (allowanceMs >= MAX_MS || nowMs - lastLoosenAt < LOOSEN_GAP_MS) return false
        allowanceMs = (allowanceMs + STEP_MS).coerceAtMost(MAX_MS)
        lastLoosenAt = nowMs
        return true
    }

    /** After [CALM_MS] without trouble, one step back toward [BASE_MS]. Returns true if changed. */
    fun tighten(nowMs: Long): Boolean {
        if (allowanceMs <= BASE_MS || nowMs - lastTroubleAt < CALM_MS) return false
        allowanceMs = (allowanceMs - STEP_MS).coerceAtLeast(BASE_MS)
        lastTroubleAt = nowMs
        return true
    }

    companion object {
        const val BASE_MS = 300
        const val STEP_MS = 100
        const val MAX_MS = 1000

        /** Waiting audio on top of the allowance: one ack's worth, so a steady stream never drops. */
        const val MARGIN_MS = 150

        /** No ack for this long while the allowance is used up: the car waits for more before it plays. */
        const val STALL_MS = 500L
        const val LOOSEN_GAP_MS = 2_000L
        const val CALM_MS = 30_000L
    }
}
