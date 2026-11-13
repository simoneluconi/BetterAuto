package com.simoneluconi.betterauto

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun wholeAddressesKeepOnlyTheirLastTwoBytes() {
        assertEquals(
            "Wireless: WiFi \"Dongle\" (XX:XX:XX:XX:CF:AE), WPA2_PERSONAL",
            DiagnosticLog.masked("Wireless: WiFi \"Dongle\" (f2:5a:80:a8:cf:ae), WPA2_PERSONAL"),
        )
    }

    @Test
    fun alreadyMaskedAddressesAndTimesStayAsTheyAre() {
        val line = "2026-11-13 18:43:31.887 Bluetooth: car at XX:XX:XX:XX:51:6A, already paired"
        assertEquals(line, DiagnosticLog.masked(line))
    }
}
