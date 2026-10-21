package com.simoneluconi.betterauto.smarthome.homeassistant

import com.simoneluconi.betterauto.smarthome.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The WebSocket client against a scripted Home Assistant (auth, subscribe_entities, events, commands). */
class HaSocketTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var url: String

    @Before
    fun start() {
        server.start()
        url = server.url("/").toString().trimEnd('/')
    }

    private val fakes = CopyOnWriteArrayList<FakeHa>()

    @After
    fun stop() {
        scope.cancel()
        fakes.forEach { runCatching { it.socket?.close(1001, null) } }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    /** Accepts [token], answers commands, sends the subscribed entities' states. */
    private class FakeHa(private val token: String = "good") : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        @Volatile var subscription = -1

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            received += msg
            val id = msg.optInt("id")
            when (msg.optString("type")) {
                "auth" -> webSocket.send(
                    if (msg.optString("access_token") == token) """{"type":"auth_ok","ha_version":"2026.10.0"}"""
                    else """{"type":"auth_invalid","message":"Invalid access token or password"}"""
                )
                "subscribe_entities" -> {
                    subscription = id
                    webSocket.send(result(id, null))
                    val added = JSONObject()
                    val ids = msg.getJSONArray("entity_ids")
                    for (i in 0 until ids.length()) added.put(ids.getString(i), JSONObject().put("s", "closed").put("a", JSONObject().put("friendly_name", "N$i")))
                    webSocket.send(JSONObject().put("id", id).put("type", "event").put("event", JSONObject().put("a", added)).toString())
                }
                "get_config" -> webSocket.send(result(id, JSONObject().put("location_name", "Casa")))
                "call_service" -> webSocket.send(
                    if (msg.optString("domain") == "cover") result(id, JSONObject().put("context", JSONObject()))
                    else """{"id":$id,"type":"result","success":false,"error":{"code":"not_found","message":"Service not found."}}"""
                )
                else -> webSocket.send(result(id, null))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun event(event: String) {
            socket!!.send("""{"id":$subscription,"type":"event","event":$event}""")
        }

        private fun result(id: Int, value: Any?) = JSONObject().put("id", id).put("type", "result").put("success", true).put("result", value ?: JSONObject.NULL).toString()
    }

    private fun serve(fake: FakeHa) {
        fakes += fake
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
    }

    private fun socket(endpoint: suspend (Boolean) -> HaEndpoint?) = HaSocket(client, scope, endpoint, backoffMs = { 50L })

    @Test
    fun authenticatesSubscribesAndFollowsChanges() = runBlocking {
        val fake = FakeHa()
        serve(fake)
        val ha = socket { HaEndpoint(url, "good") }
        ha.start(setOf("cover.gate", "light.porch"))
        assertTrue(ha.awaitOnline(5000))
        withTimeout(5000) { ha.entities.first { it.size == 2 } }
        val subscribe = fake.received.first { it.optString("type") == "subscribe_entities" }
        assertEquals(listOf("cover.gate", "light.porch"), subscribe.getJSONArray("entity_ids").let { a -> (0 until a.length()).map { a.getString(it) } })

        fake.event("""{"c":{"cover.gate":{"+":{"s":"opening","a":{"current_position":30}}}}}""")
        val changed = withTimeout(5000) { ha.entities.first { it["cover.gate"]?.state == "opening" } }.getValue("cover.gate")
        assertEquals(30, changed.attributes.getInt("current_position"))
        // Attributes not in the change stay
        assertEquals("N0", changed.attributes.getString("friendly_name"))

        fake.event("""{"c":{"cover.gate":{"-":{"a":["current_position"]}}}}""")
        withTimeout(5000) { ha.entities.first { it["cover.gate"]?.attributes?.has("current_position") == false } }
        fake.event("""{"r":["light.porch"]}""")
        withTimeout(5000) { ha.entities.first { it.size == 1 } }

        val config = ha.command("get_config") as JSONObject
        assertEquals("Casa", config.getString("location_name"))
        ha.stop()
        assertEquals(LinkState.IDLE, ha.link.value)
    }

    @Test
    fun callServiceSendsTheTargetAndReportsErrors() = runBlocking {
        val fake = FakeHa()
        serve(fake)
        val ha = socket { HaEndpoint(url, "good") }
        ha.start(emptySet())
        assertTrue(ha.awaitOnline(5000))
        ha.command("call_service") {
            put("domain", "cover")
            put("service", "open_cover")
            put("target", JSONObject().put("entity_id", "cover.gate"))
        }
        val sent = fake.received.first { it.optString("type") == "call_service" }
        assertEquals("open_cover", sent.getString("service"))
        assertEquals("cover.gate", sent.getJSONObject("target").getString("entity_id"))
        // Nothing subscribed when following nothing
        assertTrue(fake.received.none { it.optString("type") == "subscribe_entities" })
        try {
            ha.command("call_service") { put("domain", "light"); put("service", "nope") }
            fail("error expected")
        } catch (e: HaCommandFailed) {
            assertEquals("not_found", e.code)
        }
        ha.stop()
    }

    @Test
    fun aRefusedTokenIsRefreshedOnceThenWorks() = runBlocking {
        serve(FakeHa(token = "fresh"))
        val second = FakeHa(token = "fresh")
        serve(second)
        val asked = CopyOnWriteArrayList<Boolean>()
        val ha = socket { refused ->
            asked += refused
            HaEndpoint(url, if (refused) "fresh" else "expired")
        }
        ha.start(setOf("lock.front"))
        assertTrue(ha.awaitOnline(5000))
        assertEquals(listOf(false, true), asked.toList())
        withTimeout(5000) { ha.entities.first { it.containsKey("lock.front") } }
        ha.stop()
    }

    @Test
    fun aTokenRefusedTwiceStopsWithAuthFailed() = runBlocking {
        serve(FakeHa(token = "other"))
        serve(FakeHa(token = "other"))
        val ha = socket { HaEndpoint(url, "wrong") }
        ha.start(emptySet())
        assertEquals(false, ha.awaitOnline(5000))
        assertEquals(LinkState.AUTH_FAILED, ha.link.value)
        assertEquals(false, ha.running)
    }

    @Test
    fun reconnectsAndSubscribesAgainAfterTheServerCloses() = runBlocking {
        val first = FakeHa()
        val second = FakeHa()
        serve(first)
        serve(second)
        val ha = socket { HaEndpoint(url, "good") }
        ha.start(setOf("cover.gate"))
        assertTrue(ha.awaitOnline(5000))
        first.socket!!.close(1001, "restart")
        withTimeout(5000) { ha.link.first { it != LinkState.ONLINE } }
        assertTrue(ha.awaitOnline(5000))
        withTimeout(5000) {
            while (second.received.none { it.optString("type") == "subscribe_entities" }) kotlinx.coroutines.delay(20)
        }
        // Following other devices changes the subscription on the same connection
        ha.start(setOf("light.porch"))
        withTimeout(5000) {
            while (second.received.none { it.optString("type") == "unsubscribe_events" }) kotlinx.coroutines.delay(20)
        }
        val last = second.received.last { it.optString("type") == "subscribe_entities" }
        assertEquals(JSONArray(listOf("light.porch")).toString(), last.getJSONArray("entity_ids").toString())
        ha.stop()
    }

    @Test
    fun notLinkedMeansNoConnection() = runBlocking {
        val ha = socket { null }
        ha.start(setOf("x.y"))
        withTimeout(5000) { while (ha.running) kotlinx.coroutines.delay(10) }
        assertEquals(LinkState.IDLE, ha.link.value)
        assertEquals(0, server.requestCount)
    }
}
