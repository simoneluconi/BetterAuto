package com.simoneluconi.betterauto.audio

import android.content.Context
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Phone audio *moved* to the car through Shizuku ([AudioRouterService]), like the system Android Auto
 * does: no capture consent, no screen-sharing indicator, the speaker goes silent by itself. One instance
 * per stream: the music, or the navigation prompts for the car's guidance stream. Each instance has a
 * route of its own in the service, so one stopped late can never end the one that replaced it.
 * Samples arrive as float and are dithered to the car's bit depth like [AudioCapture] does.
 *
 * Connecting to Shizuku can take a moment, so it happens on the worker thread; if anything fails,
 * [onFailed] is called and the caller falls back to playback capture.
 */
class ShizukuCapture(
    private val context: Context,
    private val sink: AudioSink,
    /** Which apps' audio moves (all when empty), asked when routing starts. */
    private val uids: () -> IntArray,
    /** Which kinds of sound move (AudioAttributes.USAGE_*). */
    private val usages: IntArray,
    /** Routing is on: the music now goes to the car (called on the worker thread). */
    private val onStarted: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private val format = sink.format
    private val chunkFrames = format.sampleRate / AudioCapture.CHUNKS_PER_SECOND
    private val route = nextRoute.getAndIncrement()

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var connection: ShizukuAudio.Connection? = null
    private var input: DataInputStream? = null

    fun start() {
        running = true
        worker = thread(name = "car-audio-shizuku-$route") {
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
        val connection = ShizukuAudio.connect(context, route) ?: return null
        this.connection = connection
        val router = connection.router
        // Stopped while Shizuku was connecting: no route to start
        if (!running) {
            connection.close()
            this.connection = null
            return null
        }
        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            if (encoding == AudioFormat.ENCODING_PCM_16BIT && format.bits != 16) break
            val pipe = runCatching { router.start(route, format.sampleRate, format.channels, encoding, uids(), usages) }.getOrNull()
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
                quantizer.deliver(sink, samples, samples.size, timestamp)
            }
        } else {
            val bytes = ByteArray(chunkFrames * format.channels * 2)
            while (running) {
                stream.readFully(bytes)
                PcmQuantizer.deliver16(sink, bytes, bytes.size, SystemClock.elapsedRealtimeNanos() / 1000)
            }
        }
    }

    /** Stops routing: the service unregisters this route's policy and the phone plays that audio itself again. */
    fun stop() {
        running = false
        runCatching { input?.close() }
        connection?.close()
        connection = null
        // Called from the worker itself when it fails: nothing to wait for
        if (Thread.currentThread() !== worker) worker?.join(500)
    }

    private companion object {
        /** Route ids handed to the service, never reused while the app runs. */
        val nextRoute = java.util.concurrent.atomic.AtomicInteger(1)
    }
}
