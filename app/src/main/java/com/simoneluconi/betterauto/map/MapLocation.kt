package com.simoneluconi.betterauto.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarGpsGate
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the car is for the map, and which way it points. */
data class MapFix(
    val latitude: Double,
    val longitude: Double,
    /** Degrees from north; kept from the last move while standing, null until the car has moved. */
    val bearing: Float?,
    val speedMs: Float?,
    val accuracyM: Float?,
    val source: MapSource,
    /** [SystemClock.elapsedRealtime] when it arrived (or was estimated). */
    val at: Long,
) {
    fun toLocation(): Location = Location("betterauto").also { l ->
        l.latitude = latitude
        l.longitude = longitude
        bearing?.let { l.bearing = it }
        speedMs?.let { l.speed = it }
        accuracyM?.let { l.accuracy = it }
        l.time = System.currentTimeMillis()
        l.elapsedRealtimeNanos = at * 1_000_000
    }
}

/** Where the position on the map comes from. */
enum class MapSource {
    /** The car's own GPS. */
    CAR,
    /** The phone's location. */
    PHONE,
    /** No GPS at all (a tunnel): moved on from the last fix with the car's speed and heading. */
    ESTIMATED,
}

/** Why there is no position to show, when there is none. */
enum class MapLocationProblem { NONE, NO_PERMISSION, SEARCHING }

/**
 * The position the map follows, only while a map is on screen ([request]). The same rule as the smart-home
 * arrival buttons ([CarGpsGate]): the car's own GPS while it is live and sees satellites, then the phone's
 * GPS stays off; otherwise the phone's, once a second. The heading is the way the car moves (the GPS
 * course), never the phone's compass.
 *
 * With no GPS at all (a tunnel, a covered car park) the position goes on from the last fix as the car
 * says it moves: its speed (backwards in reverse), and for the turns its own compass or, failing that,
 * its gyroscope, once the gyroscope's sense of turning has been checked against the GPS.
 */
