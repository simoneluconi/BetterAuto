package com.simoneluconi.betterauto.map.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
