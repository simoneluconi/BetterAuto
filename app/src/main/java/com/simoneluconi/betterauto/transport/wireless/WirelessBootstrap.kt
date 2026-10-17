package com.simoneluconi.betterauto.transport.wireless

import aap_protobuf.aaw.MessageIdOuterClass.MessageId
import aap_protobuf.aaw.StatusOuterClass.Status
import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiInfoRequestOuterClass.WifiInfoRequest
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import aap_protobuf.aaw.WifiSecurityModeOuterClass.WifiSecurityMode
import com.google.protobuf.MessageLite
import com.simoneluconi.betterauto.EventLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What the head unit (or dongle) tells the phone over Bluetooth: its WiFi, and where its AAP server listens. */
data class WifiEndpoint(
    val ssid: String,
    val password: String,
    val bssid: String,
    val security: WifiSecurityMode,
    val host: String,
    val port: Int,
)

/**
 * The phone side of the wireless Android Auto bootstrap, over the Bluetooth RFCOMM channel the head unit
 * (or dongle) offers. Frames are `[u16 length][u16 message id][protobuf]`, big-endian, the length
 * counting the protobuf only. The head unit leads:
 *
 *   HU -> WifiVersionRequest (newer units only)   phone -> WifiVersionResponse
 *   HU -> WifiStartRequest (its IP and port)      phone -> WifiInfoRequest
 *   HU -> WifiInfoResponse (SSID, password, ...)  phone -> WifiStartResponse
 *   (the phone joins the WiFi and opens TCP)      phone -> WifiConnectionStatus
 */
class WirelessBootstrap(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)

    /** Runs the exchange up to the credentials; blocks until they arrive or the stream breaks. */
    fun negotiate(): WifiEndpoint {
        var host: String? = null
        var port = 0
        while (true) {
            val length = input.readUnsignedShort()
            val id = input.readUnsignedShort()
            val payload = ByteArray(length).also { input.readFully(it) }
            when (id) {
                MessageId.WIFI_VERSION_REQUEST_VALUE -> {
                    // It waits for the answer before anything else: agree on its own version
                    val request = WifiVersionRequest.parseFrom(payload)
                    val major = if (request.hasMajorVersion()) request.majorVersion else 1
                    val minor = if (request.hasMinorVersion()) request.minorVersion else 0
                    EventLog.log("Wireless: head unit bootstrap version $major.$minor")
                    send(
                        MessageId.WIFI_VERSION_RESPONSE_VALUE,
                        WifiVersionResponse.newBuilder().setMajorVersion(major).setMinorVersion(minor)
                            .setStatus(Status.STATUS_SUCCESS).build(),
                    )
                }
                MessageId.WIFI_START_REQUEST_VALUE -> {
                    val request = WifiStartRequest.parseFrom(payload)
                    host = request.ipAddress
                    port = request.port
                    EventLog.log("Wireless: head unit at $host:$port, asking for its WiFi")
                    send(MessageId.WIFI_INFO_REQUEST_VALUE, WifiInfoRequest.getDefaultInstance())
                }
                MessageId.WIFI_INFO_RESPONSE_VALUE -> {
                    val info = WifiInfoResponse.parseFrom(payload)
                    val address = host ?: throw IOException("WiFi credentials arrived before the head unit's address")
                    send(
                        MessageId.WIFI_START_RESPONSE_VALUE,
                        WifiStartResponse.newBuilder().setIpAddress(address).setPort(port).setStatus(Status.STATUS_SUCCESS).build(),
                    )
                    EventLog.log("Wireless: WiFi \"${info.ssid}\" (${info.bssid}), ${info.securityMode}")
                    return WifiEndpoint(info.ssid, info.password, info.bssid, info.securityMode, address, port)
                }
                else -> EventLog.log("Wireless: message ${MessageId.forNumber(id) ?: id} ignored (${payload.size} bytes)")
            }
        }
    }

    /** After the WiFi join and the TCP connection: tells the head unit how it went. */
    fun reportConnection(status: Status) {
        send(MessageId.WIFI_CONNECTION_STATUS_VALUE, WifiConnectionStatus.newBuilder().setStatus(status).build())
    }

    private fun send(id: Int, message: MessageLite) {
        val bytes = message.toByteArray()
        synchronized(output) {
            output.writeShort(bytes.size)
            output.writeShort(id)
            output.write(bytes)
            output.flush()
        }
    }
}
