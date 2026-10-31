package com.simoneluconi.betterauto.map.nav

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.MapFix
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.MapSource
import com.simoneluconi.betterauto.map.bearingDeg
import com.simoneluconi.betterauto.map.distanceM
import com.simoneluconi.betterauto.map.turnDeg
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.GuidanceVoice
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the guidance stands: the route, the next maneuver and how far it is, what is left, the next stop. */
data class NavState(
    val route: NavRoute,
    val next: Maneuver?,
    val toNextM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val arrived: Boolean = false,
    val rerouting: Boolean = false,
    /** The next stop on the way (null with none left), and how far along the route it is. */
    val stop: Destination? = null,
    val toStopM: Double = 0.0,
)

/** Why there is a trip but no route to follow: being calculated, none exists, or the server can't be reached. */
enum class RoutePlanning { CALCULATING, NO_ROUTE, OFFLINE }

/**
 * Turn-by-turn guidance along the [Trip], while a car screen is up: a route from Valhalla through the stops to
 * the destination, followed with [MapLocation]'s position, the next maneuver and the distance to it, a new route
 * when the car leaves this one or the trip changes (a stop added, moved, reached), the instructions spoken as a
 * navigation app does (the car's guidance stream, or the phone with the music lowered). While a route is on, the
 * position is followed even with no map on screen; with no trip or no car screen nothing runs.
 *
 * Everything on the main thread (the work per position is small; the network has its own threads), so the
 * state, the voice and the trip are never changed from two threads at once.
 *
 * The route comes from the public demonstration server of Valhalla (OpenStreetMap data): for trying it on the
 * bench only, the source for everyday use is still to be chosen.
 */
