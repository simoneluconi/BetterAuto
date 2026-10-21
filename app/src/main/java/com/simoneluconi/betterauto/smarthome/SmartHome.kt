package com.simoneluconi.betterauto.smarthome

import android.content.Context
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.smarthome.homeassistant.HomeAssistantProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What happened to the last command on a device, for the tile's feedback. */
enum class CommandFeedback { SENDING, DONE, FAILED }

/** One group of devices of a provider (an area, the arrival buttons...): what a widget card shows. */
data class SmartGroup(val providerId: String, val id: String, val title: String, val deviceIds: List<String>)

/**
 * The smart-home providers and everything built on them, without knowing any service: which providers
 * are linked, which devices the driver chose, when to talk to the services (only while a car session
 * runs, or a smart-home screen is open), the commands, and the arrival buttons ([ArrivalWatcher]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object SmartHome {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _providers = MutableStateFlow<List<SmartHomeProvider>>(emptyList())
    val providers: StateFlow<List<SmartHomeProvider>> = _providers

    /** Linked providers, in registration order: each gets its button in the app bar. */
    val linked: StateFlow<List<SmartHomeProvider>> = _providers
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { p -> p.link.map { p to (it != LinkState.NOT_LINKED) } }) { pairs -> pairs.filter { it.second }.map { it.first } }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every provider's catalog, so the arrival place follows a new fetch. */
    private val catalogs = _providers.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.catalog }) { it.toList() }
    }

    private var settingsStore: SmartHomeSettings? = null
    private val _choices = MutableStateFlow<Map<String, ProviderChoice>>(emptyMap())
    val choices: StateFlow<Map<String, ProviderChoice>> = _choices

    private val _arrivalEnabled = MutableStateFlow(true)
    /** The arrival buttons at all: off, no place shows them and no position is used; each place keeps its own choices. */
    val arrivalEnabled: StateFlow<Boolean> = _arrivalEnabled

    /** The provider the car's smart-home screen shows. */
    val screenProvider = MutableStateFlow<String?>(null)

    private val holds = MutableStateFlow(0)

    private val session = VehicleData.snapshot.map { it.connected }.distinctUntilChanged()

    private val _feedback = MutableStateFlow<Map<String, CommandFeedback>>(emptyMap())
    /** The last command's outcome per [SmartDevice.key], for a few seconds. */
    val feedback: StateFlow<Map<String, CommandFeedback>> = _feedback

    var arrival: ArrivalWatcher? = null
        private set

    /** Registers the providers and starts following the session; safe to call from every entry point. */
    @Synchronized
    fun init(context: Context) {
        if (settingsStore != null) return
        val store = SmartHomeSettings(context)
        settingsStore = store
        _choices.value = store.choices.value
        _arrivalEnabled.value = store.arrivalEnabled.value
        register(HomeAssistantProvider(context.applicationContext))
        val watcher = ArrivalWatcher(context.applicationContext, scope)
        arrival = watcher
        // A car session, or a pretend position on the bench (debug)
        val needed = combine(session, watcher.simulating) { inSession, simulating -> inSession || simulating }.distinctUntilChanged()

        // Talk to the services only while needed, and only about the chosen devices
        scope.launch {
            combine(combine(_providers, linked, ::Pair), _choices, needed, holds, _arrivalEnabled) { (all, linkedNow), choices, inSession, held, arriving ->
                val active = inSession || held > 0
                all.associate { p ->
                    val choice = choices[p.id] ?: ProviderChoice()
                    val arrival = if (arriving) choice.arrivalDevices else emptySet()
                    p.id to if (active && p in linkedNow) choice.selected.orEmpty() + arrival else emptySet()
                }
            }.distinctUntilChanged().collect { follow ->
                for (p in _providers.value) p.follow(follow[p.id].orEmpty())
            }
        }
        // Each session reads the linked services' lists again, once: names and devices changed there arrive by
        // themselves (and a list cached by an older version is replaced), no tap on "Refresh the list" needed
        scope.launch {
            needed.collect { inSession ->
                if (inSession) for (p in linked.value) launch { runCatching { p.refreshCatalog() } }
            }
        }
        // A linked service with no usable list (none yet, or an older version's): read it now, not at the next drive
        scope.launch {
            linked.collect { list ->
                for (p in list) if (p.catalog.value == null) launch { runCatching { p.refreshCatalog() } }
            }
        }
        // Arrival buttons: during a car session, for every place whose buttons are on (all off with the main switch)
        scope.launch {
            combine(catalogs, linked, _choices, needed, _arrivalEnabled) { _, linkedNow, choices, inSession, arriving ->
                if (!inSession || !arriving) emptyMap()
                else linkedNow.flatMap { p -> fences(p, choices[p.id] ?: ProviderChoice()) }.toMap()
            }.distinctUntilChanged().collect { watcher.watch(it) }
        }
    }

    private fun fences(provider: SmartHomeProvider, choice: ProviderChoice): List<Pair<String, Fence>> {
        val catalog = provider.catalog.value ?: return emptyList()
        return catalog.places.mapNotNull { place ->
            val arrival = choice.arrivalAt(place)?.takeIf { it.on && it.devices.isNotEmpty() } ?: return@mapNotNull null
            arrivalKey(provider.id, place.id) to Fence(place.latitude, place.longitude, place.radiusM, arrival.offsetM.toDouble())
        }
    }

    /** One place of one provider, as the arrival watcher and panel know it. Provider ids hold no '/'. */
    fun arrivalKey(providerId: String, placeId: String) = "$providerId/$placeId"

    /** The provider and place behind an [arrivalKey], while both are known. */
    fun arrivalPlace(key: String): Pair<SmartHomeProvider, SmartPlace>? {
        val provider = provider(key.substringBefore('/')) ?: return null
        val placeId = key.substringAfter('/')
        val place = provider.catalog.value?.places?.firstOrNull { it.id == placeId } ?: return null
        return provider to place
    }

    fun setArrivalEnabled(value: Boolean) {
        val store = settingsStore ?: return
        if (store.arrivalEnabled.value == value) return
        EventLog.log("Smart home: arrival buttons ${if (value) "on" else "off"}")
        store.setArrivalEnabled(value)
        _arrivalEnabled.value = value
    }

    fun register(provider: SmartHomeProvider) {
        _providers.value = _providers.value.filter { it.id != provider.id } + provider
    }

    fun unregister(id: String) {
        _providers.value.firstOrNull { it.id == id }?.follow(emptySet())
        _providers.value = _providers.value.filter { it.id != id }
    }

    fun provider(id: String?): SmartHomeProvider? = _providers.value.firstOrNull { it.id == id }

    /** A screen needs live states until the returned function is called. */
    fun hold(): () -> Unit {
        holds.value += 1
        var released = false
        return {
            if (!released) {
                released = true
                holds.value -= 1
            }
        }
    }

    fun choice(providerId: String): ProviderChoice = _choices.value[providerId] ?: ProviderChoice()

    fun update(providerId: String, change: (ProviderChoice) -> ProviderChoice) {
        val store = settingsStore ?: return
        store.update(providerId, change)
        _choices.value = store.choices.value
    }

    fun toggleSelected(providerId: String, deviceId: String) = update(providerId) { c ->
        val now = c.selected.orEmpty()
        c.copy(selected = if (deviceId in now) now - deviceId else now + deviceId)
    }

    /** Changes one place's arrival buttons (created when it has none yet). */
    fun updatePlace(providerId: String, placeId: String, change: (PlaceArrival) -> PlaceArrival) = update(providerId) { c ->
        c.copy(places = c.places + (placeId to change(c.places[placeId] ?: PlaceArrival())))
    }

    /** The arrival buttons of [placeId] in this order, as dragged on the phone. */
    fun orderArrival(providerId: String, placeId: String, order: List<String>) = updatePlace(providerId, placeId) { it.copy(order = order) }

    fun toggleArrival(providerId: String, placeId: String, deviceId: String) = updatePlace(providerId, placeId) { p ->
        p.copy(devices = if (deviceId in p.devices) p.devices - deviceId else p.devices + deviceId)
    }

    /** The car screen's devices back to what the pre-selection picks. */
    fun resetSelected(providerId: String) {
        val catalog = provider(providerId)?.catalog?.value ?: return
        update(providerId) { it.copy(selected = Preselection.pick(catalog)) }
    }

    /** A place's arrival devices back to the proposal (gates and garages), keeping on/off and the distance. */
    fun resetArrival(providerId: String, placeId: String) {
        val catalog = provider(providerId)?.catalog?.value ?: return
        updatePlace(providerId, placeId) { it.copy(devices = ArrivalCandidates.propose(catalog).ticked) }
    }

    /**
     * After a catalog fetch: the first time, ticks what the driver most likely wants and proposes the
     * arrival buttons (still to be confirmed); later, devices that disappeared are dropped.
     */
    fun applyCatalog(providerId: String, catalog: SmartCatalog) {
        val ids = catalog.devices.map { it.id }.toSet()
        update(providerId) { c ->
            val selected = c.selected?.let { it intersect ids } ?: Preselection.pick(catalog)
            // Buttons saved for "the home" move to the home place's own id, now known
            val home = catalog.home
            var places = c.places
            places[ProviderChoice.HOME_PLACE]?.let { saved ->
                if (home != null) places = (places - ProviderChoice.HOME_PLACE).let { if (home.id in it) it else it + (home.id to saved) }
            }
            // The first time, the home's buttons are proposed (still off: switching them on confirms them)
            if (places.isEmpty() && home != null) places = mapOf(home.id to PlaceArrival(ArrivalCandidates.propose(catalog).ticked))
            c.copy(selected = selected, places = places.mapValues { (_, p) -> p.copy(devices = p.devices intersect ids) })
        }
    }

    /** The provider is gone (unlinked): its choices go too. */
    fun forget(providerId: String) {
        val store = settingsStore ?: return
        store.forget(providerId)
        _choices.value = store.choices.value
    }

    /** The chosen devices in the car screen's order, as far as its catalog knows them. */
    fun chosenDevices(catalog: SmartCatalog?, choice: ProviderChoice): List<SmartDevice> {
        val selected = choice.selected ?: return emptyList()
        return Preselection.order(catalog?.devices.orEmpty().filter { it.id in selected })
    }

    /** Groups for widget cards: one per area holding chosen devices, then the ones without an area. */
    fun groups(providerId: String, otherTitle: String): List<SmartGroup> {
        val provider = provider(providerId) ?: return emptyList()
        val catalog = provider.catalog.value ?: return emptyList()
        val devices = chosenDevices(catalog, choice(providerId))
        val byArea = devices.groupBy { it.areaId }
        return catalog.areas.mapNotNull { a -> byArea[a.id]?.let { SmartGroup(providerId, a.id, a.name, it.map { d -> d.id }) } } +
            listOfNotNull(byArea[null]?.let { SmartGroup(providerId, "", otherTitle, it.map { d -> d.id }) })
    }

    /** One tap, one command: the tile shows it going, then done or failed for a few seconds. */
    fun perform(provider: SmartHomeProvider, device: SmartDevice, action: DeviceAction) {
        val key = device.key
        _feedback.value = _feedback.value + (key to CommandFeedback.SENDING)
        scope.launch {
            val ok = runCatching { provider.perform(device.id, action) }.getOrDefault(false)
            // Kind and action only: names and ids of the driver's devices stay out of the log
            EventLog.log("Smart home: ${provider.name} ${action.name.lowercase()} on a ${device.kind.name.lowercase()} ${if (ok) "accepted" else "failed"}")
            _feedback.value = _feedback.value + (key to if (ok) CommandFeedback.DONE else CommandFeedback.FAILED)
            delay(FEEDBACK_MS)
            if (_feedback.value[key] != CommandFeedback.SENDING) _feedback.value = _feedback.value - key
        }
    }

    private const val FEEDBACK_MS = 3000L
}
