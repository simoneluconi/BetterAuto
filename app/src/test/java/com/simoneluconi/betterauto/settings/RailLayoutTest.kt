package com.simoneluconi.betterauto.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RailLayoutTest {
    @Test
    fun roundTrip() {
        val layout = RailLayout.place(RailLayout.Default, RailItem.MAP, RailSlot.GROUP_1)
        assertEquals(layout, RailLayout.decode(RailLayout.encode(layout)))
    }

    @Test
    fun nothingSavedMeansDefault() {
        assertEquals(RailLayout.Default, RailLayout.decode(null))
        assertEquals(RailLayout.Default, RailLayout.decode(""))
    }

    @Test
    fun itemsAddedLaterAreAppended() {
        // A layout saved before the vehicle and notifications items existed
        val old = RailLayout.decode("PHONE:BAR,HOME:BAR,MUSIC:HIDDEN,MAP:GROUP_2")
        assertEquals(listOf(RailItem.PHONE, RailItem.HOME, RailItem.MUSIC, RailItem.MAP, RailItem.VEHICLE, RailItem.NOTIFICATIONS), old.map { it.item })
        assertEquals(RailSlot.HIDDEN, old.first { it.item == RailItem.MUSIC }.slot)
        assertEquals(RailSlot.HIDDEN, old.first { it.item == RailItem.NOTIFICATIONS }.slot)
    }

    @Test
    fun unknownPartsAreIgnored() {
        val layout = RailLayout.decode("RADIO:BAR,HOME:SOMEWHERE,HOME:HIDDEN")
        // Unknown item dropped, unknown slot falls back to the bar, duplicates keep the first
        assertEquals(RailSlot.BAR, layout.first { it.item == RailItem.HOME }.slot)
        assertEquals(RailItem.entries.size, layout.size)
    }

    @Test
    fun moveStaysInBounds() {
        val up = RailLayout.move(RailLayout.Default, RailItem.HOME, -1)
        assertEquals(RailLayout.Default, up)
        val down = RailLayout.move(RailLayout.Default, RailItem.HOME, 1)
        assertEquals(listOf(RailItem.MUSIC, RailItem.HOME), down.take(2).map { it.item })
    }

    @Test
    fun mediaAppsLeaveOutTheOneMusicFollows() {
        val none = { emptyList<String>() }
        // Pinned first, then recent ones not pinned
        assertEquals(listOf("b", "a", "c"), RailLayout.mediaApps(listOf("b"), listOf("a", "b", "c"), null, none))
        // The followed app goes, recent or pinned; the others keep their order
        assertEquals(listOf("b", "c"), RailLayout.mediaApps(listOf("b"), listOf("a", "b", "c"), "a", none))
        assertEquals(listOf("a", "c"), RailLayout.mediaApps(listOf("b"), listOf("a", "b", "c"), "b", none))
        // An app nobody listed changes nothing
        assertEquals(listOf("a"), RailLayout.mediaApps(emptyList(), listOf("a"), "x", none))
    }

    @Test
    fun suggestionsOnlyWithoutHistory() {
        val suggested = { listOf("s1", "s2") }
        assertEquals(listOf("s1", "s2"), RailLayout.mediaApps(emptyList(), emptyList(), null, suggested))
        assertEquals(listOf("s2"), RailLayout.mediaApps(emptyList(), emptyList(), "s1", suggested))
        // The only recent app playing in Music: no suggestions in its place
        assertEquals(emptyList<String>(), RailLayout.mediaApps(emptyList(), listOf("a"), "a", suggested))
    }
}
