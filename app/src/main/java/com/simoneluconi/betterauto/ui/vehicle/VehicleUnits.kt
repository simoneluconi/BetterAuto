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
import java.util.Locale
import com.simoneluconi.betterauto.car.Units
import com.simoneluconi.betterauto.phone.PhoneCountry
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.LocalFormatLocale

/**
 * The units chosen in the settings. Auto follows where the phone is used, its SIM's country, not its
 * language (English US in Italy still means km and °C): miles where that country uses them (US, UK, Liberia,
 * Myanmar), the temperature unit from Android's regional preferences if the driver set one, else the country's.
 */
@Composable
internal fun rememberUnits(): Units {
    val settings = LauncherSettings.get(LocalContext.current)
    val system by settings.unitSystem.collectAsStateWithLifecycle()
    val temperature by settings.temperatureUnit.collectAsStateWithLifecycle()
    // The regional preferences live in the locale, so a change there recomposes through the configuration
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val country = remember(locale) { PhoneCountry.of(context) }
    // Numbers are written as the chosen formats want (decimal comma or point)
    val formats = LocalFormatLocale.current
    return remember(system, temperature, locale, country, formats) {
        val where = Locale.Builder().setRegion(country).build()
        // An explicit regional preference ("mu" in the locale) wins over the country's custom
        val explicit = locale.getUnicodeLocaleType("mu") != null
        Units.resolve(
            system, temperature,
            phoneImperial = LocaleData.getMeasurementSystem(ULocale.forLocale(where)) != LocaleData.MeasurementSystem.SI,
            phoneFahrenheit = LocalePreferences.getTemperatureUnit(if (explicit) locale else where) ==
                LocalePreferences.TemperatureUnit.FAHRENHEIT,
            locale = formats,
        )
    }
}
