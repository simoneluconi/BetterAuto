package com.simoneluconi.betterauto.map.nav

import com.simoneluconi.betterauto.settings.RoutePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteServerTest {
    @Test
    fun whatTheDriverTypesBecomesABaseAddress() {
        assertEquals("https://valhalla.example.org", RouteServer.normalize(" valhalla.example.org/ "))
        // The route endpoint pasted whole
        assertEquals("https://valhalla.example.org", RouteServer.normalize("https://valhalla.example.org/route"))
        // Valhalla's own port: a server at home, without TLS
        assertEquals("http://192.0.2.10:8002", RouteServer.normalize("192.0.2.10:8002"))
        assertEquals("https://192.0.2.10:8002", RouteServer.normalize("https://192.0.2.10:8002"))
        // Behind a proxy, under a path
        assertEquals("https://example.org/valhalla", RouteServer.normalize("https://example.org/valhalla/route/"))
        assertEquals("https://example.org", RouteServer.normalize("https://example.org:443"))
        assertNull(RouteServer.normalize(""))
        assertNull(RouteServer.normalize("not an address"))
    }

    @Test
    fun theRouteUrl() {
        assertEquals("https://valhalla1.openstreetmap.de/route", RouteServer.routeUrl(null))
        assertEquals("http://192.0.2.10:8002/route", RouteServer.routeUrl("http://192.0.2.10:8002"))
    }

    @Test
    fun valhallasStatusNamesItsVersion() {
        assertEquals("3.5.1", RouteServer.versionOf("""{"version":"3.5.1","tileset_last_modified":1700000000}"""))
        assertNull(RouteServer.versionOf("<html>Not Valhalla</html>"))
        assertNull(RouteServer.versionOf("""{"status":"ok"}"""))
    }

    @Test
    fun theRoutesPreferencesAsValhallaOptions() {
        // Nothing asked: nothing sent, Valhalla's defaults
        assertNull(RouteServer.costingOptions(RoutePreferences()))
        val options = RouteServer.costingOptions(RoutePreferences(shortest = true, avoidTolls = true, avoidBorders = true))!!
        // Not Valhalla's "shortest": it would drop the tolls
        assertFalse(options.has("shortest"))
        assertEquals(1.0, options.getDouble("use_distance"), 0.0)
        assertEquals(0.0, options.getDouble("use_tolls"), 0.0)
        assertTrue(options.getInt("country_crossing_penalty") > 0)
        assertFalse(options.has("use_highways"))
        val all = RouteServer.costingOptions(RoutePreferences(avoidHighways = true, avoidFerries = true, avoidUnpaved = true, avoidCashTolls = true))!!
        assertEquals(0.0, all.getDouble("use_highways"), 0.0)
        assertEquals(0.0, all.getDouble("use_ferry"), 0.0)
        assertTrue(all.getBoolean("exclude_unpaved"))
        assertTrue(all.getBoolean("exclude_cash_only_tolls"))
        assertEquals("no motorways, no ferries, no unpaved, no cash-only tolls", RoutePreferences(avoidHighways = true, avoidFerries = true, avoidUnpaved = true, avoidCashTolls = true).describe())
    }
}
