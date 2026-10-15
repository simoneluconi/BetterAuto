package com.simoneluconi.betterauto.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Captures what media apps play on the phone, at the head unit's sample rate.
 *
 * Quality path: Android mixes every app in float at the mixer rate (48 kHz on phones), resampling
 * hi-res sources with its high-quality resampler. We capture that mix as float, so there is no
 * extra resampling when the car runs at the mixer rate and no early truncation to 16 bits, and we
 * quantize to the car's bit depth ourselves with TPDF dither instead of plain rounding.
 * If the phone refuses float capture we fall back to Android's own 16-bit conversion.
 *
 * Only media/game/unknown usages are capturable, and apps may opt out (Android rules).
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO is requested before capture consent
class AudioCapture(projection: MediaProjection, private val sink: AudioSink) {
    private val format = sink.format
    private val chunkFrames = format.sampleRate / CHUNKS_PER_SECOND
    private val bytesPerSample = format.bits / 8
    private val fullScale = ((1L shl (format.bits - 1)) - 1).toFloat()
    private val record: AudioRecord
    private val floatCapture: Boolean

    @Volatile
    private var running = false
    private var worker: Thread? = null

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val floatRecord = runCatching { build(config, AudioFormat.ENCODING_PCM_FLOAT) }.getOrNull()
        floatCapture = floatRecord != null
        check(floatCapture || format.bits == 16) { "${format.bits}-bit output needs float capture" }
        record = floatRecord ?: build(config, AudioFormat.ENCODING_PCM_16BIT)
    }

    private fun build(config: AudioPlaybackCaptureConfiguration, encoding: Int): AudioRecord {
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val minBuffer = AudioRecord.getMinBufferSize(format.sampleRate, channelMask, encoding)
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer, chunkFrames * format.channels * sampleBytes * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord not initialized for encoding $encoding")
        }
        return record
    }

    fun start() {
        running = true
        record.startRecording()
        EventLog.log("Audio capture started: $format from ${if (floatCapture) "float mix, TPDF dither" else "16-bit mix"}")
        worker = thread(name = "car-audio-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                if (floatCapture) loopFloat() else loop16()
            } catch (e: Exception) {
                if (running) EventLog.log("Audio capture stopped: $e")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { record.stop() }
        worker?.join(300)
        record.release()
    }

    private fun loopFloat() {
        val samples = FloatArray(chunkFrames * format.channels)
        val maxValue = fullScale.toInt()
        while (running) {
            val n = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            val timestamp = SystemClock.elapsedRealtimeNanos() / 1000
            val out = ByteArray(n * bytesPerSample)
            var peak = 0f
            for (i in 0 until n) {
                val s = samples[i].coerceIn(-1f, 1f)
                peak = max(peak, abs(s))
                // TPDF dither: sum of two uniform values, 2 LSB peak-to-peak, decorrelates the error from the music
                val dither = Random.nextFloat() - Random.nextFloat()
                val value = (s * fullScale + dither).roundToInt().coerceIn(-maxValue - 1, maxValue)
                val base = i * bytesPerSample
                for (b in 0 until bytesPerSample) out[base + b] = (value shr (8 * b)).toByte()
            }
            // Silence check before dither, so dither noise alone never counts as sound
            sink.onPcm(out, timestamp, peak < SILENCE)
        }
    }

    /** Fallback when float capture is unavailable: only 16-bit output is possible. */
    private fun loop16() {
        val buffer = ByteArray(chunkFrames * format.channels * 2)
        while (running) {
            val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            val timestamp = SystemClock.elapsedRealtimeNanos() / 1000
            var peak = 0
            var i = 0
            while (i + 1 < n) {
                val sample = ((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                peak = max(peak, abs(sample))
                i += 2
            }
            sink.onPcm(buffer.copyOf(n), timestamp, peak < SILENCE * 32768f)
        }
    }

    private companion object {
        /** 20 ms chunks: low latency, still few messages per second. */
        const val CHUNKS_PER_SECOND = 50

        /** About -78 dBFS. */
        const val SILENCE = 4f / 32768f
    }
}