object Navigation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state

    private val _planning = MutableStateFlow<RoutePlanning?>(null)

    /** Set while a trip waits for its route (null once one is followed, or with no trip). */
    val planning: StateFlow<RoutePlanning?> = _planning

    private val retrySignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var screens = 0
    private var watch: Job? = null
    private var job: Job? = null
    private var goneJob: Job? = null
    private var arrivalJob: Job? = null
    private var releaseLocation: (() -> Unit)? = null
    private var voice: NavVoice? = null
    private var appContext: Context? = null
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    // Following the route
    private var lastIndex = 0
    private var lastFixAt = 0L
    private var offRouteFixes = 0
    private var nextRerouteAt = 0L
    private var rerouteGap = REROUTE_EVERY_MS
    private var onRouteSince = 0L
    private var standingNearSince = 0L
    private var spokenSoon = -1
    private var spokenNow = -1

    /**
     * A car screen came up: the trip there is, or one set later, is guided while a screen is up. A trip broken off
     * (the car switched off on the way) goes on, without the places reached during the break. Back within a few
     * seconds (the screen rebuilt, the link dropped for a moment) the guidance simply goes on. Main thread.
     */
    fun screenShown(context: Context) {
        Trip.init(context)
        appContext = context.applicationContext
        if (screens++ > 0) return
        goneJob?.let {
            it.cancel()
            goneJob = null
            EventLog.log("Navigation: the car screen is back, guidance goes on")
            return
        }
        Trip.expireOld()
        var first = true
        watch = scope.launch {
            Trip.places.collect { places ->
                when {
                    places.isEmpty() -> pause()
                    job == null -> follow(resumed = first && Trip.interrupted)
                }
                first = false
            }
        }
    }

    /**
     * The car screen went away: after a few seconds without it the guidance stops, the trip stays for the next
     * connection. Meanwhile nothing is said. Main thread.
     */
    fun screenGone() {
        if (screens == 0) return
        if (--screens > 0) return
        voice?.silence()
        goneJob = scope.launch {
            delay(GONE_GRACE_MS)
            goneJob = null
            watch?.cancel()
            watch = null
            if (job != null) MapLocation.fix.value.let { Trip.interrupt(it?.latitude, it?.longitude) }
            // A choice nobody will see: not asked again at the next connection
            Trip.clearOffer()
            pause()
        }
    }

    /** The driver ends the trip. */
    fun end() {
        EventLog.log("Navigation: ended")
        Trip.clear()
    }

    /** The driver asks for the route again, after none could be found or the server could not be reached. */
    fun retry() {
        if (job != null) retrySignal.tryEmit(Unit)
    }

    private fun follow(resumed: Boolean) {
        val context = appContext ?: return
        // Guidance needs the position even with no map on screen
        releaseLocation = MapLocation.request(context)
        voice = NavVoice(context)
        // Nothing carried over from the last trip
        lastFixAt = 0L
        nextRerouteAt = 0L
        rerouteGap = REROUTE_EVERY_MS
        onRouteSince = 0L
        _planning.value = RoutePlanning.CALCULATING
        EventLog.log(if (resumed) "Navigation: trip resumed" else "Navigation: started")
        job = scope.launch {
            // Muted while speaking: quiet at once
            launch { LauncherSettings.get(context).guidanceVoice.collect { if (it == GuidanceVoice.MUTE) voice?.silence() } }
            val start = waitForFix()
            // After a short break the driver already heard the first instruction
            var quiet = false
            if (resumed) {
                quiet = Trip.breakMs() < Trip.MIN_BREAK_MS
                val dropped = Trip.resume(start.latitude, start.longitude)
                if (dropped > 0) EventLog.log("Navigation: $dropped place(s) reached during the break")
            }
            var firstTrip = true
            // A new route whenever the trip changes; until then, the way along this one
            Trip.places.collectLatest { places ->
                if (places.isEmpty()) return@collectLatest
                // A trip being edited (a stop dragged place by place) is routed once, when it settles
                if (!firstTrip) delay(TRIP_SETTLE_MS)
                firstTrip = false
                routeUntilFound(places, speakFirst = !quiet)
                quiet = false
                MapLocation.fix.filterNotNull().conflate().collect { onFix(it, places) }
            }
        }
    }

    private fun pause() {
        arrivalJob?.cancel()
        arrivalJob = null
        _planning.value = null
        if (job == null && _state.value == null) return
        job?.cancel()
        job = null
        releaseLocation?.invoke()
        releaseLocation = null
        voice?.shutdown()
        voice = null
        _state.value = null
        EventLog.log("Navigation: guidance off")
    }

    /** A position from now, not the one kept from the last time a map was open (maybe hours ago, elsewhere). */
    private suspend fun waitForFix(): MapFix =
        MapLocation.fix.filterNotNull().first { SystemClock.elapsedRealtime() - it.at < FRESH_FIX_MS }

    /**
     * Routes until a route is found: with no route at all, waits for the driver to ask again ([retry]); with the
     * server out of reach, tries again later and later (or at once when asked).
     */
    private suspend fun routeUntilFound(places: List<Destination>, speakFirst: Boolean) {
        var wait = RETRY_MS
        while (true) {
            when (route(waitForFix(), places, speakFirst)) {
                RouteResult.FOUND -> return
                RouteResult.NO_ROUTE -> {
                    // Nothing to follow: the old route would lead to the old trip
                    _state.value = null
                    _planning.value = RoutePlanning.NO_ROUTE
                    retrySignal.first()
                }
                RouteResult.FAILED -> {
                    _state.value = _state.value?.copy(rerouting = true)
                    _planning.value = RoutePlanning.OFFLINE
                    withTimeoutOrNull(wait) { retrySignal.first() }
                    wait = min(wait * 2, MAX_RETRY_MS)
                }
            }
            _planning.value = RoutePlanning.CALCULATING
        }
    }

    private enum class RouteResult { FOUND, NO_ROUTE, FAILED }

    /** A new route from [from] through [places]; why not when none could be had (logged). */
    private suspend fun route(from: MapFix, places: List<Destination>, speakFirst: Boolean = true): RouteResult {
        val context = appContext ?: return RouteResult.FAILED
        _state.value = _state.value?.copy(rerouting = true)
        val route = try {
            val text = requestRoute(from, places, AppLocales.voice(context).toLanguageTag())
            val raw = withContext(Dispatchers.Default) { NavRoute.fromValhalla(text) }
            if (raw.size < 2) throw RouteRefused("an empty route")
            withStopNames(context, raw, places)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouteRefused) {
            EventLog.log("Navigation: no route (${e.message})")
            _state.value = _state.value?.copy(rerouting = false)
            return RouteResult.NO_ROUTE
        } catch (e: Exception) {
            EventLog.log("Navigation: route server not reached (${e.message})")
            _state.value = _state.value?.copy(rerouting = false)
            return RouteResult.FAILED
        }
        EventLog.log("Navigation: route of ${(route.lengthM / 1000).format1()} km, ${route.maneuvers.size} maneuvers, ${places.size - 1} stop(s)")
        lastIndex = 0
        offRouteFixes = 0
        standingNearSince = 0L
        spokenSoon = -1
        spokenNow = -1
        arrivalJob?.cancel()
        arrivalJob = null
        _planning.value = null
        val first = route.maneuvers.firstOrNull()
        _state.value = NavState(
            route, route.maneuvers.getOrNull(1) ?: first, 0.0, route.lengthM, route.durationS,
            stop = places.takeIf { it.size > 1 }?.first(),
        )
        // After what was being said (a stop reached), not over it
        if (speakFirst) first?.let { say(it.spokenNow, alert = false, queue = true) }
        return RouteResult.FOUND
    }

    /** The server answered that there is no such route (a 4xx): asking again the same won't help. */
    private class RouteRefused(message: String) : IOException(message)

    private suspend fun requestRoute(from: MapFix, places: List<Destination>, language: String): String {
        val start = JSONObject().put("lat", from.latitude).put("lon", from.longitude)
        // Leave the way the car points, not behind it
        from.bearing?.let { start.put("heading", it.toInt()).put("heading_tolerance", 45) }
        val locations = JSONArray().put(start)
        // Every stop a "break": a leg of its own, with an arrival
        places.forEach { locations.put(JSONObject().put("lat", it.latitude).put("lon", it.longitude).put("type", "break")) }
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("directions_options", JSONObject().put("language", language).put("units", "kilometers"))
            .toString()
        val request = Request.Builder().url(ROUTE_URL)
            .header("User-Agent", "BetterAuto (https://github.com/simoneluconi/BetterAuto)")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).await()
    }

    /** The answer's text; the request is dropped as soon as nobody waits for it (the trip changed meanwhile). */
    private suspend fun Call.await(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val text = runCatching {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        when {
                            r.isSuccessful -> body
                            // Too many requests is the server's state, not the route's
                            r.code in 400..499 && r.code != 429 -> throw RouteRefused("HTTP ${r.code}")
                            else -> throw IOException("HTTP ${r.code}")
                        }
                    }
                }
                text.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }

    /** The arrivals at the stops named and said as stops (the server calls each one a destination). */
    private fun withStopNames(context: Context, route: NavRoute, places: List<Destination>): NavRoute {
        val res = AppLocales.voiceContext(context).resources
        val maneuvers = route.maneuvers.map { m ->
            val place = m.stop?.let { places.getOrNull(it) } ?: return@map m
            m.copy(
                instruction = place.name,
                street = place.name,
                spokenNow = res.getString(R.string.nav_spoken_stop_now, place.name),
                spokenSoon = res.getString(R.string.nav_spoken_stop_soon, place.name),
            )
        }
        return NavRoute(route.points, maneuvers, route.lengthM, route.durationS, route.legEnds)
    }

    private suspend fun onFix(fix: MapFix, places: List<Destination>) {
        val current = _state.value ?: return
        val route = current.route
        val now = SystemClock.elapsedRealtime()
        val speed = max(fix.speedMs?.toDouble() ?: 0.0, 0.0)
        val elapsedS = if (lastFixAt == 0L) 1.0 else ((now - lastFixAt) / 1000.0).coerceAtLeast(1.0)
        lastFixAt = now
        // The car moves on by so much since the last fix: a later part of the route passing close by (the way back
        // on the other carriageway) is not where it is. Long off the route it may have rejoined it anywhere
        val maxAhead = if (offRouteFixes >= OFF_ROUTE_FIXES) Double.MAX_VALUE else RouteProgress.aheadAllowance(speed, elapsedS)
        // The heading only when moving well: crawling in a queue or turning in a car park, it is noise
        val heading = fix.bearing.takeIf { speed > HEADING_MIN_MS }
        val progress = RouteProgress.locate(route, fix.latitude, fix.longitude, lastIndex, maxAhead, heading)

        // Standing a while by the next place: reached, even where the road the route ends on is out of reach (a
        // car park beside it)
        val target = places.first()
        val nearTarget = distanceM(fix.latitude, fix.longitude, target.latitude, target.longitude) < NEAR_PLACE_M
        if (nearTarget && speed < STANDING_MS) {
            if (standingNearSince == 0L) standingNearSince = now
        } else {
            standingNearSince = 0L
        }
        val stoodThere = standingNearSince != 0L && now - standingNearSince >= STOOD_NEAR_MS
        if (stoodThere && places.size > 1) return stopReached(places)

        // Off the route for a few fixes in a row: a new one from here, less and less often while it goes on
        if (progress.offRouteM > OFF_ROUTE_M) {
            onRouteSince = 0L
            if (stoodThere) return arrive()
            // An estimate (a tunnel) or a vague fix is no proof, and by the place the car is looking for a spot
            val reliable = fix.source != MapSource.ESTIMATED && (fix.accuracyM ?: 0f) <= OFF_ROUTE_M
            if (!reliable || nearTarget) return
            if (++offRouteFixes >= OFF_ROUTE_FIXES && now >= nextRerouteAt) {
                nextRerouteAt = now + rerouteGap
                rerouteGap = min(rerouteGap * 2, MAX_REROUTE_GAP_MS)
                EventLog.log("Navigation: off the route, a new one")
                route(fix, places)
            }
            return
        }
        offRouteFixes = 0
        if (onRouteSince == 0L) onRouteSince = now else if (now - onRouteSince > BACK_ON_ROUTE_MS) rerouteGap = REROUTE_EVERY_MS
        lastIndex = progress.index
        val along = progress.alongM
        // A stop reached: off the trip, which brings a route on to the next place
        val toStop = if (places.size > 1 && route.legEnds.size > 1) progress.cumulative[route.legEnds[0]] - along else null
        if (toStop != null && toStop < ARRIVED_M) return stopReached(places)
        val nextIndex = route.maneuvers.indexOfFirst { it.beginIndex > progress.index }
        val next = route.maneuvers.getOrNull(nextIndex)
        val toNext = if (next != null) progress.cumulative[next.beginIndex] - along else 0.0
        val end = progress.cumulative[route.size - 1]
        val remaining = (end - along).coerceAtLeast(0.0)
        val arrived = arrivalJob != null || (toStop == null && (next == null || remaining < ARRIVED_M || stoodThere))
        _state.value = NavState(
            route, next, toNext, remaining, route.durationS * remaining / end.coerceAtLeast(1.0), arrived,
            stop = places.takeIf { toStop != null }?.first(), toStopM = toStop ?: 0.0,
        )
        if (arrived) arrive()
        if (next == null) return
        // Spoken twice: well ahead (time to change lanes), then right before
        val pace = max(speed, MIN_SPEED_MS)
        if (nextIndex != spokenSoon && toNext < max(SOON_MIN_M, pace * SOON_S) && toNext > pace * NOW_S * 2) {
            spokenSoon = nextIndex
            appContext?.let { say(NavVoice.aheadPhrase(it, toNext, next.spokenSoon), alert = next.kind == ManeuverKind.ARRIVE) }
        }
        if (nextIndex != spokenNow && toNext < max(NOW_MIN_M, pace * NOW_S)) {
            spokenNow = nextIndex
            spokenSoon = nextIndex
            say(next.spokenNow, alert = next.kind == ManeuverKind.ARRIVE)
        }
    }

    private fun stopReached(places: List<Destination>) {
        EventLog.log("Navigation: stop reached")
        standingNearSince = 0L
        appContext?.let { say(AppLocales.voiceContext(it).resources.getString(R.string.nav_spoken_stop_reached, places[1].name), alert = true) }
        // By the place, not its index: the trip may have been changed meanwhile
        Trip.remove(places[0])
    }

    /** Arrived: the card says so a moment, then the trip is over (also when no more positions come). */
    private fun arrive() {
        if (arrivalJob != null) return
        EventLog.log("Navigation: arrived")
        _state.value = _state.value?.copy(arrived = true)
        arrivalJob = scope.launch {
            delay(ARRIVED_KEEP_MS)
            arrivalJob = null
            Trip.clear()
        }
    }

    /** Says [text] as the voice setting allows: everything, only the arrivals ([alert]), or nothing; never with no screen. */
    private fun say(text: String, alert: Boolean, queue: Boolean = false) {
        val context = appContext ?: return
        if (screens == 0) return
        when (LauncherSettings.get(context).guidanceVoice.value) {
            GuidanceVoice.FULL -> voice?.say(text, queue)
            GuidanceVoice.ALERTS -> if (alert) voice?.say(text, queue)
            GuidanceVoice.MUTE -> Unit
        }
    }

    private fun Double.format1() = "%.1f".format(this)

    /** The public demonstration server of Valhalla (FOSSGIS): fair use, for trying only. */
    private const val ROUTE_URL = "https://valhalla1.openstreetmap.de/route"
    private const val OFF_ROUTE_M = 45.0
    private const val OFF_ROUTE_FIXES = 3
    private const val REROUTE_EVERY_MS = 10_000L
    private const val MAX_REROUTE_GAP_MS = 80_000L

    /** Back on a route this long: the next time it is left, a new route comes quickly again. */
    private const val BACK_ON_ROUTE_MS = 30_000L
    private const val RETRY_MS = 10_000L
    private const val MAX_RETRY_MS = 120_000L
    private const val TRIP_SETTLE_MS = 1_000L
    private const val FRESH_FIX_MS = 10_000L
    private const val GONE_GRACE_MS = 8_000L
    private const val ARRIVED_M = 30.0
    private const val ARRIVED_KEEP_MS = 20_000L

    /** Standing this close to the next place for [STOOD_NEAR_MS]: reached. */
    private const val NEAR_PLACE_M = 80.0
    private const val STOOD_NEAR_MS = 15_000L
    private const val STANDING_MS = 1.5
    private const val HEADING_MIN_MS = 4.0
    private const val MIN_SPEED_MS = 8.0
    private const val SOON_S = 20.0
    private const val SOON_MIN_M = 250.0
    private const val NOW_S = 5.0
    private const val NOW_MIN_M = 40.0
}

