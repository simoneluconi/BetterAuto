package com.simoneluconi.betterauto.aap

import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.exlap.ExlapClient
import com.simoneluconi.betterauto.exlap.ExlapSettings

/**
 * A car maker's own channel. Its payload is raw bytes, without the usual 2-byte message id; the only one
 * we speak is VW's Exlap ([ExlapClient]), and only when it is enabled in the phone settings.
 */
class VendorExtensionChannel(
    session: AapSession,
    channelId: Int,
    private val vendorName: String,
) : ServiceChannel(session, channelId) {
    override val name = "vendor extension"

    private val exlap: ExlapClient? = ExlapSettings.current
        ?.takeIf { vendorName.contains("exlap", ignoreCase = true) }
        ?.let { settings -> ExlapClient(settings.credentials(), { settings.status.value = it }, ::sendRaw) }

    override fun onOpened() {
        when {
            exlap == null -> EventLog.log("Vendor extension \"$vendorName\" opened, not used")
            ExlapSettings.current?.enabled?.value != true -> EventLog.log("Exlap: off in the phone settings")
            else -> exlap.start()
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        if (exlap == null || ExlapSettings.current?.enabled?.value != true) return
        // Raw bytes: the first two were read as a message id, put them back
        val raw = ByteArray(2 + msg.payload.size)
        putU16(raw, 0, msg.id)
        msg.payload.copyInto(raw, 2)
        exlap.onText(raw.toString(Charsets.UTF_8))
    }

    override fun close() {
        exlap?.stop()
    }

    /** The bytes as they are: the first two travel in the message id slot of the frame. */
    private fun sendRaw(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        send(u16(bytes, 0), bytes.copyOfRange(2, bytes.size))
    }
}
