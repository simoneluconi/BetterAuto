package com.simoneluconi.betterauto.ui.launcher

import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.ActionSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.AppsSetting
import com.simoneluconi.betterauto.settings.MutedChatsSetting
import com.simoneluconi.betterauto.settings.RailLayoutSetting
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.settings.SettingHeader
import androidx.compose.ui.draw.rotate
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The car's settings screen: everything in [SettingsCatalog], in the car's style. */
@Composable
fun SettingsScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .verticalScrollWithBar()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        CarLabel(stringResource(R.string.settings_title), 36.sp, colors.text, weight = FontWeight.ExtraBold)
        val firstChoice = SettingsCatalog.all.first { it is ChoiceSetting<*> }
        for (item in SettingsCatalog.all) {
            when (item) {
                is SettingHeader -> CarLabel(stringResource(item.title), 30.sp, colors.text, Modifier.padding(top = 18.dp), weight = FontWeight.ExtraBold)
                is RailLayoutSetting -> RailEditor(item, settings)
                is ChoiceSetting<*> -> ChoiceRow(item, settings, primary = item === firstChoice)
                is ActionSetting -> Setting(item.title, listOf(Unit), null, { stringResource(item.label) }) {
                    item.run(settings, SettingsSurface.CAR)
                }
                is AppsSetting -> AppsRow(item, settings)
                is MutedChatsSetting -> MutedChatsRow(item, settings)
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(item: ChoiceSetting<T>, settings: LauncherSettings, primary: Boolean = false) {
    val selected by item.state(settings).collectAsStateWithLifecycle()
    Setting(item.title, item.options, item.options.firstOrNull { it.value == selected }, { stringResource(it.label) }, primary) {
        item.select(settings, it.value, SettingsSurface.CAR)
    }
}

/** The app bar's buttons: where each one goes (bar, a group, hidden) and in which order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RailEditor(item: RailLayoutSetting, settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val layout by settings.railLayout.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(stringResource(item.title), 26.sp, colors.text2, weight = FontWeight.Bold)
        for ((index, entry) in layout.withIndex()) {
            val name = stringResource(entry.item.label)
            Column(
                Modifier.background(colors.surface2, RoundedCornerShape(24.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CarIcon(entry.item.icon, colors.text, Modifier.size(28.dp))
                    CarLabel(name, 24.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
                    MoveButton(CarIcons.Back, stringResource(R.string.rail_move_up, name), enabled = index > 0, rotate = 90f) {
                        item.move(settings, entry.item, -1, SettingsSurface.CAR)
                    }
                    MoveButton(CarIcons.Back, stringResource(R.string.rail_move_down, name), enabled = index < layout.lastIndex, rotate = -90f) {
                        item.move(settings, entry.item, 1, SettingsSurface.CAR)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (slot in RailSlot.entries) {
                        val shape = RoundedCornerShape(50)
                        val selected = entry.slot == slot
                        val label = stringResource(slot.label)
                        Box(
                            Modifier
                                .carClickable(shape, stringResource(R.string.settings_option, name, label)) {
                                    item.place(settings, entry.item, slot, SettingsSurface.CAR)
                                }
                                .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface, shape)
                                .height(56.dp)
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CarLabel(label, 20.sp, colors.text, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        CarLabel(stringResource(R.string.rail_settings_note), 19.sp, colors.text2)
    }
}

@Composable
private fun MoveButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, rotate: Float, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, label) { if (enabled) onClick() }
            .background(colors.surface, CircleShape)
            .size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (enabled) colors.text else colors.text2.copy(alpha = 0.4f), Modifier.size(26.dp).rotate(rotate))
    }
}

/** App chips (pinned to the bar, allowed to notify...): selected ones are highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsRow(item: AppsSetting, settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val stored by item.state(settings).collectAsStateWithLifecycle()
    val apps = remember(stored) { item.apps(context, settings).mapNotNull { MediaApps.info(context, it) } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(stringResource(item.title), 26.sp, colors.text2, weight = FontWeight.Bold)
        if (apps.isEmpty()) CarLabel(stringResource(item.empty), 20.sp, colors.text2, maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (app in apps) {
                val shape = RoundedCornerShape(50)
                val selected = item.isSelected(stored, app.packageName)
                Row(
                    Modifier
                        .carClickable(shape, stringResource(R.string.settings_option, app.label, stringResource(if (selected) R.string.option_on else R.string.option_off))) {
                            item.toggle(settings, app.packageName, SettingsSurface.CAR)
                        }
                        .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(64.dp)
                        .padding(start = 12.dp, end = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
                    CarLabel(app.label, 22.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Muted chats as chips; pressing one unmutes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MutedChatsRow(item: MutedChatsSetting, settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val muted by settings.mutedChats.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(stringResource(item.title), 26.sp, colors.text2, weight = FontWeight.Bold)
        if (muted.isEmpty()) CarLabel(stringResource(R.string.settings_no_muted_chats), 20.sp, colors.text2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (chat in muted) {
                val shape = RoundedCornerShape(50)
                val app = remember(chat) { MediaApps.info(context, MutedChatsSetting.packageOf(chat)) }
                val name = MutedChatsSetting.chatName(chat)
                Row(
                    Modifier
                        .carClickable(shape, name) { item.unmute(settings, chat, SettingsSurface.CAR) }
                        .background(colors.surface2, shape)
                        .height(64.dp)
                        .padding(start = 12.dp, end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    app?.let { Image(it.icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape)) }
                    CarLabel(name, 22.sp, colors.text, weight = FontWeight.Bold)
                    CarIcon(CarIcons.Close, colors.text2, Modifier.size(22.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Setting(
    @StringRes titleRes: Int,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    primary: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val colors = LocalCarColors.current
    val title = stringResource(titleRes)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(title, 26.sp, colors.text2, weight = FontWeight.Bold)
        // Wraps on narrow (portrait) screens
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (option in options) {
                val shape = RoundedCornerShape(50)
                val isSelected = option == selected
                Box(
                    Modifier
                        .contentFocus(enabled = primary && isSelected)
                        .carClickable(shape, stringResource(R.string.settings_option, title, label(option))) { onSelect(option) }
                        .background(if (isSelected) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(64.dp)
                        .padding(horizontal = 30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarLabel(label(option), 22.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
    }
}