object MapLocation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _fix = MutableStateFlow<MapFix?>(null)

    /** The last position; kept after the map closes, so it opens where the car was. */
    val fix: StateFlow<MapFix?> = _fix

    private val _problem = MutableStateFlow(MapLocationProblem.NONE)
    val problem: StateFlow<MapLocationProblem> = _problem

    private val _lost = MutableStateFlow(false)

    /** No GPS for a while and nothing to estimate with: the position shown is the last one seen. */
    val lost: StateFlow<Boolean> = _lost

    private var users = 0
    private var job: Job? = null
    private var context: Context? = null
    private var phoneListener: LocationListener? = null
    private var lastCarAt = 0L
    /** The last fix from a GPS, the car's or the phone's (not an estimate). */
    private var lastRealAt = 0L
    private var source: MapSource? = null
    private val gyro = GyroSense()
    /** The estimate's last step, standing or moving. */
    private var stepAt = 0L

    /** Follows the position while the returned function is not called; maps on screen call it as they go. */
    fun request(context: Context): () -> Unit {
        this.context = context.applicationContext
        if (users++ == 0) start()
        var released = false
        return {
            if (!released) {
                released = true
                if (--users == 0) stop()
            }
        }
    }

    @Volatile private var phoneIgnored = false

    /** Debug: no phone location, as deep in a tunnel, to try the estimate on a bench with WiFi positioning. */
    fun ignorePhone(ignored: Boolean) {
        phoneIgnored = ignored
        if (ignored) scope.launch { stopPhone() }
    }

    /** The car says it has a GPS of its own: then a position from elsewhere is worth a word on the map. */
    fun carHasGps(snapshot: VehicleSnapshot): Boolean = "LOCATION" in snapshot.offered

    private fun start() {
        lastCarAt = 0L
        lastRealAt = 0L
        job = scope.launch {
            launch { followCar() }
            launch { watchPhone() }
            launch { reckon() }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        stopPhone()
        source = null
        _problem.value = MapLocationProblem.NONE
        _lost.value = false
    }

    private suspend fun followCar() {
        val gate = CarGpsGate()
        VehicleData.snapshot.mapNotNull { s -> s.position?.let { it to s.satellites } }.distinctUntilChanged().collect { (timed, satellites) ->
            if (gate.judge(timed, satellites) != CarGpsGate.Verdict.USE) return@collect
            lastCarAt = SystemClock.elapsedRealtime()
            stopPhone()
            val p = timed.value
            onFix(p.latitude, p.longitude, p.bearing?.toFloat(), p.speedKmh?.div(3.6)?.toFloat(), p.accuracyM?.toFloat(), MapSource.CAR)
        }
    }

    private fun carLive() = lastCarAt != 0L && SystemClock.elapsedRealtime() - lastCarAt < CarGpsGate.FRESH_MS

    /** The phone's GPS on whenever the car's is quiet, off as soon as the car talks again. */
    private suspend fun watchPhone() {
        while (true) {
            if (carLive()) stopPhone() else startPhone()
            delay(PHONE_CHECK_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhone() {
        if (phoneListener != null || phoneIgnored) return
        val context = context ?: return
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (_problem.value != MapLocationProblem.NO_PERMISSION) EventLog.log("Map: no location permission, waiting for the car's GPS")
            _problem.value = MapLocationProblem.NO_PERMISSION
            return
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val provider = bestProvider(lm) ?: return
        if (_fix.value == null) {
            _problem.value = MapLocationProblem.SEARCHING
            // Something to show at once while the first fix comes: the newest any provider remembers
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < LAST_KNOWN_MAX_AGE_MS * 1_000_000 }
                ?.let { onPhoneLocation(it, remembered = true) }
        }
        val listener = LocationListener { onPhoneLocation(it) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                // The real GPS: a balanced request would settle for the mobile network, too vague on a road. It
                // runs only while a map is on screen and the car's own GPS is not there
                val request = LocationRequest.Builder(PHONE_INTERVAL_MS).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
                lm.requestLocationUpdates(provider, request, context.mainExecutor, listener)
            } else {
                lm.requestLocationUpdates(provider, PHONE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            }
        }.onSuccess { phoneListener = listener }
    }

    private fun stopPhone() {
        val listener = phoneListener ?: return
        context?.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        phoneListener = null
    }

    private fun onPhoneLocation(loc: Location, remembered: Boolean = false) {
        if (carLive()) return
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        // In a tunnel the phone may still guess from the mobile network, hundreds of metres off: the car's
        // own reckoning is better then
        if (source == MapSource.ESTIMATED && (accuracy == null || accuracy > ESTIMATE_BEATS_M)) return
        onFix(
            loc.latitude, loc.longitude,
            if (loc.hasBearing()) loc.bearing else null,
            if (loc.hasSpeed()) loc.speed else null,
            accuracy,
            MapSource.PHONE,
            remembered = remembered,
        )
    }

    private fun onFix(latitude: Double, longitude: Double, bearing: Float?, speedMs: Float?, accuracyM: Float?, from: MapSource, remembered: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = _fix.value
        if (!remembered) {
            lastRealAt = now
            setSource(from)
            _problem.value = MapLocationProblem.NONE
            _lost.value = false
        }
        val heading = heading(last, latitude, longitude, bearing, speedMs)
        // Both from a GPS and a turn between them: does the gyroscope agree on which way is left?
        if (last != null && last.source != MapSource.ESTIMATED && !remembered) {
            gyro.learn(last.bearing, heading, (now - last.at) / 1000.0, speedMs, carMotion(VehicleData.snapshot.value)?.rotation?.third)
        }
        _fix.value = MapFix(latitude, longitude, heading, speedMs, accuracyM, from, now)
    }

    /** Once a second: with no GPS for a few seconds, moves the car on as the car says it moves. */
    private suspend fun reckon() {
        while (true) {
            delay(RECKON_STEP_MS)
            val last = _fix.value ?: continue
            val now = SystemClock.elapsedRealtime()
            val quiet = now - lastRealAt
            if (lastRealAt == 0L || quiet < RECKON_AFTER_MS) {
                _lost.value = false
                continue
            }
            val snapshot = VehicleData.snapshot.value
            val wall = System.currentTimeMillis()
            // Every piece of car data is optional: without the speed nothing can be estimated, the car stays
            // where it was last seen and, once that is clearly not a pause between fixes, the map says so
            val kmh = snapshot.speed?.takeIf { wall - it.at < CAR_DATA_FRESH_MS }?.value?.kmh
            if (kmh == null || quiet > RECKON_MAX_MS) {
                val lost = kmh != null || quiet > LOST_AFTER_MS
                if (lost && !_lost.value) EventLog.log("Map: no GPS${if (kmh == null) " and no speed from the car" else " for too long"}: the position stands")
                _lost.value = lost
                continue
            }
            _lost.value = false
            // Moved since the last fix or the last step, whichever is later: a stop in between doesn't count
            val dt = (now - maxOf(last.at, stepAt)) / 1000.0
            stepAt = now
            // Standing: a phone sends little while nothing changes, and there is nothing to move
            if (kmh < STANDING_KMH) continue
            val reverse = snapshot.gear?.takeIf { wall - it.at < CAR_DATA_FRESH_MS }?.value?.position == GearPosition.REVERSE
            val motion = carMotion(snapshot)
            val heading = carHeading(last.bearing, motion?.compassBearing, motion?.rotation?.third, gyro.sense, dt)
            val metres = kmh / 3.6 * dt * (if (reverse) -1 else 1)
            val (lat, lon) = if (heading != null) advance(last.latitude, last.longitude, heading.toDouble(), metres) else last.latitude to last.longitude
            setSource(MapSource.ESTIMATED)
            _fix.value = MapFix(lat, lon, heading, (kmh / 3.6).toFloat(), null, MapSource.ESTIMATED, now)
        }
    }

    private fun carMotion(snapshot: VehicleSnapshot) =
        snapshot.motion?.takeIf { System.currentTimeMillis() - it.at < CAR_DATA_FRESH_MS }?.value

    private fun setSource(value: MapSource) {
        if (source == value) return
        EventLog.log(
            when (value) {
                MapSource.CAR -> "Map: following the car's GPS"
                MapSource.PHONE -> "Map: following the phone's location"
                MapSource.ESTIMATED -> "Map: no GPS, estimating from the car's speed" +
                    if (gyro.sense != 0) " and gyroscope" else ""
            },
        )
        source = value
    }

    /**
     * Which way the car points: its own bearing while it moves; standing still a GPS bearing wanders, so the
     * last one stays. Without any bearing, from the way it moved since the last fix.
     */
    internal fun heading(last: MapFix?, latitude: Double, longitude: Double, bearing: Float?, speedMs: Float?): Float? {
        val moving = speedMs == null || speedMs >= MOVING_MS
        if (bearing != null && moving) return bearing
        // Standing from the start: no direction yet (north up), not whatever the GPS guesses at rest
        if (last == null) return null
        if (bearing == null && speedMs == null) {
            val d = distanceM(last.latitude, last.longitude, latitude, longitude)
            if (d >= MOVED_M) return bearingDeg(last.latitude, last.longitude, latitude, longitude)
        }
        return last.bearing
    }

    private fun bestProvider(lm: LocationManager): String? {
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    private const val PHONE_INTERVAL_MS = 1_000L
    private const val PHONE_CHECK_MS = 2_000L

    /** A remembered fix older than this would show the car somewhere else. */
    private const val LAST_KNOWN_MAX_AGE_MS = 30 * 60_000L

    /** Below this (about 5 km/h) the bearing is noise. */
    private const val MOVING_MS = 1.5f

    /** Moved enough to tell a direction from two fixes. */
    private const val MOVED_M = 8.0

    private const val RECKON_STEP_MS = 1_000L
    /** No GPS for this long: the estimate starts. */
    private const val RECKON_AFTER_MS = 3_000L
    /** The estimate drifts: past this the car just stays where it was last seen. */
    private const val RECKON_MAX_MS = 5 * 60_000L
    /** Without the car's speed, this long without a fix is no pause: the signal is gone. */
    private const val LOST_AFTER_MS = 15_000L
    private const val STANDING_KMH = 1.0
    private const val CAR_DATA_FRESH_MS = 3_000L
    /** A phone fix vaguer than this doesn't end the estimate. */
    private const val ESTIMATE_BEATS_M = 60f
}

/**
 * Which way the car's gyroscope turns. Cars differ in how they mount and report it, so its yaw rate is
 * used only once it has agreed with the GPS on a few turns: [sense] is +1 when a positive rate turns the
 * car right (clockwise), -1 left, 0 while not known.
 */
internal class GyroSense {
    private var votes = 0

    val sense: Int get() = when {
        votes >= VOTES -> 1
        votes <= -VOTES -> -1
        else -> 0
    }

    /** One step between two GPS fixes: [from] and [to] bearings, [yawRate] in rad/s from the car. */
    fun learn(from: Float?, to: Float?, seconds: Double, speedMs: Float?, yawRate: Double?) {
        if (from == null || to == null || yawRate == null || seconds <= 0 || seconds > 5) return
        if (speedMs != null && speedMs < MIN_SPEED_MS) return
        val turned = turnDeg(from, to)
        val gyroDeg = Math.toDegrees(yawRate) * seconds
        if (abs(turned) < MIN_TURN_DEG || abs(gyroDeg) < MIN_TURN_DEG) return
        votes = (votes + (sign(turned) * sign(gyroDeg)).toInt()).coerceIn(-MAX_VOTES, MAX_VOTES)
    }

    private companion object {
        const val VOTES = 3
        const val MAX_VOTES = 10
        const val MIN_TURN_DEG = 4.0
        const val MIN_SPEED_MS = 3f
    }
}

/**
 * The car's heading after [seconds]: its own compass when it has one, else [last] turned by the gyroscope's
 * yaw rate (only with a known [sense]), else unchanged.
 */
internal fun carHeading(last: Float?, compass: Double?, yawRate: Double?, sense: Int, seconds: Double): Float? {
    if (compass != null) return ((compass % 360 + 360) % 360).toFloat()
    if (last == null) return null
    if (yawRate == null || sense == 0) return last
    val turned = Math.toDegrees(yawRate) * seconds * sense
    return (((last + turned) % 360 + 360) % 360).toFloat()
}

/** Clockwise turn from [from] to [to], in -180..180 degrees. */
internal fun turnDeg(from: Float, to: Float): Double = ((to - from + 540.0) % 360.0) - 180.0

/** The point [metres] away along [bearingDeg] (negative goes backwards). */
internal fun advance(latitude: Double, longitude: Double, bearingDeg: Double, metres: Double): Pair<Double, Double> {
    val b = Math.toRadians(bearingDeg)
    val lat = latitude + metres * cos(b) / METRES_PER_DEGREE
    val lon = longitude + metres * sin(b) / (METRES_PER_DEGREE * cos(Math.toRadians(latitude)))
    return lat to lon
}

/** Equirectangular: plenty for the few metres between two fixes. */
internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return sqrt(x * x + y * y) * EARTH_RADIUS_M
}

internal fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return ((Math.toDegrees(atan2(x, y)) + 360.0) % 360.0).toFloat()
}

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METRES_PER_DEGREE = EARTH_RADIUS_M * Math.PI / 180.0
