package com.simoneluconi.betterauto.map.roads

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat

/**
 * The few parts of a Mapbox Vector Tile (https://github.com/mapbox/vector-tile-spec, v2) the roads need:
 * one layer's features, their attributes and their lines, in tile units (0..[Layer.extent]).
 * Read straight from the protobuf wire format, other layers are skipped without being decoded.
 */
object VectorTile {
    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    class Feature(
        val id: Long,
        val attributes: Map<String, Any>,
        /** Lines (or rings) as x, y pairs in tile units. */
        val lines: List<IntArray>,
    )

    /** The layer named [name] in [tile], or null when the tile has none. */
    fun layer(tile: ByteArray, name: String): Layer? {
        val input = CodedInputStream.newInstance(tile)
        while (true) {
            val tag = input.readTag()
            if (tag == 0) return null
            if (WireFormat.getTagFieldNumber(tag) == TILE_LAYERS) {
                val bytes = input.readByteArray()
                if (layerName(bytes) == name) return parseLayer(bytes)
            } else {
                input.skipField(tag)
            }
        }
    }

    private fun layerName(bytes: ByteArray): String? {
        val input = CodedInputStream.newInstance(bytes)
        while (true) {
            val tag = input.readTag()
            if (tag == 0) return null
            if (WireFormat.getTagFieldNumber(tag) == LAYER_NAME) return input.readString()
            input.skipField(tag)
        }
    }

    private class RawFeature(val id: Long, val tags: IntArray, val geometry: IntArray)

    private fun parseLayer(bytes: ByteArray): Layer {
        val input = CodedInputStream.newInstance(bytes)
        var name = ""
        var extent = DEFAULT_EXTENT
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val raw = ArrayList<RawFeature>()
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                LAYER_NAME -> name = input.readString()
                LAYER_FEATURES -> raw += parseFeature(input.readByteArray())
                LAYER_KEYS -> keys += input.readString()
                LAYER_VALUES -> values += parseValue(input.readByteArray())
                LAYER_EXTENT -> extent = input.readUInt32()
                else -> input.skipField(tag)
            }
        }
        val features = raw.map { f ->
            val attributes = HashMap<String, Any>(f.tags.size / 2)
            var i = 0
            while (i + 1 < f.tags.size) {
                val key = keys.getOrNull(f.tags[i])
                val value = values.getOrNull(f.tags[i + 1])
                if (key != null && value != null) attributes[key] = value
                i += 2
            }
            Feature(f.id, attributes, decodeLines(f.geometry))
        }
        return Layer(name, extent, features)
    }

    private fun parseFeature(bytes: ByteArray): RawFeature {
        val input = CodedInputStream.newInstance(bytes)
        var id = 0L
        var tags = IntArray(0)
        var geometry = IntArray(0)
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                FEATURE_ID -> id = input.readUInt64()
                FEATURE_TAGS -> tags = readPacked(input, tag)
                FEATURE_GEOMETRY -> geometry = readPacked(input, tag)
                else -> input.skipField(tag)
            }
        }
        return RawFeature(id, tags, geometry)
    }

    private fun readPacked(input: CodedInputStream, tag: Int): IntArray {
        if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) return intArrayOf(input.readUInt32())
        val limit = input.pushLimit(input.readRawVarint32())
        val out = ArrayList<Int>()
        while (input.bytesUntilLimit > 0) out += input.readUInt32()
        input.popLimit(limit)
        return out.toIntArray()
    }

    private fun parseValue(bytes: ByteArray): Any {
        val input = CodedInputStream.newInstance(bytes)
        var value: Any = ""
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            value = when (WireFormat.getTagFieldNumber(tag)) {
                1 -> input.readString()
                2 -> input.readFloat().toDouble()
                3 -> input.readDouble()
                4 -> input.readInt64()
                5 -> input.readUInt64()
                6 -> input.readSInt64()
                7 -> input.readBool()
                else -> {
                    input.skipField(tag)
                    value
                }
            }
        }
        return value
    }

    /** MoveTo / LineTo / ClosePath commands with zig-zag deltas, into one x,y array per line. */
    internal fun decodeLines(geometry: IntArray): List<IntArray> {
        val lines = ArrayList<IntArray>()
        var current = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        while (i < geometry.size) {
            val command = geometry[i] and 0x7
            val count = geometry[i] ushr 3
            i++
            when (command) {
                CMD_MOVE_TO, CMD_LINE_TO -> {
                    repeat(count) {
                        // A truncated geometry: keep what was complete
                        if (i + 1 >= geometry.size) return lines
                        x += zigZag(geometry[i])
                        y += zigZag(geometry[i + 1])
                        i += 2
                        if (command == CMD_MOVE_TO) {
                            if (current.size >= 4) lines += current.toIntArray()
                            current = ArrayList()
                        }
                        current += x
                        current += y
                    }
                }
                CMD_CLOSE_PATH -> if (current.size >= 2) {
                    current += current[0]
                    current += current[1]
                }
                else -> return lines
            }
        }
        if (current.size >= 4) lines += current.toIntArray()
        return lines
    }

    private fun zigZag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private const val TILE_LAYERS = 3
    private const val LAYER_NAME = 1
    private const val LAYER_FEATURES = 2
    private const val LAYER_KEYS = 3
    private const val LAYER_VALUES = 4
    private const val LAYER_EXTENT = 5
    private const val FEATURE_ID = 1
    private const val FEATURE_TAGS = 2
    private const val FEATURE_GEOMETRY = 4
    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7
    private const val DEFAULT_EXTENT = 4096
}
