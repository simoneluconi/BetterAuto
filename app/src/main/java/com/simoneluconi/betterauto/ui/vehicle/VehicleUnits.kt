package com.simoneluconi.betterauto.ui.vehicle

import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.text.util.LocalePreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.car.Units
import com.simoneluconi.betterauto.settings.LauncherSettings

/**
 * The units chosen in the settings. Auto follows the phone: the temperature unit from Android's regional
 * preferences (or the region's custom), miles where the region uses them (US, UK, Liberia, Myanmar).
 */
@Composable
internal fun rememberUnits(): Units {
    val settings = LauncherSettings.get(LocalContext.current)
    val system by settings.unitSystem.collectAsStateWithLifecycle()
    val temperature by settings.temperatureUnit.collectAsStateWithLifecycle()
    // The regional preferences live in the locale, so a change there recomposes through the configuration
    val locale = LocalConfiguration.current.locales[0]
    return remember(system, temperature, locale) {
        Units.resolve(
            system, temperature,
            phoneImperial = LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) != LocaleData.MeasurementSystem.SI,
            phoneFahrenheit = LocalePreferences.getTemperatureUnit(locale) == LocalePreferences.TemperatureUnit.FAHRENHEIT,
            locale = locale,
        )
    }
}
