package com.simoneluconi.betterauto.smarthome.homeassistant

import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.smarthome.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** One entity's state as Home Assistant sends it. */
data class HaEntityState(val state: String, val attributes: JSONObject)

/** Where to connect and with which access token, fresh (refreshed by the provider when needed). */
data class HaEndpoint(val url: String, val accessToken: String)

/** Home Assistant answered a command with an error. */
class HaCommandFailed(val code: String, message: String) : IOException("$code: $message")

/**
 * Home Assistant's WebSocket API (https://developers.home-assistant.io/docs/api/websocket): auth,
 * commands answered by id, and `subscribe_entities` for the followed entities only. Stays connected
 * while [start]ed, reconnecting with a growing pause; [stop] closes it. Tokens are never logged.
 *
 * [endpoint] gives the URL and a valid access token (true = the last one was refused, refresh it), or
 * null when not linked.
 */
class HaSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val endpoint: suspend (refused: Boolean) -> HaEndpoint?,
    private val backoffMs: (attempt: Int) -> Long = { a -> (2000L shl (a - 1).coerceIn(0, 5)).coerceAtMost(60_000L) },
) {
    private val _link = MutableStateFlow(LinkState.IDLE)
    val link: StateFlow<LinkState> = _link

    private val _entities = MutableStateFlow<Map<String, HaEntityState>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntityState>> = _entities

    private val lock = Any()
    private val ids = AtomicInteger(0)
    private val pending = HashMap<Int, CompletableDeferred<JSONObject>>()
    private var socket: WebSocket? = null
    private var authed = false
    private var job: Job? = null
    private var wanted: Set<String> = emptySet()
    private var subscription: Int? = null
    private var subscribedTo: Set<String> = emptySet()

    val running: Boolean get() = synchronized(lock) { job != null }

    /** Keeps connected and follows [entityIds] (empty: connected for commands only). */
    fun start(entityIds: Set<String>) {
        synchronized(lock) {
            wanted = entityIds
            if (job == null) {
                job = scope.launch { loop() }
                return
            }
            if (authed && entityIds != subscribedTo) resubscribe()
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            socket?.close(NORMAL_CLOSE, null)
            socket = null
            authed = false
            subscription = null
            subscribedTo = emptySet()
            failPending(IOException("closed"))
        }
        if (_link.value != LinkState.AUTH_FAILED) _link.value = LinkState.IDLE
    }

    /** Waits up to [timeoutMs] for the connection to be ready for commands. */
    suspend fun awaitOnline(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { _link.first { it == LinkState.ONLINE || it == LinkState.AUTH_FAILED } == LinkState.ONLINE } ?: false

    /**
     * Sends a command ({"type": [type], ...[fields]}) and returns its "result" (JSONObject, JSONArray
     * or null as Home Assistant gives it). Throws when offline, on timeout or on an error answer.
     */
    suspend fun command(type: String, timeoutMs: Long = COMMAND_TIMEOUT_MS, fields: JSONObject.() -> Unit = {}): Any? {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JSONObject>()
        synchronized(lock) {
            val ws = socket?.takeIf { authed } ?: throw IOException("not connected")
            pending[id] = answer
            ws.send(JSONObject().put("id", id).put("type", type).apply(fields).toString())
        }
        val msg = withTimeoutOrNull(timeoutMs) { answer.await() } ?: run {
            synchronized(lock) { pending.remove(id) }
            throw IOException("$type: no answer")
        }
        if (!msg.optBoolean("success")) {
            val error = msg.optJSONObject("error")
            throw HaCommandFailed(error?.optString("code").orEmpty(), error?.optString("message").orEmpty())
        }
        return if (msg.isNull("result")) null else msg.opt("result")
    }

    private suspend fun loop() {
        var attempt = 0
        var refused = false
        while (true) {
            val target = endpoint(refused)
            if (target == null) {
                _link.value = if (refused) LinkState.AUTH_FAILED else LinkState.IDLE
                synchronized(lock) { job = null }
                return
            }
            _link.value = LinkState.CONNECTING
            val outcome = connectOnce(target)
            when (outcome) {
                Outcome.REFUSED -> {
                    if (refused) {
                        EventLog.log("Home Assistant: the access was refused again, link it again on the phone")
                        _link.value = LinkState.AUTH_FAILED
                        synchronized(lock) { job = null }
                        return
                    }
                    // Maybe just an expired token: once more with a fresh one
                    refused = true
                    continue
                }
                Outcome.WAS_ONLINE -> {
                    attempt = 0
                    refused = false
                }
                Outcome.FAILED -> Unit
            }
            attempt++
            _link.value = LinkState.OFFLINE
            delay(backoffMs(attempt))
        }
    }

    private enum class Outcome { WAS_ONLINE, REFUSED, FAILED }

    /** One connection, until it closes. */
    private suspend fun connectOnce(target: HaEndpoint): Outcome {
        val done = CompletableDeferred<Outcome>()
        var wasOnline = false
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching { if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull() ?: return
                val messages = if (parsed is JSONArray) (0 until parsed.length()).mapNotNull { parsed.optJSONObject(it) } else listOf(parsed as JSONObject)
                for (msg in messages) {
                    when (msg.optString("type")) {
                        "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", target.accessToken).toString())
                        "auth_ok" -> {
                            wasOnline = true
                            synchronized(lock) {
                                if (socket !== webSocket) return
                                authed = true
                                resubscribe()
                            }
                            EventLog.log("Home Assistant: connected (${msg.optString("ha_version")})")
                            _link.value = LinkState.ONLINE
                        }
                        "auth_invalid" -> {
                            EventLog.log("Home Assistant: access refused")
                            done.complete(Outcome.REFUSED)
                            webSocket.close(NORMAL_CLOSE, null)
                        }
                        "result" -> synchronized(lock) { pending.remove(msg.optInt("id")) }?.complete(msg)
                        "event" -> if (msg.optInt("id") == synchronized(lock) { subscription }) onEntities(msg.optJSONObject("event"))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(if (wasOnline) Outcome.WAS_ONLINE else Outcome.FAILED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                EventLog.log("Home Assistant: connection ${if (wasOnline) "lost" else "failed"} (${t.javaClass.simpleName}${response?.let { ", HTTP ${it.code}" } ?: ""})")
                done.complete(if (wasOnline) Outcome.WAS_ONLINE else Outcome.FAILED)
            }
        }
        val request = runCatching { Request.Builder().url(HaAuth.webSocketUrl(target.url)).build() }.getOrNull()
            ?: return Outcome.FAILED.also { EventLog.log("Home Assistant: the address is not valid") }
        synchronized(lock) {
            authed = false
            subscription = null
            subscribedTo = emptySet()
            socket = client.newWebSocket(request, listener)
        }
        try {
            return done.await()
        } finally {
            synchronized(lock) {
                socket?.cancel()
                socket = null
                authed = false
                subscription = null
                failPending(IOException("connection closed"))
            }
        }
    }

    /** Follows exactly [wanted]: the old subscription goes, a new one starts (under [lock], once authed). */
    private fun resubscribe() {
        val ws = socket ?: return
        subscription?.let { old ->
            ws.send(JSONObject().put("id", ids.incrementAndGet()).put("type", "unsubscribe_events").put("subscription", old).toString())
        }
        subscription = null
        subscribedTo = wanted
        _entities.value = _entities.value.filterKeys { it in wanted }
        if (wanted.isEmpty()) return
        val id = ids.incrementAndGet()
        subscription = id
        ws.send(JSONObject().put("id", id).put("type", "subscribe_entities").put("entity_ids", JSONArray(wanted.sorted())).toString())
    }

    /**
     * A subscribe_entities event: "a" full states, "c" changes ("+" added or changed, "-" removed
     * attributes), "r" removed entities. Short keys: "s" state, "a" attributes.
     */
    private fun onEntities(event: JSONObject?) {
        event ?: return
        val map = LinkedHashMap(_entities.value)
        event.optJSONObject("a")?.let { added ->
            for (id in added.keys()) {
                val s = added.optJSONObject(id) ?: continue
                map[id] = HaEntityState(s.optString("s"), s.optJSONObject("a") ?: JSONObject())
            }
        }
        event.optJSONObject("c")?.let { changed ->
            for (id in changed.keys()) {
                val diff = changed.optJSONObject(id) ?: continue
                val old = map[id] ?: HaEntityState("unknown", JSONObject())
                val attrs = JSONObject(old.attributes.toString())
                diff.optJSONObject("-")?.optJSONArray("a")?.let { gone -> for (i in 0 until gone.length()) attrs.remove(gone.optString(i)) }
                val plus = diff.optJSONObject("+")
                plus?.optJSONObject("a")?.let { a -> for (k in a.keys()) attrs.put(k, a.get(k)) }
                map[id] = HaEntityState(plus?.optString("s")?.takeIf { plus.has("s") } ?: old.state, attrs)
            }
        }
        event.optJSONArray("r")?.let { removed -> for (i in 0 until removed.length()) map.remove(removed.optString(i)) }
        _entities.value = map
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val COMMAND_TIMEOUT_MS = 15_000L
    }
}
