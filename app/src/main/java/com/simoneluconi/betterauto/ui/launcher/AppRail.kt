package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.settings.RailEntry
import com.simoneluconi.betterauto.settings.RailItem
import com.simoneluconi.betterauto.settings.RailLayout
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.ui.smarthome.SmartHomeRailButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.audio.ShizukuAudio
import com.simoneluconi.betterauto.projection.PhoneAppChoices
import com.simoneluconi.betterauto.projection.PhoneAppDisplay
import com.simoneluconi.betterauto.ui.apps.AppIcon
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** A button of the bar: one section, or a group of them. */
private sealed interface RailButtonSpec {
    data class Single(val item: RailItem) : RailButtonSpec
    data class Group(val slot: RailSlot, val items: List<RailItem>) : RailButtonSpec
}

/** The bar's buttons in the driver's order; a group sits where its first item is. */
private fun buttons(layout: List<RailEntry>): List<RailButtonSpec> = buildList {
    for (entry in layout) {
        when (entry.slot) {
            RailSlot.BAR -> add(RailButtonSpec.Single(entry.item))
            RailSlot.HIDDEN -> Unit
            else -> if (none { it is RailButtonSpec.Group && it.slot == entry.slot }) {
                add(RailButtonSpec.Group(entry.slot, layout.filter { it.slot == entry.slot }.map { it.item }))
            }
        }
    }
}

/**
 * Sections (as the driver arranged them, some grouped) plus pinned/recent media apps (not the one Music
 * follows) and the phone apps chosen for the car, on the left (wide screens) or at the bottom; the Apps button
 * comes for the phone apps there is no room for. Settings always closes the bar. [onOpenGroup] shows a group's items.
 */
