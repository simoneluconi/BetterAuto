package com.simoneluconi.betterauto.smarthome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Where the arrival buttons stand: the places the car is in ([SmartHome.arrivalKey]), and whether the panel is open. */
data class ArrivalState(val inside: Set<String> = emptySet(), val panelOpen: Boolean = false)

/**
 * Tells when the car is at a provider's place, to show the arrival buttons. Only while a car session
 * runs. The car's own GPS first (the Android Auto location sensor or a car maker's protocol, through
 * [VehicleData]): then the phone's GPS stays off and samples are just compared. Without it, the phone's
 * platform location (no Play Services), one fix at a time, the next one as late as the car could
 * reach the nearest fence edge ([Geo.nextFixMs]), plus free fixes other apps get (passive provider).
 *
 * Debug builds can replace the position with a [FakeRoute] ([simulate]): the same decisions, logged.
 */
class ArrivalWatcher(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ArrivalState())
    val state: StateFlow<ArrivalState> = _state

    private val _simulating = MutableStateFlow(false)
    /** A pretend position is in use: arrival works even without a car session. */
    val simulating: StateFlow<Boolean> = _simulating

    private var trackers: Map<String, FenceTracker> = emptyMap()
    private var job: Job? = null
    private var passive: LocationListener? = null
    @Volatile private var lastCarSampleAt = 0L
    private var lastSample: Pair<Double, Double>? = null
    private var warnedPermission = false

    @Volatile private var fake: FakeRoute? = null
    @Volatile private var fakeCarGps = false

    fun openPanel() {
        if (_state.value.inside.isNotEmpty()) _state.value = _state.value.copy(panelOpen = true)
    }

    fun closePanel() {
        _state.value = _state.value.copy(panelOpen = false)
    }

    /**
     * Debug: replaces the car's and the phone's position with [route] (null goes back to the real one).
     * [asCarGps] feeds it every 2 s like a car GPS; otherwise it is polled like the phone's, on the
     * adaptive schedule.
     */
    fun simulate(route: FakeRoute?, asCarGps: Boolean = false) {
        synchronized(this) {
            fake = route
            fakeCarGps = asCarGps
        }
        EventLog.log("Smart home: ${if (route == null) "real position again" else "pretend position $route${if (asCarGps) ", as car GPS" else ""}"}")
        _simulating.value = route != null
        // Start over with the new source
        restart()
    }

    /** Follows these fences (by [SmartHome.arrivalKey]); an empty map stops everything. */
    @Synchronized
    fun watch(fences: Map<String, Fence>) {
        if (fences == trackers.mapValues { it.value.fence }) return
        trackers = fences.mapValues { FenceTracker(it.value) }
        restart()
    }

    @Synchronized
    private fun restart() {
        job?.cancel()
        job = null
        stopPassive()
        trackers = trackers.mapValues { FenceTracker(it.value.fence) }
        lastCarSampleAt = 0L
        lastSample = null
        _state.value = ArrivalState()
        if (trackers.isEmpty()) return
        EventLog.log(
            "Smart home: watching ${trackers.size} arrival place(s): " +
                trackers.entries.joinToString { (id, t) -> "${label(id)} shows within ${t.fence.enterM.roundToInt()} m, hides past ${t.fence.exitM.roundToInt()} m" }
        )
        job = scope.launch {
            when {
                fake != null && fakeCarGps -> launch { followFakeCar() }
                fake != null -> launch { followPhone() }
                else -> {
                    launch { followCar() }
                    launch { followPhone() }
                }
            }
        }
    }

    private suspend fun followCar() {
        VehicleData.snapshot.mapNotNull { it.position }.distinctUntilChanged().collect { timed ->
            if (System.currentTimeMillis() - timed.at > CAR_FRESH_MS) return@collect
            if (lastCarSampleAt == 0L) EventLog.log("Smart home: arrival follows the car's GPS")
            lastCarSampleAt = timed.at
            val p = timed.value
            onSample(p.latitude, p.longitude, p.accuracyM)
        }
    }

    private suspend fun followFakeCar() {
        while (true) {
            fakePosition()?.let { (lat, lon) -> onSample(lat, lon, null, verbose = true) }
            delay(FAKE_CAR_PERIOD_MS)
        }
    }

    private fun fakePosition(): Pair<Double, Double>? =
        fake?.at(System.currentTimeMillis(), trackers.values.firstOrNull()?.fence)

    private suspend fun followPhone() {
        var logged = false
        while (true) {
            if (fake != null) {
                val position = fakePosition()
                position?.let { (lat, lon) -> onSample(lat, lon, null) }
                val wait = nextDelay(fake?.speedMs)
                logDecision(wait)
                delay(wait)
                continue
            }
            // The car's GPS is talking: nothing to do on the phone
            if (System.currentTimeMillis() - lastCarSampleAt < CAR_FRESH_MS) {
                stopPassive()
                delay(CAR_FRESH_MS)
                continue
            }
            if (!hasPermission()) {
                if (!warnedPermission) EventLog.log("Smart home: no location permission, arrival buttons wait for the car's GPS")
                warnedPermission = true
                delay(Geo.MAX_INTERVAL_MS)
                continue
            }
            if (!logged) EventLog.log("Smart home: arrival follows the phone's location")
            logged = true
            startPassive()
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { oneFix() }
            fix?.let { onSample(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null) }
            val wait = nextDelay(fix?.takeIf { it.hasSpeed() }?.speed?.toDouble())
            logDecision(wait)
            delay(wait)
        }
    }

    /** One line per check: how far, inside or not, and when the next one is. Distances only, no coordinates. */
    @Synchronized
    private fun logDecision(waitMs: Long?) {
        val (lat, lon) = lastSample ?: return EventLog.log("Smart home: no position yet")
        val parts = trackers.entries.joinToString { (id, t) ->
            "${label(id)} ${t.fence.distanceM(lat, lon).roundToInt()} m, ${if (t.inside == true) "inside" else "outside"}"
        }
        EventLog.log("Smart home: $parts${waitMs?.let { ", next check in ${it / 1000} s" } ?: ""}")
    }

    @Synchronized
    private fun onSample(latitude: Double, longitude: Double, accuracyM: Double?, verbose: Boolean = false) {
        lastSample = latitude to longitude
        var inside = _state.value.inside
        var open = _state.value.panelOpen
        for ((id, tracker) in trackers) {
            val d = tracker.fence.distanceM(latitude, longitude).roundToInt()
            when (tracker.update(latitude, longitude, accuracyM)) {
                FenceTransition.ENTER -> {
                    EventLog.log("Smart home: entered ${label(id)} at $d m: arrival buttons shown")
                    inside = inside + id
                    open = true
                }
                FenceTransition.EXIT -> {
                    EventLog.log("Smart home: left ${label(id)} at $d m: arrival buttons hidden")
                    inside = inside - id
                }
                null -> Unit
            }
        }
        if (inside.isEmpty()) open = false
        _state.value = ArrivalState(inside, open)
        if (verbose) logDecision(null)
    }

    /** The soonest any fence edge could be reached, at the car's speed when known. */
    @Synchronized
    private fun nextDelay(fixSpeedMs: Double?): Long {
        val (lat, lon) = lastSample ?: return Geo.MIN_INTERVAL_MS * 6
        val carSpeed = VehicleData.snapshot.value.speed?.takeIf { System.currentTimeMillis() - it.at < CAR_FRESH_MS }?.value?.kmh?.div(3.6)
        val speed = if (fake != null) fixSpeedMs else carSpeed ?: fixSpeedMs
        return trackers.values.minOfOrNull { t ->
            val d = t.fence.distanceM(lat, lon)
            Geo.nextFixMs(Geo.edgeDistanceM(t.fence, d, t.inside == true), speed)
        } ?: Geo.MAX_INTERVAL_MS
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val manager get() = context.getSystemService(LocationManager::class.java)

    /** The best platform provider: fused (API 31+), else GPS, else the network. */
    private fun bestProvider(): String? {
        val lm = manager ?: return null
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun oneFix(): Location? {
        val lm = manager ?: return null
        val provider = bestProvider() ?: return null
        val main = Handler(Looper.getMainLooper())
        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, cancel, Executor { main.post(it) }) { if (cont.isActive) cont.resume(it) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startPassive() {
        if (passive != null) return
        val lm = manager ?: return
        val listener = LocationListener { loc ->
            if (fake == null) onSample(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, Geo.MIN_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            passive = listener
        }
    }

    @Synchronized
    private fun stopPassive() {
        passive?.let { manager?.removeUpdates(it) }
        passive = null
    }

    /** "homeassistant place 2": the log never names the driver's places. */
    private fun label(key: String): String = "${key.substringBefore('/')} place ${trackers.keys.sorted().indexOf(key) + 1}"

    private companion object {
        /** A car position older than this is not live: the phone takes over. */
        const val CAR_FRESH_MS = 20_000L
        const val FIX_TIMEOUT_MS = 30_000L
        const val FAKE_CAR_PERIOD_MS = 2_000L
    }
}
