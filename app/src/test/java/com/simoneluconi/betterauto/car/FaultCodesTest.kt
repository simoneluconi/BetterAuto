package com.simoneluconi.betterauto.car

import org.junit.Assert.assertEquals
import org.junit.Test

class FaultCodesTest {
    @Test
    fun textCodes() {
        assertEquals(listOf("P0171", "P0420"), FaultCodes.parse("P0171 p0420\n".toByteArray()))
        assertEquals(listOf("U0100"), FaultCodes.parse("U0100,U0100".toByteArray()))
    }

    @Test
    fun binaryJ2012Codes() {
        // P0171: system 00 (P), first digit 0, second 1, then 0x71
        assertEquals(listOf("P0171"), FaultCodes.parse(byteArrayOf(0x01, 0x71)))
        // C0035: system 01 (C) -> 0x40, then 0x35
        assertEquals(listOf("C0035"), FaultCodes.parse(byteArrayOf(0x40, 0x35)))
        // U0100: system 11 (U) -> 0xC1, then 0x00; trailing zero padding is ignored
        assertEquals(listOf("U0100"), FaultCodes.parse(byteArrayOf(0xC1.toByte(), 0x00, 0x00, 0x00)))
        // P242F
        assertEquals(listOf("P242F"), FaultCodes.parse(byteArrayOf(0x24, 0x2F)))
    }

    @Test
    fun emptyMeansNoFaults() {
        assertEquals(emptyList<String>(), FaultCodes.parse(ByteArray(0)))
        assertEquals(emptyList<String>(), FaultCodes.parse(byteArrayOf(0, 0)))
    }
}
