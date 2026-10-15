package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.StopOuterClass.Stop
import aap_protobuf.service.media.sink.MediaMessageIdOuterClass.MediaMessageId
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.AudioSink
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.audio.PcmFormat
import com.simoneluconi.betterauto.car.CarState
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Media audio sink of the head unit, fed with captured phone audio as uncompressed PCM in the best
 * configuration the car offers. Same flow as video (SETUP, CONFIG, START, DATA + ACK); the stream is
 * started with audio focus when sound begins and stopped after a few seconds of silence, so the car
 * can go back to its own sources while nothing plays.
 *
 * Capture and sending are decoupled: the capture thread only queues PCM, and a sender thread sends
 * everything queued in one message each time the car acknowledges. Head units may allow a single
 * unacknowledged message and ack slowly; batching keeps the throughput equal to real time instead of
 * losing the audio captured while waiting. The queue is capped so latency cannot grow without bound.
 */
class AudioSinkChannel(
    private val session: AapSession,
    channelId: Int,
    private val configs: List<AudioConfiguration>,
) : ServiceChannel(session, channelId), AudioSink {
    override val name = "media audio"

    override var format = PcmFormat(48_000, 2, 16)
        private set

    private sealed interface Outgoing
    private data object StartStream : Outgoing
    private data object StopStream : Outgoing
    private class Pcm(val timestampUs: Long, val data: ByteArray) : Outgoing

    private val queue = LinkedBlockingDeque<Outgoing>()
    private var configIndex = 0
    private var nextSessionId = 0
    private var maxBatchBytes = 0
    private var maxQueuedBytes = 0

    @Volatile
    private var window = Semaphore(1)

    @Volatile
    private var running = false
    private var sender: Thread? = null

    // Capture thread state
    private var streaming = false
    private var lastSoundAt = 0L
    private var queuedBytes = 0

    // Sender thread stats
    private var statsMessages = 0
    private var statsBytes = 0L
    private var statsDropped = 0L
    private var statsMaxWaitMs = 0L
    private var statsStart = 0L

    override fun onOpened() {
        send(
            MediaMessageId.MEDIA_MESSAGE_SETUP_VALUE,
            Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).build(),
        )
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMessageId.MEDIA_MESSAGE_CONFIG_VALUE -> onConfig(Config.parseFrom(msg.payload))
            MediaMessageId.MEDIA_MESSAGE_ACK_VALUE -> {
                val ack = Ack.parseFrom(msg.payload)
                window.release(if (ack.hasAck()) ack.ack.coerceAtLeast(1) else 1)
            }
            else -> EventLog.log("Media audio: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun onConfig(response: Config) {
        val allowed = response.configurationIndicesList.ifEmpty { configs.indices.toList() }
        // Best quality the car accepts: highest sample rate, then bit depth, then channel count
        val best = allowed.filter { it in configs.indices }.maxWithOrNull(
            compareBy<Int>({ configs[it].samplingRate }, { configs[it].numberOfBits }, { configs[it].numberOfChannels })
        )
        if (response.status != Config.Status.STATUS_READY || best == null) {
            EventLog.log("Media audio: configuration rejected (${response.status})")
            return
        }
        val cfg = configs[best]
        configIndex = best
        format = PcmFormat(cfg.samplingRate, cfg.numberOfChannels, cfg.numberOfBits)
        val bytesPerSecond = format.sampleRate * format.channels * format.bits / 8
        maxBatchBytes = bytesPerSecond * MAX_BATCH_MS / 1000
        maxQueuedBytes = bytesPerSecond * MAX_QUEUE_MS / 1000
        window = Semaphore(response.maxUnacked.coerceAtLeast(1))
        EventLog.log("Media audio ready: $format, max_unacked=${response.maxUnacked}")
        CarState.audio.value = "$format · idle"

        running = true
        sender = thread(name = "car-audio-sender") { sendLoop() }
        CarAudio.setSink(this)
    }

    /** Capture thread: never blocks, only decides start/stop and queues PCM. */
    override fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean) {
        val now = timestampUs / 1000
        if (!silent) lastSoundAt = now
        if (!streaming) {
            if (silent) return
            streaming = true
            queue.add(StartStream)
        } else if (now - lastSoundAt > IDLE_STOP_MS) {
            streaming = false
            queue.add(StopStream)
            return
        }
        queue.add(Pcm(timestampUs, data))
        synchronized(this) {
            queuedBytes += data.size
            // Too far behind (car stopped acking): drop the oldest audio rather than build up delay
            while (queuedBytes > maxQueuedBytes) {
                val oldest = queue.firstOrNull { it is Pcm } as Pcm? ?: break
                if (!queue.remove(oldest)) break
                queuedBytes -= oldest.data.size
                statsDropped += oldest.data.size
            }
        }
    }

    private fun sendLoop() {
        try {
            while (running) {
                when (val item = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue) {
                    StartStream -> startStream()
                    StopStream -> stopStream()
                    is Pcm -> sendBatch(item)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) EventLog.log("Media audio sender stopped: $e")
        }
    }

    private fun sendBatch(first: Pcm) {
        val waitStart = System.nanoTime()
        if (!window.tryAcquire(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            EventLog.log("Media audio: no ack from the car for $ACK_TIMEOUT_MS ms, sending anyway")
        }
        val waitedMs = (System.nanoTime() - waitStart) / 1_000_000

        // Everything captured while waiting goes out in this one message
        val batch = ByteArrayOutputStream(maxBatchBytes)
        batch.write(first.data)
        while (batch.size() < maxBatchBytes) {
            val next = queue.peekFirst() as? Pcm ?: break
            if (!queue.remove(next)) break
            batch.write(next.data)
        }
        synchronized(this) { queuedBytes -= batch.size() }

        val pcm = batch.toByteArray()
        val payload = ByteArray(8 + pcm.size)
        putU32(payload, 0, (first.timestampUs ushr 32).toInt())
        putU32(payload, 4, first.timestampUs.toInt())
        pcm.copyInto(payload, 8)
        send(MediaMessageId.MEDIA_MESSAGE_DATA_VALUE, payload)
        recordStats(pcm.size, waitedMs)
    }

    private fun startStream() {
        session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        val sessionId = nextSessionId++
        send(
            MediaMessageId.MEDIA_MESSAGE_START_VALUE,
            Start.newBuilder().setSessionId(sessionId).setConfigurationIndex(configIndex).build(),
        )
        CarState.audio.value = "$format · streaming"
        EventLog.log("Media audio: streaming (session $sessionId)")
    }

    private fun stopStream() {
        send(MediaMessageId.MEDIA_MESSAGE_STOP_VALUE, Stop.getDefaultInstance())
        session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
        CarState.audio.value = "$format · idle"
        EventLog.log("Media audio: idle, stream stopped")
    }

    private fun recordStats(bytes: Int, waitMs: Long) {
        statsMessages++
        statsBytes += bytes
        statsMaxWaitMs = maxOf(statsMaxWaitMs, waitMs)
        val now = System.nanoTime()
        if (statsStart == 0L) statsStart = now
        val elapsed = now - statsStart
        if (elapsed < 5_000_000_000L) return
        val bytesPerMs = format.sampleRate * format.channels * format.bits / 8 / 1000.0
        EventLog.log(
            "Media audio: %d messages, %.0f%% of real time sent, %.0f ms dropped, max ack wait %d ms".format(
                java.util.Locale.ROOT, statsMessages, statsBytes / bytesPerMs / (elapsed / 1e6) * 100,
                statsDropped / bytesPerMs, statsMaxWaitMs,
            )
        )
        statsMessages = 0
        statsBytes = 0
        statsDropped = 0
        statsMaxWaitMs = 0
        statsStart = now
    }

    override fun close() {
        running = false
        sender?.interrupt()
        CarAudio.setSink(null)
        CarState.audio.value = null
    }

    private companion object {
        const val IDLE_STOP_MS = 3_000L
        const val ACK_TIMEOUT_MS = 1_000L

        /** Upper bound for one message: enough to catch up after a slow ack. */
        const val MAX_BATCH_MS = 400

        /** Audio allowed to wait for the car before the oldest is dropped (bounds latency). */
        const val MAX_QUEUE_MS = 1000
    }
}
