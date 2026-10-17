package com.simoneluconi.betterauto.aap

import com.simoneluconi.betterauto.transport.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.X509TrustManager

class AapProtocolTest {

    @Test
    fun tlsHandshakeAndRecordsWithHeadUnitClient() {
        val phone = phoneTls()
        val headUnit = headUnitEngine()

        // aasdk sends the ClientHello first, then each side answers until done
        var toPhone = pump(headUnit, ByteArray(0))
        var rounds = 0
        while (!phone.handshakeComplete || headUnit.handshakeStatus != HandshakeStatus.NOT_HANDSHAKING) {
            val toHeadUnit = phone.handshake(toPhone)
            toPhone = pump(headUnit, toHeadUnit)
            assertTrue("handshake does not converge", ++rounds < 10)
        }
        assertEquals("TLSv1.2", headUnit.session.protocol)

        val fromHeadUnit = "service discovery".toByteArray()
        assertArrayEquals(fromHeadUnit, phone.decrypt(wrap(headUnit, fromHeadUnit)))

        val fromPhone = ByteArray(Aap.MAX_FRAME_PAYLOAD) { it.toByte() }
        assertArrayEquals(fromPhone, unwrap(headUnit, phone.encrypt(fromPhone)))
    }

    @Test
    fun largeMessageIsSplitIntoFramesAndReassembled() {
        val pipe = LoopbackTransport()
        val codec = FrameCodec(pipe, phoneTls())
        val payload = ByteArray(40_000) { (it * 7).toByte() }

        codec.write(AapMessage(channel = 3, id = 0x8001, payload = payload, encrypted = false))

        // FIRST frame header: channel, flags, frame size, total size (id + payload)
        val raw = pipe.peek()
        assertEquals(3, raw[0].toInt())
        assertEquals(Aap.FLAG_FIRST, raw[1].toInt())
        assertEquals(Aap.MAX_FRAME_PAYLOAD, u16(raw, 2))
        assertEquals(2 + payload.size, (u16(raw, 4) shl 16) or u16(raw, 6))

        val msg = codec.read()
        assertEquals(3, msg.channel)
        assertEquals(0x8001, msg.id)
        assertArrayEquals(payload, msg.payload)
    }

    @Test
    fun encryptedFrameCarriesTlsRecordOfIdAndPayload() {
        val phone = phoneTls()
        val headUnit = headUnitEngine()
        var toPhone = pump(headUnit, ByteArray(0))
        while (!phone.handshakeComplete) toPhone = pump(headUnit, phone.handshake(toPhone))

        val pipe = LoopbackTransport()
        FrameCodec(pipe, phone).write(AapMessage(0, 5, byteArrayOf(1, 2, 3), encrypted = true))

        val raw = pipe.peek()
        assertEquals(Aap.FLAG_BULK or Aap.FLAG_ENCRYPTED, raw[1].toInt())
        val record = raw.copyOfRange(4, 4 + u16(raw, 2))
        assertArrayEquals(byteArrayOf(0, 5, 1, 2, 3), unwrap(headUnit, record))
    }

    @Test(expected = IOException::class)
    fun continuationFrameWithoutFirstIsRejected() {
        val pipe = LoopbackTransport()
        pipe.write(byteArrayOf(3, Aap.FLAG_LAST.toByte(), 0, 2, 0, 1))
        FrameCodec(pipe, phoneTls()).read()
    }

    @Test(expected = IOException::class)
    fun absurdAnnouncedSizeIsRejectedBeforeAnyAllocation() {
        val pipe = LoopbackTransport()
        pipe.write(byteArrayOf(3, Aap.FLAG_FIRST.toByte(), 0, 2, 0x7f, -1, -1, -1, 0, 1))
        FrameCodec(pipe, phoneTls()).read()
    }

    @Test(expected = IOException::class)
    fun messageShorterThanItsIdIsRejected() {
        val pipe = LoopbackTransport()
        pipe.write(byteArrayOf(3, Aap.FLAG_BULK.toByte(), 0, 1, 7))
        FrameCodec(pipe, phoneTls()).read()
    }

    /** Like [AapTls.fromAssets]: every PEM file in the assets, whatever its name. */
    private fun phoneTls() = AapTls(File("src/main/assets").listFiles()!!.filter { it.extension == "pem" }.map { it.readText() })

    /** TLS 1.2 client without verification, like aasdk's SSLWrapper. */
    private fun headUnitEngine(): SSLEngine {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(trustAll), null) }
            .createSSLEngine().apply {
                useClientMode = true
                beginHandshake()
            }
    }

    /** Runs the client handshake as far as [input] allows and returns what it wants to send. */
    private fun pump(engine: SSLEngine, input: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(input)
        val out = ByteArrayOutputStream()
        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val net = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> {
                    net.clear()
                    engine.wrap(ByteBuffer.allocate(0), net)
                    out.write(net.array(), 0, net.position())
                }
                HandshakeStatus.NEED_UNWRAP -> {
                    if (!src.hasRemaining()) return out.toByteArray()
                    engine.unwrap(src, app)
                }
                HandshakeStatus.NEED_TASK -> engine.delegatedTask?.run()
                else -> return out.toByteArray()
            }
        }
    }

    private fun wrap(engine: SSLEngine, plain: ByteArray): ByteArray {
        val net = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.wrap(plain), net)
        return net.array().copyOf(net.position())
    }

    private fun unwrap(engine: SSLEngine, record: ByteArray): ByteArray {
        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
        engine.unwrap(ByteBuffer.wrap(record), app)
        return app.array().copyOf(app.position())
    }

    private class LoopbackTransport : Transport {
        override val kind = "loopback"
        private val buffer = ByteArrayOutputStream()
        private var readPos = 0

        fun peek(): ByteArray = buffer.toByteArray().copyOfRange(readPos, buffer.size())

        override fun readFully(buf: ByteArray, off: Int, len: Int) {
            val data = buffer.toByteArray()
            if (readPos + len > data.size) throw EOFException()
            data.copyInto(buf, off, readPos, readPos + len)
            readPos += len
        }

        override fun write(data: ByteArray) = buffer.write(data)

        override fun close() = Unit
    }
}
