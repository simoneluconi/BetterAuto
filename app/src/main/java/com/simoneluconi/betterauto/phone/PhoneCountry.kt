package com.simoneluconi.betterauto.phone

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The country the phone is used in, from its SIM (else the network): not the language. A phone set to
 * English (US) in Italy still dials Italian numbers and drives in kilometres.
 */
object PhoneCountry {
    /** Two-letter ISO code, upper case; the language's region when there is no SIM. */
    fun of(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val sim = runCatching { telephony?.simCountryIso }.getOrNull()
        val network = runCatching { telephony?.networkCountryIso }.getOrNull()
        return (sim?.takeIf { it.length == 2 } ?: network?.takeIf { it.length == 2 } ?: Locale.getDefault().country)
            .uppercase(Locale.ROOT)
    }
}
