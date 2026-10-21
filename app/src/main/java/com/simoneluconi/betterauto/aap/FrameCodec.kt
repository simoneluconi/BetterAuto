package com.simoneluconi.betterauto.aap

import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.transport.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

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

    // Reused on every frame: read() runs on the session thread only, write() under the lock
    private val frameIn = ByteArray(MAX_FRAME_SIZE)
    private val id = ByteArray(2)
    private val idBuffer = ByteBuffer.wrap(id)
    private var frameOut = ByteBuffer.allocate(0)

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

            transport.readFully(frameIn, 0, frameSize)
            val plain = if (flags and Aap.FLAG_ENCRYPTED != 0) tls.decrypt(frameIn, 0, frameSize) else ByteBuffer.wrap(frameIn, 0, frameSize)

            if (frameType == Aap.FLAG_BULK) return toMessage(channel, flags, plain.array(), plain.position(), plain.limit())

            val buf = if (frameType == Aap.FLAG_FIRST) {
                ByteArrayOutputStream().also { partial[channel] = it }
            } else {
                partial[channel] ?: throw IOException("Continuation frame without FIRST on channel $channel")
            }
            buf.write(plain.array(), plain.position(), plain.remaining())
            if (buf.size() > MAX_MESSAGE_SIZE) throw IOException("Message on channel $channel exceeds $MAX_MESSAGE_SIZE bytes")
            if (frameType == Aap.FLAG_LAST) {
                partial.remove(channel)
                val total = announced.remove(channel)
                if (total != null && total != buf.size().toLong()) {
                    EventLog.log("Channel $channel: message of ${buf.size()} bytes, $total announced")
                }
                val message = buf.toByteArray()
                return toMessage(channel, flags, message, 0, message.size)
            }
        }
    }

    /**
     * The message as frames: [id][payload] split every [Aap.MAX_FRAME_PAYLOAD] bytes. Each frame is sealed
     * straight from the payload into one reused buffer behind its header, so a video frame is not copied again.
     */
    @Synchronized
    fun write(msg: AapMessage) {
        putU16(id, 0, msg.id)
        val size = 2 + msg.payload.size

        var baseFlags = 0
        if (msg.encrypted) baseFlags = baseFlags or Aap.FLAG_ENCRYPTED
        if (msg.controlType) baseFlags = baseFlags or Aap.FLAG_CONTROL

        if (size <= Aap.MAX_FRAME_PAYLOAD) {
            writeFrame(msg, baseFlags or Aap.FLAG_BULK, 0, size, null)
            return
        }
        var offset = 0
        while (offset < size) {
            val n = minOf(Aap.MAX_FRAME_PAYLOAD, size - offset)
            val frameType = when {
                offset == 0 -> Aap.FLAG_FIRST
                offset + n == size -> Aap.FLAG_LAST
                else -> 0
            }
            writeFrame(msg, baseFlags or frameType, offset, n, if (frameType == Aap.FLAG_FIRST) size else null)
            offset += n
        }
    }

    /** Bytes [offset, offset + n) of the message's [id][payload] as one frame. */
    private fun writeFrame(msg: AapMessage, flags: Int, offset: Int, n: Int, total: Int?) {
        val headerSize = if (total != null) 8 else 4
        val sources = if (offset == 0) {
            idBuffer.clear()
            arrayOf(idBuffer, ByteBuffer.wrap(msg.payload, 0, n - 2))
        } else {
            arrayOf(ByteBuffer.wrap(msg.payload, offset - 2, n))
        }
        val room = headerSize + if (msg.encrypted) tls.sealedSize(n) else n
        if (frameOut.capacity() < room) frameOut = ByteBuffer.allocate(room)
        val out = frameOut
        out.clear()
        out.position(headerSize)
        if (msg.encrypted) tls.encrypt(sources, out) else sources.forEach { out.put(it) }

        val frame = out.array()
        frame[0] = msg.channel.toByte()
        frame[1] = flags.toByte()
        putU16(frame, 2, out.position() - headerSize)
        if (total != null) putU32(frame, 4, total)
        transport.write(frame, 0, out.position())
    }

    /** The message in bytes [from, to) of [plain]: its id, then the payload, copied out of the reused buffer. */
    private fun toMessage(channel: Int, flags: Int, plain: ByteArray, from: Int, to: Int): AapMessage {
        if (to - from < 2) throw IOException("Message shorter than its id on channel $channel")
        return AapMessage(
            channel = channel,
            id = u16(plain, from),
            payload = plain.copyOfRange(from + 2, to),
            encrypted = flags and Aap.FLAG_ENCRYPTED != 0,
            controlType = flags and Aap.FLAG_CONTROL != 0,
        )
    }

    private companion object {
        /** Far above anything the protocol carries (a cover is a few hundred KB): a corrupt header must not allocate. */
        const val MAX_MESSAGE_SIZE = 16L shl 20

        /** The frame size field is 16 bits. */
        const val MAX_FRAME_SIZE = 0xffff
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
