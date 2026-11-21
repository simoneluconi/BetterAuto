package com.simoneluconi.betterauto.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestNotificationOuterClass.AudioFocusRequestNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ByeByeRequestOuterClass.ByeByeRequest
import aap_protobuf.service.control.message.ByeByeResponseOuterClass.ByeByeResponse
import aap_protobuf.service.control.message.ControlMessageTypeOuterClass.ControlMessageType
import aap_protobuf.service.control.message.HeadUnitInfoOuterClass.HeadUnitInfo
import aap_protobuf.service.control.message.NavFocusNotificationOuterClass.NavFocusNotification
import aap_protobuf.service.control.message.NavFocusRequestNotificationOuterClass.NavFocusRequestNotification
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.DriverPositionOuterClass.DriverPosition
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.VoiceSessionNotificationOuterClass.VoiceSessionNotification
import aap_protobuf.service.control.message.VoiceSessionStatusOuterClass.VoiceSessionStatus
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.DisplayTypeOuterClass.DisplayType
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
    private var mediaCarrier: Int? = null
    private var guidanceCarrier: Int? = null
    /** The car's main screen: only its video and input are used (a cluster or auxiliary screen is not drawn yet). */
    private var mainDisplayId = 0
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
            ControlMessageType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE -> {
                val focus = NavFocusNotification.parseFrom(msg.payload).focusType
                EventLog.log("Navigation focus from car: $focus")
                // The cluster follows the focus: cars (the DHU too) grant it and never send INSTRUMENT_CLUSTER_START
                channels.values.filterIsInstance<NavigationStatusChannel>()
                    .forEach { it.onNavFocus(focus == NavFocusType.NAV_FOCUS_PROJECTED) }
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
        val hu = headUnitInfo(response)
        EventLog.log("Head unit: ${hu.headUnitMake} ${hu.headUnitModel} (sw ${hu.headUnitSoftwareVersion}), car: ${hu.make} ${hu.model}")
        // Right-hand drive puts the bar and the big home widget on the right
        CarState.driverOnRight.value = when {
            !response.hasDriverPosition() -> null
            response.driverPosition == DriverPosition.DRIVER_POSITION_RIGHT -> true
            response.driverPosition == DriverPosition.DRIVER_POSITION_LEFT -> false
            else -> null
        }
        EventLog.log("Head unit: driver position ${if (response.hasDriverPosition()) response.driverPosition.name else "not given"}")
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
        // A car may offer two sinks of a kind (a Toyota Harman TAS600: two media, two guidance), and only one is heard:
        // of the music's the first (sent to the last, nothing), of the prompts' the last (sent to the first with the
        // focus granted, nothing). The others are opened and left idle
        fun audioSinks(type: AudioStreamType) = services.filter {
            it.hasMediaSinkService() && it.mediaSinkService.videoConfigsCount == 0 &&
                it.mediaSinkService.audioConfigsCount > 0 && it.mediaSinkService.audioType == type
        }.map { it.id }
        mediaCarrier = audioSinks(AudioStreamType.AUDIO_STREAM_MEDIA).firstOrNull()
        guidanceCarrier = audioSinks(AudioStreamType.AUDIO_STREAM_GUIDANCE).lastOrNull()
        mainDisplayId = services.firstOrNull { it.isVideoSink() && it.isMainDisplay() }?.mediaSinkService?.displayId ?: 0
        for (service in services) {
            EventLog.log("  channel ${service.id}: ${service.kind()}")
            // Car makers' own channels (e.g. VW's Exlap): logged to find out what a car offers
            if (service.hasVendorExtensionService()) {
                val vendor = service.vendorExtensionService
                EventLog.log("    vendor extension \"${vendor.name}\", allowed apps: ${vendor.packageWhiteListList.joinToString().ifEmpty { "any" }}, ${vendor.data.size()} bytes of data")
            }
            val channel = createChannel(service) ?: continue
            if (channel.name in skippedChannels) {
                EventLog.log("    left closed for this test: ${channel.name}")
                continue
            }
            synchronized(this) {
                if (closed) return
                channels[service.id] = channel
            }
            channel.open()
        }
    }

    private fun Service.isVideoSink() = hasMediaSinkService() && mediaSinkService.videoConfigsCount > 0

    private fun Service.isMainDisplay() =
        !mediaSinkService.hasDisplayType() || mediaSinkService.displayType == DisplayType.DISPLAY_TYPE_MAIN

    private fun createChannel(service: Service): ServiceChannel? = when {
        // A second screen (instrument cluster, auxiliary): one projection only draws the main one, left closed
        service.isVideoSink() && !service.isMainDisplay() -> {
            val sink = service.mediaSinkService
            sink.videoConfigsList.forEachIndexed { i, cfg -> EventLog.log("    video config $i: ${cfg.describe()}") }
            EventLog.log("    ${sink.displayType.name} screen, display ${sink.displayId}: not drawn yet, left closed")
            null
        }
        service.hasInputSourceService() && service.inputSourceService.hasDisplayId() &&
            service.inputSourceService.displayId != mainDisplayId -> {
            val input = service.inputSourceService
            EventLog.log("    input for display ${input.displayId} (not the main screen), keys: ${input.keycodesSupportedList.joinToString()}: left closed")
            null
        }
        service.isVideoSink() -> {
            service.mediaSinkService.videoConfigsList.forEachIndexed { i, cfg ->
                EventLog.log("    video config $i: ${cfg.describe()}")
            }
            VideoChannel(this, service.id, service.mediaSinkService.videoConfigsList, projection)
        }
        // Music, and navigation prompts on their own stream so the car lowers the rest under them
        service.hasMediaSinkService() &&
            service.mediaSinkService.audioType in OpenedAudio &&
            service.mediaSinkService.audioConfigsCount > 0 -> {
            val sink = service.mediaSinkService
            val type = sink.audioType
            EventLog.log(
                "    audio type ${if (sink.hasAudioType()) type.name else "not given"}, while in a call " +
                    "${if (sink.hasAvailableWhileInCall()) sink.availableWhileInCall else "not given"}" +
                    if (sink.hasDisplayId()) ", display ${sink.displayId}" else ""
            )
            service.mediaSinkService.audioConfigsList.forEachIndexed { i, cfg ->
                EventLog.log("    audio config $i: ${cfg.samplingRate} Hz ${cfg.numberOfBits}-bit ${cfg.numberOfChannels}ch")
            }
            // Which one of its kind carries the sound: chosen with all of them in view, see onServiceDiscovery
            val carries = service.id == (if (type == AudioStreamType.AUDIO_STREAM_MEDIA) mediaCarrier else guidanceCarrier)
            AudioSinkChannel(this, service.id, service.mediaSinkService.audioConfigsList, type, audioFocus, carriesSound = carries).also {
                if (carries) {
                    if (type == AudioStreamType.AUDIO_STREAM_MEDIA) audioSink = it else guidanceSink = it
                } else {
                    EventLog.log("    another one of its kind: opened, left unused")
                }
            }
        }
        service.hasInputSourceService() -> {
            val touchscreen = service.inputSourceService.touchscreenList.firstOrNull()
            EventLog.log("    touchscreen: ${touchscreen?.width}x${touchscreen?.height}")
            // Units without one report none, or one with no size ("nullxnull" on Harman MMX2)
            CarState.touchscreen.value = touchscreen != null && touchscreen.width > 0 && touchscreen.height > 0
            // A touchpad instead of (or beside) a touchscreen: its events are not handled yet, logged to learn them
            service.inputSourceService.touchpadList.forEach { pad ->
                EventLog.log(
                    "    touchpad: ${pad.width}x${pad.height}" +
                        (if (pad.hasPhysicalWidth()) ", ${pad.physicalWidth}x${pad.physicalHeight} physical" else "") +
                        ", navigation ${pad.uiNavigation}, absolute ${pad.uiAbsolute}, tap as select ${pad.tapAsSelect}" +
                        if (pad.hasSensitivity()) ", sensitivity ${pad.sensitivity}" else ""
                )
            }
            val keys = service.inputSourceService.keycodesSupportedList
            EventLog.log("    keys: ${keys.joinToString()}")
            InputChannel(
                this, service.id, touchscreen?.width ?: 0, touchscreen?.height ?: 0, keys, projection,
                touchpad = service.inputSourceService.touchpadList.firstOrNull(),
            )
        }
        service.hasMediaPlaybackService() -> MediaStatusChannel(this, service.id)
        service.hasMediaSourceService() -> {
            val mic = service.mediaSourceService
            EventLog.log("    microphone: ${if (mic.hasAudioConfig()) "${mic.audioConfig.samplingRate} Hz ${mic.audioConfig.numberOfBits}-bit ${mic.audioConfig.numberOfChannels}ch" else "no config"}")
            MicrophoneChannel(this, service.id, if (mic.hasAudioConfig()) mic.audioConfig else null, ::voiceSession)
        }
        service.hasSensorSourceService() -> {
            val sensors = service.sensorSourceService.sensorsList.map { it.sensorType }
            EventLog.log("    sensors: ${sensors.joinToString()}")
            SensorChannel(this, service.id, sensors)
        }
        service.hasVendorExtensionService() -> VendorExtensionChannel(this, service.id, service.vendorExtensionService.name)
        service.hasBluetoothService() -> BluetoothChannel(this, service.id, service.bluetoothService, projection.context)
        service.hasPhoneStatusService() -> PhoneStatusChannel(this, service.id, projection.context)
        service.hasNavigationStatusService() ->
            NavigationStatusChannel(this, service.id, service.navigationStatusService, projection.context, ::requestNavFocus)
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

    /**
     * Asks to be the car's navigation, as navigation apps do when they start guiding: the car stops its own guidance
     * and hands its cluster over (an Audi MMI offered the cluster but never started it without this).
     */
    private fun requestNavFocus() {
        EventLog.log("Navigation focus: asking the car")
        send(
            ControlMessageType.MESSAGE_NAV_FOCUS_REQUEST_VALUE,
            NavFocusRequestNotification.newBuilder().setFocusType(NavFocusType.NAV_FOCUS_PROJECTED).build(),
            encrypted = true,
        )
    }

    /**
     * Tells the car a voice command starts or ends. The microphone is opened only inside one: a Harman MMI
     * (Audi) puts up "observe the warnings on your phone" when it is opened without.
     */
    private fun voiceSession(active: Boolean) {
        val status = if (active) VoiceSessionStatus.VOICE_SESSION_START else VoiceSessionStatus.VOICE_SESSION_END
        EventLog.log("Voice session: $status")
        send(
            ControlMessageType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE,
            VoiceSessionNotification.newBuilder().setStatus(status).build(),
            encrypted = true,
        )
        if (!active && audioFocus.reclaimForMusic()) {
            EventLog.log("Media audio: focus asked again after the voice command, the car's volume back on the music")
        }
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

    /**
     * The car and head unit names: in headunit_info on newer head units, in the older top-level fields on
     * others (an Audi MMI sends only those), each field taken from wherever it is filled in.
     */
    @Suppress("DEPRECATION")
    private fun headUnitInfo(r: ServiceDiscoveryResponse): HeadUnitInfo {
        val hu = r.headunitInfo
        fun pick(new: String, old: String) = new.ifBlank { old }
        return HeadUnitInfo.newBuilder()
            .setMake(pick(hu.make, r.make))
            .setModel(pick(hu.model, r.model))
            .setYear(pick(hu.year, r.year))
            .setVehicleId(pick(hu.vehicleId, r.vehicleId))
            .setHeadUnitMake(pick(hu.headUnitMake, r.headUnitMake))
            .setHeadUnitModel(pick(hu.headUnitModel, r.headUnitModel))
            .setHeadUnitSoftwareBuild(pick(hu.headUnitSoftwareBuild, r.headUnitSoftwareBuild))
            .setHeadUnitSoftwareVersion(pick(hu.headUnitSoftwareVersion, r.headUnitSoftwareVersion))
            .build()
    }

    companion object {
        /**
         * Debug: channels (by [ServiceChannel.name]) not opened from the next session on, to find which one
         * a car objects to. Set by the debug receiver only.
         */
        @Volatile
        var skippedChannels: Set<String> = emptySet()

        /** The car's audio streams BetterAuto feeds; system sounds and calls stay on the phone (calls go by Bluetooth). */
        private val OpenedAudio = setOf(AudioStreamType.AUDIO_STREAM_MEDIA, AudioStreamType.AUDIO_STREAM_GUIDANCE)
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
