package com.simoneluconi.betterauto

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import com.simoneluconi.betterauto.exlap.ExlapPhoneSettings
import com.simoneluconi.betterauto.exlap.exlapSummary
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.smarthome.SmartHomePhoneSettings
import com.simoneluconi.betterauto.ui.smarthome.smartHomeSummary

/**
 * A phone-only part of the settings (it needs the phone in hand: a sign-in, a system dialog, typing):
 * it plugs into a [category] of the phone's settings like a catalog entry, with its own [Content].
 * Add one to [PhoneSettingsSections.all]; if the car should mention it, add a `PhoneNote` to the catalog.
 */
interface PhoneSettingsSection {
    /** Stable name. */
    val key: String
    val category: SettingsCategory

    @get:StringRes
    val title: Int

    /** What it does, in a short plain line (and what Auto means): shown under the title, like every setting. */
    @get:StringRes
    val description: Int

    /** Rarely changed: under the category's "More options". */
    val more: Boolean

    /** The options of this home widget: listed under the home screen's widget options. */
    val widget: WidgetType?

    /** Its value in a few words, for the settings home; null to say nothing. */
    @Composable
    fun summary(): String?

    @Composable
    fun Content()
}

object PhoneSettingsSections {
    val all: List<PhoneSettingsSection> = listOf(
        Section("weather", SettingsCategory.HOME_SCREEN, R.string.weather_title, R.string.settings_weather_desc, widget = WidgetType.WEATHER,
            summary = { weatherSummary() }) { WeatherPhoneSettings() },
        Section("voice_language", SettingsCategory.VOICE, R.string.language_voice, R.string.settings_voice_language_desc,
            summary = { voiceLanguageSummary() }) { VoiceLanguageSettings() },
        Section("formats", SettingsCategory.VEHICLE, R.string.language_formats, R.string.settings_formats_desc) { FormatSettings() },
        // VW group cars only
        Section("exlap", SettingsCategory.VEHICLE, R.string.exlap_title, R.string.settings_exlap_desc, more = true,
            summary = { exlapSummary() }) { ExlapPhoneSettings() },
        Section("smart_home", SettingsCategory.SMART_HOME, R.string.smarthome_title, R.string.settings_smart_home_desc,
            summary = { smartHomeSummary() }) { SmartHomePhoneSettings() },
    )

    private class Section(
        override val key: String,
        override val category: SettingsCategory,
        @param:StringRes override val title: Int,
        @param:StringRes override val description: Int,
        override val more: Boolean = false,
        override val widget: WidgetType? = null,
        private val summary: @Composable () -> String? = { null },
        private val content: @Composable () -> Unit,
    ) : PhoneSettingsSection {
        @Composable
        override fun summary(): String? = summary.invoke()

        @Composable
        override fun Content() = content()
    }
}
