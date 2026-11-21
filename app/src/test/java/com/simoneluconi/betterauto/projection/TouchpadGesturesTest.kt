package com.simoneluconi.betterauto.projection

import com.simoneluconi.betterauto.projection.TouchpadGestures.Action
import com.simoneluconi.betterauto.projection.TouchpadGestures.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DHU's pad: 240 units over 55 mm, so about 4.4 units a millimetre. */
class TouchpadGesturesTest {
    private fun pad(tapAsSelect: Boolean = true) = TouchpadGestures(240, 55, tapAsSelect)

    /** Pad units for [mm] millimetres on the DHU's pad. */
    private fun u(mm: Float) = Math.round(mm * 240 / 55)

    /** A finger moved from the middle by (dx, dy) mm in [moves] even moves, then lifted: all the actions. */
    private fun slide(g: TouchpadGestures, dxMm: Float, dyMm: Float, moves: Int = 20): List<Action> {
        val out = mutableListOf<Action>()
        out += g.down(120, 120, 0)
        for (i in 1..moves) out += g.move(120 + u(dxMm * i / moves), 120 + u(dyMm * i / moves), i * 20L)
        out += g.up(120 + u(dxMm), 120 + u(dyMm), moves * 20L + 10)
        return out
    }

    private fun steps(actions: List<Action>) = actions.filterIsInstance<Action.Step>().map { it.direction }

    @Test
    fun aTapPresses() {
        val g = pad()
        g.down(100, 100, 0)
        g.move(102, 101, 50)
        assertEquals(listOf(Action.Press), g.up(102, 101, 120))
    }

    @Test
    fun aTapDoesNothingWhenThePadDoesNotSelectByTapping() {
        val g = pad(tapAsSelect = false)
        g.down(100, 100, 0)
        assertTrue(g.up(100, 100, 100).isEmpty())
    }

    @Test
    fun aSlideGoesFromControlToControlThatWay() {
        // 22 mm: three steps of 7 mm, one control after the other
        assertEquals(List(3) { Direction.RIGHT }, steps(slide(pad(), 22f, 0f)))
        assertEquals(List(2) { Direction.UP }, steps(slide(pad(), 0f, -15f)))
        assertEquals(List(4) { Direction.LEFT }, steps(slide(pad(), -29f, 0f)))
        assertEquals(List(1) { Direction.DOWN }, steps(slide(pad(), 0f, 8f)))
    }

    @Test
    fun aSlantedSlideStaysOnItsWay() {
        // Mostly to the right, drifting down a little: along the row, no step down
        assertEquals(List(3) { Direction.RIGHT }, steps(slide(pad(), 22f, 6f)))
    }

    @Test
    fun turningTheCornerStepsTheNewWay() {
        val g = pad()
        val out = mutableListOf<Action>()
        out += g.down(60, 60, 0)
        for (i in 1..10) out += g.move(60 + u(15f * i / 10), 60, i * 20L)
        for (i in 1..10) out += g.move(60 + u(15f), 60 + u(15f * i / 10), 200 + i * 20L)
        out += g.up(60 + u(15f), 60 + u(15f), 500)
        assertEquals(listOf(Direction.RIGHT, Direction.RIGHT, Direction.DOWN, Direction.DOWN), steps(out))
    }

    @Test
    fun aSlideIsNotAPress() {
        assertTrue(slide(pad(), 20f, 0f).none { it is Action.Press })
    }

    @Test
    fun aStillFingerHeldPressesLong() {
        val g = pad()
        g.down(100, 100, 0)
        assertTrue(g.tick(300).isEmpty())
        assertEquals(listOf(Action.LongPressStart), g.tick(TouchpadGestures.LONG_PRESS_MS))
        // Once only, and moving afterwards does not step
        assertTrue(g.tick(900).isEmpty())
        assertTrue(g.move(100 + u(30f), 100, 1000).isEmpty())
        assertEquals(listOf(Action.LongPressEnd), g.up(100, 100, 1100))
    }

    @Test
    fun movingCancelsTheLongPress() {
        val g = pad()
        g.down(100, 100, 0)
        g.move(100 + u(5f), 100, 100)
        assertNull(g.longPressAt)
        assertTrue(g.tick(800).isEmpty())
    }

    @Test
    fun aPadWithoutItsSizeUsesATypicalOne() {
        // 240 units with no physical size: taken as 55 mm, the same as the DHU's
        val g = TouchpadGestures(240, null, tapAsSelect = true)
        assertEquals(List(3) { Direction.RIGHT }, steps(slide(g, 22f, 0f)))
    }
}
