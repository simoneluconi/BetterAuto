package com.simoneluconi.betterauto.aap

import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.transport.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Splits AAP messages into frames and back.
 *
 * Frame: [channel:1][flags:1][frameSize:2] (+[totalSize:4] on a FIRST frame) [payload:frameSize]
 * Encrypted frames are encrypted one by one, so frameSize is the ciphertext size
 * while totalSize is the plaintext size of the whole message.
 */
class FrameCodec(private val transport: Transport, private val tls: AapTls) {
    private val partial = HashMap<Int, ByteArrayOutputStream>()
    private val announced = HashMap<Int, Long>()
    private val header = ByteArray(4)
    private val totalSize = ByteArray(4)

    /** Blocks until a complete message is received. */
    fun read(): AapMessage {
        while (true) {
            transport.readFully(header)
            val channel = header[0].toInt() and 0xff
            val flags = header[1].toInt() and 0xff
            val frameType = flags and Aap.FLAG_BULK
            val frameSize = u16(header, 2)
            if (frameType == Aap.FLAG_FIRST) {
                transport.readFully(totalSize)
                val total = u32(totalSize, 0)
                if (total > MAX_MESSAGE_SIZE) throw IOException("Message of $total bytes announced on channel $channel")
                announced[channel] = total
            }

            val data = ByteArray(frameSize)
            transport.readFully(data)
            val plain = if (flags and Aap.FLAG_ENCRYPTED != 0) tls.decrypt(data) else data

            if (frameType == Aap.FLAG_BULK) return toMessage(channel, flags, plain)

            val buf = if (frameType == Aap.FLAG_FIRST) {
                ByteArrayOutputStream().also { partial[channel] = it }
            } else {
                partial[channel] ?: throw IOException("Continuation frame without FIRST on channel $channel")
            }
            buf.write(plain)
            if (buf.size() > MAX_MESSAGE_SIZE) throw IOException("Message on channel $channel exceeds $MAX_MESSAGE_SIZE bytes")
            if (frameType == Aap.FLAG_LAST) {
                partial.remove(channel)
                val total = announced.remove(channel)
                if (total != null && total != buf.size().toLong()) {
                    EventLog.log("Channel $channel: message of ${buf.size()} bytes, $total announced")
                }
                return toMessage(channel, flags, buf.toByteArray())
            }
        }
    }

    @Synchronized
    fun write(msg: AapMessage) {
        val plain = ByteArray(2 + msg.payload.size)
        putU16(plain, 0, msg.id)
        msg.payload.copyInto(plain, 2)

        var baseFlags = 0
        if (msg.encrypted) baseFlags = baseFlags or Aap.FLAG_ENCRYPTED
        if (msg.controlType) baseFlags = baseFlags or Aap.FLAG_CONTROL

        if (plain.size <= Aap.MAX_FRAME_PAYLOAD) {
            writeFrame(msg.channel, baseFlags or Aap.FLAG_BULK, seal(plain, msg.encrypted), null)
            return
        }
        var offset = 0
        while (offset < plain.size) {
            val n = minOf(Aap.MAX_FRAME_PAYLOAD, plain.size - offset)
            val frameType = when {
                offset == 0 -> Aap.FLAG_FIRST
                offset + n == plain.size -> Aap.FLAG_LAST
                else -> 0
            }
            val chunk = seal(plain.copyOfRange(offset, offset + n), msg.encrypted)
            writeFrame(msg.channel, baseFlags or frameType, chunk, if (frameType == Aap.FLAG_FIRST) plain.size else null)
            offset += n
        }
    }

    private fun seal(data: ByteArray, encrypted: Boolean) = if (encrypted) tls.encrypt(data) else data

    private fun writeFrame(channel: Int, flags: Int, data: ByteArray, total: Int?) {
        val headerSize = if (total != null) 8 else 4
        val frame = ByteArray(headerSize + data.size)
        frame[0] = channel.toByte()
        frame[1] = flags.toByte()
        putU16(frame, 2, data.size)
        if (total != null) putU32(frame, 4, total)
        data.copyInto(frame, headerSize)
        transport.write(frame)
    }

    private fun toMessage(channel: Int, flags: Int, plain: ByteArray): AapMessage {
        if (plain.size < 2) throw IOException("Message shorter than its id on channel $channel")
        return AapMessage(
            channel = channel,
            id = u16(plain, 0),
            payload = plain.copyOfRange(2, plain.size),
            encrypted = flags and Aap.FLAG_ENCRYPTED != 0,
            controlType = flags and Aap.FLAG_CONTROL != 0,
        )
    }

    private companion object {
        /** Far above anything the protocol carries (a cover is a few hundred KB): a corrupt header must not allocate. */
        const val MAX_MESSAGE_SIZE = 16L shl 20
    }
}

internal fun u32(b: ByteArray, off: Int): Long = (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()

internal fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

internal fun putU16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v ushr 8).toByte()
    b[off + 1] = v.toByte()
}

internal fun putU32(b: ByteArray, off: Int, v: Int) {
    putU16(b, off, v ushr 16)
    putU16(b, off + 2, v)
}
