package com.simoneluconi.betterauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.simoneluconi.betterauto.aap.AapSession
import com.simoneluconi.betterauto.aap.AapTls
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val accessory = intent?.accessoryExtra()
        val fd = accessory?.let { getSystemService(UsbManager::class.java).openAccessory(it) }
        if (fd == null) {
            EventLog.log("Cannot open the USB accessory")
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }

        session?.let {
            EventLog.log("Replacing the previous session")
            it.close()
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
                newSession.close()
                main.post { onSessionEnded(newSession) }
            }
        }
        return START_NOT_STICKY
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
        session?.close()
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "aap"
        const val NOTIFICATION_ID = 1
    }
}

internal fun Intent.accessoryExtra(): UsbAccessory? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
    }
