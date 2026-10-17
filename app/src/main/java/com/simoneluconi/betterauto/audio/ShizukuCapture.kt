package com.simoneluconi.betterauto.audio

import android.content.Context
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.NowPlaying
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * The phone's media audio *moved* to the car through Shizuku ([AudioRouterService]), like the system
 * Android Auto does: no capture consent, no screen-sharing indicator, the speaker goes silent by itself.
 * Samples arrive as float and are dithered to the car's bit depth like [AudioCapture] does.
 *
 * Connecting to Shizuku can take a moment, so it happens on the worker thread; if anything fails,
 * [onFailed] is called and the caller falls back to playback capture.
 */
class ShizukuCapture(
    private val context: Context,
    private val sink: AudioSink,
    /** Routing is on: the music now goes to the car (called on the worker thread). */
    private val onStarted: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private val format = sink.format
    private val chunkFrames = format.sampleRate / AudioCapture.CHUNKS_PER_SECOND

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var connection: ShizukuAudio.Connection? = null
    private var input: DataInputStream? = null

    fun start() {
        running = true
        worker = thread(name = "car-audio-shizuku") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pipe = open()
            if (pipe == null) {
                if (running) onFailed()
                return@thread
            }
            // Stopped while Shizuku was still connecting: undo what open() did
            if (!running) {
                runCatching { pipe.first.close() }
                connection?.close()
                return@thread
            }
            onStarted()
            try {
                stream(pipe)
            } catch (e: IOException) {
                if (running) {
                    EventLog.log("Car audio: Shizuku routing stopped: $e")
                    onFailed()
                }
            }
        }
    }

    /** The service and its pipe, float first and 16-bit if float is refused. */
    private fun open(): Pair<DataInputStream, Boolean>? {
        val connection = ShizukuAudio.connect(context) ?: return null
        this.connection = connection
        val router = connection.router
        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            if (encoding == AudioFormat.ENCODING_PCM_16BIT && format.bits != 16) break
            val pipe = runCatching { router.start(format.sampleRate, format.channels, encoding, musicApps(), CarAudio.carUsages()) }.getOrNull()
            if (pipe != null) {
                EventLog.log("Car audio: moved through Shizuku, ${runCatching { router.status() }.getOrDefault("")}")
                return DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe)).also { input = it } to
                    (encoding == AudioFormat.ENCODING_PCM_FLOAT)
            }
        }
        EventLog.log("Car audio: Shizuku could not route the audio (${runCatching { router.status() }.getOrDefault("service gone")})")
        connection.close()
        this.connection = null
        return null
    }

    /**
     * Only the music apps go to the car: the players BetterAuto lists (they offer a media browser) and the
     * one playing now. Videos in a social app, for instance, keep playing on the phone.
     */
    private fun musicApps(): IntArray {
        val packages = (MediaApps.installed(context) + listOfNotNull(NowPlaying.track.value?.packageName)).distinct()
        return packages.mapNotNull { runCatching { context.packageManager.getApplicationInfo(it, 0).uid }.getOrNull() }
            .distinct().toIntArray()
    }

    private fun stream(pipe: Pair<DataInputStream, Boolean>) {
        val (stream, float) = pipe
        if (float) {
            val quantizer = PcmQuantizer(format)
            val bytes = ByteArray(chunkFrames * format.channels * 4)
            val samples = FloatArray(chunkFrames * format.channels)
            val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            while (running) {
                stream.readFully(bytes)
                val timestamp = SystemClock.elapsedRealtimeNanos() / 1000
                view.rewind()
                view.get(samples)
                val (out, silent) = quantizer.quantize(samples, samples.size)
                sink.onPcm(out, timestamp, silent)
            }
        } else {
            val bytes = ByteArray(chunkFrames * format.channels * 2)
            while (running) {
                stream.readFully(bytes)
                val timestamp = SystemClock.elapsedRealtimeNanos() / 1000
                var peak = 0
                var i = 0
                while (i + 1 < bytes.size) {
                    val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, kotlin.math.abs(sample))
                    i += 2
                }
                sink.onPcm(bytes.copyOf(), timestamp, peak < PcmQuantizer.SILENCE * 32768f)
            }
        }
    }

    /** Stops routing: the service unregisters its policy and the phone plays its own audio again. */
    fun stop() {
        running = false
        runCatching { input?.close() }
        connection?.close()
        connection = null
        worker?.join(500)
    }
}
