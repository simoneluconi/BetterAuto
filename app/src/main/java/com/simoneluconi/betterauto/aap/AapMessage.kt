package com.simoneluconi.betterauto.aap

/** Frame layout constants, mirrored from the headers in aasdk include/aasdk/Messenger. */
object Aap {
    const val CONTROL_CHANNEL = 0

    /** Messages larger than this are split into FIRST / MIDDLE / LAST frames. */
    const val MAX_FRAME_PAYLOAD = 0x4000

    // Second byte of the frame header
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_BULK = FLAG_FIRST or FLAG_LAST
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08
}

/**
 * A complete, decrypted AAP message.
 * On the wire the payload is preceded by the 2-byte big-endian [id].
 * [controlType] marks control messages (channel open, ...) sent on a service channel.
 */
class AapMessage(
    val channel: Int,
    val id: Int,
    val payload: ByteArray,
    val encrypted: Boolean,
    val controlType: Boolean = false,
)
