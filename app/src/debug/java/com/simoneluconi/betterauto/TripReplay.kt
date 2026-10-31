package com.simoneluconi.betterauto

import android.content.Context
import android.util.Xml
import com.simoneluconi.betterauto.car.FieldValue
import com.simoneluconi.betterauto.car.Gear
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.Position
import com.simoneluconi.betterauto.car.Satellites
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleField
import com.simoneluconi.betterauto.car.VehicleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Debug: plays a drive again as the car's data, through the demo source, with its own timing ([speed] times
 * faster): one recorded by [TripRecorder] (everything the car said, tunnels included) or a GPX track from
 * anywhere (position only; speed and heading worked out from the points, the GPS always seeing satellites).
 */
object TripReplay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** [what]: "latest", a recorded trip's name, or a path to a .gpx file on the phone. */
    fun play(context: Context, what: String, speed: Double) {
        stop()
        val file = when {
            what == "latest" -> TripRecorder.trips(context).firstOrNull()
            what.endsWith(".gpx", ignoreCase = true) -> File(what)
            else -> File(File(context.filesDir, "trips"), what)
        }
        if (file == null || !file.canRead()) {
            EventLog.log("Debug: nothing to replay (${what})")
            return
        }
        val events = runCatching { if (file.name.endsWith(".gpx", true)) gpx(file) else recorded(file) }
            .onFailure { EventLog.log("Debug: ${file.name} unreadable: ${it.message}") }
            .getOrNull() ?: return
        EventLog.log("Debug: replaying ${file.name} (${events.size} readings, ${(events.lastOrNull()?.first ?: 0) / 1000} s) at ${speed}x")
        TripRecorder.paused = true
        job = scope.launch {
            val start = System.currentTimeMillis()
            for ((t, publish) in events) {
                val wait = (t / speed).toLong() - (System.currentTimeMillis() - start)
                if (wait > 0) delay(wait)
                publish()
            }
            EventLog.log("Debug: replay of ${file.name} finished")
            TripRecorder.paused = false
        }
    }

    fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
        TripRecorder.paused = false
        VehicleData.publish(
            VehicleSource.DEMO,
            VehicleField.Location of null, VehicleField.GpsSatellites of null, VehicleField.Speed of null,
            VehicleField.CurrentGear of null, VehicleField.Compass of null, VehicleField.Rotation of null, VehicleField.SteeringAngle of null,
        )
        EventLog.log("Debug: replay stopped")
    }

    private fun recorded(file: File): List<Pair<Long, () -> Unit>> = file.readLines().mapNotNull { line ->
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
        val t = o.optLong("t")
        val action: (() -> Unit)? = when {
            o.has("pos") -> o.getJSONArray("pos").let { a ->
                publishing(VehicleField.Location of Position(a.getDouble(0), a.getDouble(1), a.num(2), a.num(3), a.num(4), a.num(5)))
            }
            o.has("sat") -> o.getJSONArray("sat").let { a -> publishing(VehicleField.GpsSatellites of Satellites(a.getInt(0), a.num(1)?.toInt())) }
            o.has("spd") -> publishing(VehicleField.Speed of o.getDouble("spd"))
            o.has("gear") -> runCatching { GearPosition.valueOf(o.getString("gear")) }.getOrNull()?.let { g ->
                publishing(VehicleField.CurrentGear of Gear(g, null))
            }
            o.has("mot") -> o.getJSONArray("mot").let { a ->
                val rotation = a.num(1)?.let { x -> Triple(x, a.num(2) ?: 0.0, a.num(3) ?: 0.0) }
                publishing(VehicleField.Compass of a.num(0), VehicleField.Rotation of rotation, VehicleField.SteeringAngle of a.num(4))
            }
            else -> null
        }
        action?.let { t to it }
    }

    /** A GPX track: each point a car fix, speed and heading from the way to the next point. */
    private fun gpx(file: File): List<Pair<Long, () -> Unit>> {
        class Point(val lat: Double, val lon: Double, val time: Long?)
        val points = ArrayList<Point>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var lat = 0.0
            var lon = 0.0
            var time: Long? = null
            var inPoint = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "trkpt", "rtept" -> {
                            inPoint = true
                            lat = parser.getAttributeValue(null, "lat").toDouble()
                            lon = parser.getAttributeValue(null, "lon").toDouble()
                            time = null
                        }
                        "time" -> if (inPoint) time = runCatching { Instant.parse(parser.nextText().trim()).toEpochMilli() }.getOrNull()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "trkpt" || parser.name == "rtept") {
                        points += Point(lat, lon, time)
                        inPoint = false
                    }
                }
            }
        }
        if (points.size < 2) return emptyList()
        // Without times (a planned route) the car goes at 50 km/h
        val start = points.first().time
        var t = 0L
        return points.mapIndexed { i, p ->
            val next = points.getOrElse(i + 1) { p }
            val prev = points.getOrElse(i - 1) { p }
            val metres = distance(p.lat, p.lon, next.lat, next.lon)
            if (i > 0) {
                t = if (start != null && p.time != null) p.time - start else t + (distance(prev.lat, prev.lon, p.lat, p.lon) / (50 / 3.6) * 1000).toLong()
            }
            val seconds = if (p.time != null && next.time != null && next.time > p.time) (next.time - p.time) / 1000.0 else metres / (50 / 3.6)
            val kmh = if (seconds > 0) metres / seconds * 3.6 else 0.0
            val heading = bearing(p.lat, p.lon, next.lat, next.lon)
            t to publishing(
                VehicleField.Location of Position(p.lat, p.lon, 5.0, null, kmh, heading),
                VehicleField.GpsSatellites of Satellites(9, 12),
                VehicleField.Speed of kmh,
            )
        }
    }

    /** Publishing these readings as the car's, later. */
    private fun publishing(vararg changes: FieldValue<*>): () -> Unit = { VehicleData.publish(VehicleSource.DEMO, changes.toList()) }

    private fun JSONArray.num(i: Int): Double? = if (isNull(i) || opt(i) == "null") null else optDouble(i).takeUnless { it.isNaN() }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)) * 111_320.0
        val y = (lat2 - lat1) * 111_320.0
        return sqrt(x * x + y * y)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return (Math.toDegrees(atan2(x, lat2 - lat1)) + 360) % 360
    }
}
