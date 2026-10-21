package com.simoneluconi.betterauto.smarthome.homeassistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class HaAuthTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var base: String

    @Before
    fun start() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun clientIdAndRedirectComeFromTheProviderId() {
        assertEquals("https://simoneluconi.github.io/BetterAuto/auth/homeassistant/", HaAuth.CLIENT_ID)
        assertEquals("betterauto://auth/homeassistant", HaAuth.REDIRECT_URI)
    }

    @Test
    fun addressesAreNormalised() {
        assertEquals("https://abc.ui.nabu.casa", HaAuth.normalizeUrl(" abc.ui.nabu.casa/ "))
        assertEquals("http://192.168.1.5:8123", HaAuth.normalizeUrl("192.168.1.5:8123"))
        assertEquals("https://ha.example.org", HaAuth.normalizeUrl("https://ha.example.org/lovelace/0?x=1"))
        assertEquals("https://ha.example.org:8443", HaAuth.normalizeUrl("HTTPS://ha.example.org:8443"))
        assertEquals("http://ha.example.org", HaAuth.normalizeUrl("http://ha.example.org:80"))
        assertNull(HaAuth.normalizeUrl(""))
        assertNull(HaAuth.normalizeUrl("not an address"))
    }

    @Test
    fun clipboardTakesAnyWebAddress() {
        assertEquals("https://home.example.org:9000", HaAuth.clipboardUrl("https://home.example.org:9000/dashboard"))
        assertNull(HaAuth.clipboardUrl("home.example.org"))
        assertNull(HaAuth.clipboardUrl("https://a.org\nsecond line"))
        assertNull(HaAuth.clipboardUrl(null))
    }

    @Test
    fun homeOnlyAddressesAreRecognised() {
        listOf("http://homeassistant.local:8123", "http://192.168.1.10:8123", "http://10.0.0.2", "http://172.20.1.1", "http://homeassistant:8123")
            .forEach { assertTrue(it, HaAuth.isLocal(it)) }
        listOf("https://abc.ui.nabu.casa", "https://ha.example.org", "http://100.101.102.103:8123", "http://203.0.113.7")
            .forEach { assertFalse(it, HaAuth.isLocal(it)) }
    }

    @Test
    fun authorizeUrlCarriesTheOAuthParameters() {
        val url = HaAuth.authorizeUrl("https://ha.example.org", "s t&ate").toHttpUrl()
        assertEquals("/auth/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(HaAuth.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(HaAuth.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("s t&ate", url.queryParameter("state"))
    }

    @Test
    fun tokensAreParsed() {
        val t = HaAuth.parseTokens("""{"access_token":"A","expires_in":1800,"refresh_token":"R","token_type":"Bearer"}""", 1_000L, null)
        assertEquals(HaTokens("A", "R", 1_801_000L), t)
        // A refresh answer has no refresh token: the old one stays
        val r = HaAuth.parseTokens("""{"access_token":"B","expires_in":1800,"token_type":"Bearer"}""", 0L, "R")
        assertEquals("R", r.refresh)
        assertTrue(r.expiresSoon(1_800_000L - 30_000L))
        assertFalse(r.expiresSoon(0L))
        assertFalse(HaTokens.longLived("L").expiresSoon(Long.MAX_VALUE - 1))
    }

    @Test
    fun codeIsExchangedAtTheTokenEndpoint() {
        server.enqueue(MockResponse().setBody("""{"access_token":"A","expires_in":1800,"refresh_token":"R","token_type":"Bearer"}"""))
        val tokens = HaAuth.exchange(client, base, "the-code", nowMs = 0L)
        assertEquals(HaTokens("A", "R", 1_800_000L), tokens)
        val request = server.takeRequest()
        assertEquals("/auth/token", request.path)
        val form = form(request.body.readUtf8())
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals(HaAuth.CLIENT_ID, form["client_id"])
    }

    @Test
    fun refreshKeepsTheRefreshToken() {
        server.enqueue(MockResponse().setBody("""{"access_token":"B","expires_in":1800,"token_type":"Bearer"}"""))
        val tokens = HaAuth.refresh(client, base, "R", nowMs = 0L)
        assertEquals(HaTokens("B", "R", 1_800_000L), tokens)
        val form = form(server.takeRequest().body.readUtf8())
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("R", form["refresh_token"])
    }

    @Test
    fun aRefusalIsNotANetworkError() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        try {
            HaAuth.refresh(client, base, "revoked")
            fail("refused")
        } catch (e: HaAuthRefused) {
            assertEquals("invalid_grant", e.message)
        }
    }

    @Test
    fun homeAssistantIsRecognisedByItsManifest() {
        server.enqueue(MockResponse().setBody("""{"name":"Home Assistant","short_name":"Assistant"}"""))
        assertTrue(HaAuth.isHomeAssistant(client, base))
        assertEquals("/manifest.json", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"name":"Some router"}"""))
        assertFalse(HaAuth.isHomeAssistant(client, base))
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(HaAuth.isHomeAssistant(client, base))
    }

    @Test
    fun webSocketUrlFollowsTheScheme() {
        assertEquals("wss://ha.example.org/api/websocket", HaAuth.webSocketUrl("https://ha.example.org"))
        assertEquals("ws://10.0.0.2:8123/api/websocket", HaAuth.webSocketUrl("http://10.0.0.2:8123"))
    }

    @Test
    fun credentialsSurviveSaving() {
        val c = HaCredentials("https://ha.example.org", HaTokens("A", null, Long.MAX_VALUE), "Casa")
        assertEquals(c, HaCredentials.fromJson(c.toJson()))
        val d = HaCredentials("https://ha.example.org", HaTokens("A", "R", 5L))
        assertEquals(d, HaCredentials.fromJson(d.toJson()))
    }

    private fun form(body: String): Map<String, String> = body.split('&').associate { part ->
        val (k, v) = part.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}
