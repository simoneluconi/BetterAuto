package com.simoneluconi.betterauto.video

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.view.Surface
import com.simoneluconi.betterauto.EventLog
import kotlin.concurrent.thread

/**
 * H.264 Baseline encoder fed through [inputSurface]. Output is Annex-B:
 * SPS/PPS go to [start]'s onCodecConfig once, every frame to onFrame with its timestamp.
 */
class VideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    val name: String get() = codec.name

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            setInteger(MediaFormat.KEY_LATENCY, 1) // emit each frame as soon as it is encoded
            // A static UI produces no new frames: keep the stream going at the target rate
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        EventLog.log("Encoder ${codec.name}: ${width}x$height $fps fps ${bitrate / 1000} kbps")
    }

    fun start(onCodecConfig: (ByteArray) -> Unit, onFrame: (data: ByteArray, timestampUs: Long) -> Unit) {
        running = true
        codec.start()
        drainThread = thread(name = "video-encoder") {
            try {
                drain(onCodecConfig, onFrame)
            } catch (e: Exception) {
                if (running) EventLog.log("Encoder stopped: $e")
            }
        }
    }

    private fun drain(onCodecConfig: (ByteArray) -> Unit, onFrame: (ByteArray, Long) -> Unit) {
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

            val buffer = codec.getOutputBuffer(index) ?: continue
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val data = buffer.toArray()
            codec.releaseOutputBuffer(index, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                if (!configSent) onCodecConfig(data)
                configSent = true
            } else if (data.isNotEmpty()) {
                onFrame(data, info.presentationTimeUs)
            }
        }
    }

    fun stop() {
        running = false
        drainThread?.join(500)
        runCatching { codec.stop() }
        codec.release()
        inputSurface.release()
    }

    private fun java.nio.ByteBuffer.toArray() = ByteArray(remaining()).also { get(it) }
}
