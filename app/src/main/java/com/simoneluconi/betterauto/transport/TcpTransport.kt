package com.simoneluconi.betterauto.transport

import com.simoneluconi.betterauto.EventLog
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The same byte pipe over TCP: a head unit listening on the network (a bench head unit in wireless
 * mode, later a wireless dongle). Unlike the USB accessory, short reads lose nothing here.
 */
class TcpTransport(private val socket: Socket, private val onClose: () -> Unit = {}) : Transport {
    override val kind = "TCP"

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun readFully(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = input.read(buf, off + done, len - done)
            if (n < 0) throw EOFException("TCP connection closed")
            done += n
        }
    }

    private var lastSlowLog = 0L

    @Synchronized
    override fun write(data: ByteArray) {
        val start = System.nanoTime()
        output.write(data)
        output.flush()
        // A write only blocks when the link backs up: the sign of a slow or stalling WiFi
        val end = System.nanoTime()
        val tookMs = (end - start) / 1_000_000
        if (tookMs >= SLOW_WRITE_MS && end - lastSlowLog > 1_000_000_000L) {
            lastSlowLog = end
            EventLog.log("TCP: sending ${data.size} bytes took $tookMs ms, the link is backing up")
        }
    }

    override fun close() {
        runCatching { socket.close() }
        onClose()
    }

    companion object {
        private const val SLOW_WRITE_MS = 300L

        /** Opens the connection; throws [IOException] if the head unit is not there within [timeoutMs]. */
        fun connect(host: String, port: Int, timeoutMs: Int): TcpTransport {
            val socket = Socket()
            try {
                // Video frames and acks are small and latency-bound: no Nagle delay
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            return TcpTransport(socket)
        }
    }
}
