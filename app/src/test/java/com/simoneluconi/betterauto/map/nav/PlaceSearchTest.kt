package com.simoneluconi.betterauto.map.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a share from another app points to, and Photon's answers. */
class PlaceSearchTest {
    @Test
    fun coordinatesInMapLinks() {
        // A place's own pin wins over the view's centre
        assertEquals(
            43.3184 to 11.3316,
            PlaceSearch.coordinatesIn("https://www.google.com/maps/place/Piazza+del+Campo/@43.318,11.33,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d43.3184!4d11.3316"),
        )
        assertEquals(43.318 to 11.33, PlaceSearch.coordinatesIn("https://www.google.com/maps/@43.318,11.33,17z"))
        assertEquals(45.4642 to 9.19, PlaceSearch.coordinatesIn("geo:45.4642,9.19?z=15"))
        assertEquals(45.0 to 9.5, PlaceSearch.coordinatesIn("google.navigation:q=45.0,9.5"))
        assertEquals(41.9028 to 12.4964, PlaceSearch.coordinatesIn("Ci vediamo qui: 41.9028, 12.4964"))
        // geo:0,0 with a query has no coordinates of its own
        assertNull(PlaceSearch.coordinatesIn("geo:0,0?q=Piazza%20del%20Campo"))
        assertNull(PlaceSearch.coordinatesIn("Via Roma 12, Prato"))
    }

    @Test
    fun namesAndQueriesInLinks() {
        assertEquals("Piazza del Campo", PlaceSearch.placeNameIn("https://www.google.com/maps/place/Piazza+del+Campo/@43.3,11.3,17z"))
        assertEquals("Piazza del Campo, Siena", PlaceSearch.queryIn("geo:0,0?q=Piazza%20del%20Campo%2C%20Siena"))
        assertNull(PlaceSearch.queryIn("geo:45.0,9.0?q=45.0,9.0"))
    }

    @Test
    fun contactCardAddress() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Mario Rossi\r\nADR;TYPE=HOME:;;Via Roma 12;Prato;;59100;Italia\r\nEND:VCARD"
        assertEquals("Mario Rossi" to "Via Roma 12, Prato, 59100, Italia", PlaceSearch.vCardAddress(card))
    }

    @Test
    fun photonAnswer() {
        val json = """{"features":[
            {"geometry":{"coordinates":[11.1782,43.8063]},"properties":{"name":"Roadhouse","street":"Via del Cantone","city":"Sesto Fiorentino"}},
            {"geometry":{"coordinates":[11.25,43.77]},"properties":{"street":"Via Roma","housenumber":"12","city":"Firenze"}}
        ]}"""
        assertEquals(
            listOf(
                Destination("Roadhouse", "Via del Cantone, Sesto Fiorentino", 43.8063, 11.1782),
                Destination("Via Roma 12", "Firenze", 43.77, 11.25),
            ),
            PlaceSearch.parsePhoton(json),
        )
    }

    @Test
    fun recentsSurviveTheirOwnFormat() {
        val list = listOf(Destination("Roadhouse", "Via del Cantone", 43.8, 11.1), Destination("Casa", null, 43.0, 11.0))
        assertEquals(list, Destinations.decode(Destinations.encode(list)))
    }
}
