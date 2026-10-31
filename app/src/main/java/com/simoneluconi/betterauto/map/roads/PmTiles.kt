package com.simoneluconi.betterauto.map.roads

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Reads byte ranges of a remote (or local) file: the only thing [PmTiles] needs from the network. */
fun interface RangeReader {
    /** [length] bytes from [offset]; throws [IOException] when it can't. */
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * A PMTiles v3 archive read a piece at a time (https://github.com/protomaps/PMTiles): the header, then the
 * directories down to the one tile wanted, then the tile. A big world file thus costs a few small requests
 * per area. Directories are kept in memory, tiles are the caller's to cache. Not thread-safe: one caller at a time.
 */
class PmTiles(private val reader: RangeReader) {
    class Header(
        val rootOffset: Long,
        val rootLength: Int,
        val leafOffset: Long,
        val dataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val minZoom: Int,
        val maxZoom: Int,
    )

    /** One directory entry: [runLength] 0 points to a leaf directory, otherwise to tile data. */
    internal data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private var header: Header? = null
    private var root: List<Entry>? = null
    private val leaves = LinkedHashMap<Long, List<Entry>>(16, 0.75f, true)

    fun header(): Header = header ?: parseHeader(reader.read(0, HEADER_BYTES)).also { header = it }

    /** The tile's bytes, uncompressed, or null when the archive has no such tile (open sea...). */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val h = header()
        if (z < h.minZoom || z > h.maxZoom) return null
        val id = tileId(z, x, y)
        var entries = root ?: readDirectory(h.rootOffset, h.rootLength, h).also { root = it }
        repeat(MAX_DEPTH) {
            val entry = find(entries, id) ?: return null
            if (entry.runLength > 0) {
                return decompress(reader.read(h.dataOffset + entry.offset, entry.length), h.tileCompression)
            }
            val leafAt = h.leafOffset + entry.offset
            entries = leaves[leafAt] ?: readDirectory(leafAt, entry.length, h).also {
                leaves[leafAt] = it
                if (leaves.size > MAX_LEAVES) leaves.remove(leaves.keys.first())
            }
        }
        return null
    }

    private fun readDirectory(offset: Long, length: Int, h: Header): List<Entry> =
        parseDirectory(decompress(reader.read(offset, length), h.internalCompression))

    companion object {
        const val HEADER_BYTES = 127
        private const val MAX_DEPTH = 4
        private const val MAX_LEAVES = 64

        internal fun parseHeader(bytes: ByteArray): Header {
            if (bytes.size < HEADER_BYTES || String(bytes, 0, 7, Charsets.US_ASCII) != "PMTiles" || bytes[7].toInt() != 3) {
                throw IOException("not a PMTiles v3 archive")
            }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return Header(
                rootOffset = b.getLong(8),
                rootLength = b.getLong(16).toInt(),
                leafOffset = b.getLong(40),
                dataOffset = b.getLong(56),
                internalCompression = bytes[97].toInt(),
                tileCompression = bytes[98].toInt(),
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF,
            )
        }

        /** Directory bytes: counts, then ids (delta), run lengths, lengths and offsets as varints, column by column. */
        internal fun parseDirectory(bytes: ByteArray): List<Entry> {
            val r = VarintReader(bytes)
            val n = r.next().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) {
                last += r.next()
                ids[i] = last
            }
            val runs = IntArray(n) { r.next().toInt() }
            val lengths = IntArray(n) { r.next().toInt() }
            val offsets = LongArray(n)
            for (i in 0 until n) {
                val v = r.next()
                // 0 means "right after the previous one"
                offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }

        /** The entry holding [id]: the last one starting at or before it, if its run (or leaf) covers it. */
        internal fun find(entries: List<Entry>, id: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (entries[mid].tileId <= id) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (found < 0) return null
            val e = entries[found]
            return if (e.runLength == 0 || id < e.tileId + e.runLength) e else null
        }

        /** The tile's number along the Hilbert curve of all zoom levels, as PMTiles orders them. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (t in 0 until z) acc += 1L shl (2 * t)
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if (tx and s > 0) 1L else 0L
                val ry = if (ty and s > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val swap = tx
                    tx = ty
                    ty = swap
                }
                s /= 2
            }
            return acc + d
        }

        private fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
            COMPRESSION_NONE, COMPRESSION_UNKNOWN -> bytes
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            else -> throw IOException("unsupported PMTiles compression $compression")
        }

        private const val COMPRESSION_UNKNOWN = 0
        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_GZIP = 2
    }
}

private class VarintReader(private val bytes: ByteArray) {
    private var i = 0

    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (i >= bytes.size) throw IOException("truncated PMTiles directory")
            val b = bytes[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }
}
