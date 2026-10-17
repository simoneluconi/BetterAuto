package com.simoneluconi.betterauto.settings

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.EventLog
import android.content.Context
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.TemperatureUnit
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.media.MediaApps
import kotlinx.coroutines.flow.StateFlow

/** Where a setting is being changed from, for the log. */
enum class SettingsSurface { CAR, PHONE }

/**
 * A user-facing setting. Declared once in [SettingsCatalog]: the car's settings screen and the
 * phone app both list the catalog, so a new entry shows up in both places.
 */
sealed interface SettingItem {
    /** Stable name, used in the log. */
    val key: String

    @get:StringRes
    val title: Int
}

data class SettingOption<T>(val value: T, @param:StringRes val label: Int)

/** Pick one of a few values, shown as a row of chips. */
class ChoiceSetting<T>(
    override val key: String,
    @param:StringRes override val title: Int,
    val options: List<SettingOption<T>>,
    private val current: (LauncherSettings) -> StateFlow<T>,
    private val store: (LauncherSettings, T) -> Unit,
) : SettingItem {
    fun state(settings: LauncherSettings): StateFlow<T> = current(settings)

    fun select(settings: LauncherSettings, value: T, from: SettingsSurface) {
        if (current(settings).value == value) return
        EventLog.log("Setting $key: ${current(settings).value} -> $value (from ${from.name.lowercase()})")
        store(settings, value)
    }
}

/** A one-shot command, shown as a single button. */
class ActionSetting(
    override val key: String,
    @param:StringRes override val title: Int,
    @param:StringRes val label: Int,
    private val action: (LauncherSettings) -> Unit,
) : SettingItem {
    fun run(settings: LauncherSettings, from: SettingsSurface) {
        EventLog.log("Setting $key: run (from ${from.name.lowercase()})")
        action(settings)
    }
}

/** Pick apps from a list (pinned to the bar, allowed to notify...): each surface draws its own app chips. */
class AppsSetting(
    override val key: String,
    @param:StringRes override val title: Int,
    /** Shown when there are no apps to pick yet. */
    @param:StringRes val empty: Int,
    private val candidates: (Context, LauncherSettings) -> List<String>,
    /** The stored list; [listedMeansSelected] says whether being in it means on or off. */
    private val stored: (LauncherSettings) -> StateFlow<List<String>>,
    private val listedMeansSelected: Boolean,
    private val toggleStored: (LauncherSettings, String) -> Unit,
) : SettingItem {
    fun apps(context: Context, settings: LauncherSettings): List<String> = candidates(context, settings)

    fun state(settings: LauncherSettings): StateFlow<List<String>> = stored(settings)

    fun isSelected(storedList: List<String>, packageName: String) = (packageName in storedList) == listedMeansSelected

    fun toggle(settings: LauncherSettings, packageName: String, from: SettingsSurface) {
        val selected = !isSelected(stored(settings).value, packageName)
        EventLog.log("Setting $key: $packageName ${if (selected) "on" else "off"} (from ${from.name.lowercase()})")
        toggleStored(settings, packageName)
    }
}

/** Chats muted from the notifications widget, to bring them back. */
class MutedChatsSetting(
    override val key: String,
    @param:StringRes override val title: Int,
) : SettingItem {
    fun unmute(settings: LauncherSettings, conversationKey: String, from: SettingsSurface) {
        EventLog.log("Setting $key: unmute a chat of ${conversationKey.substringBefore('|')} (from ${from.name.lowercase()})")
        settings.toggleMutedChat(conversationKey)
    }

    companion object {
        /** "package|chat" to the chat name. */
        fun chatName(conversationKey: String) = conversationKey.substringAfter('|')

        fun packageOf(conversationKey: String) = conversationKey.substringBefore('|')
    }
}

/** A title that starts a group of settings. */
class SettingHeader(override val key: String, @param:StringRes override val title: Int) : SettingItem

