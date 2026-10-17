package com.simoneluconi.betterauto.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UnitsTest {
    private val metric = Units(imperial = false, fahrenheit = false, locale = Locale.ITALY)
    private val imperial = Units(imperial = true, fahrenheit = true, locale = Locale.US)

    @Test
    fun temperatures() {
        assertEquals("21,5 °C", metric.temperature(21.5))
        assertEquals("70.7 °F", imperial.temperature(21.5))
        assertEquals("32 °F", imperial.temperature(0.0, decimals = 0))
        assertEquals("-40 °F", imperial.temperature(-40.0, decimals = 0))
    }

    @Test
    fun distancesAndSpeeds() {
        assertEquals("12.345 km", metric.distance(12_345.0))
        assertEquals("7,671 mi", imperial.distance(12_345.0))
        assertEquals("3,2 km", metric.distance(3.2, decimals = 1))
        assertEquals("62 mph", imperial.speed(100.0))
        assertEquals("130 km/h", metric.speed(130.0))
    }

    @Test
    fun pressuresAndLengths() {
        assertEquals("2,4 bar", metric.tirePressure(2.4))
        assertEquals("35 psi", imperial.tirePressure(2.4))
        assertEquals("1013 hPa", metric.airPressure(101.3))
        assertEquals("29.91 inHg", imperial.airPressure(101.3))
        assertEquals("100 m", metric.length(100.0))
        assertEquals("328 ft", imperial.length(100.0))
    }

    @Test
    fun autoFollowsThePhoneAndAChoiceOverridesIt() {
        val auto = Units.resolve(UnitSystem.AUTO, TemperatureUnit.AUTO, phoneImperial = true, phoneFahrenheit = false)
        assertTrue(auto.imperial)
        assertFalse(auto.fahrenheit)

        val chosen = Units.resolve(UnitSystem.METRIC, TemperatureUnit.FAHRENHEIT, phoneImperial = true, phoneFahrenheit = false)
        assertFalse(chosen.imperial)
        assertTrue(chosen.fahrenheit)
    }
}
