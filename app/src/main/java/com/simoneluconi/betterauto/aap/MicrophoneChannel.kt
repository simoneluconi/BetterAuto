package com.simoneluconi.betterauto.aap

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.sink.MediaMessageIdOuterClass.MediaMessageId
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.CarMicrophone
import com.simoneluconi.betterauto.audio.PcmFormat

/**
 * The car's microphone (media source service): set up once, then opened on demand. While open, the
 * car sends PCM chunks with a timestamp, each acknowledged so it keeps sending.
 *   phone -> SETUP(PCM)        HU -> CONFIG
 *   phone -> MICROPHONE_REQUEST(open)   HU -> MICROPHONE_RESPONSE(session) then DATA...   phone -> ACK
 */
class MicrophoneChannel(
    session: AapSession,
    channelId: Int,
    private val config: AudioConfiguration?,
) : ServiceChannel(session, channelId) {
    override val name = "microphone"

    private var sessionId = 0
    private var chunks = 0

    private val format = config?.let { PcmFormat(it.samplingRate, it.numberOfChannels, it.numberOfBits) } ?: PcmFormat(16_000, 1, 16)

    override fun onOpened() {
        send(MediaMessageId.MEDIA_MESSAGE_SETUP_VALUE, Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).build())
        // Usable from now on, in the format the car announced; head units differ on the setup handshake
        CarMicrophone.onAvailable(format, ::request)
        EventLog.log("Car microphone: available, $format")
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMessageId.MEDIA_MESSAGE_CONFIG_VALUE -> EventLog.log("Car microphone: config ${Config.parseFrom(msg.payload).status}")
            // Some head units run the setup the other way round: confirm it
            MediaMessageId.MEDIA_MESSAGE_SETUP_VALUE -> {
                EventLog.log("Car microphone: setup from the car, confirmed")
                send(
                    MediaMessageId.MEDIA_MESSAGE_CONFIG_VALUE,
                    Config.newBuilder().setStatus(Config.Status.STATUS_READY).setMaxUnacked(1).addConfigurationIndices(0).build(),
                )
            }
            MediaMessageId.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE -> {
                val response = MicrophoneResponse.parseFrom(msg.payload)
                sessionId = response.sessionId
                chunks = 0
                EventLog.log("Car microphone: response status ${response.status}, session ${response.sessionId}")
            }
            MediaMessageId.MEDIA_MESSAGE_DATA_VALUE -> {
                // 8-byte timestamp, then PCM
                if (msg.payload.size > 8) CarMicrophone.onPcm(msg.payload.copyOfRange(8, msg.payload.size))
                if (chunks++ == 0) EventLog.log("Car microphone: first audio (${msg.payload.size - 8} bytes)")
                send(MediaMessageId.MEDIA_MESSAGE_ACK_VALUE, Ack.newBuilder().setSessionId(sessionId).setAck(1).build())
            }
            else -> EventLog.log("Car microphone: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun request(open: Boolean) {
        send(
            MediaMessageId.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE,
            MicrophoneRequest.newBuilder().setOpen(open).setAncEnabled(true).setEcEnabled(true).setMaxUnacked(1).build(),
        )
    }

    override fun close() {
        CarMicrophone.onAvailable(null, null)
    }
}
