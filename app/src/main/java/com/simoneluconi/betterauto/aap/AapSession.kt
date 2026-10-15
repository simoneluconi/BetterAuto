package com.simoneluconi.betterauto.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ByeByeRequestOuterClass.ByeByeRequest
import aap_protobuf.service.control.message.ByeByeResponseOuterClass.ByeByeResponse
import aap_protobuf.service.control.message.ControlMessageTypeOuterClass.ControlMessageType
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import android.os.Build
import com.google.protobuf.MessageLite
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.transport.Transport
import java.io.Closeable

/**
 * Phone side of the Android Auto Protocol on one connection.
 *
 * Control channel flow (head unit = HU):
 *   HU -> VERSION_REQUEST          phone -> VERSION_RESPONSE
 *   HU <-> ENCAPSULATED_SSL ...    (TLS handshake, HU is the client)
 *   HU -> AUTH_COMPLETE            phone -> SERVICE_DISCOVERY_REQUEST (encrypted from here on)
 *   HU -> SERVICE_DISCOVERY_RESPONSE: the channels (video, audio, input, sensors...) the car offers
 *   next: CHANNEL_OPEN_REQUEST per channel, then media setup and the video stream (TODO)
 */
class AapSession(
    private val transport: Transport,
    private val tls: AapTls,
    private val projection: Projection,
) : Closeable {
    private val codec = FrameCodec(transport, tls)
    private val channels = HashMap<Int, ServiceChannel>()

    @Volatile
    private var running = true

    /** Head unit channels, available after service discovery. */
    var services: List<Service> = emptyList()
        private set

    /** Blocks until the connection ends. */
    fun run() {
        EventLog.log("Session started, waiting for VERSION_REQUEST")
        while (running) {
            val msg = codec.read()
            if (msg.channel == Aap.CONTROL_CHANNEL) onControlMessage(msg) else onChannelMessage(msg)
        }
    }

    private fun onControlMessage(msg: AapMessage) {
        when (msg.id) {
            ControlMessageType.MESSAGE_VERSION_REQUEST_VALUE -> onVersionRequest(msg.payload)
            ControlMessageType.MESSAGE_ENCAPSULATED_SSL_VALUE -> onSslData(msg.payload)
            ControlMessageType.MESSAGE_AUTH_COMPLETE_VALUE -> onAuthComplete(AuthResponse.parseFrom(msg.payload))
            ControlMessageType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE ->
                onServiceDiscovery(ServiceDiscoveryResponse.parseFrom(msg.payload))
            ControlMessageType.MESSAGE_PING_REQUEST_VALUE -> {
                val ping = PingRequest.parseFrom(msg.payload)
                send(ControlMessageType.MESSAGE_PING_RESPONSE_VALUE, PingResponse.newBuilder().setTimestamp(ping.timestamp).build(), msg.encrypted)
            }
            ControlMessageType.MESSAGE_PING_RESPONSE_VALUE -> Unit
            ControlMessageType.MESSAGE_BYEBYE_REQUEST_VALUE -> {
                EventLog.log("Head unit is closing the session: ${ByeByeRequest.parseFrom(msg.payload).reason}")
                send(ControlMessageType.MESSAGE_BYEBYE_RESPONSE_VALUE, ByeByeResponse.getDefaultInstance(), msg.encrypted)
                running = false
            }
            ControlMessageType.MESSAGE_BYEBYE_RESPONSE_VALUE -> running = false
            else -> EventLog.log("Control: unhandled message ${describeId(msg.id)} (${msg.payload.size} bytes)")
        }
    }

    private fun onVersionRequest(payload: ByteArray) {
        val major = u16(payload, 0)
        val minor = u16(payload, 2)
        EventLog.log("Head unit protocol version: $major.$minor")
        // Echo the requested version with STATUS_SUCCESS (0)
        val response = ByteArray(6)
        putU16(response, 0, major)
        putU16(response, 2, minor)
        putU16(response, 4, 0)
        send(ControlMessageType.MESSAGE_VERSION_RESPONSE_VALUE, response, encrypted = false)
    }

    private fun onSslData(payload: ByteArray) {
        val reply = tls.handshake(payload)
        if (reply.isNotEmpty()) send(ControlMessageType.MESSAGE_ENCAPSULATED_SSL_VALUE, reply, encrypted = false)
        if (tls.handshakeComplete) {
            EventLog.log("TLS handshake complete: ${tls.describeSession()}")
            CarState.link.value = "USB · ${tls.protocol}"
        }
    }

    private fun onAuthComplete(auth: AuthResponse) {
        EventLog.log("AUTH_COMPLETE, status ${auth.status}")
        val request = ServiceDiscoveryRequest.newBuilder()
            .setLabelText("BetterAuto")
            .setDeviceName(Build.MODEL)
            .build()
        send(ControlMessageType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE, request, encrypted = true)
    }

    private fun onServiceDiscovery(response: ServiceDiscoveryResponse) {
        services = response.channelsList
        val hu = response.headunitInfo
        EventLog.log("Head unit: ${hu.headUnitMake} ${hu.headUnitModel} (sw ${hu.headUnitSoftwareVersion}), car: ${hu.make} ${hu.model}")
        for (service in services) {
            EventLog.log("  channel ${service.id}: ${service.kind()}")
            val channel = createChannel(service) ?: continue
            channels[service.id] = channel
            channel.open()
        }
    }

    private fun createChannel(service: Service): ServiceChannel? = when {
        service.hasMediaSinkService() && service.mediaSinkService.videoConfigsCount > 0 -> {
            service.mediaSinkService.videoConfigsList.forEachIndexed { i, cfg ->
                EventLog.log("    video config $i: ${cfg.describe()}")
            }
            VideoChannel(this, service.id, service.mediaSinkService.videoConfigsList, projection)
        }
        service.hasInputSourceService() -> {
            val touchscreen = service.inputSourceService.touchscreenList.firstOrNull()
            EventLog.log("    touchscreen: ${touchscreen?.width}x${touchscreen?.height}")
            val keys = service.inputSourceService.keycodesSupportedList
            EventLog.log("    keys: ${keys.joinToString()}")
            InputChannel(this, service.id, touchscreen?.width ?: 0, touchscreen?.height ?: 0, keys, projection)
        }
        service.hasSensorSourceService() -> {
            val sensors = service.sensorSourceService.sensorsList.map { it.sensorType }
            EventLog.log("    sensors: ${sensors.joinToString()}")
            SensorChannel(this, service.id, sensors)
        }
        else -> null
    }

    private fun onChannelMessage(msg: AapMessage) {
        val channel = channels[msg.channel]
        if (channel != null) {
            channel.onMessage(msg)
        } else {
            EventLog.log("Channel ${msg.channel}: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    fun send(channel: Int, id: Int, payload: ByteArray, encrypted: Boolean = true, controlType: Boolean = false) {
        codec.write(AapMessage(channel, id, payload, encrypted, controlType))
    }

    private fun send(id: Int, message: MessageLite, encrypted: Boolean) = send(id, message.toByteArray(), encrypted)

    private fun send(id: Int, payload: ByteArray, encrypted: Boolean) =
        send(Aap.CONTROL_CHANNEL, id, payload, encrypted)

    override fun close() {
        running = false
        channels.values.forEach { runCatching { it.close() } }
        channels.clear()
        transport.close()
    }

    private fun describeId(id: Int) = ControlMessageType.forNumber(id)?.name ?: id.toString()

    private fun Service.kind() = when {
        hasMediaSinkService() -> "media sink (video/audio to the car)"
        hasMediaSourceService() -> "media source (microphone)"
        hasInputSourceService() -> "input (touch/keys)"
        hasSensorSourceService() -> "sensors"
        hasBluetoothService() -> "bluetooth"
        hasNavigationStatusService() -> "navigation status"
        hasMediaPlaybackService() -> "media playback status"
        hasPhoneStatusService() -> "phone status"
        hasMediaBrowserService() -> "media browser"
        hasRadioService() -> "radio"
        hasVendorExtensionService() -> "vendor extension"
        hasGenericNotificationService() -> "notifications"
        hasWifiProjectionService() -> "wifi projection"
        else -> "unknown"
    }
}
