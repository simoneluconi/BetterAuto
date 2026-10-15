package com.simoneluconi.betterauto.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.simoneluconi.betterauto.notifications.CarNotifications

/**
 * Notification access does two things: MediaSessionManager hands us the phone's active media
 * sessions, and the notifications themselves feed the car's notifications widget.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() = CarNotifications.onConnected(this)

    override fun onListenerDisconnected() = CarNotifications.onDisconnected()

    override fun onNotificationPosted(sbn: StatusBarNotification) = CarNotifications.onPosted(this, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = CarNotifications.onRemoved(sbn)
}
