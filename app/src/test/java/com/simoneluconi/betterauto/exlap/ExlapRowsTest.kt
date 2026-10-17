package com.simoneluconi.betterauto.exlap

import org.junit.Assert.assertEquals
import org.junit.Test

class ExlapRowsTest {
    private fun v(url: String, name: String, value: String?, unit: String? = null) = ExlapValue(url, name, value, unit, null)

    @Test
    fun oneRowPerObjectWithItsUnit() {
        val rows = exlapRows(
            listOf(
                v("vehicleSpeed", "speed", "0"), v("vehicleSpeed", "unit", "kmh"), v("vehicleSpeed", "state", "valid"),
                v("coolantTemperature", "temperature", "61", "°C"), v("coolantTemperature", "state", "valid"),
            )
        )
        assertEquals(listOf("Vehicle speed" to "0 kmh", "Coolant temperature" to "61 °C"), rows)
    }

    @Test
    fun objectsWithSeveralValuesNameEachOne() {
        val rows = exlapRows(listOf(v("doorState", "frontLeft", "closed"), v("doorState", "frontRight", "open")))
        assertEquals(listOf("Door state · Front left" to "closed", "Door state · Front right" to "open"), rows)
    }

    @Test
    fun objectsWithoutValuesAreLeftOut() {
        assertEquals(emptyList<Pair<String, String>>(), exlapRows(listOf(v("oilLevel", "level", null), v("oilLevel", "state", "nodata"))))
    }

    @Test
    fun readableNames() {
        assertEquals("System active source", readable("System_activeSource"))
        assertEquals("Vehicle identication number", readable("vehicleIdenticationNumber"))
    }
}
