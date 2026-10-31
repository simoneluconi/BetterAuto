package com.simoneluconi.betterauto

import android.content.Context
import com.simoneluconi.betterauto.car.Position
import com.simoneluconi.betterauto.car.Satellites
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleField
import com.simoneluconi.betterauto.car.VehicleSource
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.roads.RoadInfo
import com.simoneluconi.betterauto.map.roads.RoadMatcher
import com.simoneluconi.betterauto.map.roads.RoadSegment
import com.simoneluconi.betterauto.map.roads.TravelDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Debug: the car's GPS and speed driving around, for the map on the bench, once a second like a car, through
 * the demo source. [straight]: a line from a point, turning right every [TURN_EVERY_S] seconds. [onRoads]: along
 * the real roads of the map's road data, at their speed limit (or a usual speed for the kind of road), mostly
 * straight on at junctions with a turn now and then. In a [tunnel] the car keeps driving and sending its speed,
 * its GPS sees no satellites and the phone's location is ignored: the map's estimate takes over.
 */
object FakeMapDrive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var inTunnel = false

    fun straight(latitude: Double, longitude: Double, kmh: Double, bearing: Double) {
        stop(clear = false)
        TripRecorder.paused = true
        job = scope.launch {
            var lat = latitude
            var lon = longitude
            var heading = bearing
            var seconds = 0
            while (isActive) {
                val p = advance(lat, lon, heading, kmh / 3.6)
                lat = p.first
                lon = p.second
                if (++seconds % TURN_EVERY_S == 0) heading = (heading + 90) % 360
                publish(lat, lon, heading, kmh)
                delay(1_000L)
            }
        }
        EventLog.log("Debug: pretend car GPS driving at $kmh km/h")
    }

    fun onRoads(context: Context, latitude: Double, longitude: Double, kmh: Double?) {
        stop(clear = false)
        TripRecorder.paused = true
        RoadInfo.init(context)
        job = scope.launch {
            val roads = RoadInfo.roadsNear(latitude, longitude)
            // The nearest road vertex, however far (the start point need not be on a road); a through road within
            // a few hundred metres rather than a service lane, whose dead ends would turn the car round and round
            val vertices = roads.orEmpty().flatMap { r -> (0 until r.size).map { i -> Triple(r, i, distance(latitude, longitude, r.lat(i), r.lon(i))) } }
            val start = vertices.filter { it.first.roadClass !in MINOR && it.third < THROUGH_ROAD_M }.minByOrNull { it.third }
                ?: vertices.minByOrNull { it.third }
            if (start == null) {
                EventLog.log("Debug: no road data around the start point (${roads?.size ?: "no tile"}): pretend drive not started")
                return@launch
            }
            EventLog.log("Debug: pretend car driving on the roads${kmh?.let { " at $it km/h" } ?: " at their limits"}, from ${start.first.name ?: start.first.roadClass} (${roads?.size} roads around)")
            var road = start.first
            // The vertex just passed, and where between it and the next one the car is
            var index = start.second
            var forward = index < road.size - 1
            var lat = road.lat(index)
            var lon = road.lon(index)
            var heading = 0.0
            while (isActive) {
                // A chosen speed, but never more than a lane allows: 110 km/h in a car park spins the map round
                val speed = kmh?.let { minOf(it, maxSpeed(road)) } ?: usualSpeed(road, forward)
                var metres = speed / 3.6
                while (metres > 0.01) {
                    val next = if (forward) index + 1 else index - 1
                    if (next !in 0 until road.size) {
                        // The end of this piece of road: on to a road joining here
                        val turn = nextRoad(lat, lon, heading, road)
                        if (turn == null) {
                            // A dead end: turn around
                            forward = !forward
                            continue
                        }
                        road = turn.first
                        index = turn.second
                        forward = turn.third
                        continue
                    }
                    val d = distance(lat, lon, road.lat(next), road.lon(next))
                    heading = bearing(lat, lon, road.lat(next), road.lon(next))
                    if (d <= metres) {
                        lat = road.lat(next)
                        lon = road.lon(next)
                        index = next
                        metres -= d
                    } else {
                        val p = advance(lat, lon, heading, metres)
                        lat = p.first
                        lon = p.second
                        metres = 0.0
                    }
                }
                publish(lat, lon, heading, speed)
                delay(1_000L)
            }
        }
    }

    /** Into ([on]) or out of a tunnel: no GPS anywhere, the car's speed only. */
    fun tunnel(on: Boolean) {
        inTunnel = on
        MapLocation.ignorePhone(on)
        EventLog.log("Debug: pretend ${if (on) "tunnel: no GPS, the car's speed only" else "out of the tunnel"}")
    }

    fun stop(clear: Boolean = true) {
        job?.cancel()
        job = null
        if (clear) {
            TripRecorder.paused = false
            if (inTunnel) tunnel(false)
            VehicleData.publish(
                VehicleSource.DEMO,
                VehicleField.Location of null, VehicleField.GpsSatellites of null, VehicleField.Speed of null,
            )
            EventLog.log("Debug: pretend car GPS off")
        }
    }

    private fun publish(lat: Double, lon: Double, heading: Double, kmh: Double) {
        // In the tunnel the car keeps the last fix it had, as real ones do, with no satellites
        if (!inTunnel) {
            VehicleData.publish(
                VehicleSource.DEMO,
                VehicleField.Location of Position(lat, lon, 5.0, null, kmh, heading),
                VehicleField.GpsSatellites of Satellites(9, 12),
            )
        } else {
            VehicleData.publish(VehicleSource.DEMO, VehicleField.GpsSatellites of Satellites(0, 3))
        }
        VehicleData.publish(VehicleSource.DEMO, VehicleField.Speed of kmh)
    }

    /**
     * A road joining at the point: one with a vertex within a few metres, going on from there either way.
     * The rest of a road cut at a tile's edge first, then mostly straight on, now and then a turn; never back.
     */
    private suspend fun nextRoad(lat: Double, lon: Double, heading: Double, from: RoadSegment): Triple<RoadSegment, Int, Boolean>? {
        val roads = RoadInfo.roadsNear(lat, lon) ?: return null
        var best: Triple<RoadSegment, Int, Boolean>? = null
        var bestScore = Double.MAX_VALUE
        for (road in roads) {
            if (road === from) continue
            for (i in 0 until road.size) {
                if (distance(lat, lon, road.lat(i), road.lon(i)) > JOIN_M) continue
                for (forward in listOf(true, false)) {
                    val j = if (forward) i + 1 else i - 1
                    if (j !in 0 until road.size) continue
                    val turn = RoadMatcher.angleDiff(heading, bearing(road.lat(i), road.lon(i), road.lat(j), road.lon(j)))
                    if (turn > 150) continue
                    val same = road.id == from.id
                    // Service lanes only when nothing else goes on from here
                    val minor = if (road.roadClass in MINOR && from.roadClass !in MINOR) 500.0 else 0.0
                    val score = if (same) -1000.0 else turn + minor + Random.nextDouble(0.0, 60.0)
                    if (score < bestScore) {
                        bestScore = score
                        best = Triple(road, i, forward)
                    }
                }
            }
        }
        return best
    }

    /** The road's own limit, else what one usually drives there. */
    private fun usualSpeed(road: RoadSegment, forward: Boolean): Double {
        road.limitAt(0.5, if (forward) TravelDirection.FORWARD else TravelDirection.BACKWARD)?.let { return it.kmh }
        return when (road.roadClass) {
            "motorway" -> 120.0
            "trunk" -> 100.0
            "primary" -> 70.0
            "secondary" -> 60.0
            "tertiary", "unclassified" -> 50.0
            "residential" -> 30.0
            else -> 20.0
        }
    }

    /** The most a car does on this kind of road, whatever speed was asked for. */
    private fun maxSpeed(road: RoadSegment): Double = when (road.roadClass) {
        "service", "unknown", "living_street" -> 30.0
        "residential" -> 50.0
        else -> 200.0
    }

    private fun advance(lat: Double, lon: Double, bearingDeg: Double, metres: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        return (lat + metres * cos(b) / METRES_PER_DEGREE) to (lon + metres * sin(b) / (METRES_PER_DEGREE * cos(Math.toRadians(lat))))
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)) * METRES_PER_DEGREE
        val y = (lat2 - lat1) * METRES_PER_DEGREE
        return sqrt(x * x + y * y)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = lat2 - lat1
        return (Math.toDegrees(atan2(x, y)) + 360) % 360
    }

    private const val METRES_PER_DEGREE = 111_320.0
    private const val TURN_EVERY_S = 30
    private const val JOIN_M = 6.0
    private const val THROUGH_ROAD_M = 300.0

    /** Lanes into yards and car parks: dead ends more often than not. */
    private val MINOR = setOf("service", "unknown")
}
