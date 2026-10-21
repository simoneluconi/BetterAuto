package com.simoneluconi.betterauto.video

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.simoneluconi.betterauto.EventLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * H.264 Baseline encoder fed through [inputSurface]. Output is Annex-B:
 * SPS/PPS go to [start]'s onCodecConfig once, every frame to onFrame with its timestamp.
 */
class VideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
    private val codec: MediaCodec
    val inputSurface: Surface
    val name: String get() = codec.name

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    /** When the encoder last handed out a frame: a static screen still gives a few a second (repeats). */
    @Volatile
    var lastOutputNanos = System.nanoTime()
        private set

    init {
        // A new session's encoder must not be set up while the previous one is still being torn down
        Lifecycle.lock()
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            Lifecycle.unlock()
            throw e
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            setInteger(MediaFormat.KEY_LATENCY, 1) // emit each frame as soon as it is encoded
            // A static UI produces no new frames: keep the stream alive with a few repeats a second
            // rather than re-encoding the same picture at full rate (battery, heat, USB)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, STATIC_REPEAT_US)
            // Animations draw at the phone's refresh rate: frames beyond what the car shows are dropped before encoding
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
        } catch (e: Exception) {
            codec.release()
            throw e
        } finally {
            Lifecycle.unlock()
        }
        EventLog.log("Encoder ${codec.name}: ${width}x$height $fps fps ${bitrate / 1000} kbps")
    }

    /**
     * Each frame comes in its own array after [frameHeadroom] free bytes, so the caller can put its message
     * header there instead of copying the frame again.
     */
    fun start(
        onCodecConfig: (ByteArray) -> Unit,
        frameHeadroom: Int,
        onFrame: (frame: ByteArray, timestampUs: Long) -> Unit,
    ) {
        running = true
        lastOutputNanos = System.nanoTime()
        Lifecycle.withLock { codec.start() }
        drainThread = thread(name = "video-encoder") {
            try {
                drain(onCodecConfig, frameHeadroom, onFrame)
            } catch (e: Exception) {
                if (running) EventLog.log("Encoder stopped: $e")
            }
        }
    }

    private fun drain(onCodecConfig: (ByteArray) -> Unit, headroom: Int, onFrame: (ByteArray, Long) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 100_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !configSent) {
                val format = codec.outputFormat
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")
                if (sps != null && pps != null) {
                    onCodecConfig(sps.toArray() + pps.toArray())
                    configSent = true
                }
            }
            if (index < 0) continue
            lastOutputNanos = System.nanoTime()

            val buffer = codec.getOutputBuffer(index) ?: continue
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                val data = buffer.toArray()
                codec.releaseOutputBuffer(index, false)
                if (!configSent) onCodecConfig(data)
                configSent = true
            } else {
                val frame = ByteArray(headroom + info.size)
                buffer.get(frame, headroom, info.size)
                codec.releaseOutputBuffer(index, false)
                if (info.size > 0) onFrame(frame, info.presentationTimeUs)
            }
        }
    }

    /** No frames while paused; resuming starts with a keyframe, so the car's decoder can pick up at once. */
    fun setPaused(paused: Boolean) {
        if (!paused) lastOutputNanos = System.nanoTime()
        runCatching {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (paused) 1 else 0)
                    if (!paused) putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }.onFailure { EventLog.log("Encoder: cannot ${if (paused) "pause" else "resume"}: $it") }
    }

    fun stop() {
        running = false
        drainThread?.join(500)
        Lifecycle.withLock {
            runCatching { codec.stop() }
            codec.release()
            inputSurface.release()
        }
    }

    private fun java.nio.ByteBuffer.toArray() = ByteArray(remaining()).also { get(it) }

    private companion object {
        /** One encoder set up or torn down at a time, across sessions. */
        val Lifecycle = ReentrantLock()

        /** Keyframes are big: one every couple of seconds is enough, focus changes request their own. */
        const val KEYFRAME_INTERVAL_S = 2

        /** Repeat rate of an unchanged screen: 4 fps keeps every decoder fed. */
        const val STATIC_REPEAT_US = 250_000L
    }
}
