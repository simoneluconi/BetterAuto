package com.simoneluconi.betterauto.map

import com.simoneluconi.betterauto.ui.theme.DayColors
import com.simoneluconi.betterauto.ui.theme.NightColors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The style file and the palette must name the same colors, or a layer would stay unpainted. */
class MapStyleTest {
    private val template = File("src/main/res/raw/map_style.json").readText()

    @Test
    fun everyPlaceholderIsAPaletteColorAndBack() {
        val used = Regex("\"@([A-Za-z]+)\"").findAll(template).map { it.groupValues[1] }.toSet()
        assertEquals(DayColors.map.byName().keys, used)
    }

    @Test
    fun filledStyleIsValidJsonWithoutPlaceholders() {
        for (colors in listOf(DayColors.map, NightColors.map)) {
            val style = MapStyle.fill(template, colors.byName())
            assertFalse(style.contains("\"@"))
            JSONObject(style)
        }
    }

    @Test
    fun themedPaintFindsEveryColoredProperty() {
        val slots = MapStyle.themedPaint(template)
        val placeholders = Regex("\"@[A-Za-z]+\"").findAll(template).count()
        assertEquals(placeholders, slots.size)
        assertTrue(MapStyle.ThemedPaint("land", "background-color", "land") in slots)
    }

    @Test
    fun cssColorIsHex() {
        assertEquals("#171b22", MapStyle.css(NightColors.map.land).lowercase())
    }
}
