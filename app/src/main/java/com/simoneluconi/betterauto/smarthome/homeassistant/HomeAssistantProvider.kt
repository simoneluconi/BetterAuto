package com.simoneluconi.betterauto.smarthome.homeassistant

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.smarthome.DeviceAction
import com.simoneluconi.betterauto.smarthome.DeviceState
import com.simoneluconi.betterauto.smarthome.LinkState
import com.simoneluconi.betterauto.smarthome.SecretStore
import com.simoneluconi.betterauto.smarthome.SmartCatalog
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.smarthome.SmartHomeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** How linking ended, for the phone card. */
sealed interface HaLinkResult {
    /** Linked; [switchedTo] is the address used from now on when the one typed only works at home. */
    data class Linked(val switchedTo: String?) : HaLinkResult
    data object Refused : HaLinkResult
    data object Unreachable : HaLinkResult
}

/** Linking in progress, for the phone card (the browser answer arrives in another activity). */
sealed interface HaLinking {
    data object Idle : HaLinking
    data object Working : HaLinking
    data class Done(val result: HaLinkResult) : HaLinking
}

/** Home Assistant as a [SmartHomeProvider]: OAuth or a long-lived token, WebSocket for everything else. */
class HomeAssistantProvider(private val context: Context) : SmartHomeProvider {
    override val id = HaMapping.PROVIDER_ID
    override val name = "Home Assistant"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("smarthome_homeassistant", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, "homeassistant")
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Finds a dead connection (car leaving WiFi, tunnel) within a minute
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val credentials = MutableStateFlow(HaCredentials.fromJson(secrets.get(KEY_CREDENTIALS)))
    private val socket = HaSocket(client, scope, ::endpoint)

    override val link: StateFlow<LinkState> = combine(credentials, socket.link) { c, l -> if (c == null) LinkState.NOT_LINKED else l }
        .stateIn(scope, SharingStarted.Eagerly, if (credentials.value == null) LinkState.NOT_LINKED else LinkState.IDLE)

    private val _homeName = MutableStateFlow(credentials.value?.homeName)
    override val homeName: StateFlow<String?> = _homeName

    private val _catalog = MutableStateFlow(SmartCatalog.fromJson(prefs.getString(KEY_CATALOG, null)))
    override val catalog: StateFlow<SmartCatalog?> = _catalog

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    override val states: StateFlow<Map<String, DeviceState>> = _states

    /** The address in use, for the phone card. */
    val url: String? get() = credentials.value?.url

    private var following: Set<String> = emptySet()

    init {
        // Live states as they come; the last known stay while disconnected
        scope.launch {
            socket.entities.collect { entities ->
                if (entities.isEmpty()) return@collect
                _states.value = _states.value + entities.mapValues { (id, raw) -> HaMapping.state(id, raw) }
            }
        }
    }

    override fun icon(context: Context): ImageBitmap? = CompanionApps.firstNotNullOfOrNull { MediaApps.info(context, it)?.icon }

    override fun appInstalled(context: Context) = companionInstalled(context)

    /** The official companion app is on the phone: the linking card says so. */
    fun companionInstalled(context: Context): Boolean = CompanionApps.any {
        runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess
    }

    @Synchronized
    override fun follow(deviceIds: Set<String>) {
        if (deviceIds == following && (deviceIds.isEmpty() || socket.running)) return
        following = deviceIds
        if (credentials.value == null) return
        if (deviceIds.isEmpty()) {
            if (!catalogBusy) socket.stop()
        } else {
            socket.start(deviceIds)
        }
    }

    @Volatile private var catalogBusy = false

