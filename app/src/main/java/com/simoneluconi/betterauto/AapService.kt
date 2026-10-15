package com.simoneluconi.betterauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.simoneluconi.betterauto.aap.AapSession
import com.simoneluconi.betterauto.aap.AapTls
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.transport.UsbAccessoryTransport
import kotlin.concurrent.thread

/**
 * Owns the head unit session and keeps it alive in background.
 * Every accessory attach starts a fresh session: a new attach means the previous USB link is gone.
 */
class AapService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: AapSession? = null

    // Every startForeground call replaces the types, so keep the media projection type once granted
    private var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    // Unplugging normally fails the USB read, but not on every phone: the detach broadcast is the sure way out
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            EventLog.log("Accessory detached")
            endSession(session ?: return)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Media sessions are needed by audio (auto-play, pause on disconnect) before the screen is up
        NowPlaying.acquire(this)
        CarAudio.init(this)
        CarAudio.onProjectionChanged = { main.post { refreshNotification() } }
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detached, filter, RECEIVER_EXPORTED) else registerReceiver(detached, filter)
    }

    /** Closing stops the encoder and the projection: off the main thread. The session thread then reports the end. */
    private fun endSession(ended: AapSession) {
        thread(name = "aap-close") { ended.close() }
    }

    private fun refreshNotification() {
        if (session != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_AUDIO_CONSENT) {
            startAudioCapture(intent)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes)

        val accessory = intent?.accessoryExtra()
        val fd = accessory?.let { getSystemService(UsbManager::class.java).openAccessory(it) }
        if (fd == null) {
            EventLog.log("Cannot open the USB accessory")
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }

        session?.let {
            EventLog.log("Replacing the previous session")
            endSession(it)
        }
        EventLog.status = "${accessory.manufacturer} ${accessory.model}"
        val newSession = AapSession(UsbAccessoryTransport(fd), AapTls.fromAssets(this), Projection(this))
        session = newSession
        thread(name = "aap-session") {
            try {
                newSession.run()
            } catch (e: Exception) {
                EventLog.log("Session ended: $e")
            } finally {
                // Like Android Auto: pause on disconnect, before the phone volume comes back
                CarAudio.onDisconnected()
                NowPlaying.pause()
                newSession.close()
                main.post { onSessionEnded(newSession) }
            }
        }
        return START_NOT_STICKY
    }

    /** Turns the user's capture consent into a MediaProjection that feeds the car's media channel. */
    private fun startAudioCapture(intent: Intent) {
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            if (session == null) stopSelf()
            return
        }
        if (session == null) {
            // The car went away while the consent dialog was up: a projection with nothing to feed
            // would only keep the capture indicator on and this service alive
            EventLog.log("Audio capture consent arrived after the car disconnected, ignored")
            stopSelf()
            return
        }
        // The service must already run as a media projection service when the projection is created
        foregroundTypes = foregroundTypes or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes)
        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data) ?: return
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                EventLog.log("Audio capture consent ended")
                CarAudio.setProjection(null)
            }
        }, main)
        EventLog.log("Audio capture consent granted")
        CarAudio.setProjection(projection)
    }

    private fun onSessionEnded(ended: AapSession) {
        // A newer session may already have replaced this one
        if (session !== ended) return
        session = null
        EventLog.status = null
        CarState.reset()
        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(detached)
        CarAudio.onProjectionChanged = null
        CarAudio.setProjection(null)
        NowPlaying.release()
        session?.let { endSession(it) }
        session = null
        EventLog.status = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        if (CarAudio.hasProjection) {
            builder.setContentText(getString(R.string.notification_text))
        } else {
            // Without capture consent (declined, or not pre-approved via ADB) offer a way to grant it later
            val consent = PendingIntent.getActivity(
                this, 1, Intent(this, AudioConsentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentText(getString(R.string.notification_audio_phone))
                .addAction(Notification.Action.Builder(null, getString(R.string.action_enable_car_audio), consent).build())
        }
        return builder.build()
    }

    companion object {
        const val ACTION_AUDIO_CONSENT = "com.simoneluconi.betterauto.AUDIO_CONSENT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "aap"
        private const val NOTIFICATION_ID = 1
    }
}

internal fun Intent.accessoryExtra(): UsbAccessory? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
    }
