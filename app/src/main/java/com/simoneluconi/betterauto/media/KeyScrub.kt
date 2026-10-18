package com.simoneluconi.betterauto.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * The car's next/previous keys: a short press changes track, holding runs through the track instead,
 * slowly at first and faster the longer the key is held, up to a speed that suits the track's length (a
 * song never races, a podcast can). The press is decided on release, so that a hold does not change track
 * first. Main thread only, like the keys it gets.
 */
object KeyScrub {
    private val main = Handler(Looper.getMainLooper())
    /** 1 forward, -1 back, 0 when no key is held. */
    private var direction = 0
    private var scrubbing = false
    private var holdStart = 0L
    private var target = 0L
    private var duration = 0L
    /** Top speed for this track: a long podcast goes faster than a song. */
    private var topRate = 0.0
    private var lastTick = 0L
    private var lastSeek = 0L

    private val _running = MutableStateFlow(0)
    /** 1 running forward, -1 back, 0 not: the screen shows it instead of a play button flickering at each jump. */
    val running: StateFlow<Int> = _running

    private val startScrub = Runnable {
        val track = NowPlaying.track.value ?: return@Runnable
        if (track.durationMs <= 0) return@Runnable // a stream without length: the release changes track
        scrubbing = true
        _running.value = direction
        duration = track.durationMs
        topRate = (duration.toDouble() / CROSS_MS).coerceIn(MIN_TOP_RATE, MAX_RATE)
        target = positionNow(track)
        holdStart = SystemClock.elapsedRealtime()
        lastTick = holdStart
        lastSeek = 0L
        EventLog.log("Input: running through the track ${if (direction > 0) "forward" else "back"}")
        tick.run()
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            step()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun press(forward: Boolean) {
        if (direction != 0) return // the car repeating the key while held
        direction = if (forward) 1 else -1
        main.postDelayed(startScrub, HOLD_MS)
    }

    fun release() {
        main.removeCallbacks(startScrub)
        main.removeCallbacks(tick)
        when {
            direction == 0 -> Unit
            scrubbing -> {
                NowPlaying.seekTo(target)
                EventLog.log("Input: track at ${clock(target)}")
            }
            direction > 0 -> NowPlaying.next()
            else -> NowPlaying.previous()
        }
        direction = 0
        scrubbing = false
        _running.value = 0
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val held = now - holdStart
        // Track time per second held: 4x real time, doubling every 1.5 s, up to the track's top speed
        val rate = (START_RATE * 2.0.pow(held / DOUBLE_EVERY_MS.toDouble())).coerceAtMost(topRate)
        target = (target + direction * (now - lastTick) * rate).toLong().coerceIn(0L, (duration - END_MARGIN_MS).coerceAtLeast(0L))
        lastTick = now
        // Not at every tick: each seek costs the player a moment of buffering
        if (now - lastSeek >= SEEK_EVERY_MS) {
            NowPlaying.seekTo(target)
            lastSeek = now
        }
    }

    private fun positionNow(track: Track): Long {
        val elapsed = if (track.playing) (SystemClock.elapsedRealtime() - track.positionUpdatedAt) * track.speed else 0f
        return (track.positionMs + elapsed.toLong()).coerceIn(0L, track.durationMs)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** How long a press must last to run through the track instead of changing it. */
    private const val HOLD_MS = 500L
    private const val TICK_MS = 50L
    private const val SEEK_EVERY_MS = 250L
    private const val START_RATE = 4.0
    private const val DOUBLE_EVERY_MS = 1500L
    /** At top speed the whole track goes by in about this long: no need to race through a song. */
    private const val CROSS_MS = 8000L
    private const val MIN_TOP_RATE = 8.0
    private const val MAX_RATE = 240.0
    /** Running forward stops short of the end, so that the track does not finish while held. */
    private const val END_MARGIN_MS = 2000L
}
