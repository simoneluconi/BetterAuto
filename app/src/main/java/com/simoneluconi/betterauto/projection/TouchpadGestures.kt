package com.simoneluconi.betterauto.projection

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A car's touchpad driving the screen, apart from Android so it can be tested. The car sends the bare finger
 * (down, moves, up in the pad's own units) and the phone reads the gestures: the finger moved a step's length one way
 * takes the focus to the next control that way (a Tab with a direction: right, left, up or down, one control after
 * the other, never stopping between them), a quick double tap presses (a single one does nothing: a finger laid on
 * the pad by chance), a still finger held presses long. Distances are in
 * millimetres on the pad when the car gives its physical size, so a step feels the same on any pad.
 */
class TouchpadGestures(
    width: Int,
    physicalWidthMm: Int?,
    private val tapAsSelect: Boolean,
) {
    sealed interface Action {
        /** To the next control toward [direction]. */
        data class Step(val direction: Direction) : Action
        object Press : Action
        object LongPressStart : Action
        object LongPressEnd : Action
    }

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    /** Pad units in a millimetre: from the car's sizes, or a pad of [DEFAULT_WIDTH_MM] when it gives none. */
    private val unitsPerMm = width.toFloat() / (physicalWidthMm?.takeIf { it > 0 } ?: DEFAULT_WIDTH_MM)

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** The finger's way since the last step, in millimetres. */
    private var wayX = 0f
    private var wayY = 0f
    private var moved = false
    private var longPressed = false
    private var down = false

    /** When a still finger becomes a long press, for the caller to check with [tick]; null when it cannot any more. */
    var longPressAt: Long? = null
        private set

    fun down(x: Int, y: Int, atMs: Long): List<Action> {
        down = true
        startX = x.toFloat()
        startY = y.toFloat()
        lastX = startX
        lastY = startY
        wayX = 0f
        wayY = 0f
        moved = false
        longPressed = false
        longPressAt = atMs + LONG_PRESS_MS
        return emptyList()
    }

    fun move(x: Int, y: Int, @Suppress("UNUSED_PARAMETER") atMs: Long): List<Action> {
        if (!down || longPressed) return emptyList()
        if (!moved) {
            // Still a tap while the finger stays within the slop; once past it, the whole way so far counts
            if (hypot((x - startX) / unitsPerMm, (y - startY) / unitsPerMm) < SLOP_MM) return emptyList()
            moved = true
            longPressAt = null
            lastX = startX
            lastY = startY
        }
        wayX += (x - lastX) / unitsPerMm
        wayY += (y - lastY) / unitsPerMm
        lastX = x.toFloat()
        lastY = y.toFloat()
        val steps = mutableListOf<Action>()
        // The way it mostly went: a slightly slanted slide still goes straight along a row or a column
        while (maxOf(abs(wayX), abs(wayY)) >= STEP_MM) {
            if (abs(wayX) >= abs(wayY)) {
                steps += Action.Step(if (wayX > 0) Direction.RIGHT else Direction.LEFT)
                wayX -= if (wayX > 0) STEP_MM else -STEP_MM
                wayY = 0f
            } else {
                steps += Action.Step(if (wayY > 0) Direction.DOWN else Direction.UP)
                wayY -= if (wayY > 0) STEP_MM else -STEP_MM
                wayX = 0f
            }
        }
        return steps
    }

    fun up(x: Int, y: Int, atMs: Long): List<Action> {
        if (!down) return emptyList()
        down = false
        longPressAt = null
        if (longPressed) {
            lastTapAt = null
            return listOf(Action.LongPressEnd)
        }
        val still = !moved && hypot((x - startX) / unitsPerMm, (y - startY) / unitsPerMm) < SLOP_MM
        if (!still || !tapAsSelect) {
            lastTapAt = null
            return emptyList()
        }
        // One tap is nothing (a finger laid on the pad by chance); the second soon after, near the first, presses
        val first = lastTapAt
        val near = first != null && hypot((x - lastTapX) / unitsPerMm, (y - lastTapY) / unitsPerMm) < DOUBLE_TAP_MM
        if (first != null && near && atMs - first <= DOUBLE_TAP_MS) {
            lastTapAt = null
            return listOf(Action.Press)
        }
        lastTapAt = atMs
        lastTapX = x.toFloat()
        lastTapY = y.toFloat()
        return emptyList()
    }

    /** The tap before, waiting for its second one. */
    private var lastTapAt: Long? = null
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** The finger still down and still at [atMs]: the long press starts once. */
    fun tick(atMs: Long): List<Action> {
        val due = longPressAt ?: return emptyList()
        if (!down || moved || atMs < due) return emptyList()
        longPressAt = null
        longPressed = true
        return listOf(Action.LongPressStart)
    }

    companion object {
        /** Finger travel that is still a tap, not a slide. */
        const val SLOP_MM = 2.5f

        /** The finger moved this far one way: the next control that way. */
        const val STEP_MM = 7f

        /** Two taps within this time, this close together, are a press. */
        const val DOUBLE_TAP_MS = 350L
        const val DOUBLE_TAP_MM = 10f

        /** A still finger held this long presses long (the knob's held press). */
        const val LONG_PRESS_MS = 600L

        /** A pad that does not say its size: about the DHU's and a car's palm rest pad. */
        const val DEFAULT_WIDTH_MM = 55
    }
}
