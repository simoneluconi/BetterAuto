package com.simoneluconi.betterauto.map.roads

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Roads from Overture Maps' transportation tiles (https://docs.overturemaps.org/examples/overture-tiles/):
 * the "segment" layer, every attribute kept by Overture's own tiling, structured ones as JSON text. Overture's
 * transportation data comes from OpenStreetMap (ODbL). Only roads a car drives on are kept.
 */
object OvertureRoads {
    const val ZOOM = 14

    /** The roads in one tile ([z]/[x]/[y]) as decoded from [tile]. */
    fun parse(tile: ByteArray, z: Int, x: Int, y: Int): List<RoadSegment> {
        val layer = VectorTile.layer(tile, "segment") ?: return emptyList()
        val out = ArrayList<RoadSegment>()
        for (f in layer.features) {
            val a = f.attributes
            if (a["subtype"] != "road") continue
            val roadClass = a["class"] as? String ?: continue
            if (roadClass !in DRIVABLE) continue
            val id = a["id"] as? String ?: continue
            val name = (a["@name"] as? String)?.takeIf { it.isNotBlank() }
            val refs = (a["routes"] as? String)?.let(::routeRefs).orEmpty()
            val limits = (a["speed_limits"] as? String)?.let(::limitRules).orEmpty()
            val names = (a["names"] as? String)?.let(::nameRules).orEmpty()
            for (line in f.lines) {
                // Cut at the tile's edge (the tile keeps a margin beyond it): not the whole road
                val partial = f.lines.size > 1 || line.indices.step(2).any { i ->
                    line[i] <= 0 || line[i] >= layer.extent || line[i + 1] <= 0 || line[i + 1] >= layer.extent
                }
                val points = DoubleArray(line.size)
                for (i in line.indices step 2) {
                    points[i] = tileLat(z, y + line[i + 1].toDouble() / layer.extent)
                    points[i + 1] = tileLon(z, x + line[i].toDouble() / layer.extent)
                }
                out += RoadSegment(id, name, refs, roadClass, points, limits, partial, names)
            }
        }
        return out
    }

    /**
     * The numbers of `[{"ref":"A1","network":"IT:A-road"},{"ref":"E 35","network":"e-road"}]`: the country's own
     * first, then a European route, two at most (what fits on the road's pill), each once.
     */
    internal fun routeRefs(json: String): List<RouteRef> = runCatching {
        val routes = JSONArray(json)
        val all = (0 until routes.length()).mapNotNull { i ->
            val r = routes.optJSONObject(i) ?: return@mapNotNull null
            val ref = r.optString("ref").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RouteRef(ref, r.optString("network").takeIf { it.isNotBlank() })
        }.distinctBy { it.ref }
        all.sortedBy { if (it.network.equals("e-road", ignoreCase = true)) 1 else 0 }.take(MAX_REFS)
    }.getOrDefault(emptyList())

    /**
     * `[{"max_speed":{"value":50,"unit":"km/h"},"between":[0.0,0.68],"when":{"heading":"forward"}}]`. A rule for some
     * vehicles, times or conditions only ("when" with anything but the heading) is left out: it may not be this car's.
     */
    internal fun limitRules(json: String): List<LimitRule> = runCatching {
        val rules = JSONArray(json)
        (0 until rules.length()).mapNotNull { i ->
            val r = rules.optJSONObject(i) ?: return@mapNotNull null
            val max = r.optJSONObject("max_speed") ?: return@mapNotNull null
            val value = max.optInt("value", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val mph = max.optString("unit") == "mph"
            val (from, to) = between(r)
            val whenRule = r.optJSONObject("when")
            var direction: TravelDirection? = null
            if (whenRule != null) {
                if (whenRule.keys().asSequence().any { it != "heading" }) return@mapNotNull null
                direction = when (whenRule.optString("heading")) {
                    "forward" -> TravelDirection.FORWARD
                    "backward" -> TravelDirection.BACKWARD
                    else -> null
                }
            }
            LimitRule(SpeedLimit(value, mph), from, to, direction)
        }
    }.getOrDefault(emptyList())

    /** The common names along parts of the road: `{"rules":[{"variant":"common","value":"...","between":[0,0.85]}]}`. */
    internal fun nameRules(json: String): List<NameRule> = runCatching {
        val rules = JSONObject(json).optJSONArray("rules") ?: return@runCatching emptyList()
        (0 until rules.length()).mapNotNull { i ->
            val r = rules.optJSONObject(i) ?: return@mapNotNull null
            if (r.optString("variant") != "common" || !r.has("between") || r.has("language")) return@mapNotNull null
            val (from, to) = between(r)
            NameRule(r.optString("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null, from, to)
        }
    }.getOrDefault(emptyList())

    private fun between(r: JSONObject): Pair<Double, Double> {
        val b = r.optJSONArray("between") ?: return 0.0 to 1.0
        return b.optDouble(0, 0.0) to b.optDouble(1, 1.0)
    }

    /** The z/x/y tile holding a point. */
    fun tileOf(latitude: Double, longitude: Double, z: Int = ZOOM): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((longitude + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        val latR = Math.toRadians(latitude)
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(latR) + 1 / kotlin.math.cos(latR)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }

    /** Longitude of a (fractional) tile column. */
    fun tileLon(z: Int, x: Double): Double = x / (1 shl z) * 360 - 180

    /** Latitude of a (fractional) tile row. */
    fun tileLat(z: Int, y: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    private const val MAX_REFS = 2

    /** Roads a car drives on; paths, steps, footways and the like are not. */
    private val DRIVABLE = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "residential", "living_street", "unclassified", "service", "unknown",
    )
}
