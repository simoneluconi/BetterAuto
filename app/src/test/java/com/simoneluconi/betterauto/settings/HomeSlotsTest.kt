package com.simoneluconi.betterauto.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSlotsTest {
    private val music = WidgetChoice(WidgetType.MUSIC)
    private val notifications = WidgetChoice(WidgetType.NOTIFICATIONS)
    private val favorites = WidgetChoice(WidgetType.FAVORITES)
    private val clock = WidgetChoice(WidgetType.CLOCK)
    private val weather = WidgetChoice(WidgetType.WEATHER)

    @Test
    fun nothingSavedGivesTheDefault() {
        assertEquals(listOf(music, clock, notifications), HomeSlots.decode(null))
    }

    @Test
    fun savedSlotsComeBackAndGapsAreFilled() {
        assertEquals(listOf(clock, weather, music), HomeSlots.decode("CLOCK,WEATHER"))
        // A widget that no longer exists is dropped, a repeated one kept once
        assertEquals(listOf(clock, music, notifications), HomeSlots.decode("CLOCK,GONE,CLOCK"))
        assertEquals(listOf(clock, weather, music), HomeSlots.decode(HomeSlots.encode(listOf(clock, weather, music))))
    }

    @Test
    fun aSmartHomeGroupNeedsItsGroupAndNeverFillsAGap() {
        val group = WidgetChoice(WidgetType.SMART_HOME, "homeassistant/garden")
        assertEquals(listOf(group, music, clock), HomeSlots.decode(HomeSlots.encode(listOf(group, music, clock))))
        // Without its group it means nothing: dropped, and the gap gets an ordinary widget
        assertEquals(listOf(clock, music, notifications), HomeSlots.decode("CLOCK,SMART_HOME"))
        assertEquals(false, HomeSlots.decode("CLOCK").any { it.type == WidgetType.SMART_HOME })
    }

    @Test
    fun aParameterSurvivesTheCommasOfTheList() {
        val group = WidgetChoice(WidgetType.MAP, "Living room, ground floor")
        val saved = HomeSlots.encode(listOf(group, music, clock))
        assertEquals(listOf(group, music, clock), HomeSlots.decode(saved))
        assertEquals(WidgetChoice(WidgetType.MAP), WidgetChoice.decode("MAP"))
    }

    @Test
    fun pickingAWidgetShownElsewhereSwapsTheTwo() {
        val slots = listOf(music, notifications, favorites)
        assertEquals(listOf(clock, notifications, favorites), HomeSlots.pick(slots, 0, clock))
        assertEquals(listOf(favorites, notifications, music), HomeSlots.pick(slots, 0, favorites))
        assertEquals(slots, HomeSlots.pick(slots, 5, clock))
    }

    @Test
    fun rotatingGivesEachTheBigSlot() {
        assertEquals(listOf(notifications, favorites, music), HomeSlots.rotate(listOf(music, notifications, favorites)))
    }
}
