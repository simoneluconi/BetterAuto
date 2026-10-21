package com.simoneluconi.betterauto.exlap

import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleDemand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The client against a scripted Exlap server: what it asks, and what ends up in [ExlapData] and [VehicleData]. */
class ExlapClientTest {
    private val session = "testsession"
    private val sent = ArrayList<String>()
    private val statuses = ArrayList<ExlapStatus>()

    @Before
    fun clean() {
        ExlapData.publishIntervalMs = 0
        ExlapData.clear()
        VehicleData.clear()
    }

    @After
    fun cleanAfter() {
        ExlapData.clear()
        VehicleData.clear()
    }

    private fun client(credentials: Pair<String, String>?) =
        ExlapClient(credentials, { statuses += it }, { sent += it }, sessionId = session).also { it.start() }

    private fun statement(body: String) = "<ExlapStatement session_id=\"$session\">$body</ExlapStatement>"

    /** The id of the last request whose body contains [what]. */
    private fun idOf(what: String): String =
        Regex("""<Req id="(\d+)">""").find(sent.last { it.contains(what) })!!.groupValues[1]

    private fun loginAs(client: ExlapClient) {
        client.onText("<ExlapConnectionReturn session_id=\"$session\" connected=\"true\" />")
        client.onText(statement("<Status><Init/></Status>"))
        client.onText(statement("<Rsp id=\"${idOf("<Protocol")}\"><Capabilities service=\"Test\" version=\"5.0\"/></Rsp>"))
        client.onText(statement("<Rsp id=\"${idOf("phase=\"challenge\"")}\"><Challenge nonce=\"AAAAAAAAAAAAAAAAAAAAAA==\"/></Rsp>"))
        client.onText(statement("<Rsp id=\"${idOf("phase=\"response\"")}\"/>"))
    }

    /** Logged in, objects listed and described, each read once with [values] as the first answer. */
    private fun discovered(client: ExlapClient, values: Map<String, String>) {
        loginAs(client)
        val matches = values.keys.joinToString("") { "<Match url=\"$it\"/>" } + "<Match url=\"Media_Pause\" type=\"function\"/>"
        client.onText(statement("<Rsp id=\"${idOf("<Dir")}\"><UrlList>$matches</UrlList></Rsp>"))
        for (url in values.keys) {
            client.onText(statement("<Rsp id=\"${idOf("<Interface url=\"$url\"")}\"><Object url=\"$url\"><Absolute name=\"$url\" unit=\"°C\"/></Object></Rsp>"))
        }
        for ((url, value) in values) {
            client.onText(statement("<Rsp id=\"${idOf("<Get url=\"$url\"")}\"><ObjectData url=\"$url\"><Abs name=\"$url\" val=\"$value\"/></ObjectData></Rsp>"))
        }
    }

    private fun subscriptions() = sent.filter { it.contains("<Subscribe") }.map { Regex("url=\"([^\"]+)\"").find(it)!!.groupValues[1] }

    @Test
    fun everythingIsReadOnceThenOnlyWhatTheScreensNeedIsFollowed() {
        val client = client("user" to "secret")
        discovered(client, mapOf("outsideTemperature" to "12.5", "totalDistance" to "1000", "engineSpeed" to "800", "Comfort_CarSeatsStatus" to "x"))

        // Functions are the car's actions: never described nor read
        assertTrue(sent.none { it.contains("Media_Pause") && !it.contains("<Dir") })
        assertEquals(ExlapStatus(ExlapState.LOGGED_IN, 4), statuses.last())
        // The first read already gives the vehicle screen its values
        assertEquals(12.5, VehicleData.snapshot.value.environment!!.value.temperatureC!!, 0.01)
        // Nothing on screen: only who is aboard and the outside temperature (clock widget) are followed
        assertEquals(setOf("Comfort_CarSeatsStatus", "outsideTemperature"), subscriptions().toSet())

        client.setDemand(VehicleDemand.OVERVIEW)
        assertEquals(setOf("Comfort_CarSeatsStatus", "outsideTemperature", "totalDistance"), subscriptions().toSet())
        client.setDemand(VehicleDemand.DETAIL)
        assertTrue("engineSpeed" in subscriptions())

        client.setDemand(VehicleDemand.NONE)
        assertTrue(sent.any { it.contains("<Unsubscribe url=\"engineSpeed\"") })
        assertTrue(sent.any { it.contains("<Unsubscribe url=\"totalDistance\"") })
        assertFalse(sent.any { it.contains("<Unsubscribe url=\"Comfort_CarSeatsStatus\"") })
        assertFalse(sent.any { it.contains("<Unsubscribe url=\"outsideTemperature\"") })
    }

    @Test
    fun pushedValuesReachTheVehicleData() {
        val client = client("user" to "secret")
        client.setDemand(VehicleDemand.OVERVIEW)
        discovered(client, mapOf("outsideTemperature" to "12.5"))
        client.onText(statement("<Dat url=\"outsideTemperature\"><Abs name=\"outsideTemperature\" val=\"14\"/></Dat>"))

        assertEquals("14", ExlapData.values.value["outsideTemperature/outsideTemperature"]!!.value)
        assertEquals("°C", ExlapData.values.value["outsideTemperature/outsideTemperature"]!!.unit)
        assertEquals(14.0, VehicleData.snapshot.value.environment!!.value.temperatureC!!, 0.01)
        // Mapped values are not repeated among the raw extras
        assertTrue(VehicleData.snapshot.value.extras.isEmpty())

        client.onText(statement("<Dat url=\"outsideTemperature\"><Abs name=\"outsideTemperature\" state=\"nodata\"/></Dat>"))
        assertEquals(null, VehicleData.snapshot.value.environment)
    }

    @Test
    fun listsAndObjectsInsideAnObjectAreFlattened() {
        val client = client("user" to "secret")
        discovered(client, mapOf("Comfort_CarSeatsStatus" to "x"))
        client.onText(
            statement(
                "<Dat url=\"Comfort_CarSeatsStatus\"><List name=\"seats\">" +
                    "<Elem><Enm name=\"position\" val=\"passenger\"/><Act name=\"occupied\" val=\"true\"/></Elem>" +
                    "</List></Dat>"
            )
        )
        assertEquals("true", ExlapData.values.value["Comfort_CarSeatsStatus/seats[0].occupied"]!!.value)
        assertEquals(true, VehicleData.othersOnBoard.value)
    }

    @Test
    fun objectsOutsideTheUsersRightsAreSkipped() {
        val client = client("limited" to "secret")
        loginAs(client)
        client.onText(statement("<Rsp id=\"${idOf("<Dir")}\"><UrlList><Match url=\"Comfort_CarSeatsStatus\"/><Match url=\"secretThing\"/></UrlList></Rsp>"))
        // Answered out of order, one of them refused: the session must not get stuck
        client.onText(statement("<Rsp id=\"${idOf("url=\"secretThing\"")}\" status=\"noMatchingUrl\"/>"))
        client.onText(statement("<Rsp id=\"${idOf("<Interface url=\"Comfort_CarSeatsStatus\"")}\"><Object url=\"Comfort_CarSeatsStatus\"><Absolute name=\"val\"/></Object></Rsp>"))
        client.onText(statement("<Rsp id=\"${idOf("<Get url=\"Comfort_CarSeatsStatus\"")}\"><ObjectData url=\"Comfort_CarSeatsStatus\"/></Rsp>"))

        assertEquals(listOf("Comfort_CarSeatsStatus"), subscriptions())
        assertTrue(sent.none { it.contains("<Get url=\"secretThing\"") })
        assertEquals(ExlapStatus(ExlapState.LOGGED_IN, 1), statuses.last())
    }

    @Test
    fun aRefusedSubscriptionIsNotAskedAgain() {
        val client = client("user" to "secret")
        client.setDemand(VehicleDemand.OVERVIEW)
        discovered(client, mapOf("outsideTemperature" to "1"))
        client.onText(statement("<Rsp id=\"${idOf("<Subscribe url=\"outsideTemperature\"")}\" status=\"accessViolation\"/>"))
        client.setDemand(VehicleDemand.NONE)
        client.setDemand(VehicleDemand.OVERVIEW)
        assertEquals(1, subscriptions().count { it == "outsideTemperature" })
    }

    @Test
    fun aUserWhoCanReadNothingEndsWithNoValues() {
        val client = client("nobody" to "secret")
        loginAs(client)
        client.onText(statement("<Rsp id=\"${idOf("<Dir")}\"><UrlList/></Rsp>"))

        assertEquals(ExlapStatus(ExlapState.LOGGED_IN, 0), statuses.last())
        assertTrue(ExlapData.values.value.isEmpty())
    }

    @Test
    fun aRefusedLoginSaysSoAndCloses() {
        val client = client("user" to "wrong")
        client.onText("<ExlapConnectionReturn session_id=\"$session\" connected=\"true\" />")
        client.onText(statement("<Status><Init/></Status>"))
        client.onText(statement("<Rsp id=\"${idOf("<Protocol")}\"><Capabilities service=\"Test\" version=\"5.0\"/></Rsp>"))
        client.onText(statement("<Rsp id=\"${idOf("phase=\"challenge\"")}\"><Challenge nonce=\"AAAAAAAAAAAAAAAAAAAAAA==\"/></Rsp>"))
        client.onText(statement("<Rsp id=\"${idOf("phase=\"response\"")}\" status=\"authenticationFailed\"/>"))

        assertEquals(ExlapStatus(ExlapState.LOGIN_REFUSED), statuses.last())
        assertTrue(sent.last().contains("<Bye/>"))
        assertTrue(sent.none { it.contains("<Dir") })
    }

    @Test
    fun withoutCredentialsItOnlyAsksWhatTheServerIs() {
        val client = client(null)
        client.onText("<ExlapConnectionReturn session_id=\"$session\" connected=\"true\" />")
        client.onText(statement("<Status><Init/></Status>"))
        client.onText(statement("<Rsp id=\"${idOf("<Protocol")}\"><Capabilities service=\"Test\"/></Rsp>"))

        assertTrue(sent.last().contains("<Bye/>"))
        assertTrue(sent.none { it.contains("Authenticate") })
        assertEquals(ExlapStatus(ExlapState.NO_CREDENTIALS), statuses.first())
    }

    @Test
    fun digestCutsEachFieldAt44Characters() {
        val long = "x".repeat(60)
        assertEquals(ExlapClient.digest("u", long, "n", "c"), ExlapClient.digest("u", long.take(44), "n", "c"))
    }
}
