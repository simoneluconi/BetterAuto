package com.simoneluconi.betterauto

import org.junit.Assert.assertEquals
import org.junit.Test

/** Licence texts joined for the phone's width, the words and the list items as they were. */
class LicenceReflowTest {
    @Test
    fun paragraphsJoinListItemsStay() {
        val text = "Copyright 2008 Google Inc.\n\nRedistribution and use in source\nand binary forms are permitted:\n\n" +
            "    * Redistributions of source code must\nretain the above notice.\n    * Redistributions in binary form must\nreproduce it.\n"
        assertEquals(
            "Copyright 2008 Google Inc.\n\nRedistribution and use in source and binary forms are permitted:\n\n" +
                "    * Redistributions of source code must retain the above notice.\n    * Redistributions in binary form must reproduce it.\n",
            reflow(text),
        )
    }
}
