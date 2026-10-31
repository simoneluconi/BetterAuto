package com.simoneluconi.betterauto.map.roads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class PmTilesTest {
    @Test
    fun tileIdsFollowTheHilbertCurve() {
        // The PMTiles spec's own examples
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
    }

    @Test
    fun readsATileThroughALeafDirectory() {
        val tileA = "first tile".toByteArray()
        val tileB = "second tile".toByteArray()
        // Two neighbouring tiles of central Rome, in the archive's order
        val (first, second) = listOf(8760 to 6087, 8761 to 6087).sortedBy { PmTiles.tileId(14, it.first, it.second) }
        val idA = PmTiles.tileId(14, first.first, first.second)
        val idB = PmTiles.tileId(14, second.first, second.second)
        val data = gzip(tileA) + gzip(tileB)
        val leaf = gzip(directory(listOf(Entry(idA, 0, gzip(tileA).size, 1), Entry(idB, gzip(tileA).size.toLong(), gzip(tileB).size, 1))))
        val root = gzip(directory(listOf(Entry(0, 0, leaf.size, 0))))
        val rootOffset = 127L
        val leafOffset = rootOffset + root.size
        val dataOffset = leafOffset + leaf.size
        val file = header(rootOffset, root.size, leafOffset, leaf.size, dataOffset, data.size) + root + leaf + data
        var reads = 0
        val archive = PmTiles { offset, length ->
            reads++
            file.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        assertArrayEquals(tileA, archive.tile(14, first.first, first.second))
        assertArrayEquals(tileB, archive.tile(14, second.first, second.second))
        // Header, root and leaf once, then one read per tile
        assertEquals(5, reads)
        assertNull(archive.tile(14, 0, 0))
    }

    @Test
    fun aTileOutsideEveryRunIsMissing() {
        val entries = PmTiles.parseDirectory(directory(listOf(Entry(10, 0, 5, 2), Entry(20, 5, 5, 1))))
        assertEquals(10L, PmTiles.find(entries, 11)!!.tileId)
        assertNull(PmTiles.find(entries, 12))
        assertNull(PmTiles.find(entries, 9))
    }

    private data class Entry(val id: Long, val offset: Long, val length: Int, val run: Int)

    private fun directory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var n = v
            while (n >= 0x80) {
                out.write(((n and 0x7F) or 0x80).toInt())
                n = n ushr 7
            }
            out.write(n.toInt())
        }
        varint(entries.size.toLong())
        var last = 0L
        for (e in entries) {
            varint(e.id - last)
            last = e.id
        }
        for (e in entries) varint(e.run.toLong())
        for (e in entries) varint(e.length.toLong())
        for (e in entries) varint(e.offset + 1)
        return out.toByteArray()
    }

    private fun header(rootOffset: Long, rootLength: Int, leafOffset: Long, leafLength: Int, dataOffset: Long, dataLength: Int): ByteArray {
        val b = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PMTiles".toByteArray())
        b.put(3)
        b.putLong(8, rootOffset)
        b.putLong(16, rootLength.toLong())
        b.putLong(40, leafOffset)
        b.putLong(48, leafLength.toLong())
        b.putLong(56, dataOffset)
        b.putLong(64, dataLength.toLong())
        b.put(97, 2)
        b.put(98, 2)
        b.put(100, 0)
        b.put(101, 14)
        return b.array()
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
}
