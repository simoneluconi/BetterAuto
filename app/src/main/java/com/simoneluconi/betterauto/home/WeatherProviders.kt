package com.simoneluconi.betterauto.home

import java.util.Locale

/** An app widget some installed app offers, as far as picking a weather one goes; sizes in px, 0 if unknown. */
data class WidgetCandidate(
    val packageName: String,
    val className: String,
    val label: String,
    val minWidth: Int = 0,
    val minHeight: Int = 0,
) {
    /** The same text as ComponentName.flattenToString, so a choice can be saved. */
    val key: String get() = "$packageName/$className"
}

/**
 * Which installed app widgets show the weather, best first. There is no category for it, so it goes by
 * names: weather apps whose widgets are all weather, then any widget whose class, label or package says
 * weather (labels come in the phone's language, class names are nearly always English). Pure, for tests.
 */
object WeatherProviders {
    /** Weather apps whose package name doesn't say so. */
    private val WeatherPackages = setOf(
        "com.sec.android.daemonapp", // Samsung Weather
    )

    /** "Weather" in the languages a widget label is likely to be in. */
    private val Words = listOf(
        "weather", "forecast", "meteo", "météo", "wetter", "tiempo", "clima", "weer", "pogoda", "väder", "vejr", "vær",
    )

    fun rank(candidates: List<WidgetCandidate>, ownPackage: String? = null): List<WidgetCandidate> =
        candidates
            .filter { it.packageName != ownPackage }
            .map { it to score(it) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<WidgetCandidate, Int>> { it.second }.thenBy { it.first.label.lowercase(Locale.ROOT) }.thenBy { it.first.key })
            .map { it.first }
            .distinctBy { it.key }

    /**
     * One widget per app, apps in [rank] order: weather apps offer many (13 sizes of the same one), the driver
     * picks an app, not a widget. Of an app's widgets, the one shaped most like the car's wide cards (about 2:1).
     */
    fun bestPerApp(ranked: List<WidgetCandidate>): List<WidgetCandidate> =
        ranked.groupBy { it.packageName }.values.map { widgets ->
            widgets.withIndex().minWith(compareBy<IndexedValue<WidgetCandidate>> { shapeDistance(it.value) }.thenBy { it.index }).value
        }

    private fun shapeDistance(candidate: WidgetCandidate): Double {
        if (candidate.minWidth <= 0 || candidate.minHeight <= 0) return Double.MAX_VALUE
        return kotlin.math.abs(kotlin.math.ln(candidate.minWidth.toDouble() / candidate.minHeight / CARD_ASPECT))
    }

    private const val CARD_ASPECT = 2.0

    /** The saved choice while it is still installed, otherwise the best one; null when there is none. */
    fun pick(ranked: List<WidgetCandidate>, chosen: String?): WidgetCandidate? =
        ranked.firstOrNull { it.key == chosen } ?: ranked.firstOrNull()

    private fun score(candidate: WidgetCandidate): Int {
        var score = 0
        if (candidate.packageName in WeatherPackages) score += 4
        if (says(candidate.className)) score += 2
        if (says(candidate.label)) score += 2
        if (says(candidate.packageName)) score += 2
        return score
    }

    private fun says(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return Words.any { it in lower }
    }
}