/** Order and place (bar, group, hidden) of the app bar's items; each surface draws its own editor. */
class RailLayoutSetting(override val key: String, @param:StringRes override val title: Int) : SettingItem {
    fun move(settings: LauncherSettings, item: RailItem, delta: Int, from: SettingsSurface) {
        EventLog.log("Setting $key: move ${item.name} ${if (delta < 0) "up" else "down"} (from ${from.name.lowercase()})")
        settings.moveRailItem(item, delta)
    }

    fun place(settings: LauncherSettings, item: RailItem, slot: RailSlot, from: SettingsSurface) {
        if (settings.railLayout.value.firstOrNull { it.item == item }?.slot == slot) return
        EventLog.log("Setting $key: ${item.name} -> ${slot.name} (from ${from.name.lowercase()})")
        settings.placeRailItem(item, slot)
    }
}

object SettingsCatalog {
    private val onOff = listOf(SettingOption(false, R.string.option_off), SettingOption(true, R.string.option_on))

    private val shownHidden = listOf(SettingOption(true, R.string.option_show), SettingOption(false, R.string.option_hide))

    val all: List<SettingItem> = listOf(
        SettingHeader("look", R.string.settings_section_look),
        ChoiceSetting(
            "theme", R.string.settings_theme,
            listOf(
                SettingOption(ThemeMode.AUTO, R.string.option_auto),
                SettingOption(ThemeMode.DAY, R.string.option_day),
                SettingOption(ThemeMode.NIGHT, R.string.option_night),
            ),
            { it.themeMode }, LauncherSettings::setThemeMode,
        ),
        ChoiceSetting(
            "home_layout", R.string.settings_home_layout,
            listOf(
                SettingOption(HomeLayout.SINGLE, R.string.layout_one),
                SettingOption(HomeLayout.SPLIT, R.string.layout_two),
                SettingOption(HomeLayout.TRIO, R.string.layout_three),
            ),
            { it.layout }, LauncherSettings::setLayout,
        ),
        ActionSetting("widget_order", R.string.settings_widget_order, R.string.rotate_widgets, LauncherSettings::rotateWidgets),
        ChoiceSetting(
            "units", R.string.settings_units,
            listOf(
                SettingOption(UnitSystem.AUTO, R.string.option_auto),
                SettingOption(UnitSystem.METRIC, R.string.option_metric),
                SettingOption(UnitSystem.IMPERIAL, R.string.option_imperial),
            ),
            { it.unitSystem }, LauncherSettings::setUnitSystem,
        ),
        ChoiceSetting(
            "temperature_unit", R.string.settings_temperature_unit,
            listOf(
                SettingOption(TemperatureUnit.AUTO, R.string.option_auto),
                SettingOption(TemperatureUnit.CELSIUS, R.string.option_celsius),
                SettingOption(TemperatureUnit.FAHRENHEIT, R.string.option_fahrenheit),
            ),
            { it.temperatureUnit }, LauncherSettings::setTemperatureUnit,
        ),

        SettingHeader("app_bar_section", R.string.settings_section_app_bar),
        ChoiceSetting(
            "app_bar", R.string.settings_app_bar,
            listOf(
                SettingOption(BarPosition.AUTO, R.string.option_auto),
                SettingOption(BarPosition.LEFT, R.string.option_left),
                SettingOption(BarPosition.BOTTOM, R.string.option_bottom),
            ),
            { it.barPosition }, LauncherSettings::setBarPosition,
        ),
        RailLayoutSetting("rail_layout", R.string.settings_rail_layout),
        AppsSetting(
            "bar_apps", R.string.settings_bar_apps, R.string.settings_no_apps,
            { context, _ -> MediaApps.installed(context) },
            { it.pinnedApps }, listedMeansSelected = true, LauncherSettings::togglePinned,
        ),

        SettingHeader("status_bar", R.string.settings_section_status_bar),
        ChoiceSetting(
            "clock", R.string.settings_clock,
            listOf(
                SettingOption(ClockStyle.AUTO, R.string.option_clock_auto),
                SettingOption(ClockStyle.H24, R.string.option_clock_24),
                SettingOption(ClockStyle.H12, R.string.option_clock_12),
                SettingOption(ClockStyle.HIDDEN, R.string.option_hide),
            ),
            { it.clockStyle }, LauncherSettings::setClockStyle,
        ),
        ChoiceSetting(
            "date", R.string.settings_date,
            listOf(
                SettingOption(DateStyle.SHORT, R.string.option_date_short),
                SettingOption(DateStyle.LONG, R.string.option_date_long),
                SettingOption(DateStyle.NUMERIC, R.string.option_date_numeric),
                SettingOption(DateStyle.HIDDEN, R.string.option_hide),
            ),
            { it.dateStyle }, LauncherSettings::setDateStyle,
        ),
        ChoiceSetting("engine_light", R.string.settings_engine_light, shownHidden, { it.showEngineLight }, LauncherSettings::setShowEngineLight),
        ChoiceSetting("bell", R.string.settings_bell, shownHidden, { it.showBell }, LauncherSettings::setShowBell),
        ChoiceSetting("signal", R.string.settings_signal, shownHidden, { it.showSignal }, LauncherSettings::setShowSignal),
        ChoiceSetting("battery", R.string.settings_battery, shownHidden, { it.showBattery }, LauncherSettings::setShowBattery),
        ChoiceSetting("layout_button", R.string.settings_layout_button, shownHidden, { it.showLayoutButton }, LauncherSettings::setShowLayoutButton),

        SettingHeader("media", R.string.settings_section_media),
        ChoiceSetting(
            "music_view", R.string.settings_music_view,
            listOf(
                SettingOption(false, R.string.option_music_fullscreen),
                SettingOption(true, R.string.option_music_panel),
            ),
            { it.musicPanel }, LauncherSettings::setMusicPanel,
        ),
        ChoiceSetting("auto_play", R.string.settings_auto_play, onOff, { it.autoPlay }, LauncherSettings::setAutoPlay),

        SettingHeader("phone", R.string.settings_section_phone),
        ChoiceSetting(
            "contact_names", R.string.settings_contact_names,
            listOf(
                SettingOption(NameOrder.FIRST_LAST, R.string.option_first_last),
                SettingOption(NameOrder.LAST_FIRST, R.string.option_last_first),
            ),
            { it.nameOrder }, LauncherSettings::setNameOrder,
        ),

        SettingHeader("voice", R.string.settings_section_voice),
        ChoiceSetting("voice_enabled", R.string.settings_voice, onOff, { it.voiceEnabled }, LauncherSettings::setVoiceEnabled),
        ChoiceSetting(
            "voice_microphone", R.string.settings_voice_microphone,
            listOf(SettingOption(MicSource.CAR, R.string.option_mic_car), SettingOption(MicSource.PHONE, R.string.option_mic_phone)),
            { it.voiceMicrophone }, LauncherSettings::setVoiceMicrophone,
        ),
        ChoiceSetting("voice_replies", R.string.settings_voice_replies, onOff, { it.voiceReplies }, LauncherSettings::setVoiceReplies),
        ChoiceSetting("mic_button", R.string.settings_mic_button, shownHidden, { it.showMicButton }, LauncherSettings::setShowMicButton),

        SettingHeader("notifications", R.string.settings_section_notifications),
        ChoiceSetting("quiet_mode", R.string.settings_quiet_mode, onOff, { it.quietMode }, LauncherSettings::setQuietMode),
        ChoiceSetting(
            "notification_groups", R.string.settings_notification_groups, shownHidden,
            { it.notificationGroups }, LauncherSettings::setNotificationGroups,
        ),
        AppsSetting(
            "notification_apps", R.string.settings_notification_apps, R.string.settings_no_notification_apps,
            { _, settings -> settings.notificationApps.value },
            { it.notificationHiddenApps }, listedMeansSelected = false, LauncherSettings::toggleNotificationApp,
        ),
        MutedChatsSetting("muted_chats", R.string.settings_muted_chats),

        SettingHeader("advanced", R.string.settings_section_advanced),
        ChoiceSetting("debug_overlay", R.string.settings_debug, onOff, { it.debugOverlay }, LauncherSettings::setDebugOverlay),
    )
}
