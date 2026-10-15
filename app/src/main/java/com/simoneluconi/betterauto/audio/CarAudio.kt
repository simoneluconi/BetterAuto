package com.simoneluconi.betterauto.audio

import android.content.Context
import android.media.projection.MediaProjection
import com.simoneluconi.betterauto.EventLog

data class PcmFormat(val sampleRate: Int, val channels: Int, val bits: Int) {
    override fun toString() = "PCM $sampleRate Hz $bits-bit ${if (channels == 1) "mono" else "${channels}ch"}"
}

/** Receives captured phone audio, already in [format]. */
interface AudioSink {
    val format: PcmFormat

    /** Called on the capture thread with one chunk of PCM; [silent] when the chunk carries no sound. */
    fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean)
}

/**
 * Joins the two halves of car audio, which arrive independently: the user's capture consent
 * (a MediaProjection) and the head unit's media channel, configured with its PCM format.
 * Capture runs only while both are present.
 */
object CarAudio {
    private lateinit var appContext: Context
    private var projection: MediaProjection? = null
    private var sink: AudioSink? = null
    private var capture: AudioCapture? = null

    /** Call once before use; also puts back a phone volume left muted by a previous crash. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (capture == null) PhoneSpeakerMute.release(appContext)
    }

    val hasProjection: Boolean
        @Synchronized get() = projection != null

    /** Told when the capture consent appears or goes away (called on the caller's thread). */
    @Volatile
    var onProjectionChanged: (() -> Unit)? = null

    @Synchronized
    fun setProjection(value: MediaProjection?) {
        if (value === projection) return
        projection?.stop()
        projection = value
        restart()
        onProjectionChanged?.invoke()
    }

    @Synchronized
    fun setSink(value: AudioSink?) {
        sink = value
        restart()
    }

    private fun restart() {
        capture?.stop()
        capture = null
        val p = projection
        val s = sink
        if (p == null || s == null) {
            PhoneSpeakerMute.release(appContext)
            return
        }
        capture = try {
            AudioCapture(p, s).also { it.start() }
        } catch (e: Exception) {
            EventLog.log("Audio capture failed: $e")
            null
        }
        if (capture != null) PhoneSpeakerMute.engage(appContext) else PhoneSpeakerMute.release(appContext)
    }
}
