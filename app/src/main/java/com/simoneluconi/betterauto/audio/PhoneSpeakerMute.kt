package com.simoneluconi.betterauto.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog

/**
 * Playback capture copies the phone's audio instead of moving it, so while music goes to the car we
 * turn the phone's media volume down to zero (the capture is taken before volume, so the car still
 * gets full level) and keep it there. The previous volume is persisted and put back when car audio
 * stops, or on the next start if the app died in between. A navigation prompt, which capture cannot
 * take, lifts it back for a moment ([lift]) so the driver still hears it from the phone.
 */
object PhoneSpeakerMute {
    private const val PREFS = "phone_speaker_mute"
    private const val KEY_SAVED = "saved_media_volume"
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private var receiver: BroadcastReceiver? = null

    /** The saved volume is back for a navigation prompt: volume changes are not undone meanwhile. */
    @Volatile
    private var lifted = false

    fun engage(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) prefs.edit { putInt(KEY_SAVED, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC || lifted) return
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    }
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiver = r
        }
        EventLog.log("Phone speaker muted while audio goes to the car")
    }

    /** While muted: the saved volume back for a navigation prompt (the music is heard on the phone too, briefly). */
    fun lift(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED) || lifted) return
        lifted = true
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(KEY_SAVED, 0), 0)
    }

    /** The prompt is over: silent again, if still muted. */
    fun lower(context: Context) {
        if (!lifted) return
        lifted = false
        val app = context.applicationContext
        if (!isMuted(app)) return
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    /** True when the phone is (or was left) muted by [engage] and not yet restored. */
    fun isMuted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SAVED)

    /** Puts back the volume saved by [engage]; safe to call when nothing was saved. */
    fun release(context: Context) {
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        lifted = false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) return
        val saved = prefs.getInt(KEY_SAVED, 0)
        prefs.edit { remove(KEY_SAVED) }
        val audio = app.getSystemService(AudioManager::class.java)
        // Still at zero: ours to put back. Otherwise the user already set a volume they want, after the app died
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        EventLog.log("Phone media volume restored to $saved")
    }
}