@Composable
fun AppRail(
    settings: LauncherSettings,
    section: Section,
    vertical: Boolean,
    firstFocus: FocusRequester,
    onOpenGroup: (RailSlot) -> Unit,
    /** Beside the screen on the right (right-hand drive): its hairline on the screen's side, the left. */
    onRight: Boolean = false,
) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val pinned by settings.pinnedApps.collectAsStateWithLifecycle()
    val recent by settings.recentApps.collectAsStateWithLifecycle()
    val followed by NowPlaying.followedApp.collectAsStateWithLifecycle()
    val layout by settings.railLayout.collectAsStateWithLifecycle()
    val buttons = remember(layout) { buttons(layout) }
    // Linked smart-home services sit with the apps, each with its own icon
    val homes by SmartHome.linked.collectAsStateWithLifecycle()
    val screenHome by SmartHome.screenProvider.collectAsStateWithLifecycle()
    // Then the phone's apps chosen for the car, while they can be shown
    val chosenPhoneApps by settings.phoneApps.collectAsStateWithLifecycle()
    val shizuku by ShizukuAudio.changes.collectAsStateWithLifecycle()
    val openPhoneApp by PhoneAppDisplay.open.collectAsStateWithLifecycle()
    // Package queries and icons off the main thread: the bar draws without them for a moment at most
    val phoneApps by produceState(emptyList<MediaApps.App>(), chosenPhoneApps, shizuku) {
        value = withContext(Dispatchers.IO) {
            if (PhoneAppDisplay.supported(context)) PhoneAppChoices.available(context, chosenPhoneApps).mapNotNull { MediaApps.info(context, it) } else emptyList()
        }
    }
    val appsPlaced = layout.any { it.item == RailItem.APPS && it.slot != RailSlot.HIDDEN }

    val surface = Modifier
        .background(colors.surface)
        .drawBehind {
            val stroke = 1.dp.toPx()
            val x = if (onRight) 0f else size.width
            if (vertical) drawLine(colors.line, Offset(x, 0f), Offset(x, size.height), stroke)
            else drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), stroke)
        }
        .padding(16.dp)
    BoxWithConstraints(if (vertical) surface.fillMaxHeight().width(RAIL_WIDTH) else surface.fillMaxWidth().height(RAIL_HEIGHT)) {
        // Media apps, then phone apps, take the room left by the sections, never pushing Settings out of the screen;
        // phone apps left out bring the Apps button
        val length = if (vertical) maxHeight else maxWidth
        val free = ((length - SLOT * (buttons.size + 1 + homes.size) - DIVIDER) / SLOT).toInt()
        // An app chosen as a phone app gets that button only, not a media one too
        val mediaApps by produceState(emptyList<MediaApps.App>(), pinned, recent, followed, phoneApps) {
            value = withContext(Dispatchers.IO) {
                RailLayout.mediaApps(pinned, recent, followed) { MediaApps.suggested(context) }
                    .filter { p -> phoneApps.none { it.packageName == p } }
                    .mapNotNull { MediaApps.info(context, it) }.take(MAX_APPS)
            }
        }
        val room = RailLayout.phoneAppsRoom(free, mediaApps.size, phoneApps.size, appsPlaced)
        val apps = mediaApps.take(room.media)
        val shownPhoneApps = phoneApps.take(room.phone)
        // The phone app on screen lights its own button, else the Apps button
        val appOnBar = section == Section.PHONE_APP && shownPhoneApps.any { it.packageName == openPhoneApp }
        fun RailItem.lit() = section == this.section || (this == RailItem.APPS && section == Section.PHONE_APP && !appOnBar)
        // The knob starts from the current section's button (or its group), else from the first one
        val focused = buttons.firstOrNull { b ->
            when (b) {
                is RailButtonSpec.Single -> b.item.lit()
                is RailButtonSpec.Group -> b.items.any { it.lit() }
            }
        } ?: buttons.firstOrNull().takeIf { section != Section.SETTINGS && !appOnBar && !(room.appsButton && RailItem.APPS.lit()) }
        val items: @Composable (spacerModifier: Modifier) -> Unit = { spacerModifier ->
            for (button in buttons) {
                val mod = if (button == focused) Modifier.focusRequester(firstFocus) else Modifier
                when (button) {
                    is RailButtonSpec.Single -> RailButton(
                        icon = button.item.icon,
                        label = stringResource(button.item.label),
                        selected = button.item.lit(),
                        modifier = mod,
                        onLongClick = { BarEditor.open(button.item) },
                    ) { settings.setSection(button.item.section) }
                    is RailButtonSpec.Group -> GroupButton(
                        button.items, selected = button.items.any { it.lit() }, modifier = mod,
                        onLongClick = { BarEditor.open(button.items.first()) },
                    ) {
                        onOpenGroup(button.slot)
                    }
                }
            }
            if (apps.isNotEmpty() || homes.isNotEmpty() || shownPhoneApps.isNotEmpty() || room.appsButton) RailDivider(vertical)
            for (home in homes) {
                SmartHomeRailButton(home, selected = section == Section.SMART_HOME && home.id == (screenHome ?: homes.first().id)) {
                    settings.setSection(Section.SMART_HOME)
                }
            }
            for (app in apps) {
                val isPinned = app.packageName in pinned
                Box(
                    Modifier
                        .carClickable(
                            RoundedCornerShape(22.dp),
                            if (isPinned) stringResource(R.string.app_pinned, app.label) else app.label,
                            onLongClick = { settings.togglePinned(app.packageName) },
                        ) {
                            NowPlaying.open(app.packageName)
                            settings.setSection(Section.MUSIC)
                        }
                        .size(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
                    if (isPinned) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(12.dp)
                                .background(colors.focus, CircleShape)
                        )
                    }
                }
            }
            // The phone's apps: a rounded square with the phone badge, as in the Apps screen
            for (app in shownPhoneApps) {
                val shape = RoundedCornerShape(22.dp)
                val selected = section == Section.PHONE_APP && app.packageName == openPhoneApp
                Box(
                    Modifier
                        .then(if (selected) Modifier.focusRequester(firstFocus) else Modifier)
                        .carClickable(shape, stringResource(R.string.app_from_phone, app.label)) {
                            PhoneAppDisplay.open(app.packageName)
                            settings.setSection(Section.PHONE_APP)
                        }
                        .background(if (selected) colors.focus.copy(alpha = 0.2f) else colors.surface, shape)
                        .size(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(app.icon, phone = true, size = 48)
                }
            }
            if (room.appsButton) {
                val lit = RailItem.APPS.lit()
                RailButton(
                    RailItem.APPS.icon, stringResource(RailItem.APPS.label), lit,
                    modifier = if (lit) Modifier.focusRequester(firstFocus) else Modifier,
                    onLongClick = { BarEditor.open(RailItem.APPS) },
                ) { settings.setSection(Section.APPS) }
            }
            Spacer(spacerModifier)
            RailButton(
                CarIcons.Settings, stringResource(R.string.nav_settings), section == Section.SETTINGS,
                modifier = if (focused == null && !appOnBar && !(room.appsButton && RailItem.APPS.lit())) Modifier.focusRequester(firstFocus) else Modifier,
                onLongClick = { BarEditor.open(null) },
            ) { settings.setSection(Section.SETTINGS) }
        }
        if (vertical) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { items(Modifier.weight(1f)) }
        } else {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { items(Modifier.weight(1f)) }
        }
    }
}

