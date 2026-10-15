package com.simoneluconi.betterauto.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class NotificationMessage(val sender: String?, val text: String, val time: Long)

/** A notification worth showing while driving: a message, a missed call, a reminder. */
data class CarNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val appIcon: ImageBitmap?,
    /** Identifies the chat across notifications, for muting it. */
    val conversationKey: String,
    /** Chat or group name, or the sender, or the notification title. */
    val title: String,
    val isGroup: Boolean,
    /** Oldest first; a single entry for plain notifications. */
    val messages: List<NotificationMessage>,
    val time: Long,
    /** Sender or contact picture, when the app provides one. */
    val avatar: ImageBitmap?,
) {
    val last: NotificationMessage? get() = messages.lastOrNull()
}

/**
 * The phone's notifications, as delivered to our notification listener (the same one that gives
 * access to music sessions). Only conversations and alerts are kept: no ongoing, media, progress or
 * system notifications, no silent ones. Filters chosen by the driver are applied by [visible].
 */
object CarNotifications {
    private val _all = MutableStateFlow<List<CarNotification>>(emptyList())
    val all: StateFlow<List<CarNotification>> = _all

    private val _arrived = MutableSharedFlow<CarNotification>(extraBufferCapacity = 8)
    /** A new notification, or a new message in a chat: what a banner would announce. */
    val arrived: SharedFlow<CarNotification> = _arrived

    /** False until notification access is granted and the listener is connected. */
    val connected = MutableStateFlow(false)

    private var listener: NotificationListenerService? = null
    private val byKey = LinkedHashMap<String, CarNotification>()

    /** Development: show only pretend notifications, e.g. for screenshots without personal data. */
    @Volatile
    var demoOnly = false
        set(value) {
            field = value
            publish()
        }

    /** Visible notifications, newest first, with the driver's filters applied. */
    fun visible(list: List<CarNotification>, settings: LauncherSettings): List<CarNotification> {
        if (demoOnly) return list.filter { it.key.startsWith(DEBUG_KEY) }
        val hiddenApps = settings.notificationHiddenApps.value
        val mutedChats = settings.mutedChats.value
        val groups = settings.notificationGroups.value
        return list.filter { n ->
            n.packageName !in hiddenApps && n.conversationKey !in mutedChats && (groups || !n.isGroup)
        }
    }

    fun dismiss(notification: CarNotification) {
        EventLog.log("Notifications: dismiss from ${notification.packageName}")
        runCatching { listener?.cancelNotification(notification.key) }
        remove(notification.key)
    }

    fun dismissAll(list: List<CarNotification>) {
        EventLog.log("Notifications: dismiss ${list.size}")
        list.forEach { n -> runCatching { listener?.cancelNotification(n.key) }; byKey.remove(n.key) }
        publish()
    }

    /** Development: a pretend message, to try the widget and the banner. */
    fun simulate(title: String, text: String, group: Boolean) {
        val n = CarNotification(
            key = "$DEBUG_KEY${System.nanoTime()}",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            appIcon = null,
            conversationKey = "com.whatsapp|$title",
            title = title,
            isGroup = group,
            messages = listOf(NotificationMessage(if (group) "Luca" else title, text, System.currentTimeMillis())),
            time = System.currentTimeMillis(),
            avatar = null,
        )
        EventLog.log("Notifications: simulated ${if (group) "group " else ""}message")
        byKey[n.key] = n
        connected.value = true
        publish()
        _arrived.tryEmit(n)
    }

    internal fun onConnected(service: NotificationListenerService) {
        listener = service
        connected.value = true
        byKey.clear()
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
        for (sbn in active) parse(service, sbn)?.let { byKey[it.key] = it }
        EventLog.log("Notifications: listener connected, ${byKey.size} of ${active.size} kept")
        publish()
    }

    internal fun onDisconnected() {
        listener = null
        connected.value = false
        EventLog.log("Notifications: listener disconnected")
    }

    internal fun onPosted(service: NotificationListenerService, sbn: StatusBarNotification) {
        val parsed = parse(service, sbn) ?: return
        val previous = byKey[parsed.key]
        byKey.remove(parsed.key)
        byKey[parsed.key] = parsed
        publish()
        // Apps re-post the same notification for every update: announce only something new
        if (previous == null || previous.last != parsed.last) {
            EventLog.log("Notifications: new from ${parsed.packageName}${if (parsed.isGroup) " (group)" else ""}")
            LauncherSettings.get(service).markNotificationApp(parsed.packageName)
            _arrived.tryEmit(parsed)
        }
    }

    internal fun onRemoved(sbn: StatusBarNotification) {
        if (byKey.containsKey(sbn.key)) remove(sbn.key)
    }

    private fun remove(key: String) {
        byKey.remove(key)
        publish()
    }

    private fun publish() {
        _all.value = byKey.values.sortedByDescending { it.time }
    }

    private fun parse(service: NotificationListenerService, sbn: StatusBarNotification): CarNotification? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == service.packageName || sbn.packageName in SystemPackages) return null
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if (n.category in SkippedCategories) return null
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        if (!important(service, sbn)) return null

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val messages: List<NotificationMessage>
        val chatTitle: String
        val isGroup: Boolean
        if (style != null && style.messages.isNotEmpty()) {
            messages = style.messages.takeLast(MAX_MESSAGES).map {
                NotificationMessage(it.person?.name?.toString(), it.text?.toString().orEmpty(), it.timestamp)
            }
            isGroup = style.isGroupConversation
            chatTitle = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: messages.lastOrNull()?.sender ?: title
        } else {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            messages = (lines?.takeLast(MAX_MESSAGES) ?: listOf(text)).filter { it.isNotBlank() }
                .map { NotificationMessage(null, it, sbn.postTime) }
            isGroup = false
            chatTitle = title
        }
        if (chatTitle.isBlank() && messages.isEmpty()) return null

        val app = MediaApps.info(service, sbn.packageName)
        return CarNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = app?.label ?: appLabel(service, sbn.packageName),
            appIcon = app?.icon,
            conversationKey = "${sbn.packageName}|${chatTitle.ifBlank { sbn.tag ?: sbn.id.toString() }}",
            title = chatTitle.ifBlank { app?.label ?: sbn.packageName },
            isGroup = isGroup,
            messages = messages,
            time = messages.lastOrNull()?.time?.takeIf { it > 0 } ?: sbn.postTime,
            avatar = n.getLargeIcon()?.let { iconBitmap(service, it) },
        )
    }

    /** Silent and minimized notifications (promotions, sync status...) stay off the car screen. */
    private fun important(service: NotificationListenerService, sbn: StatusBarNotification): Boolean {
        val ranking = NotificationListenerService.Ranking()
        val found = runCatching { service.currentRanking?.getRanking(sbn.key, ranking) }.getOrNull() == true
        return !found || ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun iconBitmap(context: Context, icon: Icon): ImageBitmap? = runCatching {
        icon.loadDrawable(context)?.toBitmap(AVATAR_PX, AVATAR_PX)?.asImageBitmap()
    }.getOrNull()

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** Android's own notifications (USB, charging, system updates) are not for the road. */
    private val SystemPackages = setOf("android", "com.android.systemui", "com.android.providers.downloads")

    private val SkippedCategories = setOf(
        Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_CALL, Notification.CATEGORY_LOCATION_SHARING, Notification.CATEGORY_STOPWATCH,
        Notification.CATEGORY_WORKOUT,
    )

    private const val MAX_MESSAGES = 12
    private const val DEBUG_KEY = "debug:"
    private const val AVATAR_PX = 128
}
