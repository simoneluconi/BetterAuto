package com.simoneluconi.betterauto.transport

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** The TCP pipe on loopback: the AAP layers above rely on readFully and on whole writes. */
class TcpTransportTest {
    private val server = ServerSocket(0)
    private lateinit var peer: Socket

    @After
    fun close() {
        runCatching { peer.close() }
        server.close()
    }

    private fun connect(): TcpTransport {
        var accepted: Socket? = null
        val acceptor = thread { accepted = server.accept() }
        val transport = TcpTransport.connect("127.0.0.1", server.localPort, 2000)
        acceptor.join(2000)
        peer = accepted!!
        return transport
    }

    @Test
    fun readFullyWaitsForWritesSplitInPieces() {
        val transport = connect()
        val message = ByteArray(5000) { it.toByte() }
        thread {
            peer.getOutputStream().apply {
                write(message, 0, 7)
                flush()
                Thread.sleep(20)
                write(message, 7, message.size - 7)
                flush()
            }
        }
        val received = ByteArray(message.size)
        transport.readFully(received)
        assertArrayEquals(message, received)
    }

    @Test(expected = EOFException::class)
    fun aClosedPeerEndsTheRead() {
        val transport = connect()
        peer.close()
        transport.readFully(ByteArray(4))
    }

    @Test
    fun concurrentWritesStayWhole() {
        val transport = connect()
        val writers = (1..4).map { id ->
            thread { repeat(50) { transport.write(ByteArray(64) { id.toByte() }) } }
        }
        val total = 4 * 50 * 64
        val received = ByteArray(total)
        val input = peer.getInputStream()
        var read = 0
        while (read < total) read += input.read(received, read, total - read)
        writers.forEach { it.join() }
        // Every 64-byte block came from one writer only
        for (block in 0 until total / 64) {
            val first = received[block * 64]
            assertTrue((0 until 64).all { received[block * 64 + it] == first })
        }
    }

    @Test
    fun closeUnblocksAWaitingRead() {
        val transport = connect()
        var failure: Throwable? = null
        val reader = thread {
            try {
                transport.readFully(ByteArray(4))
            } catch (e: IOException) {
                failure = e
            }
        }
        Thread.sleep(100)
        transport.close()
        reader.join(2000)
        assertTrue(failure is IOException)
        assertEquals("TCP", transport.kind)
    }

    @Test
    fun closeRunsTheCleanup() {
        val socket = Socket("127.0.0.1", server.localPort)
        peer = server.accept()
        var cleaned = false
        TcpTransport(socket, onClose = { cleaned = true }).close()
        assertTrue(cleaned)
        assertTrue(socket.isClosed)
    }

    @Test(expected = IOException::class)
    fun nobodyListeningFailsFast() {
        val port = ServerSocket(0).use { it.localPort }
        TcpTransport.connect("127.0.0.1", port, 1000)
    }
}