    override suspend fun refreshCatalog(): Boolean = withContext(Dispatchers.IO) {
        if (credentials.value == null) return@withContext false
        catalogBusy = true
        val started = !socket.running
        if (started) socket.start(following)
        try {
            if (!socket.awaitOnline(CONNECT_WAIT_MS)) return@withContext false
            val states = socket.command("get_states") as? JSONArray
            val areas = runCatching { socket.command("config/area_registry/list") as? JSONArray }.getOrNull()
            val devices = runCatching { socket.command("config/device_registry/list") as? JSONArray }.getOrNull()
            val entities = runCatching { socket.command("config/entity_registry/list", timeoutMs = 30_000) as? JSONArray }.getOrNull()
            // Admin only: without it nothing is pre-ticked by exposure, the guess by kind takes over
            val exposed = runCatching { HaMapping.exposed(socket.command("homeassistant/expose_entity/list")) }.getOrNull()
            val config = runCatching { socket.command("get_config") as? JSONObject }.getOrNull()
            val fetched = HaMapping.catalog(areas, devices, entities, states, exposed, config)
            EventLog.log(
                "Home Assistant: ${fetched.devices.size} devices in ${fetched.areas.size} areas, ${fetched.places.size} places, " +
                    "${fetched.devices.count { it.suggested }} exposed to assistants${if (exposed == null) " (list not readable)" else ""}"
            )
            _catalog.value = fetched
            prefs.edit { putString(KEY_CATALOG, fetched.toJson()) }
            SmartHome.applyCatalog(id, fetched)
            true
        } catch (e: IOException) {
            EventLog.log("Home Assistant: reading the devices failed (${e.javaClass.simpleName})")
            false
        } finally {
            catalogBusy = false
            if (started && following.isEmpty()) socket.stop()
        }
    }

    override suspend fun perform(deviceId: String, action: DeviceAction): Boolean = withContext(Dispatchers.IO) {
        val call = HaMapping.serviceCall(deviceId, action) ?: return@withContext false
        if (!socket.running) socket.start(following)
        if (!socket.awaitOnline(CONNECT_WAIT_MS)) return@withContext false
        runCatching {
            socket.command("call_service") {
                put("domain", call.domain)
                put("service", call.service)
                put("target", JSONObject().put("entity_id", call.entityId))
            }
        }.isSuccess
    }

    override fun unlink() {
        val old = credentials.value
        socket.stop()
        secrets.put(KEY_CREDENTIALS, null)
        credentials.value = null
        _homeName.value = null
        _catalog.value = null
        _states.value = emptyMap()
        prefs.edit { remove(KEY_CATALOG) }
        SmartHome.forget(id)
        EventLog.log("Home Assistant: unlinked")
        old?.tokens?.refresh?.let { refresh -> scope.launch { HaAuth.revoke(client, old.url, refresh) } }
    }

    @Composable
    override fun LinkCard() = HaLinkCard(this)

    private val _linking = MutableStateFlow<HaLinking>(HaLinking.Idle)
    val linking: StateFlow<HaLinking> = _linking

    /** The browser came back ([com.simoneluconi.betterauto.smarthome.SignInActivity]): finish linking in the background, the card follows [linking]. */
    override fun onSignInAnswer(params: Map<String, String>) {
        val code = params["code"]
        val state = params["state"]
        val error = params["error"]
        if (code == null) {
            EventLog.log("Home Assistant: login not completed (${error ?: "no code"})")
            _linking.value = HaLinking.Done(HaLinkResult.Refused)
            return
        }
        _linking.value = HaLinking.Working
        scope.launch { _linking.value = HaLinking.Done(completeLogin(code, state)) }
    }

    /** Advanced linking with a long-lived token, in the background. */
    fun startTokenLink(baseUrl: String, token: String) {
        _linking.value = HaLinking.Working
        scope.launch { _linking.value = HaLinking.Done(linkWithToken(baseUrl, token)) }
    }

    fun clearLinking() {
        if (_linking.value is HaLinking.Done) _linking.value = HaLinking.Idle
    }

    /** Whether [baseUrl] is a Home Assistant (no login, a few seconds at most): to prefill the card from the clipboard. */
    suspend fun probe(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        HaAuth.isHomeAssistant(client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build(), baseUrl)
    }

    /** Starts the browser login: remembers where and which state to expect back. */
    fun authorizeUrl(baseUrl: String): String {
        val state = ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        prefs.edit {
            putString(KEY_OAUTH_URL, baseUrl)
            putString(KEY_OAUTH_STATE, state)
        }
        return HaAuth.authorizeUrl(baseUrl, state)
    }

    /** The browser came back with [code]: exchange it (only for the state we sent) and finish linking. */
    suspend fun completeLogin(code: String, state: String?): HaLinkResult = withContext(Dispatchers.IO) {
        val expected = prefs.getString(KEY_OAUTH_STATE, null)
        val baseUrl = prefs.getString(KEY_OAUTH_URL, null)
        prefs.edit {
            remove(KEY_OAUTH_STATE)
            remove(KEY_OAUTH_URL)
        }
        if (expected == null || baseUrl == null || state != expected) {
            EventLog.log("Home Assistant: login answer with an unexpected state, ignored")
            return@withContext HaLinkResult.Refused
        }
        val tokens = try {
            HaAuth.exchange(client, baseUrl, code)
        } catch (_: HaAuthRefused) {
            return@withContext HaLinkResult.Refused
        } catch (e: IOException) {
            EventLog.log("Home Assistant: token exchange failed (${e.javaClass.simpleName})")
            return@withContext HaLinkResult.Unreachable
        }
        finishLink(baseUrl, tokens)
    }

