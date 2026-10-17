package com.simoneluconi.betterauto.exlap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The client against a scripted Exlap server: what it asks, and what ends up in [ExlapData]. */
class ExlapClientTest {
    private val session = "testsession"
    private val sent = ArrayList<String>()
    private val statuses = ArrayList<ExlapStatus>()

    @Before
    fun clean() = ExlapData.clear()

    @After
    fun cleanAfter() = ExlapData.clear()

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
    }

    @Test
    fun loggedInUserGetsEveryValue() {
        val client = client("user" to "secret")
        loginAs(client)
        client.onText(statement("<Rsp id=\"${idOf("phase=\"response\"")}\"/>"))
        client.onText(statement("<Rsp id=\"${idOf("<Dir")}\"><UrlList><Match url=\"engineSpeed\"/></UrlList></Rsp>"))
        client.onText(statement("<Rsp id=\"${idOf("<Interface")}\"><Object url=\"engineSpeed\"><Absolute name=\"val\" unit=\"rpm\"/></Object></Rsp>"))
        assertTrue(sent.last().contains("<Subscribe url=\"engineSpeed\""))
        client.onText(statement("<Dat url=\"engineSpeed\"><Abs name=\"val\" val=\"2500\"/></Dat>"))

        val value = ExlapData.values.value.values.single()
        assertEquals("2500", value.value)
        assertEquals("rpm", value.unit)
        assertEquals(ExlapStatus(ExlapState.LOGGED_IN, 1), statuses.last())
    }

    @Test
    fun objectsOutsideTheUsersRightsAreSkipped() {
        val client = client("limited" to "secret")
        loginAs(client)
        client.onText(statement("<Rsp id=\"${idOf("phase=\"response\"")}\"/>"))
        client.onText(statement("<Rsp id=\"${idOf("<Dir")}\"><UrlList><Match url=\"engineSpeed\"/><Match url=\"secretThing\"/></UrlList></Rsp>"))
        // Answered out of order, one of them refused: the session must not get stuck
        client.onText(statement("<Rsp id=\"${idOf("url=\"secretThing\"")}\" status=\"noMatchingUrl\"/>"))
        client.onText(statement("<Rsp id=\"${idOf("url=\"engineSpeed\"")}\"><Object url=\"engineSpeed\"><Absolute name=\"val\"/></Object></Rsp>"))

        assertTrue(sent.any { it.contains("<Subscribe url=\"engineSpeed\"") })
        assertTrue(sent.none { it.contains("<Subscribe url=\"secretThing\"") })
        assertEquals(ExlapStatus(ExlapState.LOGGED_IN, 1), statuses.last())
    }

    @Test
    fun aUserWhoCanReadNothingEndsWithNoValues() {
        val client = client("nobody" to "secret")
        loginAs(client)
        client.onText(statement("<Rsp id=\"${idOf("phase=\"response\"")}\"/>"))
        client.onText(statement("<Rsp id=\"${idOf("<Dir")}\"><UrlList/></Rsp>"))

        assertEquals(ExlapStatus(ExlapState.LOGGED_IN, 0), statuses.last())
        assertTrue(ExlapData.values.value.isEmpty())
    }

    @Test
    fun aRefusedLoginSaysSoAndCloses() {
        val client = client("user" to "wrong")
        loginAs(client)
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
