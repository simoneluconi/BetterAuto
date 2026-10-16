package com.simoneluconi.betterauto.car

import android.content.Context
import androidx.annotation.StringRes
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import org.json.JSONObject

/** How urgent a fault is. */
enum class FaultSeverity { STOP, CHECK, MINOR }

data class FaultCode(
    val code: String,
    /** Specific description from the catalog, null for codes it doesn't list. */
    val title: String?,
    @param:StringRes val system: Int,
    /** What part of the system a generic code belongs to; null for manufacturer codes. */
    @param:StringRes val area: Int?,
    val manufacturerSpecific: Boolean,
    val severity: FaultSeverity,
)

/**
 * OBD-II fault codes (SAE J2012): parsing what the car sends and explaining it. The catalog of common
 * generic codes is a raw resource per language (res/raw/dtc.json, res/raw-it/dtc.json); codes it
 * doesn't list still get their system and area from the code structure.
 */
object FaultCodes {
    private val Format = Regex("[PCBU][0-9A-F]{4}")
    private var catalog: Map<String, Pair<String, FaultSeverity>>? = null
    private var catalogLocale: String? = null

    /** The car's raw diagnostics payload: either text ("P0171 P0420") or 2-byte J2012 codes. */
    fun parse(raw: ByteArray): List<String> {
        if (raw.isEmpty()) return emptyList()
        // Text only if it really spells codes: two binary bytes can be printable too ("@5" is C0035)
        val written = Format.findAll(raw.toString(Charsets.US_ASCII).uppercase()).map { it.value }.distinct().toList()
        if (written.isNotEmpty()) return written
        return raw.toList().chunked(2).filter { it.size == 2 }.mapNotNull { (a, b) ->
            val hi = a.toInt() and 0xff
            val lo = b.toInt() and 0xff
            if (hi == 0 && lo == 0) return@mapNotNull null
            val system = "PCBU"[hi shr 6]
            "%c%d%X%02X".format(system, (hi shr 4) and 0x3, hi and 0xf, lo)
        }.distinct()
    }

    fun describe(context: Context, code: String): FaultCode {
        val entry = catalogFor(context)[code]
        val system = when (code.firstOrNull()) {
            'P' -> R.string.dtc_system_powertrain
            'C' -> R.string.dtc_system_chassis
            'B' -> R.string.dtc_system_body
            'U' -> R.string.dtc_system_network
            else -> R.string.dtc_system_unknown
        }
        // Second character: 0 and 2 are generic (same meaning on every car), 1 and 3 are the maker's own
        val manufacturer = code.getOrNull(1) == '1' || code.getOrNull(1) == '3'
        val area = if (code.firstOrNull() == 'P' && !manufacturer) {
            when (code.getOrNull(2)) {
                '0', '1', '2' -> R.string.dtc_area_fuel_air
                '3' -> R.string.dtc_area_ignition
                '4' -> R.string.dtc_area_emissions
                '5' -> R.string.dtc_area_speed_idle
                '6' -> R.string.dtc_area_computer
                '7', '8', '9' -> R.string.dtc_area_transmission
                'A', 'B', 'C' -> R.string.dtc_area_hybrid
                else -> null
            }
        } else {
            null
        }
        return FaultCode(
            code = code,
            title = entry?.first,
            system = system,
            area = area,
            manufacturerSpecific = manufacturer,
            severity = entry?.second ?: FaultSeverity.CHECK,
        )
    }

    private fun catalogFor(context: Context): Map<String, Pair<String, FaultSeverity>> {
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        catalog?.takeIf { catalogLocale == locale }?.let { return it }
        val loaded = runCatching {
            val json = context.resources.openRawResource(R.raw.dtc).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            root.keys().asSequence().associateWith { key ->
                val item = root.getJSONObject(key)
                val severity = FaultSeverity.entries.firstOrNull { it.name.equals(item.optString("severity"), true) }
                    ?: FaultSeverity.CHECK
                item.getString("title") to severity
            }
        }.onFailure { EventLog.log("Fault codes: catalog not loaded: $it") }.getOrDefault(emptyMap())
        catalog = loaded
        catalogLocale = locale
        return loaded
    }
}
