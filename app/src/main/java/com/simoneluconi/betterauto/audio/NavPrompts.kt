package com.simoneluconi.betterauto.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Tells when a navigation app (Maps, Waze...) is speaking a prompt on the phone, for the times the
 * prompt cannot go to the car's guidance stream: playback capture cannot take navigation audio, and
 * without Shizuku that is the only way. Android shows any app which kinds of sound are playing (not
 * who plays them), which is all this needs.
 *
 * [onChange] runs on the main thread: true as a prompt starts, false a moment after the last one ends,
 * so the gaps inside one prompt do not flap.
 */
class NavPrompts(context: Context, private val onChange: (Boolean) -> Unit) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var speaking = false
    private val ended = Runnable {
        speaking = false
        onChange(false)
    }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) = update(configs)
    }

    fun start() {
        audio.registerAudioPlaybackCallback(callback, main)
        update(audio.activePlaybackConfigurations)
    }

    fun stop() {
        audio.unregisterAudioPlaybackCallback(callback)
        main.removeCallbacks(ended)
        if (speaking) ended.run()
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        val now = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE && it.playing() }
        if (now) {
            main.removeCallbacks(ended)
            if (!speaking) {
                speaking = true
                onChange(true)
            }
        } else if (speaking) {
            main.removeCallbacks(ended)
            main.postDelayed(ended, TAIL_MS)
        }
    }

    /**
     * The list holds idle players too (a navigation app keeps its voice ready between prompts). Android
     * keeps the player's state from apps but prints it ("state:started" while it plays); without that
     * text, any listed player counts.
     */
    private fun AudioPlaybackConfiguration.playing(): Boolean {
        val text = toString()
        val at = text.indexOf(STATE)
        return at < 0 || text.startsWith("started", at + STATE.length)
    }

    private companion object {
        const val STATE = "state:"

        /** After the last prompt: long enough to bridge the pause between two sentences of one instruction. */
        const val TAIL_MS = 700L
    }
}
