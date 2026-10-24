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
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.AudioSink
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.audio.PcmFormat
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.media.NowPlaying
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An audio sink of the head unit, fed with phone audio as uncompressed PCM in the best configuration
 * the car offers: the media one (music) or the guidance one (navigation prompts, which the car plays
 * over its other sources, lowering them). Same flow as video (SETUP, CONFIG, START, DATA + ACK); the stream is
 * started with audio focus when sound begins and stopped after a few seconds of silence, so the car
 * can go back to its own sources while nothing plays.
 *
 * Capture and sending are decoupled: the capture thread only queues PCM, and a sender thread sends
 * everything queued in one message each time the car acknowledges. Head units may allow a single
 * unacknowledged message and ack slowly; batching keeps the throughput equal to real time instead of
 * losing the audio captured while waiting. The queue is capped so latency cannot grow without bound.
 *
 * Audio focus works like Android Auto ([CarAudioFocus] shares it between the two sinks): the music
 * starts once the car grants it, and when the car takes it back (radio, a call, its own navigation
 * prompt) the phone player is paused, to come back by itself after a transient loss. A prompt plays
 * over the music when that holds the focus, and otherwise asks for it only for a moment.
 */
class AudioSinkChannel(
    session: AapSession,
    channelId: Int,
    private val configs: List<AudioConfiguration>,
    stream: AudioStreamType,
    private val focus: CarAudioFocus,
) : ServiceChannel(session, channelId), AudioSink {
    private val media = stream != AudioStreamType.AUDIO_STREAM_GUIDANCE
    private val owner = if (media) CarAudioFocus.Owner.MEDIA else CarAudioFocus.Owner.GUIDANCE
    override val name = if (media) "media audio" else "guidance audio"
    private val label = if (media) "Media audio" else "Guidance audio"

    override var format = PcmFormat(48_000, 2, 16)
        private set

    /** The music is lowered while a navigation prompt plays outside the guidance channel (from the phone). */
    @Volatile
    var ducked = false

    override val gain: Float get() = if (ducked) DUCK_GAIN else 1f

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

    // Set when the car takes the sound back while we hold the focus; the capture thread then ends the stream
    @Volatile
    private var focusLost = false

    // Capture thread state
    private var streaming = false
    private var lastSoundAt = 0L
    private var queuedBytes = 0

    // Sender thread: the chunks of the message being built, and stats
    private val batch = ArrayList<ByteArray>()
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
            MediaMessageId.MEDIA_MESSAGE_ACK_VALUE -> window.release(ackedMessages(msg.payload))
            else -> EventLog.log("$label: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun onConfig(response: Config) {
        val allowed = response.configurationIndicesList.ifEmpty { configs.indices.toList() }
        // Best quality the car accepts: highest sample rate, then bit depth, then channel count
        val best = allowed.filter { it in configs.indices }.maxWithOrNull(
            compareBy<Int>({ configs[it].samplingRate }, { configs[it].numberOfBits }, { configs[it].numberOfChannels })
        )
        if (response.status != Config.Status.STATUS_READY || best == null) {
            EventLog.log("$label: configuration rejected (${response.status})")
            return
        }
        val cfg = configs[best]
        configIndex = best
        format = PcmFormat(cfg.samplingRate, cfg.numberOfChannels, cfg.numberOfBits)
        val bytesPerSecond = format.sampleRate * format.channels * format.bits / 8
        // Prompts are a few KB a second: larger messages keep them in real time even with a car that acks slowly
        maxBatchBytes = bytesPerSecond * (if (media) MAX_BATCH_MS else MAX_BATCH_GUIDANCE_MS) / 1000
        maxQueuedBytes = bytesPerSecond * (if (media) MAX_QUEUE_MS else MAX_QUEUE_GUIDANCE_MS) / 1000
        window = Semaphore(response.maxUnacked.coerceAtLeast(1))
        EventLog.log("$label ready: $format, max_unacked=${response.maxUnacked}")
        if (media) CarState.audio.value = "$format · idle"

        running = true
        sender = thread(name = if (media) "car-audio-sender" else "car-guidance-sender") { sendLoop() }
        if (media) CarAudio.setSink(this) else CarAudio.setGuidanceSink(this)
    }

    /** Session thread: the answer to our focus requests, or an unsolicited change from the car. */
    fun onFocus(state: AudioFocusStateType) {
        if (!media) return onGuidanceFocus(state)
        when (state) {
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY -> {
                hasFocus = true
                focusLost = false
                focus.mediaGained()
                focusGranted.release()
                if (pausedByCar) {
                    pausedByCar = false
                    EventLog.log("Media audio: focus is back, resuming the player")
                    main.post { NowPlaying.resume() }
                }
            }
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT -> {
                // The reply to our own release is a loss too: only losing a focus we hold means the car took the sound
                if (!hasFocus) return
                hasFocus = false
                focusLost = true
                pausedByCar = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT &&
                    NowPlaying.track.value?.playing == true
                EventLog.log("Media audio: the car took the sound, pausing the player")
                NowPlaying.pause()
            }
            // Ducking: the car mixes its prompt over our audio at a lower level, keep playing
            else -> Unit
        }
    }

    /** A prompt never pauses the player: it only waits for a granted focus, or ends when the car takes it. */
    private fun onGuidanceFocus(state: AudioFocusStateType) {
        when (state) {
            in CarAudioFocus.Gains -> {
                focusLost = false
                focusGranted.release()
            }
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT -> if (streaming) focusLost = true
            else -> Unit
        }
    }

    /** Silence matters only inside a stream (it keeps the car's timing); outside one [onPcm] drops it anyway. */
    override val wantsSilence: Boolean get() = streaming

    /** Capture thread: never blocks, only decides start/stop and queues PCM. */
    override fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean) {
        val now = timestampUs / 1000
        if (!silent) lastSoundAt = now
        if (!streaming) {
            if (silent) return
            streaming = true
            focusLost = false
            queue.add(StartStream)
        } else if (focusLost) {
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
                // Waits for as long as nothing is queued: close() interrupts it
                when (val item = queue.take()) {
                    StartStream -> startStream()
                    is StopStream -> stopStream(item.releaseFocus)
                    is Pcm -> sendBatch(item)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) EventLog.log("$label sender stopped: $e")
        }
    }

    private fun sendBatch(first: Pcm) {
        val waitStart = System.nanoTime()
        if (!window.tryAcquire(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            EventLog.log("$label: no ack from the car for $ACK_TIMEOUT_MS ms, sending anyway")
        }
        val waitedMs = (System.nanoTime() - waitStart) / 1_000_000

        // Everything captured while waiting goes out in this one message, copied once into it
        batch.clear()
        batch += first.data
        var size = first.data.size
        while (size < maxBatchBytes) {
            val next = queue.peekFirst() as? Pcm ?: break
            if (!queue.remove(next)) break
            batch += next.data
            size += next.data.size
        }
        synchronized(this) { queuedBytes -= size }

        val payload = ByteArray(MEDIA_TIMESTAMP_BYTES + size)
        putMediaTimestamp(payload, first.timestampUs)
        var at = MEDIA_TIMESTAMP_BYTES
        for (chunk in batch) {
            chunk.copyInto(payload, at)
            at += chunk.size
        }
        batch.clear()
        send(MediaMessageId.MEDIA_MESSAGE_DATA_VALUE, payload)
        recordStats(size, waitedMs)
    }

    /**
     * A quiet passage while the player runs is still part of the music: only a paused player gives
     * the car back its speakers quickly, so its own sources never cut into a track. A prompt's pauses
     * are short: its stream ends soon after it, so the car brings its own sources back up.
     */
    private fun idleStopMs() = when {
        !media -> IDLE_STOP_GUIDANCE_MS
        NowPlaying.track.value?.playing == true -> IDLE_STOP_PLAYING_MS
        else -> IDLE_STOP_MS
    }

    private fun startStream() {
        val ask = if (media) {
            if (hasFocus) null else AudioFocusRequestType.AUDIO_FOCUS_GAIN
        } else {
            focus.guidanceStarted()
            if (focus.granted) null else AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        if (ask != null) {
            focusGranted.drainPermits()
            focus.request(owner, ask)
            if (!focusGranted.tryAcquire(FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                EventLog.log("$label: no focus answer from the car for $FOCUS_TIMEOUT_MS ms, starting anyway")
            }
        }
        val sessionId = nextSessionId++
        send(
            MediaMessageId.MEDIA_MESSAGE_START_VALUE,
            Start.newBuilder().setSessionId(sessionId).setConfigurationIndex(configIndex).build(),
        )
        if (media) CarState.audio.value = "$format · streaming"
        EventLog.log("$label: streaming (session $sessionId)")
    }

    private fun stopStream(releaseFocus: Boolean) {
        send(MediaMessageId.MEDIA_MESSAGE_STOP_VALUE, Stop.getDefaultInstance())
        // A prompt always gives its share back (after a loss the car has already cleared it)
        if (releaseFocus || !media) {
            hasFocus = false
            focus.release(owner)
        }
        if (media) CarState.audio.value = "$format · idle"
        EventLog.log("$label: stream stopped (${if (releaseFocus) "idle" else "focus lost"})")
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
            "$label: %d messages, %.0f%% of real time sent, %.0f ms dropped, max ack wait %d ms".format(
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
        // On the main thread, after the pause a disconnect posts there: the phone volume comes back only then
        if (media) {
            main.post { CarAudio.setSink(null) }
            CarState.audio.value = null
        } else {
            main.post { CarAudio.setGuidanceSink(null) }
        }
    }

    private companion object {
        const val IDLE_STOP_MS = 3_000L
        const val IDLE_STOP_PLAYING_MS = 30_000L
        const val IDLE_STOP_GUIDANCE_MS = 1_500L
        const val ACK_TIMEOUT_MS = 1_000L
        const val FOCUS_TIMEOUT_MS = 1_000L

        /** Upper bound for one message: enough to catch up after a slow ack. */
        const val MAX_BATCH_MS = 400

        /** Audio allowed to wait for the car before the oldest is dropped (bounds latency). */
        const val MAX_QUEUE_MS = 1000

        /** The same for prompts, where a late word matters less than a lost one. */
        const val MAX_BATCH_GUIDANCE_MS = 1200
        const val MAX_QUEUE_GUIDANCE_MS = 3000

        /** About -10 dB: the music under a prompt, still there but out of its way. */
        const val DUCK_GAIN = 0.3f
    }
}
