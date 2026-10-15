package com.simoneluconi.betterauto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormatTest {

    @Test
    fun indexLetterDropsAccentsAndFilesTheRestUnderHash() {
        assertEquals('E', indexLetter("Élodie"))
        assertEquals('N', indexLetter("Ñandú"))
        assertEquals('Z', indexLetter("  zoe"))
        assertEquals('#', indexLetter("42 Entertainment"))
        assertEquals('#', indexLetter(""))
    }

    @Test
    fun formatTimeShowsHoursOnlyWhenNeeded() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("1:01:01", formatTime(3_661_000))
    }
}
