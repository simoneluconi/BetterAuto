package com.simoneluconi.betterauto.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.simoneluconi.betterauto.EventLog
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Spoken replies and the listening chime. Both play as media, the kind of sound that goes to the car
 * with the music, so the driver hears them from the car's speakers.
 */
object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    private val Media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun say(context: Context, text: String) {
        val engine = tts
        if (engine != null && ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "reply")
            return
        }
        pending = text
        if (engine != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val t = tts ?: return@TextToSpeech
            if (!ready) {
                EventLog.log("Voice: text-to-speech unavailable ($status)")
                return@TextToSpeech
            }
            t.setAudioAttributes(Media)
            t.language = Locale.getDefault()
            pending?.let { t.speak(it, TextToSpeech.QUEUE_FLUSH, Bundle(), "reply") }
            pending = null
        }
    }

    fun stop() {
        tts?.stop()
    }

    /** Two short rising tones: listening starts. */
    fun chime() = tones(listOf(660.0, 990.0))

    /** One short low tone: nothing understood, or cancelled. */
    fun cancelTone() = tones(listOf(440.0))

    private fun tones(frequencies: List<Double>) {
        val rate = 48_000
        val each = rate * 90 / 1000
        val samples = ShortArray(each * frequencies.size)
        frequencies.forEachIndexed { n, f ->
            for (i in 0 until each) {
                // Short fade in and out, no clicks
                val envelope = minOf(1.0, i / 480.0, (each - i) / 480.0)
                samples[n * each + i] = (sin(2 * PI * f * i / rate) * envelope * 0.35 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }.onFailure { EventLog.log("Voice: chime failed: $it") }
    }
}