/** Where the car is along a route: the point it has passed, how far along, and how far off the line. */
internal class RouteProgress(val index: Int, val alongM: Double, val offRouteM: Double, val cumulative: DoubleArray) {
    companion object {
        /**
         * Looks a little behind [fromIndex] and ahead of it, at most [maxAheadM] along the route: the car moves on
         * along the route, not backwards, and not farther than it can have driven. With the car's [bearing], a part
         * of the route going the other way (the way back on the other carriageway) is not taken.
         */
        fun locate(
            route: NavRoute,
            latitude: Double,
            longitude: Double,
            fromIndex: Int,
            maxAheadM: Double = Double.MAX_VALUE,
            bearing: Float? = null,
        ): RouteProgress {
            val cumulative = cumulative(route)
            val cosLat = cos(Math.toRadians(latitude))
            var best = Double.MAX_VALUE
            var bestIndex = fromIndex
            var bestAlong = cumulative.getOrElse(fromIndex) { 0.0 }
            val startI = (fromIndex - 2).coerceAtLeast(0)
            val limit = cumulative.getOrElse(fromIndex) { 0.0 } + maxAheadM
            for (i in startI until route.size - 1) {
                if (cumulative[i] > limit) break
                val ax = Math.toRadians(route.lon(i) - longitude) * cosLat * R
                val ay = Math.toRadians(route.lat(i) - latitude) * R
                val dx = Math.toRadians(route.lon(i + 1) - route.lon(i)) * cosLat * R
                val dy = Math.toRadians(route.lat(i + 1) - route.lat(i)) * R
                val len2 = dx * dx + dy * dy
                if (bearing != null && len2 > MIN_HEADING_LEN_M * MIN_HEADING_LEN_M) {
                    val segment = bearingDeg(route.lat(i), route.lon(i), route.lat(i + 1), route.lon(i + 1))
                    if (abs(turnDeg(bearing, segment)) > OPPOSITE_DEG) continue
                }
                val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy).div(len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx
                val py = ay + t * dy
                val d = sqrt(px * px + py * py)
                if (d < best) {
                    best = d
                    bestIndex = i
                    bestAlong = cumulative[i] + t * sqrt(len2)
                }
                // Far ahead along the route already: the car is not there yet
                if (cumulative[i] - cumulative[startI] > LOOK_AHEAD_M && best < 30) break
            }
            return RouteProgress(bestIndex, bestAlong, best, cumulative)
        }

        /** How far along the route the car can have gone in [seconds] at [speedMs] (generous: fixes are late, speeds wrong). */
        fun aheadAllowance(speedMs: Double, seconds: Double): Double = max(speedMs, MIN_ALLOWANCE_MS) * seconds * 2 + AHEAD_MARGIN_M

        private val cache = HashMap<NavRoute, DoubleArray>()

        private fun cumulative(route: NavRoute): DoubleArray = synchronized(cache) {
            cache.getOrPut(route) {
                if (cache.size > 4) cache.clear()
                val out = DoubleArray(route.size)
                for (i in 1 until route.size) {
                    val cosLat = cos(Math.toRadians(route.lat(i)))
                    val dx = Math.toRadians(route.lon(i) - route.lon(i - 1)) * cosLat * R
                    val dy = Math.toRadians(route.lat(i) - route.lat(i - 1)) * R
                    out[i] = out[i - 1] + sqrt(dx * dx + dy * dy)
                }
                out
            }
        }

        private const val R = 6_371_000.0
        private const val LOOK_AHEAD_M = 2_000.0
        private const val MIN_ALLOWANCE_MS = 15.0
        private const val AHEAD_MARGIN_M = 150.0

        /** Further from the car's way than this, a part of the route goes the other way. */
        private const val OPPOSITE_DEG = 120.0

        /** Shorter segments have no direction to speak of. */
        private const val MIN_HEADING_LEN_M = 10.0
    }
}

