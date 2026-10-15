package com.simoneluconi.betterauto.audio

import android.content.Context
import android.media.projection.MediaProjection
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.settings.LauncherSettings

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
    private var autoPlayed = false

    /**
     * Call once before use (after NowPlaying is acquired). If the app died while the phone was muted,
     * the music may still be playing: pause it first, so restoring the volume never blasts the phone.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (capture == null && PhoneSpeakerMute.isMuted(appContext)) {
            NowPlaying.pause()
            PhoneSpeakerMute.release(appContext)
        }
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

    /**
     * Call when the car disconnects, before the music is paused. Back within [RESUME_MEMORY_MS], the
     * music comes back as it was: still paused if the driver had paused it, playing if it was playing.
     * Only real sessions count (the car's audio channel was set up), not failed connection attempts.
     */
    @Synchronized
    fun onDisconnected() {
        if (sink == null) return
        val playing = NowPlaying.track.value?.playing == true
        prefs().edit { putLong(KEY_DISCONNECTED_AT, System.currentTimeMillis()).putBoolean(KEY_WAS_PLAYING, playing) }
        EventLog.log("Car audio: disconnected, music was ${if (playing) "playing" else "paused"}")
    }

    private fun shouldAutoPlay(): Boolean {
        val prefs = prefs()
        val away = System.currentTimeMillis() - prefs.getLong(KEY_DISCONNECTED_AT, 0L)
        if (away in 0 until RESUME_MEMORY_MS) {
            val wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false)
            EventLog.log("Car audio: back after ${away / 1000} s, music ${if (wasPlaying) "resumes" else "stays paused"} as it was")
            return wasPlaying
        }
        return LauncherSettings.get(appContext).autoPlay.value
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "car_audio_state"
    private const val KEY_DISCONNECTED_AT = "disconnected_at"
    private const val KEY_WAS_PLAYING = "was_playing"
    /** How long a disconnection keeps the music as it was, instead of the auto-play setting. */
    private const val RESUME_MEMORY_MS = 15 * 60 * 1000L

    @Synchronized
    fun setSink(value: AudioSink?) {
        // A new car audio channel is a new connection: allow auto-play again
        if (value == null) autoPlayed = false
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
        if (capture == null) {
            PhoneSpeakerMute.release(appContext)
            return
        }
        PhoneSpeakerMute.engage(appContext)
        // Like Android Auto: start the music once per connection, only when it really goes to the car
        if (!autoPlayed) {
            autoPlayed = true
            if (shouldAutoPlay()) {
                EventLog.log("Car audio: auto-play on connect")
                NowPlaying.resume()
            }
        }
    }
}
