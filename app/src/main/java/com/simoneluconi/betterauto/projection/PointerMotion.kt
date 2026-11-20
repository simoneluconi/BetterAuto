package com.simoneluconi.betterauto.projection

/** A place on a phone app's picture, in the app display's pixels. */
data class PointerPosition(val x: Float, val y: Float)

/** One finger event of a synthetic swipe: [atMs] after its start, at ([x], [y]). */
data class SwipeTouch(val atMs: Long, val phase: Phase, val x: Float, val y: Float) {
    enum class Phase { DOWN, MOVE, UP }
}

/**
 * Pointer mode's arithmetic (`ui/apps/PhoneAppScreen`), apart from Android so it can be tested: how far the knob's
 * nudges move our cursor over a phone app, and the finger's path that scrolls the app for a turn of the knob.
 * Steps are shares of the picture's shorter side, scrolls of its height: the same part of the app on any car screen.
 */
object PointerMotion {
    /** One nudge, pressed and let go. */
    const val NUDGE_STEP = 0.04f

    /** Quick nudges the same way (a car that sends presses only, no hold) grow by half a step each, up to this. */
    const val MAX_STREAK_STEPS = 3f

    /** A nudge this close behind the last one the same way is part of a streak. */
    const val STREAK_GAP_MS = 400L

    /** A nudge held this long starts gliding, moved every [REPEAT_MS]. */
    const val HOLD_DELAY_MS = 300L
    const val REPEAT_MS = 40L

    /** The glide's speed in shorter sides a second: from [START_SPEED] up to [TOP_SPEED] over [RAMP_MS] of holding. */
    const val START_SPEED = 0.6f
    const val TOP_SPEED = 2.4f
    const val RAMP_MS = 1200L

    /** One knob detent scrolls by a finger moved this share of the height (less the app's touch slop). */
    const val SCROLL_STEP = 0.22f

    /** Detents gathered into one swipe at most; the rest follows in the next. */
    const val MAX_SCROLL_STEPS = 3

    /** The swipe's finger keeps this share of the picture from its edges (bars, gestures at the very edge). */
    const val SWIPE_EDGE = 0.06f

    /** The finger moves in this many steps this far apart, then rests: a scroll by exactly that much, no fling. */
    const val SWIPE_MOVES = 8
    const val SWIPE_MOVE_MS = 18L

    /** Longer than Android's 40 ms after which a pointer counts as stopped: the velocity is then zero. */
    const val SWIPE_REST_MS = 60L

    /** How far one nudge moves, as a share of the shorter side, the [streak]-th quick one in a row (0: the first). */
    fun nudgeStep(streak: Int): Float = NUDGE_STEP * minOf(1f + 0.5f * streak.coerceAtLeast(0), MAX_STREAK_STEPS)

    /** How far one glide repeat moves, as a share of the shorter side, [heldMs] after the nudge was pressed. */
    fun heldStep(heldMs: Long): Float {
        val ramp = ((heldMs - HOLD_DELAY_MS).toFloat() / RAMP_MS).coerceIn(0f, 1f)
        return (START_SPEED + (TOP_SPEED - START_SPEED) * ramp) * REPEAT_MS / 1000f
    }

    /** The middle of a [width] x [height] picture. */
    fun center(width: Int, height: Int): PointerPosition = PointerPosition(width / 2f, height / 2f)

    /** [position] kept on a [width] x [height] picture (its last pixel at most). */
    fun clamp(position: PointerPosition, width: Int, height: Int): PointerPosition = PointerPosition(
        position.x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat()),
        position.y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat()),
    )

    /** [position] moved toward ([dx], [dy]) (each -1, 0 or 1) by [share] of the shorter side, kept on the picture. */
    fun move(position: PointerPosition, dx: Int, dy: Int, share: Float, width: Int, height: Int): PointerPosition {
        val step = share * minOf(width, height)
        return clamp(PointerPosition(position.x + dx * step, position.y + dy * step), width, height)
    }

    /** The same place on the picture at its new size: the cursor stays over the same part of the app. */
    fun rescale(position: PointerPosition, oldWidth: Int, oldHeight: Int, width: Int, height: Int): PointerPosition =
        if (oldWidth <= 0 || oldHeight <= 0) center(width, height)
        else clamp(PointerPosition(position.x * width / oldWidth, position.y * height / oldHeight), width, height)

    /**
     * The finger's path that scrolls the app [steps] detents at [x], [y] on a [width] x [height] picture: positive
     * (the knob turned forward) shows what is further down, so the finger moves up, as on a phone. The path stays
     * inside the picture, shifted when the cursor is near an edge; it ends with the finger at rest, so the app
     * scrolls by the distance and doesn't fling. Empty for no steps.
     */
    fun swipe(x: Float, y: Float, width: Int, height: Int, steps: Int): List<SwipeTouch> {
        if (steps == 0 || width <= 0 || height <= 0) return emptyList()
        val count = steps.coerceIn(-MAX_SCROLL_STEPS, MAX_SCROLL_STEPS)
        val edge = height * SWIPE_EDGE
        val room = height - 2 * edge
        val distance = minOf(SCROLL_STEP * height * kotlin.math.abs(count), room)
        val up = count > 0
        // Where the finger may start for the whole path to fit
        val startY = if (up) y.coerceIn(edge + distance, height - edge) else y.coerceIn(edge, height - edge - distance)
        val startX = x.coerceIn(width * SWIPE_EDGE, width * (1 - SWIPE_EDGE))
        val endY = if (up) startY - distance else startY + distance
        val touches = ArrayList<SwipeTouch>(SWIPE_MOVES + 3)
        touches += SwipeTouch(0, SwipeTouch.Phase.DOWN, startX, startY)
        for (i in 1..SWIPE_MOVES) {
            touches += SwipeTouch(i * SWIPE_MOVE_MS, SwipeTouch.Phase.MOVE, startX, startY + (endY - startY) * i / SWIPE_MOVES)
        }
        val restAt = SWIPE_MOVES * SWIPE_MOVE_MS + SWIPE_REST_MS
        touches += SwipeTouch(restAt, SwipeTouch.Phase.MOVE, startX, endY)
        touches += SwipeTouch(restAt, SwipeTouch.Phase.UP, startX, endY)
        return touches
    }
}

/**
 * Counts quick nudges the same way, for [PointerMotion.nudgeStep]: a car that sends only presses (no hold) still
 * speeds up as the driver keeps nudging.
 */
class NudgeStreak {
    private var lastDx = 0
    private var lastDy = 0
    private var lastAt = Long.MIN_VALUE
    private var count = 0

    /** A nudge toward ([dx], [dy]) at [nowMs]: how many came just before it the same way. */
    fun press(dx: Int, dy: Int, nowMs: Long): Int {
        val quick = lastAt != Long.MIN_VALUE && nowMs - lastAt in 0..PointerMotion.STREAK_GAP_MS
        count = if (quick && dx == lastDx && dy == lastDy) count + 1 else 0
        lastDx = dx
        lastDy = dy
        lastAt = nowMs
        return count
    }

    fun reset() {
        lastAt = Long.MIN_VALUE
        count = 0
    }
}
