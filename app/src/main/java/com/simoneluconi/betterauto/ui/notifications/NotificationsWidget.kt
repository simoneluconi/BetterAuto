package com.simoneluconi.betterauto.ui.notifications

import com.simoneluconi.betterauto.ui.LazyColumnWithBar
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.notifications.CarNotification
import com.simoneluconi.betterauto.notifications.CarNotifications
import com.simoneluconi.betterauto.notifications.NotificationContent
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.widgets.PanelMessage
import kotlinx.coroutines.delay

/** The notifications the driver allowed, newest first. Recomposes when notifications or filters change. */
@Composable
fun rememberVisibleNotifications(settings: LauncherSettings): List<CarNotification> {
    val all by CarNotifications.all.collectAsStateWithLifecycle()
    val hidden by settings.notificationHiddenApps.collectAsStateWithLifecycle()
    val muted by settings.mutedChats.collectAsStateWithLifecycle()
    val groups by settings.notificationGroups.collectAsStateWithLifecycle()
    return remember(all, hidden, muted, groups) { CarNotifications.visible(all, settings) }
}

/**
 * Notifications card, as a home widget or as the whole notifications section. A notification opens
 * in place to read it all, with dismiss / mute chat / mute app. The quiet-mode bell is in the header.
 */
@Composable
fun NotificationsWidget(settings: LauncherSettings, modifier: Modifier = Modifier, primary: Boolean = false) {
    val colors = LocalCarColors.current
    val connected by CarNotifications.connected.collectAsStateWithLifecycle()
    val list = rememberVisibleNotifications(settings)
    var openKey by remember { mutableStateOf<String?>(null) }
    val open = list.firstOrNull { it.key == openKey }
    // Once for the whole panel, not per row
    val hidden = contentHidden()

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(24.dp)
    ) {
        val compact = maxHeight < 260.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Header(settings, list, compact)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !connected -> PanelMessage(stringResource(R.string.notif_no_access))
                    open != null -> Detail(open, settings, hidden, onClose = { openKey = null })
                    list.isEmpty() -> PanelMessage(stringResource(R.string.notif_empty))
                    compact -> CompactList(list, hidden) { openKey = it.key }
                    else -> LazyColumnWithBar(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.key }) { n ->
                            NotificationRow(n, hidden, Modifier.contentFocus(enabled = primary && n === list.first())) { openKey = n.key }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(settings: LauncherSettings, list: List<CarNotification>, compact: Boolean) {
    val colors = LocalCarColors.current
    val quiet by settings.quietMode.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CarLabel(stringResource(R.string.notif_title), if (compact) 22.sp else 26.sp, colors.text, weight = FontWeight.ExtraBold)
        if (list.isNotEmpty()) {
            Box(Modifier.background(colors.focus, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp)) {
                CarLabel(list.size.toString(), 17.sp, colors.bg, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        // A moon, not a bell: the bell in the status bar opens and closes this screen
        HeaderButton(
            CarIcons.Moon,
            stringResource(if (quiet) R.string.notif_quiet_on else R.string.notif_quiet_off),
            active = quiet,
            text = if (compact) null else stringResource(R.string.notif_quiet),
        ) { settings.setQuietMode(!quiet) }
        if (list.isNotEmpty() && !compact) {
            HeaderButton(CarIcons.Close, stringResource(R.string.notif_clear_all), active = false) {
                CarNotifications.dismissAll(list)
            }
        }
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    text: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .carClickable(shape, label, onClick = onClick)
            .background(if (active) colors.focus.copy(alpha = 0.25f) else colors.surface2, shape)
            .height(52.dp)
            .padding(horizontal = if (text != null) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarIcon(icon, if (active) colors.text else colors.text2, Modifier.size(24.dp))
        if (text != null) CarLabel(text, 19.sp, if (active) colors.text else colors.text2, weight = FontWeight.Bold)
    }
}

/** Small widget slot: the newest notification only, and how many more there are. */
@Composable
private fun CompactList(list: List<CarNotification>, hidden: Boolean, onOpen: (CarNotification) -> Unit) {
    val colors = LocalCarColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NotificationRow(list.first(), hidden) { onOpen(list.first()) }
        if (list.size > 1) CarLabel(stringResource(R.string.notif_more, list.size - 1), 18.sp, colors.text2, Modifier.padding(start = 8.dp))
    }
}

/**
 * The privacy setting: what notifications say is not shown at all; who, which app and when stay. On Auto
 * it follows who the car says is aboard, so a notification on screen changes as someone gets in or out.
 */
@Composable
private fun contentHidden(): Boolean {
    val context = LocalContext.current
    val mode by remember { LauncherSettings.get(context).notificationPrivacy }.collectAsStateWithLifecycle()
    val othersOnBoard by VehicleData.othersOnBoard.collectAsStateWithLifecycle()
    return NotificationContent.hidden(mode, othersOnBoard)
}

/** The line under a notification's title: the sender in a group, then the last message unless [hidden]. */
private fun previewOf(n: CarNotification, hidden: Boolean): String =
    listOfNotNull(n.last?.sender?.takeIf { n.isGroup }, n.last?.text?.takeUnless { hidden }).joinToString(": ")

@Composable
private fun NotificationRow(n: CarNotification, hidden: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    val preview = previewOf(n, hidden)
    Row(
        modifier
            .fillMaxWidth()
            .carClickable(shape, stringResource(R.string.notif_open, n.title), onClick = onClick)
            .background(colors.surface2, shape)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NotificationAvatar(n, 60.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CarLabel(n.title, 23.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold)
                CarLabel(timeAgo(n.time), 17.sp, colors.text2, weight = FontWeight.Bold)
            }
            if (preview.isNotEmpty()) CarLabel(preview, 21.sp, colors.text, weight = FontWeight.Medium, maxLines = 2)
            CarLabel(n.appLabel, 16.sp, colors.text2, weight = FontWeight.Bold)
        }
    }
}

/** Sender picture with the app icon as a badge, or the app icon alone. */
@Composable
private fun NotificationAvatar(n: CarNotification, size: androidx.compose.ui.unit.Dp) {
    val colors = LocalCarColors.current
    Box(Modifier.size(size)) {
        val main = n.avatar ?: n.appIcon
        if (main != null) {
            Image(main, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Box(Modifier.size(size).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                CarIcon(CarIcons.Bell, colors.text2, Modifier.size(size * 0.45f))
            }
        }
        if (n.avatar != null && n.appIcon != null) {
            Image(
                n.appIcon, null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .border(2.dp, colors.surface2, CircleShape),
            )
        }
    }
}

@Composable
private fun Detail(n: CarNotification, settings: LauncherSettings, hidden: Boolean, onClose: () -> Unit) {
    val colors = LocalCarColors.current
    Column(
        Modifier
            .fillMaxSize()
            // Back closes the notification before leaving the screen
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HeaderButton(CarIcons.Back, stringResource(R.string.music_back), active = false, onClick = onClose)
            NotificationAvatar(n, 52.dp)
            Column(Modifier.weight(1f)) {
                CarLabel(n.title, 24.sp, colors.text, weight = FontWeight.ExtraBold)
                CarLabel("${n.appLabel} · ${timeAgo(n.time)}", 18.sp, colors.text2)
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(n.messages.size) { if (n.messages.isNotEmpty()) listState.scrollToItem(n.messages.lastIndex) }
        // With the privacy setting on, no messages at all: the actions stay at the bottom
        if (hidden) Spacer(Modifier.weight(1f)) else LazyColumnWithBar(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(n.messages) { message ->
                Column(
                    Modifier
                        .widthIn(max = 900.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface2)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    message.sender?.takeIf { n.isGroup || it != n.title }?.let {
                        CarLabel(it, 18.sp, colors.focus, weight = FontWeight.Bold)
                    }
                    CarLabel(message.text, 22.sp, colors.text, maxLines = 8, lineHeight = 29.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionChip(stringResource(R.string.notif_dismiss), CarIcons.Check2) {
                CarNotifications.dismiss(n)
                onClose()
            }
            ActionChip(stringResource(R.string.notif_mute_chat), CarIcons.BellOff) {
                settings.toggleMutedChat(n.conversationKey)
                onClose()
            }
            ActionChip(stringResource(R.string.notif_mute_app, n.appLabel), CarIcons.Close) {
                settings.toggleNotificationApp(n.packageName)
                onClose()
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .carClickable(shape, label, onClick = onClick)
            .background(colors.surface2, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarIcon(icon, colors.text, Modifier.size(22.dp))
        CarLabel(label, 20.sp, colors.text, weight = FontWeight.Bold)
    }
}

/**
 * Short banner at the top for a new notification, unless quiet mode is on or the driver filtered it.
 * It never takes the focus (the knob keeps working on the screen below) and goes away by itself.
 */
@Composable
fun NotificationBanner(settings: LauncherSettings, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<CarNotification?>(null) }
    // Kept while the banner slides away, so it doesn't go blank during the exit animation
    var lastShown by remember { mutableStateOf<CarNotification?>(null) }
    LaunchedEffect(Unit) {
        CarNotifications.arrived.collect { n ->
            if (settings.quietMode.value) return@collect
            if (CarNotifications.visible(listOf(n), settings).isEmpty()) return@collect
            shown = n
            lastShown = n
        }
    }
    LaunchedEffect(shown) {
        if (shown != null) {
            delay(BANNER_MS)
            shown = null
        }
    }
    val colors = LocalCarColors.current
    AnimatedVisibility(
        visible = shown != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val current = shown ?: lastShown ?: return@AnimatedVisibility
        // A card like the others, lifted by a deep shadow; the only color is the album accent bar
        val shape = RoundedCornerShape(28.dp)
        Row(
            Modifier
                .widthIn(min = 520.dp, max = 860.dp)
                .shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.line, shape)
                .carTapOnly { settings.setSection(Section.NOTIFICATIONS); shown = null }
                .padding(end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.width(8.dp).height(96.dp).background(colors.album))
            NotificationAvatar(current, 60.dp)
            Column(Modifier.weight(1f, fill = false).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CarLabel(current.title, 24.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold)
                    CarLabel(current.appLabel, 17.sp, colors.text2, weight = FontWeight.Bold)
                }
                val preview = previewOf(current, contentHidden())
                if (preview.isNotEmpty()) CarLabel(preview, 22.sp, colors.text, weight = FontWeight.Medium, maxLines = 2)
            }
        }
    }
}

/** Touch only, never focusable: the banner must not steal the knob from the screen below. */
private fun Modifier.carTapOnly(onTap: () -> Unit): Modifier =
    pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }

@Composable
private fun timeAgo(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.notif_now)
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private const val BANNER_MS = 6000L
