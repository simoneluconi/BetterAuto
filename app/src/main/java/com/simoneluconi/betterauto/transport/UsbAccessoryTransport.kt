package com.simoneluconi.betterauto.transport

import android.os.ParcelFileDescriptor
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream

class UsbAccessoryTransport(private val fd: ParcelFileDescriptor) : Transport {
    private val input = FileInputStream(fd.fileDescriptor)
    private val output = FileOutputStream(fd.fileDescriptor)

    // The accessory driver works per USB bulk transfer: always read full 16 KiB blocks
    // and serve smaller reads from this buffer, short reads can drop the rest of a transfer.
    private val readBuf = ByteArray(16 * 1024)
    private var readPos = 0
    private var readEnd = 0

    override fun readFully(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            if (readPos == readEnd) {
                val n = input.read(readBuf)
                if (n < 0) throw EOFException("USB accessory closed")
                readPos = 0
                readEnd = n
            }
            val n = minOf(len - done, readEnd - readPos)
            System.arraycopy(readBuf, readPos, buf, off + done, n)
            readPos += n
            done += n
        }
    }

    @Synchronized
    override fun write(data: ByteArray) {
        output.write(data)
    }

    override fun close() {
        fd.close()
    }
}
