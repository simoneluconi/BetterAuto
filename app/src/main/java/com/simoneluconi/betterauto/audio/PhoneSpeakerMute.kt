package com.simoneluconi.betterauto.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog

/**
 * Playback capture copies the phone's audio instead of moving it, so while music goes to the car we
 * turn the phone's media volume down to zero (the capture is taken before volume, so the car still
 * gets full level) and keep it there. The previous volume is persisted and put back when car audio
 * stops, or on the next start if the app died in between.
 */
object PhoneSpeakerMute {
    private const val PREFS = "phone_speaker_mute"
    private const val KEY_SAVED = "saved_media_volume"
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private var receiver: BroadcastReceiver? = null

    fun engage(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) prefs.edit { putInt(KEY_SAVED, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, IntentFilter(VOLUME_CHANGED), Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(r, IntentFilter(VOLUME_CHANGED))
            }
            receiver = r
        }
        EventLog.log("Phone speaker muted while audio goes to the car")
    }

    /** True when the phone is (or was left) muted by [engage] and not yet restored. */
    fun isMuted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SAVED)

    /** Puts back the volume saved by [engage]; safe to call when nothing was saved. */
    fun release(context: Context) {
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) return
        val saved = prefs.getInt(KEY_SAVED, 0)
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        prefs.edit { remove(KEY_SAVED) }
        EventLog.log("Phone media volume restored to $saved")
    }
}
