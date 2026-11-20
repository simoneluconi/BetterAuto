package com.simoneluconi.betterauto.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMotionTest {
    @Test
    fun aNudgeMovesAShareOfTheShorterSide() {
        val moved = PointerMotion.move(PointerPosition(500f, 300f), 1, 0, PointerMotion.NUDGE_STEP, 1000, 600)
        assertEquals(500f + 0.04f * 600, moved.x, 0.01f)
        assertEquals(300f, moved.y, 0.01f)
        val up = PointerMotion.move(PointerPosition(500f, 300f), 0, -1, PointerMotion.NUDGE_STEP, 1000, 600)
        assertEquals(300f - 24f, up.y, 0.01f)
    }

    @Test
    fun theCursorStaysOnThePicture() {
        val left = PointerMotion.move(PointerPosition(5f, 5f), -1, -1, 0.5f, 1000, 600)
        assertEquals(PointerPosition(0f, 0f), left)
        val right = PointerMotion.move(PointerPosition(995f, 595f), 1, 1, 0.5f, 1000, 600)
        assertEquals(PointerPosition(999f, 599f), right)
    }

    @Test
    fun quickNudgesTheSameWaySpeedUpToALimit() {
        val streak = NudgeStreak()
        assertEquals(0, streak.press(1, 0, 1000))
        assertEquals(1, streak.press(1, 0, 1200))
        assertEquals(2, streak.press(1, 0, 1500))
        assertEquals(PointerMotion.NUDGE_STEP, PointerMotion.nudgeStep(0), 1e-6f)
        assertEquals(PointerMotion.NUDGE_STEP * 2, PointerMotion.nudgeStep(2), 1e-6f)
        assertEquals(PointerMotion.NUDGE_STEP * PointerMotion.MAX_STREAK_STEPS, PointerMotion.nudgeStep(50), 1e-6f)
    }

    @Test
    fun aPauseOrAnotherWayStartsTheStreakAgain() {
        val streak = NudgeStreak()
        streak.press(1, 0, 1000)
        assertEquals(1, streak.press(1, 0, 1100))
        assertEquals(0, streak.press(0, 1, 1200))
        assertEquals(0, streak.press(0, 1, 1200 + PointerMotion.STREAK_GAP_MS + 1))
    }

    @Test
    fun holdingGlidesFasterAndFaster() {
        val first = PointerMotion.heldStep(PointerMotion.HOLD_DELAY_MS)
        val later = PointerMotion.heldStep(PointerMotion.HOLD_DELAY_MS + PointerMotion.RAMP_MS / 2)
        val top = PointerMotion.heldStep(PointerMotion.HOLD_DELAY_MS + PointerMotion.RAMP_MS)
        assertTrue(first < later && later < top)
        assertEquals(PointerMotion.START_SPEED * PointerMotion.REPEAT_MS / 1000f, first, 1e-6f)
        assertEquals(top, PointerMotion.heldStep(60_000), 1e-6f)
        // At full speed the cursor crosses a wide picture's shorter side in well under a second
        assertTrue(top * (1000f / PointerMotion.REPEAT_MS) >= 2f)
    }

    @Test
    fun theCursorKeepsItsPlaceWhenThePictureResizes() {
        assertEquals(PointerPosition(500f, 150f), PointerMotion.rescale(PointerPosition(250f, 75f), 500, 300, 1000, 600))
        assertEquals(PointerPosition(500f, 300f), PointerMotion.rescale(PointerPosition(1f, 1f), 0, 0, 1000, 600))
    }

    @Test
    fun turningForwardSwipesUpAndEndsAtRest() {
        val path = PointerMotion.swipe(400f, 300f, 1000, 600, 1)
        assertEquals(SwipeTouch.Phase.DOWN, path.first().phase)
        assertEquals(SwipeTouch.Phase.UP, path.last().phase)
        assertTrue(path.drop(1).dropLast(1).all { it.phase == SwipeTouch.Phase.MOVE })
        assertEquals(300f, path.first().y, 0.01f)
        assertEquals(300f - PointerMotion.SCROLL_STEP * 600, path.last().y, 0.01f)
        assertTrue(path.all { it.x == 400f })
        // Times only go forward, and the finger rests before it lifts: no fling
        assertTrue(path.zipWithNext().all { (a, b) -> b.atMs >= a.atMs })
        val beforeRest = path[path.size - 3]
        val rest = path[path.size - 2]
        assertEquals(beforeRest.y, rest.y, 0.01f)
        assertTrue(rest.atMs - beforeRest.atMs > 40)
    }

    @Test
    fun turningBackSwipesDown() {
        val path = PointerMotion.swipe(400f, 300f, 1000, 600, -1)
        assertTrue(path.last().y > path.first().y)
    }

    @Test
    fun aSwipeNearAnEdgeIsShiftedInside() {
        val edge = 600 * PointerMotion.SWIPE_EDGE
        val atTop = PointerMotion.swipe(400f, 2f, 1000, 600, 1)
        assertTrue(atTop.all { it.y >= edge - 0.01f && it.y <= 600 - edge + 0.01f })
        val atBottom = PointerMotion.swipe(0f, 599f, 1000, 600, -1)
        assertTrue(atBottom.all { it.y >= edge - 0.01f && it.y <= 600 - edge + 0.01f })
        assertTrue(atBottom.all { it.x >= 1000 * PointerMotion.SWIPE_EDGE - 0.01f })
    }

    @Test
    fun manyDetentsMakeALongerSwipeThatStillFits() {
        val one = PointerMotion.swipe(500f, 300f, 1000, 600, 1)
        val three = PointerMotion.swipe(500f, 300f, 1000, 600, 3)
        val many = PointerMotion.swipe(500f, 300f, 1000, 600, 40)
        fun length(path: List<SwipeTouch>) = path.first().y - path.last().y
        assertEquals(3 * length(one), length(three), 0.5f)
        assertEquals(length(three), length(many), 0.5f)
        assertTrue(length(many) <= 600 * (1 - 2 * PointerMotion.SWIPE_EDGE) + 0.01f)
        assertTrue(PointerMotion.swipe(500f, 300f, 1000, 600, 0).isEmpty())
    }
}
