package com.simoneluconi.betterauto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.simoneluconi.betterauto.settings.AppLocales

/**
 * Debug: speaks [text] the way a navigation app does (usage navigation guidance), to try the prompts'
 * way to the car without driving: the car's guidance stream with Shizuku, the phone otherwise.
 */
object FakeNavPrompt {
    private var tts: TextToSpeech? = null

    /** What any app sees of the navigation players: NavPrompts relies on the state in this text. */
    private fun logPlayers(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        EventLog.log("Debug: ${audio.activePlaybackConfigurations.size} players listed")
        audio.activePlaybackConfigurations
            .filter { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
            .forEach { EventLog.log("Debug: navigation player $it") }
    }

    fun speak(context: Context, text: String) {
        tts?.shutdown()
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                EventLog.log("Debug: no text-to-speech for the navigation prompt ($status)")
                return@TextToSpeech
            }
            // The voice language (Italian here), not the phone's: an Italian prompt read with an English voice sounds wrong
            engine.language = AppLocales.voice(context)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    EventLog.log("Debug: navigation prompt speaking")
                    // The engine's player starts a moment after
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ logPlayers(context) }, 600)
                }
                override fun onDone(id: String?) = EventLog.log("Debug: navigation prompt done")
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = EventLog.log("Debug: navigation prompt failed")
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
        }
        tts = engine
    }
}
