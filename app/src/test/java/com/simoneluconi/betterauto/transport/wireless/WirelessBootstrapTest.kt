package com.simoneluconi.betterauto.transport.wireless

import aap_protobuf.aaw.MessageIdOuterClass.MessageId
import aap_protobuf.aaw.StatusOuterClass.Status
import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import aap_protobuf.aaw.WifiSecurityModeOuterClass.WifiSecurityMode
import com.google.protobuf.MessageLite
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** The bootstrap against a scripted dongle: what the phone answers, and what it learns. */
class WirelessBootstrapTest {
    private fun frame(out: DataOutputStream, id: Int, message: MessageLite) {
        val bytes = message.toByteArray()
        out.writeShort(bytes.size)
        out.writeShort(id)
        out.write(bytes)
    }

    /** What the dongle sends, in order, as one stream. */
    private fun dongle(vararg frames: Pair<Int, MessageLite>): ByteArrayInputStream {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out -> frames.forEach { (id, m) -> frame(out, id, m) } }
        return ByteArrayInputStream(buffer.toByteArray())
    }

    /** What the phone sent: (message id, payload) frames. */
    private fun sent(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val frames = ArrayList<Pair<Int, ByteArray>>()
        while (input.available() > 0) {
            val length = input.readUnsignedShort()
            val id = input.readUnsignedShort()
            frames += id to ByteArray(length).also { input.readFully(it) }
        }
        return frames
    }

    private val start = MessageId.WIFI_START_REQUEST_VALUE to
        WifiStartRequest.newBuilder().setIpAddress("192.168.4.1").setPort(5288).build()
    private val info = MessageId.WIFI_INFO_RESPONSE_VALUE to WifiInfoResponse.newBuilder()
        .setSsid("AndroidAuto-1234").setPassword("secret-pass").setBssid("02:11:22:33:44:55")
        .setSecurityMode(WifiSecurityMode.WPA2_PERSONAL).build()

    @Test
    fun learnsTheWifiAndAnswersInOrder() {
        val phone = ByteArrayOutputStream()
        val bootstrap = WirelessBootstrap(dongle(start, info), phone)
        val endpoint = bootstrap.negotiate()

        assertEquals(WifiEndpoint("AndroidAuto-1234", "secret-pass", "02:11:22:33:44:55", WifiSecurityMode.WPA2_PERSONAL, "192.168.4.1", 5288), endpoint)
        val frames = sent(phone.toByteArray())
        assertEquals(listOf(MessageId.WIFI_INFO_REQUEST_VALUE, MessageId.WIFI_START_RESPONSE_VALUE), frames.map { it.first })
        val response = WifiStartResponse.parseFrom(frames[1].second)
        assertEquals("192.168.4.1", response.ipAddress)
        assertEquals(5288, response.port)
        assertEquals(Status.STATUS_SUCCESS, response.status)

        bootstrap.reportConnection(Status.STATUS_SUCCESS)
        val last = sent(phone.toByteArray()).last()
        assertEquals(MessageId.WIFI_CONNECTION_STATUS_VALUE, last.first)
        assertEquals(Status.STATUS_SUCCESS, WifiConnectionStatus.parseFrom(last.second).status)
    }

    @Test
    fun unknownMessagesAreSkipped() {
        val ping = 9 to WifiStartRequest.newBuilder().setIpAddress("x").setPort(1).build()
        val endpoint = WirelessBootstrap(dongle(ping, start, info), ByteArrayOutputStream()).negotiate()
        assertEquals("AndroidAuto-1234", endpoint.ssid)
    }

    @Test
    fun aVersionRequestIsAnsweredFirstWithItsVersion() {
        // Newer dongles (AAWireless) open with it and wait for the answer
        val version = MessageId.WIFI_VERSION_REQUEST_VALUE to WifiVersionRequest.newBuilder().setMajorVersion(1).setMinorVersion(3).build()
        val phone = ByteArrayOutputStream()
        WirelessBootstrap(dongle(version, start, info), phone).negotiate()

        val frames = sent(phone.toByteArray())
        assertEquals(
            listOf(MessageId.WIFI_VERSION_RESPONSE_VALUE, MessageId.WIFI_INFO_REQUEST_VALUE, MessageId.WIFI_START_RESPONSE_VALUE),
            frames.map { it.first },
        )
        val response = WifiVersionResponse.parseFrom(frames[0].second)
        assertEquals(1, response.majorVersion)
        assertEquals(3, response.minorVersion)
        assertEquals(Status.STATUS_SUCCESS, response.status)
    }

    @Test
    fun securityModesAreTheBootstrapsBitFlags() {
        // An AAWireless dongle sends 8 for its WPA2 network; the projection channel's numbering read it as enterprise
        assertEquals(WifiSecurityMode.WPA2_PERSONAL, WifiSecurityMode.forNumber(8))
        assertEquals(WifiSecurityMode.WPA_WPA2_PERSONAL, WifiSecurityMode.forNumber(12))
    }

    @Test(expected = IOException::class)
    fun credentialsWithoutAnAddressAreAnError() {
        WirelessBootstrap(dongle(info), ByteArrayOutputStream()).negotiate()
    }

    @Test(expected = EOFException::class)
    fun aDongleThatHangsUpEndsTheExchange() {
        WirelessBootstrap(dongle(start), ByteArrayOutputStream()).negotiate()
    }
}
