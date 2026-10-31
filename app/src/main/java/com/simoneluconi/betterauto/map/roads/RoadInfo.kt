package com.simoneluconi.betterauto.map.roads

import android.content.Context
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.map.MapFix
import com.simoneluconi.betterauto.map.MapLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** The road the car is on, for the map: what the signs would say. */
data class RoadState(val name: String?, val refs: List<RouteRef>, val limit: SpeedLimit?)

/**
 * The road under the car, while a map is on screen ([request]): the roads around [MapLocation]'s position
 * from Overture's tiles ([RoadTileStore]), the one the car is on picked by [RoadMatcher], its name, number and
 * speed limit published in [state]. Only what the data states: a road without a limit shows none.
 */
object RoadInfo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RoadState?>(null)
    val state: StateFlow<RoadState?> = _state

    private var users = 0
    private var job: Job? = null
    private var store: RoadTileStore? = null
    private val lock = Mutex()
    private val matcher = RoadMatcher()
    private var lastSegment: RoadSegment? = null

    /** Tiles in memory, the latest used last. */
    private val tiles = LinkedHashMap<Pair<Int, Int>, List<RoadSegment>>(16, 0.75f, true)

    /** Ready to read roads (for [roadsNear]); [request] does it too. */
    fun init(context: Context) {
        if (store == null) store = RoadTileStore(context.applicationContext)
    }

    /** Follows the road while the returned function is not called. Main thread. */
    fun request(context: Context): () -> Unit {
        init(context)
        if (users++ == 0) start()
        var released = false
        return {
            if (!released) {
                released = true
                if (--users == 0) stop()
            }
        }
    }

    private fun start() {
        matcher.reset()
        job = scope.launch {
            MapLocation.fix.filterNotNull().conflate().collect { fix ->
                runCatching { onFix(fix) }.onFailure { EventLog.log("Map: road lookup failed: ${it.message}") }
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        _state.value = null
    }

    private suspend fun onFix(fix: MapFix) {
        val roads = roadsNear(fix.latitude, fix.longitude) ?: return
        val match = matcher.match(roads, fix.latitude, fix.longitude, fix.bearing, fix.accuracyM)
        // What the data says about each road the car enters, without naming it (the log never says where the car is)
        if (match?.segment !== lastSegment) {
            lastSegment = match?.segment
            match?.segment?.let { s -> EventLog.log("Map: on a ${s.roadClass} road, ${s.limits.size} limit rule(s)${if (s.partial) ", piece of it" else ""}") }
        }
        val next = match?.let { RoadState(it.segment.nameAt(it.fraction), it.segment.refs, it.segment.limitAt(it.fraction, it.direction)) }
        if (next != _state.value) {
            if (next?.limit != _state.value?.limit) EventLog.log("Map: speed limit ${next?.limit?.let { "${it.value}${if (it.mph) " mph" else ""}" } ?: "unknown"}")
            _state.value = next
        }
    }

    /**
     * The roads within reach of a point: its tile, plus the next ones when it is near their edge (a road there
     * may be drawn only in the neighbour). Null when the tile itself can't be had (no network, nothing cached).
     */
    suspend fun roadsNear(latitude: Double, longitude: Double): List<RoadSegment>? = lock.withLock {
        val store = store ?: return null
        val wanted = tilesNear(latitude, longitude)
        val out = ArrayList<RoadSegment>()
        for ((i, t) in wanted.withIndex()) {
            val roads = tiles[t] ?: withContext(Dispatchers.IO) { store.roads(t.first, t.second) }?.also {
                tiles[t] = it
                while (tiles.size > MAX_TILES) tiles.remove(tiles.keys.first())
            }
            if (roads == null && i == 0) return null
            roads?.let { out += it }
        }
        out
    }

    /** The point's tile first, then the neighbours whose edge is within [EDGE_M]. */
    internal fun tilesNear(latitude: Double, longitude: Double): List<Pair<Int, Int>> {
        val n = 1 shl OvertureRoads.ZOOM
        val fx = (longitude + 180) / 360 * n
        val latR = Math.toRadians(latitude)
        val fy = (1 - ln(tan(latR) + 1 / cos(latR)) / PI) / 2 * n
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        // The edge margin in tile units: tiles are narrower in metres away from the equator
        val tileM = 40_075_016.0 * cos(latR) / n
        val margin = EDGE_M / tileM
        val dx = when {
            fx - x < margin -> -1
            x + 1 - fx < margin -> 1
            else -> 0
        }
        val dy = when {
            fy - y < margin -> -1
            y + 1 - fy < margin -> 1
            else -> 0
        }
        val out = mutableListOf(x to y)
        if (dx != 0) out += (x + dx) to y
        if (dy != 0) out += x to (y + dy)
        if (dx != 0 && dy != 0) out += (x + dx) to (y + dy)
        return out.map { (tx, ty) -> ((tx % n + n) % n) to ty.coerceIn(0, n - 1) }.distinct()
    }

    private const val MAX_TILES = 12
    private const val EDGE_M = 150.0
}
