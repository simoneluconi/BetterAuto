package com.simoneluconi.betterauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.phone.PhoneCountry
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.FormatRegion
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.VoiceLanguage
import java.util.Locale

/*
 * Phone-only: the voice's language (under Voice) and how dates and numbers are written in the car
 * (under Vehicle and units). The interface follows the phone like any app; these two default to Auto,
 * which picks the phone's language of the SIM's country, and Auto says what it picked.
 */

private fun language(locale: Locale) = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }

/** What the voice uses now, for the settings home and the Auto chip. */
@Composable
internal fun voiceLanguageSummary(): String {
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    val voice by settings.voiceLanguage.collectAsStateWithLifecycle()
    val ui = LocalConfiguration.current.locales[0]
    val picked = remember(ui, voice) { AppLocales.voice(voice, AppLocales.phoneLocales(), PhoneCountry.of(context)) }
    return if (voice == VoiceLanguage.AUTO) "${stringResource(R.string.option_auto)} · ${language(picked)}" else language(picked)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceLanguageSettings() {
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    val voice by settings.voiceLanguage.collectAsStateWithLifecycle()
    val ui = LocalConfiguration.current.locales[0]
    val phone = remember(ui) { AppLocales.phoneLocales() }
    val country = remember(ui) { PhoneCountry.of(context) }
    val auto = stringResource(R.string.option_auto)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val autoVoice = AppLocales.voice(VoiceLanguage.AUTO, phone, country)
        Chip("$auto · ${language(autoVoice)}", selected = voice == VoiceLanguage.AUTO) { settings.setVoiceLanguage(VoiceLanguage.AUTO) }
        Chip(language(Locale.ITALIAN), selected = voice == VoiceLanguage.ITALIAN) { settings.setVoiceLanguage(VoiceLanguage.ITALIAN) }
        Chip(language(Locale.ENGLISH), selected = voice == VoiceLanguage.ENGLISH) { settings.setVoiceLanguage(VoiceLanguage.ENGLISH) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatSettings() {
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    val formats by settings.formatRegion.collectAsStateWithLifecycle()
    val ui = LocalConfiguration.current.locales[0]
    val phone = remember(ui) { AppLocales.phoneLocales() }
    val country = remember(ui) { PhoneCountry.of(context) }
    val auto = stringResource(R.string.option_auto)
    fun region(locale: Locale) = locale.getDisplayCountry(ui)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val autoFormats = AppLocales.formats(FormatRegion.AUTO, phone, country)
        Chip("$auto · ${region(autoFormats).ifEmpty { language(autoFormats) }}", selected = formats == FormatRegion.AUTO) {
            settings.setFormatRegion(FormatRegion.AUTO)
        }
        Chip(region(Locale.ITALY), selected = formats == FormatRegion.ITALY) { settings.setFormatRegion(FormatRegion.ITALY) }
        Chip(region(Locale.US), selected = formats == FormatRegion.US) { settings.setFormatRegion(FormatRegion.US) }
        Chip(region(Locale.UK), selected = formats == FormatRegion.UK) { settings.setFormatRegion(FormatRegion.UK) }
    }
}
