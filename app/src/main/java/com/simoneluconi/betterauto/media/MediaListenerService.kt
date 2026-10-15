package com.simoneluconi.betterauto.media

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose: granting BetterAuto notification access is what lets
 * MediaSessionManager hand us the phone's active media sessions.
 */
class MediaListenerService : NotificationListenerService()