    /** Advanced: an address and a long-lived access token made in the Home Assistant profile. */
    suspend fun linkWithToken(baseUrl: String, token: String): HaLinkResult = withContext(Dispatchers.IO) {
        finishLink(baseUrl, HaTokens.longLived(token))
    }

    /**
     * Saves the credentials and checks them: get_config gives the home's name and its external
     * address, used instead of a local one (a local address does not work in the car).
     */
    private suspend fun finishLink(baseUrl: String, tokens: HaTokens): HaLinkResult {
        save(HaCredentials(baseUrl, tokens))
        socket.stop()
        socket.start(following)
        val online = socket.awaitOnline(CONNECT_WAIT_MS)
        if (!online) {
            val refused = socket.link.value == LinkState.AUTH_FAILED
            socket.stop()
            save(null)
            EventLog.log("Home Assistant: linking failed (${if (refused) "refused" else "not reachable"})")
            return if (refused) HaLinkResult.Refused else HaLinkResult.Unreachable
        }
        val config = runCatching { socket.command("get_config") as? JSONObject }.getOrNull()
        var url = baseUrl
        var switched: String? = null
        if (HaAuth.isLocal(baseUrl)) {
            val external = config?.optString("external_url")?.takeIf { it.isNotEmpty() && !config.isNull("external_url") }?.let { HaAuth.normalizeUrl(it) }
                ?: remoteUiUrl()
            if (external != null && external != baseUrl) {
                url = external
                switched = external
            }
        }
        val home = config?.optString("location_name")?.takeIf { it.isNotEmpty() }
        save(HaCredentials(url, credentials.value?.tokens ?: tokens, home))
        EventLog.log("Home Assistant: linked${if (switched != null) ", using its external address" else ""}")
        if (switched != null) {
            // Reconnect on the address the car will use
            socket.stop()
            socket.start(following)
            socket.awaitOnline(CONNECT_WAIT_MS)
        }
        refreshCatalog()
        if (following.isEmpty()) socket.stop()
        return HaLinkResult.Linked(switched)
    }

    /** Nabu Casa's remote address, when Home Assistant Cloud has remote access on (admin only). */
    private suspend fun remoteUiUrl(): String? = runCatching {
        val status = socket.command("cloud/status") as? JSONObject ?: return null
        val domain = status.optString("remote_domain").takeIf { it.isNotEmpty() && !status.isNull("remote_domain") } ?: return null
        if (!status.optBoolean("remote_enabled", true)) return null
        "https://$domain"
    }.getOrNull()

    private fun save(value: HaCredentials?) {
        secrets.put(KEY_CREDENTIALS, value?.toJson())
        credentials.value = value
        _homeName.value = value?.homeName
    }

    /** The address and a valid access token, refreshed when it is about to expire or was refused. */
    private suspend fun endpoint(refused: Boolean): HaEndpoint? {
        val current = credentials.value ?: return null
        val tokens = current.tokens
        if (!refused && !tokens.expiresSoon(System.currentTimeMillis())) return HaEndpoint(current.url, tokens.access)
        val refresh = tokens.refresh ?: return if (refused) null else HaEndpoint(current.url, tokens.access)
        return try {
            val fresh = withContext(Dispatchers.IO) { HaAuth.refresh(client, current.url, refresh) }
            save(current.copy(tokens = fresh))
            HaEndpoint(current.url, fresh.access)
        } catch (_: HaAuthRefused) {
            EventLog.log("Home Assistant: the login expired, link it again on the phone")
            null
        } catch (e: IOException) {
            // Not reachable now: try with what we have, the socket retries later
            EventLog.log("Home Assistant: token refresh failed (${e.javaClass.simpleName})")
            HaEndpoint(current.url, tokens.access)
        }
    }

    companion object {
        private const val KEY_CREDENTIALS = "credentials"
        private const val KEY_CATALOG = "catalog"
        private const val KEY_OAUTH_STATE = "oauth_state"
        private const val KEY_OAUTH_URL = "oauth_url"
        private const val CONNECT_WAIT_MS = 20_000L

        /** The official app and its minimal (no Play Services) build. */
        val CompanionApps = listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")
    }
}
