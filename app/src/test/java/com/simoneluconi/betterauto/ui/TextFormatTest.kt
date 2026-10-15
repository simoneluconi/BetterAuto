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
    fun firstOfLetterPointsAtTheFirstItemOfEachLetterInOrder() {
        val titles = listOf("zebra", "Alba", "anna", "42", "Émile")
        assertEquals(mapOf('#' to 3, 'A' to 1, 'E' to 4, 'Z' to 0), firstOfLetter(titles) { it })
    }

    @Test
    fun firstOfLetterWithHeadersCountsTheHeaderRows() {
        val names = listOf("Alba", "Anna", "Bruno", "Carla")
        // A header, Alba, Anna, B header, Bruno, C header, Carla
        assertEquals(mapOf('A' to 1, 'B' to 4, 'C' to 6), firstOfLetterWithHeaders(names) { it })
        assertEquals(emptyMap<Char, Int>(), firstOfLetterWithHeaders(emptyList<String>()) { it })
    }

    @Test
    fun formatTimeShowsHoursOnlyWhenNeeded() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("1:01:01", formatTime(3_661_000))
    }
}