/** One button for a group: its first items' icons together. Held, it arranges the bar. */
@Composable
private fun GroupButton(items: List<RailItem>, selected: Boolean, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(22.dp)
    val names = items.map { stringResource(it.label) }.joinToString(", ")
    Box(
        modifier
            .carClickable(shape, stringResource(R.string.rail_group, names), onLongClick = onLongClick, onClick = onClick)
            .background(if (selected) colors.focus.copy(alpha = 0.2f) else colors.surface2, shape)
            .size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (selected) colors.text else colors.text2
        if (items.size == 1) {
            CarIcon(items[0].icon, tint, Modifier.size(34.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { CarIcon(it.icon, tint, Modifier.size(24.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * A group's items as big buttons, over the screen next to the bar. The focus stays inside;
 * Back or a choice closes it.
 */
@Composable
fun RailGroupPanel(
    settings: LauncherSettings,
    slot: RailSlot,
    section: Section,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCarColors.current
    val layout by settings.railLayout.collectAsStateWithLifecycle()
    val items = layout.filter { it.slot == slot }.map { it.item }
    val first = remember { FocusRequester() }
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }
    if (items.isEmpty()) return
    Row(
        modifier
            .shadow(24.dp, RoundedCornerShape(30.dp))
            .clip(RoundedCornerShape(30.dp))
            .background(colors.surface)
            .padding(18.dp)
            // Neither the knob nor a nudge leaves the panel; only a choice or Back closes it
            .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
            .focusGroup()
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEachIndexed { i, item ->
            val selected = item.section == section || (item == RailItem.APPS && section == Section.PHONE_APP)
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .carClickable(shape, stringResource(item.label)) {
                        settings.setSection(item.section)
                        onClose()
                    }
                    .background(if (selected) colors.focus.copy(alpha = 0.2f) else colors.surface2, shape)
                    .size(width = 150.dp, height = 132.dp)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                CarIcon(item.icon, colors.text, Modifier.size(40.dp))
                CarLabel(stringResource(item.label), 20.sp, colors.text, weight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
    LaunchedEffect(slot) { runCatching { first.requestFocus() } }
}

private val SLOT = 80.dp
private val DIVIDER = 18.dp
private const val MAX_APPS = 4

/** A section's button; held (finger or knob center), it arranges the bar. */
@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .carClickable(shape, label, onLongClick = onLongClick, onClick = onClick)
            .background(if (selected) colors.focus.copy(alpha = 0.2f) else colors.surface, shape)
            .size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (selected) colors.text else colors.text2, Modifier.size(34.dp))
    }
}

@Composable
private fun RailDivider(vertical: Boolean) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .padding(if (vertical) 6.dp else 0.dp, if (vertical) 0.dp else 6.dp)
            .then(if (vertical) Modifier.width(44.dp).height(2.dp) else Modifier.width(2.dp).height(44.dp))
            .background(colors.line, RoundedCornerShape(1.dp))
    )
}

/** The app bar's size on the left and at the bottom: what floats over the screen centers beside it. */
internal val RAIL_WIDTH = 112.dp
internal val RAIL_HEIGHT = 104.dp
