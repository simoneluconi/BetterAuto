package com.simoneluconi.betterauto.transport

import java.io.Closeable

/** Byte pipe to the head unit (USB accessory today, TCP for wireless later). */
interface Transport : Closeable {
    /** How it reaches the head unit, for the log and the debug overlay ("USB", "TCP"). */
    val kind: String

    /** Blocks until exactly [len] bytes are read; throws [java.io.EOFException] on disconnect. */
    fun readFully(buf: ByteArray, off: Int = 0, len: Int = buf.size)

    /** Writes [len] bytes of [data] from [off] in one go (the USB accessory sends each write as one transfer). */
    fun write(data: ByteArray, off: Int = 0, len: Int = data.size)
}
