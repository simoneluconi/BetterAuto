package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.simoneluconi.betterauto.audio.ShizukuAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.home.CalendarEvents
import com.simoneluconi.betterauto.home.PhoneCalendar
import com.simoneluconi.betterauto.settings.AppsSetting
import com.simoneluconi.betterauto.settings.CalendarsSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.MutedChatsSetting
import com.simoneluconi.betterauto.settings.PhoneNote
import com.simoneluconi.betterauto.settings.RailLayoutSetting
import com.simoneluconi.betterauto.settings.SettingItem
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.settings.WidgetSlotsSetting
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.widgets.HomeWidgets
import com.simoneluconi.betterauto.ui.widgets.currentHome

/** A setting's current value in words: the chosen option, or a list's summary ("Shown: 3 of 4"). */
@Composable
fun settingValue(item: SettingItem, settings: LauncherSettings): String? = when (item) {
    is ChoiceSetting<*> -> choiceValue(item, settings)
    is AppsSetting -> {
        val context = LocalContext.current
        val stored by item.state(settings).collectAsStateWithLifecycle()
        // Off the main thread and once: the phone apps' candidates are every app on the phone
        val apps by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { item.apps(context, settings) } }
        apps?.let { list -> stringResource(item.summary, list.count { item.isSelected(stored, it) }, list.size) }
    }
    is MutedChatsSetting -> {
        val muted by settings.mutedChats.collectAsStateWithLifecycle()
        if (muted.isEmpty()) stringResource(R.string.settings_list_none) else stringResource(item.summary, muted.size)
    }
    is CalendarsSetting -> {
        val context = LocalContext.current
        val hidden by settings.hiddenCalendars.collectAsStateWithLifecycle()
        val calendars by produceState<List<PhoneCalendar>?>(null) { value = CalendarEvents.calendars(context) }
        calendars?.takeIf { it.isNotEmpty() }?.let { list -> stringResource(item.summary, list.count { item.isShown(hidden, it.id) }, list.size) }
    }
    is RailLayoutSetting -> {
        val layout by settings.railLayout.collectAsStateWithLifecycle()
        stringResource(item.summary, item.shown(layout), layout.size)
    }
    is WidgetSlotsSetting -> {
        val context = LocalContext.current
        stringResource(item.summary, currentHome(settings).mains.joinToString(", ") { HomeWidgets.label(context, it) })
    }
    is PhoneNote -> null
}

@Composable
private fun <T> choiceValue(item: ChoiceSetting<T>, settings: LauncherSettings): String? {
    val value by item.state(settings).collectAsStateWithLifecycle()
    return item.label(value)?.let { stringResource(it) }
}

/**
 * The phone's line under a category: "Title: value" of its [SettingsCategory.summaryKeys] (lists give
 * just their summary), then whatever [extra] adds (the phone-only sections).
 */
@Composable
fun categorySummary(category: SettingsCategory, settings: LauncherSettings, extra: List<String> = emptyList()): String {
    val parts = category.summaryKeys.mapNotNull { key ->
        val item = SettingsCatalog.all.firstOrNull { it.key == key && SettingsSurface.PHONE in it.surfaces } ?: return@mapNotNull null
        val value = settingValue(item, settings) ?: return@mapNotNull null
        if (item is ChoiceSetting<*>) stringResource(R.string.settings_option, stringResource(item.title), value) else value
    }
    return (parts + extra).joinToString(" · ")
}

/**
 * The highlighted line under a setting when it doesn't work right now (its widget not on the home screen, phone apps
 * without Shizuku), or null. The same in the car and on the phone.
 */
@Composable
fun settingNote(item: SettingItem, settings: LauncherSettings): String? {
    val widget = item.widget
    if (widget != null && !widgetPlaced(settings, widget)) return stringResource(R.string.settings_widget_not_placed)
    val unavailable = (item as? AppsSetting)?.unavailable ?: return null
    val context = LocalContext.current
    // Shizuku started, stopped or allowed meanwhile
    val shizuku by ShizukuAudio.changes.collectAsStateWithLifecycle()
    val reason = remember(shizuku) { unavailable(context) }
    return reason?.let { stringResource(it) }
}

/** Whether a widget's options matter now: its widget is on the home screen. */
@Composable
fun widgetPlaced(settings: LauncherSettings, type: WidgetType): Boolean = currentHome(settings).widgets.any { it.type == type }
