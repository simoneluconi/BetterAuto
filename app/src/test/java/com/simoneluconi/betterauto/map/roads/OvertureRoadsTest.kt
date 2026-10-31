package com.simoneluconi.betterauto.map.roads

import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Overture's road tiles, as written by their tiling: a "segment" layer, structured attributes as JSON text. */
class OvertureRoadsTest {
    @Test
    fun decodesTheRoadsOfATile() {
        val tile = tile(
            "connector" to listOf(feature(mapOf("id" to "c1"), listOf(10, 10))),
            "segment" to listOf(
                feature(
                    mapOf(
                        "id" to "road-1", "subtype" to "road", "class" to "primary", "@name" to "Via Roma",
                        "routes" to """[{"ref":"SS67","network":"IT:national"}]""",
                        "speed_limits" to """[{"max_speed":{"unit":"km/h","value":50}}]""",
                    ),
                    listOf(100, 100, 3000, 100),
                ),
                feature(mapOf("id" to "foot-1", "subtype" to "road", "class" to "footway"), listOf(100, 200, 3000, 200)),
                feature(mapOf("id" to "rail-1", "subtype" to "rail", "class" to "tram"), listOf(100, 300, 3000, 300)),
                // Going past the tile's edge into its margin: only a piece of the road
                feature(mapOf("id" to "road-2", "subtype" to "road", "class" to "residential"), listOf(2000, 2000, 4200, 2000)),
            ),
        )
        val roads = OvertureRoads.parse(tile, 14, 8760, 6087)
        assertEquals(listOf("road-1", "road-2"), roads.map { it.id })
        val road = roads[0]
        assertEquals("Via Roma", road.name)
        assertEquals(listOf(RouteRef("SS67", "IT:national")), road.refs)
        assertEquals(SpeedLimit(50, mph = false), road.limitAt(0.5, TravelDirection.FORWARD))
        assertFalse(road.partial)
        assertTrue(roads[1].partial)
        // Tile units back to degrees: inside the tile's own bounds
        val west = OvertureRoads.tileLon(14, 8760.0)
        val east = OvertureRoads.tileLon(14, 8761.0)
        assertTrue(road.lon(0) in west..east)
        assertTrue(road.lat(0) < OvertureRoads.tileLat(14, 6087.0) && road.lat(0) > OvertureRoads.tileLat(14, 6088.0))
    }

    @Test
    fun limitRulesKeepOnlyWhatHoldsForAnyCar() {
        val rules = OvertureRoads.limitRules(
            """[{"max_speed":{"unit":"km/h","value":30},"between":[0.05,1.0]},
               {"max_speed":{"unit":"km/h","value":70},"when":{"heading":"forward"}},
               {"max_speed":{"unit":"km/h","value":80},"when":{"vehicle":[{"dimension":"weight"}]}},
               {"max_speed":{"unit":"mph","value":30},"when":{"heading":"backward"}}]""",
        )
        assertEquals(
            listOf(
                LimitRule(SpeedLimit(30, false), 0.05, 1.0, null),
                LimitRule(SpeedLimit(70, false), 0.0, 1.0, TravelDirection.FORWARD),
                LimitRule(SpeedLimit(30, true), 0.0, 1.0, TravelDirection.BACKWARD),
            ),
            rules,
        )
        assertTrue(OvertureRoads.limitRules("not json").isEmpty())
    }

    @Test
    fun namesAndNumbers() {
        assertEquals(listOf(RouteRef("RA 3", "IT:B-road")), OvertureRoads.routeRefs("""[{"ref":"RA 3","name":"Raccordo","network":"IT:B-road"}]"""))
        assertTrue(OvertureRoads.routeRefs("""[{"name":"no number"}]""").isEmpty())
        // The country's own number first, the European route after it, each once
        assertEquals(
            listOf("A1", "E 35"),
            OvertureRoads.routeRefs("""[{"ref":"E 35","network":"e-road"},{"ref":"A1","network":"IT:A-road"},{"ref":"A1","network":"IT:A-road"}]""").map { it.ref },
        )
        val names = OvertureRoads.nameRules(
            """{"rules":[{"variant":"common","value":"Viale del Giardino","between":[0.0,0.85]},
               {"variant":"common","value":"Piazzale del Giardino","between":[0.85,1.0]},
               {"variant":"common","value":"Zoo Avenue","language":"en","between":[0.0,1.0]}],"primary":"Viale del Giardino"}""",
        )
        assertEquals(listOf("Viale del Giardino", "Piazzale del Giardino"), names.map { it.name })
    }

