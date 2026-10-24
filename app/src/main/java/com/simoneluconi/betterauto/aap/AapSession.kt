package com.simoneluconi.betterauto.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestNotificationOuterClass.AudioFocusRequestNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ByeByeRequestOuterClass.ByeByeRequest
import aap_protobuf.service.control.message.ByeByeResponseOuterClass.ByeByeResponse
import aap_protobuf.service.control.message.ControlMessageTypeOuterClass.ControlMessageType
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import android.os.Build
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import android.os.Looper
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleInfo
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.transport.Transport
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Phone side of the Android Auto Protocol on one connection.
 *
 * Control channel flow (head unit = HU):
 *   HU -> VERSION_REQUEST          phone -> VERSION_RESPONSE
 *   HU <-> ENCAPSULATED_SSL ...    (TLS handshake, HU is the client)
 *   HU -> AUTH_COMPLETE            phone -> SERVICE_DISCOVERY_REQUEST (encrypted from here on)
 *   HU -> SERVICE_DISCOVERY_RESPONSE: the channels (video, audio, input, sensors...) the car offers
 *   then CHANNEL_OPEN_REQUEST for every channel we serve, and each channel runs its own flow
 */
class AapSession(
    private val transport: Transport,
    private val tls: AapTls,
    private val projection: Projection,
    /** The car accepted the phone and listed its channels. Called on the session thread. */
    private val onEstablished: () -> Unit = {},
    /** Something the driver should know, with the car's status code when it sent one. Called on the session thread. */
    private val onProblem: (ConnectionProblem, code: Int?) -> Unit = { _, _ -> },
    /** The app icon as PNG in [com.simoneluconi.betterauto.AppIcon.Sizes], for the head unit's source list. */
    private val icons: () -> List<ByteArray> = { emptyList() },
) : Closeable {
    private val codec = FrameCodec(transport, tls)
    private val channels = ConcurrentHashMap<Int, ServiceChannel>()
    private var audioSink: AudioSinkChannel? = null
    private var guidanceSink: AudioSinkChannel? = null
    private val audioFocus = CarAudioFocus(::requestAudioFocus)

    @Volatile
    private var running = true
    private var closed = false

    @Volatile
    private var established = false

    @Volatile
    private var problemReported = false

    /** Blocks until the connection ends. */
    fun run() {
        EventLog.log("Session started, waiting for VERSION_REQUEST")
        try {
            while (running) {
                val msg = codec.read()
                if (msg.channel == Aap.CONTROL_CHANNEL) onControlMessage(msg) else onChannelMessage(msg)
            }
        } catch (e: IOException) {
            // A drop during setup that we did not cause and the car did not explain
            val closedByPhone = synchronized(this) { closed }
            if (!established && !closedByPhone && !problemReported) report(ConnectionProblem.DROPPED_DURING_SETUP)
            throw e
        }
    }

    private fun report(problem: ConnectionProblem, code: Int? = null) {
        problemReported = true
        onProblem(problem, code)
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
                val reason = ByeByeRequest.parseFrom(msg.payload).reason
                EventLog.log("Head unit is closing the session: $reason")
                ConnectionProblem.ofByeBye(reason)?.let(::report)
                send(ControlMessageType.MESSAGE_BYEBYE_RESPONSE_VALUE, ByeByeResponse.getDefaultInstance(), msg.encrypted)
                running = false
            }
            ControlMessageType.MESSAGE_BYEBYE_RESPONSE_VALUE -> running = false
            ControlMessageType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE -> {
                val focus = AudioFocusNotification.parseFrom(msg.payload)
                EventLog.log("Audio focus from car: ${focus.focusState}${if (focus.unsolicited) " (unsolicited)" else ""}")
                for (owner in audioFocus.onNotification(focus.focusState, focus.unsolicited)) {
                    val sink = if (owner == CarAudioFocus.Owner.MEDIA) audioSink else guidanceSink
                    sink?.onFocus(focus.focusState)
                }
            }
            // Flags too: a wireless dongle sends an empty message 0 right before it hangs up, not yet understood
            else -> EventLog.log(
                "Control: unhandled message ${describeId(msg.id)} (${msg.payload.size} bytes, " +
                    "${if (msg.encrypted) "encrypted" else "plain"}, ${if (msg.controlType) "control" else "specific"} type)"
            )
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
            CarState.link.value = "${transport.kind} · ${tls.protocol}"
        }
    }

    private fun onAuthComplete(auth: AuthResponse) {
        EventLog.log("AUTH_COMPLETE, status ${auth.status}")
        // A head unit that checks the phone certificate says so here, before anything else
        ConnectionProblem.ofAuthStatus(auth.status)?.let { problem ->
            val name = MessageStatus.forNumber(auth.status)?.name ?: "unknown status"
            EventLog.log("The head unit rejected the authentication: $name (${auth.status}), $problem")
            report(problem, auth.status)
        }
        val request = ServiceDiscoveryRequest.newBuilder()
            .setLabelText("BetterAuto")
            .setDeviceName(Build.MODEL)
        icons().map(ByteString::copyFrom).let { png ->
            png.getOrNull(0)?.let(request::setSmallIcon)
            png.getOrNull(1)?.let(request::setMediumIcon)
            png.getOrNull(2)?.let(request::setLargeIcon)
            EventLog.log("Service discovery request with ${png.size} icons (${png.sumOf { it.size() }} bytes)")
        }
        send(ControlMessageType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE, request.build(), encrypted = true)
    }

    private fun onServiceDiscovery(response: ServiceDiscoveryResponse) {
        val services = response.channelsList
        val hu = response.headunitInfo
        EventLog.log("Head unit: ${hu.headUnitMake} ${hu.headUnitModel} (sw ${hu.headUnitSoftwareVersion}), car: ${hu.make} ${hu.model}")
        established = true
        onEstablished()
        val sensors = services.firstOrNull { it.hasSensorSourceService() }?.sensorSourceService
        VehicleData.onConnected(
            VehicleInfo(
                make = hu.make.ifBlank { null },
                model = hu.model.ifBlank { null },
                year = hu.year.ifBlank { null },
                headUnit = listOf(hu.headUnitMake, hu.headUnitModel).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
                headUnitSoftware = hu.headUnitSoftwareVersion.ifBlank { null },
                fuelTypes = sensors?.supportedFuelTypesList.orEmpty().map { it.name.removePrefix("FUEL_TYPE_") },
                evConnectors = sensors?.supportedEvConnectorTypesList.orEmpty().map { it.name.removePrefix("EV_CONNECTOR_TYPE_") },
            ),
            sensors?.sensorsList.orEmpty().map { it.sensorType.name.removePrefix("SENSOR_") },
        )
        for (service in services) {
            EventLog.log("  channel ${service.id}: ${service.kind()}")
            // Car makers' own channels (e.g. VW's Exlap): logged to find out what a car offers
            if (service.hasVendorExtensionService()) {
                val vendor = service.vendorExtensionService
                EventLog.log("    vendor extension \"${vendor.name}\", allowed apps: ${vendor.packageWhiteListList.joinToString().ifEmpty { "any" }}, ${vendor.data.size()} bytes of data")
            }
            val channel = createChannel(service) ?: continue
            synchronized(this) {
                if (closed) return
                channels[service.id] = channel
            }
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
        // Music, and navigation prompts on their own stream so the car lowers the rest under them
        service.hasMediaSinkService() &&
            service.mediaSinkService.audioType in OpenedAudio &&
            service.mediaSinkService.audioConfigsCount > 0 -> {
            val type = service.mediaSinkService.audioType
            service.mediaSinkService.audioConfigsList.forEachIndexed { i, cfg ->
                EventLog.log("    audio config $i: ${cfg.samplingRate} Hz ${cfg.numberOfBits}-bit ${cfg.numberOfChannels}ch")
            }
            AudioSinkChannel(this, service.id, service.mediaSinkService.audioConfigsList, type, audioFocus).also {
                if (type == AudioStreamType.AUDIO_STREAM_MEDIA) audioSink = it else guidanceSink = it
            }
        }
        service.hasInputSourceService() -> {
            val touchscreen = service.inputSourceService.touchscreenList.firstOrNull()
            EventLog.log("    touchscreen: ${touchscreen?.width}x${touchscreen?.height}")
            val keys = service.inputSourceService.keycodesSupportedList
            EventLog.log("    keys: ${keys.joinToString()}")
            InputChannel(this, service.id, touchscreen?.width ?: 0, touchscreen?.height ?: 0, keys, projection)
        }
        service.hasMediaPlaybackService() -> MediaStatusChannel(this, service.id)
        service.hasMediaSourceService() -> {
            val mic = service.mediaSourceService
            EventLog.log("    microphone: ${if (mic.hasAudioConfig()) "${mic.audioConfig.samplingRate} Hz ${mic.audioConfig.numberOfBits}-bit ${mic.audioConfig.numberOfChannels}ch" else "no config"}")
            MicrophoneChannel(this, service.id, if (mic.hasAudioConfig()) mic.audioConfig else null)
        }
        service.hasSensorSourceService() -> {
            val sensors = service.sensorSourceService.sensorsList.map { it.sensorType }
            EventLog.log("    sensors: ${sensors.joinToString()}")
            SensorChannel(this, service.id, sensors)
        }
        service.hasVendorExtensionService() -> VendorExtensionChannel(this, service.id, service.vendorExtensionService.name)
        service.hasBluetoothService() -> BluetoothChannel(this, service.id, service.bluetoothService, projection.context)
        service.hasPhoneStatusService() -> PhoneStatusChannel(this, service.id, projection.context)
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
        val message = AapMessage(channel, id, payload, encrypted, controlType)
        // Over WiFi a write is network I/O, which Android forbids on the main thread (broadcast receivers,
        // UI callbacks): those go out on their own thread, in the order they were sent
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // After close the queue refuses new work: the session is gone, nothing to send
            runCatching {
                mainThreadSends.execute {
                    runCatching { codec.write(message) }.onFailure { EventLog.log("Send from the main thread failed: $it") }
                }
            }
            return
        }
        codec.write(message)
    }

    private val mainThreadSends = Executors.newSingleThreadExecutor { Thread(it, "aap-send").apply { isDaemon = true } }

    private fun send(id: Int, message: MessageLite, encrypted: Boolean) = send(id, message.toByteArray(), encrypted)

    /** Asks the car for (or gives back) the right to play sound on its speakers. */
    fun requestAudioFocus(type: AudioFocusRequestType) {
        send(
            ControlMessageType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE,
            AudioFocusRequestNotification.newBuilder().setRequest(type).build(),
            encrypted = true,
        )
    }

    private fun send(id: Int, payload: ByteArray, encrypted: Boolean) =
        send(Aap.CONTROL_CHANNEL, id, payload, encrypted)

    /** Ends the connection; safe from any thread and more than once. Closing the transport unblocks [run]. */
    override fun close() {
        mainThreadSends.shutdown()
        synchronized(this) {
            if (closed) return
            closed = true
        }
        running = false
        channels.values.forEach { runCatching { it.close() } }
        channels.clear()
        audioSink = null
        guidanceSink = null
        runCatching { transport.close() }
    }

    private fun describeId(id: Int) = ControlMessageType.forNumber(id)?.name ?: id.toString()

    private companion object {
        /** The car's audio streams BetterAuto feeds; system sounds and calls stay on the phone (calls go by Bluetooth). */
        val OpenedAudio = setOf(AudioStreamType.AUDIO_STREAM_MEDIA, AudioStreamType.AUDIO_STREAM_GUIDANCE)
    }

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