/**
 * The instructions, spoken as a navigation app speaks them (usage navigation guidance), in the voice language.
 * Safe from any thread: the engine reports ready on its own thread.
 */
internal class NavVoice(private val context: Context) {
    private val lock = Any()
    private var ready = false
    private var closed = false
    private val pending = ArrayList<String>()
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status != TextToSpeech.SUCCESS) {
            EventLog.log("Navigation: no text-to-speech ($status)")
            return@TextToSpeech
        }
        synchronized(lock) {
            if (closed) return@TextToSpeech
            ready = true
            configure()
            pending.forEach { speakNow(it, queue = true) }
            pending.clear()
        }
    }

    private fun configure() {
        tts.language = AppLocales.voice(context)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** Says [text], over what was being said unless [queue]. */
    fun say(text: String, queue: Boolean = false) {
        synchronized(lock) {
            if (closed) return
            if (ready) return speakNow(text, queue)
            if (!queue) pending.clear()
            pending.add(text)
        }
    }

    private fun speakNow(text: String, queue: Boolean) {
        EventLog.log("Navigation: speaking")
        tts.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    fun silence() {
        synchronized(lock) {
            pending.clear()
            if (ready && !closed) tts.stop()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            closed = true
            pending.clear()
        }
        tts.shutdown()
    }

    companion object {
        /**
         * "In 300 metres, [maneuver]" in the voice language (which may not be the app's), the distance rounded as a
         * driver hears it: 50 m steps near, 100 m farther, tenths of a km beyond.
         */
        fun aheadPhrase(context: Context, metres: Double, maneuver: String): String {
            val locale = AppLocales.voice(context)
            val res = AppLocales.voiceContext(context).resources
            if (metres >= 950) {
                val tenths = Math.round(metres / 100)
                return if (tenths % 10 == 0L) {
                    val km = (tenths / 10).toInt()
                    res.getQuantityString(R.plurals.nav_spoken_in_km, km, km, maneuver)
                } else {
                    res.getString(R.string.nav_spoken_in_km_decimal, "%.1f".format(locale, tenths / 10.0), maneuver)
                }
            }
            val step = if (metres < 300) 50 else 100
            val m = (Math.round(metres / step) * step).coerceAtLeast(step.toLong()).toInt()
            return res.getString(R.string.nav_spoken_in_metres, m, maneuver)
        }
    }
}