    @Test
    fun roadNumbersInTheirCountrysColours() {
        // Italy: motorways and the link roads run as motorways green, every other numbered road blue
        assertEquals(ShieldKind.GREEN, shieldKind("A11", "IT:A-road"))
        assertEquals(ShieldKind.GREEN, shieldKind("RA1", "IT:A-road"))
        assertEquals(ShieldKind.BLUE, shieldKind("RA 3", "IT:B-road"))
        assertEquals(ShieldKind.BLUE, shieldKind("SP73", "IT:SI"))
        assertEquals(ShieldKind.BLUE, shieldKind("SR66", "IT:Toscana"))
        assertEquals(ShieldKind.GREEN, shieldKind("E 45", "e-road"))
        assertEquals(ShieldKind.BLUE, shieldKind("A7", "DE:BAB"))
        assertEquals(ShieldKind.YELLOW, shieldKind("B27", "DE:BS"))
        assertEquals(ShieldKind.RED, shieldKind("N7", "FR:N-road"))
        assertEquals(ShieldKind.YELLOW, shieldKind("D907", "FR:D-road"))
        assertEquals(ShieldKind.UK_PRIMARY, shieldKind("A303", "GB:A-road"))
        // Not known here: neutral rather than wrong
        assertEquals(ShieldKind.NEUTRAL, shieldKind("B17", "AT:B-road"))
        assertEquals(ShieldKind.NEUTRAL, shieldKind("SP12", null))
    }

    @Test
    fun releasesFromTheBucketListing() {
        val listing = "<ListBucketResult><CommonPrefixes><Prefix>tiles/2026-09-23.0/</Prefix></CommonPrefixes>" +
            "<CommonPrefixes><Prefix>tiles/2026-10-21.0/</Prefix></CommonPrefixes><Prefix>tiles/</Prefix></ListBucketResult>"
        assertEquals(listOf("2026-09-23.0", "2026-10-21.0"), RoadTileStore.releases(listing))
    }

    @Test
    fun tilesNearAnEdgeIncludeTheNeighbour() {
        // The middle of a tile: that tile alone
        val (x, y) = OvertureRoads.tileOf(43.7696, 11.2558)
        val midLon = OvertureRoads.tileLon(14, x + 0.5)
        val midLat = OvertureRoads.tileLat(14, y + 0.5)
        assertEquals(listOf(x to y), RoadInfo.tilesNear(midLat, midLon))
        // A few metres from its west edge: the tile to the west too
        val westLon = OvertureRoads.tileLon(14, x + 0.01)
        assertEquals(listOf(x to y, (x - 1) to y), RoadInfo.tilesNear(midLat, westLon))
    }

    // --- A minimal vector tile writer, for these tests only ---

    private class Feature(val attributes: Map<String, String>, val line: List<Int>)

    private fun feature(attributes: Map<String, String>, line: List<Int>) = Feature(attributes, line)

    private fun tile(vararg layers: Pair<String, List<Feature>>): ByteArray {
        val out = ByteArrayOutputStream()
        val tile = CodedOutputStream.newInstance(out)
        for ((name, features) in layers) tile.writeByteArray(3, layer(name, features))
        tile.flush()
        return out.toByteArray()
    }

    private fun layer(name: String, features: List<Feature>): ByteArray {
        val keys = features.flatMap { it.attributes.keys }.distinct()
        val values = features.flatMap { it.attributes.values }.distinct()
        return message { o ->
            o.writeUInt32(15, 2)
            o.writeString(1, name)
            for (f in features) o.writeByteArray(2, feature(f, keys, values))
            for (k in keys) o.writeString(3, k)
            for (v in values) o.writeByteArray(4, message { it.writeString(1, v) })
            o.writeUInt32(5, 4096)
        }
    }

    private fun feature(f: Feature, keys: List<String>, values: List<String>): ByteArray = message { o ->
        o.writeByteArray(2, packed(f.attributes.flatMap { (k, v) -> listOf(keys.indexOf(k), values.indexOf(v)) }))
        o.writeUInt32(3, 2)
        // MoveTo the first point, LineTo the others, as zig-zag deltas
        val geometry = ArrayList<Int>()
        var px = 0
        var py = 0
        val points = f.line.chunked(2)
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) geometry += (1 and 0x7) or (1 shl 3)
            if (i == 1) geometry += (2 and 0x7) or ((points.size - 1) shl 3)
            geometry += zigZag(x - px)
            geometry += zigZag(y - py)
            px = x
            py = y
        }
        o.writeByteArray(4, packed(geometry))
    }

    private fun zigZag(n: Int) = (n shl 1) xor (n shr 31)

    private fun packed(values: List<Int>): ByteArray = message { o -> values.forEach { o.writeUInt32NoTag(it) } }

    private fun message(write: (CodedOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val o = CodedOutputStream.newInstance(out)
        write(o)
        o.flush()
        return out.toByteArray()
    }
}
