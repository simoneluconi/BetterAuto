package com.simoneluconi.betterauto.aap

import aap_protobuf.service.media.source.message.AckOuterClass.Ack

/** MEDIA_MESSAGE_DATA starts with its timestamp in µs, 8 bytes big-endian: senders leave room for it in front. */
internal const val MEDIA_TIMESTAMP_BYTES = 8

internal fun putMediaTimestamp(payload: ByteArray, timestampUs: Long) {
    putU32(payload, 0, (timestampUs ushr 32).toInt())
    putU32(payload, 4, timestampUs.toInt())
}

/** How many sent messages a media ACK frees in the sender's window: at least one. */
internal fun ackedMessages(payload: ByteArray): Int {
    val ack = Ack.parseFrom(payload)
    return if (ack.hasAck()) ack.ack.coerceAtLeast(1) else 1
}
