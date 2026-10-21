package com.simoneluconi.betterauto.car

import java.util.Locale

/** Distances, speeds and pressures: kilometres and bar, or miles and psi. */
enum class UnitSystem { AUTO, METRIC, IMPERIAL }

enum class TemperatureUnit { AUTO, CELSIUS, FAHRENHEIT }

/**
 * How car values are shown. The car reports metric (°C, km, km/h, bar, kPa, m) and [VehicleData] keeps
 * them so: they are converted only here, when they become text.
 */
data class Units(val imperial: Boolean, val fahrenheit: Boolean, val locale: Locale = Locale.getDefault()) {

    fun temperature(celsius: Double, decimals: Int = 1): String =
        if (fahrenheit) "%.${decimals}f °F".format(locale, celsius * 9 / 5 + 32)
        else "%.${decimals}f °C".format(locale, celsius)

    fun distance(km: Double, decimals: Int = 0): String =
        if (imperial) "%,.${decimals}f mi".format(locale, km / KM_PER_MILE)
        else "%,.${decimals}f km".format(locale, km)

    fun speed(kmh: Double): String =
        if (imperial) "%.0f mph".format(locale, kmh / KM_PER_MILE) else "%.0f km/h".format(locale, kmh)

    /** Tire pressure without its unit, for the car diagram where the unit sits on its own line. */
    fun tirePressureValue(bar: Double): String =
        if (imperial) "%.0f".format(locale, bar * PSI_PER_BAR) else "%.1f".format(locale, bar)

    val tirePressureUnit: String get() = if (imperial) "psi" else "bar"

    fun tirePressure(bar: Double): String = "${tirePressureValue(bar)} $tirePressureUnit"

    /** Altitude and GPS accuracy. */
    fun length(m: Double): String =
        if (imperial) "%.0f ft".format(locale, m / M_PER_FOOT) else "%.0f m".format(locale, m)

    /** Air pressure outside: hPa, or inches of mercury. */
    fun airPressure(kpa: Double): String =
        if (imperial) "%.2f inHg".format(locale, kpa / KPA_PER_INHG) else "%.0f hPa".format(locale, kpa * 10)

    /** Fuel used: l/100 km, or miles per gallon (the UK's own gallon there). */
    fun consumption(litresPer100km: Double): String = when {
        !imperial -> "%.1f l/100 km".format(locale, litresPer100km)
        litresPer100km <= 0 -> "– mpg"
        else -> "%.1f mpg".format(locale, (if (locale.country == "GB") MPG_UK_TIMES_L100 else MPG_US_TIMES_L100) / litresPer100km)
    }

    fun voltage(volts: Double): String = "%.1f V".format(locale, volts)

    companion object {
        private const val KM_PER_MILE = 1.609344
        private const val PSI_PER_BAR = 14.5037738
        private const val M_PER_FOOT = 0.3048
        private const val KPA_PER_INHG = 3.386389
        private const val MPG_US_TIMES_L100 = 235.214583
        private const val MPG_UK_TIMES_L100 = 282.480936

        /** The driver's choice; for Auto, what the phone uses ([phoneImperial], [phoneFahrenheit]). */
        fun resolve(
            system: UnitSystem,
            temperature: TemperatureUnit,
            phoneImperial: Boolean,
            phoneFahrenheit: Boolean,
            locale: Locale = Locale.getDefault(),
        ) = Units(
            imperial = when (system) {
                UnitSystem.AUTO -> phoneImperial
                UnitSystem.METRIC -> false
                UnitSystem.IMPERIAL -> true
            },
            fahrenheit = when (temperature) {
                TemperatureUnit.AUTO -> phoneFahrenheit
                TemperatureUnit.CELSIUS -> false
                TemperatureUnit.FAHRENHEIT -> true
            },
            locale = locale,
        )
    }
}
