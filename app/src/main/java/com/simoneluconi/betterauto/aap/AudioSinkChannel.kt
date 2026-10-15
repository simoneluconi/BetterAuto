package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
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
import com.simoneluconi.betterauto.media.NowPlaying
import android.os.Handler
import android.os.Looper
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
 *
 * Audio focus works like Android Auto: the stream starts once the car grants it, and when the car
 * takes it back (radio, a call, a navigation prompt) the phone player is paused, to come back by
 * itself after a transient loss.
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
    private class StopStream(val releaseFocus: Boolean) : Outgoing
    private class Pcm(val timestampUs: Long, val data: ByteArray) : Outgoing

    private val queue = LinkedBlockingDeque<Outgoing>()
    private val main = Handler(Looper.getMainLooper())
    private var configIndex = 0
    private var nextSessionId = 0
    private var maxBatchBytes = 0
    private var maxQueuedBytes = 0

    @Volatile
    private var window = Semaphore(1)

    @Volatile
    private var running = false
    private var sender: Thread? = null

    // Audio focus granted by the car; the sender waits on it before starting a stream
    @Volatile
    private var hasFocus = false
    private val focusGranted = Semaphore(0)
    private var pausedByCar = false

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

    /** Session thread: the answer to our focus requests, or an unsolicited change from the car. */
    fun onFocus(state: AudioFocusStateType) {
        when (state) {
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY -> {
                hasFocus = true
                focusGranted.release()
                if (pausedByCar) {
                    pausedByCar = false
                    EventLog.log("Media audio: focus is back, resuming the player")
                    main.post { NowPlaying.resume() }
                }
            }
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS -> {
                hasFocus = false
                pausedByCar = false
                NowPlaying.pause()
            }
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT -> {
                hasFocus = false
                if (NowPlaying.track.value?.playing == true) pausedByCar = true
                NowPlaying.pause()
            }
            // Ducking: the car mixes its prompt over our audio at a lower level, keep playing
            else -> Unit
        }
    }

    /** Capture thread: never blocks, only decides start/stop and queues PCM. */
    override fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean) {
        val now = timestampUs / 1000
        if (!silent) lastSoundAt = now
        if (!streaming) {
            if (silent) return
            streaming = true
            queue.add(StartStream)
        } else if (!hasFocus) {
            // The car took the sound back: stop at once, the focus is not ours to release
            streaming = false
            queue.add(StopStream(releaseFocus = false))
            return
        } else if (now - lastSoundAt > idleStopMs()) {
            streaming = false
            queue.add(StopStream(releaseFocus = true))
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
                    is StopStream -> stopStream(item.releaseFocus)
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

    /**
     * A quiet passage while the player runs is still part of the music: only a paused player gives
     * the car back its speakers quickly, so its own sources never cut into a track.
     */
    private fun idleStopMs() =
        if (NowPlaying.track.value?.playing == true) IDLE_STOP_PLAYING_MS else IDLE_STOP_MS

    private fun startStream() {
        if (!hasFocus) {
            focusGranted.drainPermits()
            session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
            if (!focusGranted.tryAcquire(FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                EventLog.log("Media audio: no focus answer from the car for $FOCUS_TIMEOUT_MS ms, starting anyway")
            }
        }
        val sessionId = nextSessionId++
        send(
            MediaMessageId.MEDIA_MESSAGE_START_VALUE,
            Start.newBuilder().setSessionId(sessionId).setConfigurationIndex(configIndex).build(),
        )
        CarState.audio.value = "$format · streaming"
        EventLog.log("Media audio: streaming (session $sessionId)")
    }

    private fun stopStream(releaseFocus: Boolean) {
        send(MediaMessageId.MEDIA_MESSAGE_STOP_VALUE, Stop.getDefaultInstance())
        if (releaseFocus) {
            hasFocus = false
            session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
        }
        CarState.audio.value = "$format · idle"
        EventLog.log("Media audio: stream stopped (${if (releaseFocus) "idle" else "focus lost"})")
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
        const val IDLE_STOP_PLAYING_MS = 30_000L
        const val ACK_TIMEOUT_MS = 1_000L
        const val FOCUS_TIMEOUT_MS = 1_000L

        /** Upper bound for one message: enough to catch up after a slow ack. */
        const val MAX_BATCH_MS = 400

        /** Audio allowed to wait for the car before the oldest is dropped (bounds latency). */
        const val MAX_QUEUE_MS = 1000
    }
}
