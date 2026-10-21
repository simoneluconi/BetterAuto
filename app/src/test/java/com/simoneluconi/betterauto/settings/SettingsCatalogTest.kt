package com.simoneluconi.betterauto.settings

import com.simoneluconi.betterauto.PhoneSettingsSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog's own rules, so a new setting can't skip them. */
class SettingsCatalogTest {
    private val all = SettingsCatalog.all

    @Test
    fun keysAreUnique() {
        val keys = all.map { it.key } + PhoneSettingsSections.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everySettingSaysWhatItDoes() {
        for (item in all) {
            assertNotEquals("${item.key} has no description", 0, item.description)
            assertNotEquals("${item.key}: the description repeats the title", item.title, item.description)
        }
        for (section in PhoneSettingsSections.all) {
            assertNotEquals("${section.key} has no description", 0, section.description)
            assertNotEquals("${section.key}: the description repeats the title", section.title, section.description)
        }
    }

    @Test
    fun everyCategoryTheCarShowsHasSomething() {
        val shown = SettingsCatalog.carCategories()
        assertTrue(shown.isNotEmpty())
        for (category in shown) assertTrue("$category is empty", SettingsCatalog.items(category, SettingsSurface.CAR).isNotEmpty())
        // Each category is somewhere: in the car, or on the phone through a section
        for (category in SettingsCategory.entries) {
            assertTrue("$category is shown nowhere", category in shown || PhoneSettingsSections.all.any { it.category == category })
        }
    }

    @Test
    fun listsHaveASummary() {
        for (item in all.filterIsInstance<ListSetting>()) assertNotEquals("${item.key} has no summary", 0, item.summary)
    }

    @Test
    fun categorySummariesNameSettingsOfTheirOwnCategory() {
        for (category in SettingsCategory.entries) {
            for (key in category.summaryKeys) {
                val item = all.firstOrNull { it.key == key }
                assertTrue("$category sums up $key, which is not in it", item != null && item.category == category)
            }
        }
    }

    @Test
    fun switchesAreOnAndOff() {
        for (item in all.filterIsInstance<ChoiceSetting<*>>().filter { it.isSwitch }) {
            assertEquals(setOf(false, true), item.options.map { it.value }.toSet())
        }
    }

    @Test
    fun phoneNotesOnlyShowInTheCar() {
        for (item in all.filterIsInstance<PhoneNote>()) assertEquals(CarOnly, item.surfaces)
    }
}
