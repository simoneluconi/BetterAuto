package com.simoneluconi.betterauto.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.format.DateFormat
import com.simoneluconi.betterauto.phone.PhoneCountry
import java.util.Locale

/** The language voice commands are heard and answered in. */
enum class VoiceLanguage { AUTO, ITALIAN, ENGLISH }

/** How dates, times and numbers are written. */
enum class FormatRegion { AUTO, ITALY, US, UK }

/**
 * The languages behind the car screen, apart from the interface (which follows the phone like any app).
 * Auto is smart: among the languages set on the phone, the one of the SIM's country. A phone in English
 * (US) with Italian second and an Italian SIM hears and answers in Italian and writes "mer 21 ott", 24 h.
 */
object AppLocales {
    /** What [com.simoneluconi.betterauto.voice.CommandParser] understands. */
    private val VoiceLanguages = setOf("it", "en")

    fun voice(choice: VoiceLanguage, phone: List<Locale>, country: String): Locale = when (choice) {
        VoiceLanguage.ITALIAN -> Locale.ITALY
        VoiceLanguage.ENGLISH -> phone.firstOrNull { it.language == "en" } ?: Locale.US
        VoiceLanguage.AUTO -> smart(phone, country) { it.language in VoiceLanguages } ?: Locale.US
    }

    fun formats(choice: FormatRegion, phone: List<Locale>, country: String): Locale = when (choice) {
        FormatRegion.ITALY -> Locale.ITALY
        FormatRegion.US -> Locale.US
        FormatRegion.UK -> Locale.UK
        FormatRegion.AUTO -> smart(phone, country) { true } ?: phone.firstOrNull() ?: Locale.getDefault()
    }

    /** The phone's language for the SIM's country, else its first acceptable one. */
    internal fun smart(phone: List<Locale>, country: String, accept: (Locale) -> Boolean): Locale? =
        phone.firstOrNull { it.country.equals(country, ignoreCase = true) && accept(it) } ?: phone.firstOrNull(accept)

    fun phoneLocales(): List<Locale> = LocaleList.getDefault().let { list -> (0 until list.size()).map(list::get) }

    fun voice(context: Context): Locale =
        voice(LauncherSettings.get(context).voiceLanguage.value, phoneLocales(), PhoneCountry.of(context))

    fun formats(context: Context): Locale =
        formats(LauncherSettings.get(context).formatRegion.value, phoneLocales(), PhoneCountry.of(context))

    private var voiceResources: Pair<Locale, Context>? = null

    /** A context whose strings are in the voice language: what the assistant says and shows. */
    fun voiceContext(context: Context): Context {
        val locale = voice(context)
        voiceResources?.takeIf { it.first == locale }?.let { return it.second }
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).also { voiceResources = locale to it }
    }

    /**
     * 24-hour clock for the "Auto" clock setting: the phone's own switch while the formats are the phone's,
     * else the custom of the chosen formats (Italian formats on an English phone: 24 h).
     */
    fun is24Hours(context: Context, formats: Locale): Boolean =
        if (formats == Locale.getDefault()) DateFormat.is24HourFormat(context)
        else DateFormat.getBestDateTimePattern(formats, "jm").contains('H')
}
