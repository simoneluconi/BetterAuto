package com.simoneluconi.betterauto.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherProvidersTest {
    private val samsung = WidgetCandidate("com.sec.android.daemonapp", "com.sec.android.daemonapp.appwidget.WeatherAppWidget", "Meteo")
    private val google = WidgetCandidate("com.google.android.googlequicksearchbox", "com.google.android.apps.search.weather.WeatherWidgetProvider", "Meteo")
    private val clock = WidgetCandidate("com.google.android.deskclock", "com.android.alarmclock.DigitalAppWidgetProvider", "Orologio")
    private val search = WidgetCandidate("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.SearchWidgetProvider", "Ricerca")
    private val other = WidgetCandidate("org.example.forecast", "org.example.forecast.Small", "Previsioni")
    private val ours = WidgetCandidate("com.simoneluconi.betterauto", "com.simoneluconi.betterauto.WeatherThing", "Weather")

    @Test
    fun onlyWeatherWidgetsBestFirst() {
        val ranked = WeatherProviders.rank(listOf(clock, other, search, google, samsung, ours), ownPackage = "com.simoneluconi.betterauto")
        assertEquals(listOf(samsung, google, other), ranked)
    }

    @Test
    fun theDriversChoiceWinsWhileInstalled() {
        val ranked = listOf(samsung, google)
        assertEquals(google, WeatherProviders.pick(ranked, google.key))
        assertEquals(samsung, WeatherProviders.pick(ranked, "uninstalled/Widget"))
        assertEquals(samsung, WeatherProviders.pick(ranked, null))
        assertNull(WeatherProviders.pick(emptyList(), null))
    }

    @Test
    fun oneWidgetPerAppTheWidestCardShape() {
        val small = WidgetCandidate("it.meteo", "it.meteo.Small", "Meteo", minWidth = 100, minHeight = 100)
        val wide = WidgetCandidate("it.meteo", "it.meteo.Wide", "Meteo", minWidth = 400, minHeight = 200)
        val tall = WidgetCandidate("it.meteo", "it.meteo.Tall", "Meteo", minWidth = 200, minHeight = 400)
        val ranked = listOf(samsung, small, tall, wide, google)
        assertEquals(listOf(samsung, wide, google), WeatherProviders.bestPerApp(ranked))
    }
}
