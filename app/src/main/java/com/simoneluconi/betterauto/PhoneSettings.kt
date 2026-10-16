package com.simoneluconi.betterauto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.ActionSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.AppsSetting
import com.simoneluconi.betterauto.settings.MutedChatsSetting
import com.simoneluconi.betterauto.settings.RailLayoutSetting
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.settings.SettingHeader
import com.simoneluconi.betterauto.settings.SettingItem
import com.simoneluconi.betterauto.ui.launcher.icon
import com.simoneluconi.betterauto.ui.launcher.label
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The car screen's settings on the phone: the same [SettingsCatalog] the car shows, in the phone's style. */
@Composable
fun PhoneSettingsPage(onBack: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarLabel(stringResource(R.string.phone_settings_title), 26.sp, colors.text, weight = FontWeight.ExtraBold)
        }
        CarLabel(stringResource(R.string.phone_settings_hint), 15.sp, colors.text2, Modifier.padding(horizontal = 4.dp), maxLines = 3)
        SettingsList(settings)
    }
}

@Composable
private fun SettingsList(settings: LauncherSettings) {
    val colors = LocalCarColors.current
    // One card per section, a header starts the next one
    val sections = buildList<Pair<SettingHeader?, MutableList<SettingItem>>> {
        for (item in SettingsCatalog.all) {
            if (item is SettingHeader || isEmpty()) add((item as? SettingHeader) to mutableListOf())
            if (item !is SettingHeader) last().second += item
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((header, items) in sections) {
            header?.let { CarLabel(stringResource(it.title), 19.sp, colors.text, Modifier.padding(start = 4.dp, top = 6.dp), weight = FontWeight.Bold) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surface)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                for (item in items) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CarLabel(stringResource(item.title), 16.sp, colors.text2, weight = FontWeight.Bold)
                        when (item) {
                            is SettingHeader -> Unit
                            is RailLayoutSetting -> PhoneRailEditor(item, settings)
                            is ChoiceSetting<*> -> PhoneChoice(item, settings)
                            is ActionSetting -> Chip(stringResource(item.label), selected = false) {
                                item.run(settings, SettingsSurface.PHONE)
                            }
                            is AppsSetting -> PhoneApps(item, settings)
                            is MutedChatsSetting -> PhoneMutedChats(item, settings)
                        }
                    }
                }
            }
        }
    }
}

/** The app bar's buttons on the phone: place chips and up/down arrows for each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneRailEditor(item: RailLayoutSetting, settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val layout by settings.railLayout.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((index, entry) in layout.withIndex()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface2).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CarIcon(entry.item.icon, colors.text, Modifier.size(22.dp))
                    CarLabel(stringResource(entry.item.label), 16.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
                    ArrowButton("▲", enabled = index > 0) { item.move(settings, entry.item, -1, SettingsSurface.PHONE) }
                    ArrowButton("▼", enabled = index < layout.lastIndex) { item.move(settings, entry.item, 1, SettingsSurface.PHONE) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (slot in RailSlot.entries) {
                        Chip(stringResource(slot.label), selected = entry.slot == slot) {
                            item.place(settings, entry.item, slot, SettingsSurface.PHONE)
                        }
                    }
                }
            }
        }
        CarLabel(stringResource(R.string.rail_settings_note), 14.sp, colors.text2, maxLines = 2)
    }
}

@Composable
private fun ArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(symbol, 16.sp, if (enabled) colors.text else colors.text2.copy(alpha = 0.4f), weight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> PhoneChoice(item: ChoiceSetting<T>, settings: LauncherSettings) {
    val selected by item.state(settings).collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in item.options) {
            Chip(stringResource(option.label), selected = option.value == selected) {
                item.select(settings, option.value, SettingsSurface.PHONE)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneApps(item: AppsSetting, settings: LauncherSettings) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val stored by item.state(settings).collectAsStateWithLifecycle()
    val apps = remember(stored) { item.apps(context, settings).mapNotNull { MediaApps.info(context, it) } }
    if (apps.isEmpty()) CarLabel(stringResource(item.empty), 15.sp, colors.text2, maxLines = 2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (app in apps) {
            Chip(app.label, selected = item.isSelected(stored, app.packageName), icon = app.icon) {
                item.toggle(settings, app.packageName, SettingsSurface.PHONE)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneMutedChats(item: MutedChatsSetting, settings: LauncherSettings) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val muted by settings.mutedChats.collectAsStateWithLifecycle()
    if (muted.isEmpty()) CarLabel(stringResource(R.string.settings_no_muted_chats), 15.sp, colors.text2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (chat in muted) {
            val app = remember(chat) { MediaApps.info(context, MutedChatsSetting.packageOf(chat)) }
            Chip(MutedChatsSetting.chatName(chat) + "  ×", selected = false, icon = app?.icon) {
                item.unmute(settings, chat, SettingsSurface.PHONE)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, icon: ImageBitmap? = null, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface2)
            .clickable(onClick = onClick)
            .padding(start = if (icon != null) 6.dp else 16.dp, end = 16.dp, top = if (icon != null) 6.dp else 10.dp, bottom = if (icon != null) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape)) }
        CarLabel(label, 15.sp, colors.text, weight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}
