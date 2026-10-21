package com.simoneluconi.betterauto.settings

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.EventLog
import android.content.Context
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.TemperatureUnit
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.media.MediaApps
import kotlinx.coroutines.flow.StateFlow

/** Where a setting is being changed from, for the log, and where it is shown. */
enum class SettingsSurface { CAR, PHONE }

val Everywhere: Set<SettingsSurface> = setOf(SettingsSurface.CAR, SettingsSurface.PHONE)

/** Only a line in the car saying it is set up on the phone. */
val CarOnly: Set<SettingsSurface> = setOf(SettingsSurface.CAR)

/**
 * The settings' topics, in the order the phone's settings home and the car's settings list show them.
 * [summaryKeys]: the settings whose values the phone sums up on the category's row.
 */
enum class SettingsCategory(@param:StringRes val title: Int, val summaryKeys: List<String>) {
    HOME_SCREEN(R.string.settings_cat_home, listOf("home_widgets")),
    DISPLAY(R.string.settings_cat_display, listOf("theme", "app_bar")),
    MUSIC(R.string.settings_cat_music, listOf("auto_play")),
    NOTIFICATIONS(R.string.settings_cat_notifications, listOf("quiet_mode", "notification_privacy")),
    VOICE(R.string.settings_cat_voice, listOf("voice_enabled", "voice_microphone")),
    VEHICLE(R.string.settings_cat_vehicle, listOf("units", "temperature_unit")),
    SMART_HOME(R.string.settings_cat_smart_home, emptyList()),
    ADVANCED(R.string.settings_cat_advanced, listOf("debug_overlay")),
}

/**
 * A user-facing setting. Declared once in [SettingsCatalog]: the car's settings screen and the
 * phone app both list the catalog, so a new entry shows up in both places, under its [category].
 */
sealed class SettingItem(
    /** Stable name, used in the log. */
    val key: String,
    val category: SettingsCategory,
    @param:StringRes val title: Int,
    /** What it does in a short plain line (and what Auto means, where there is one): shown under the title everywhere. */
    @param:StringRes val description: Int,
    /** Rarely changed: under the category's "More options". */
    val more: Boolean,
    val surfaces: Set<SettingsSurface>,
    /** Options of this home widget: listed under the home screen's widget options, saying so when it isn't placed. */
    val widget: WidgetType?,
)

data class SettingOption<T>(val value: T, @param:StringRes val label: Int)

/** The labels of a plain on/off: such settings show as a switch. */
private val SwitchLabels = setOf(R.string.option_off, R.string.option_on, R.string.option_hide, R.string.option_show)

/** Pick one of a few values, shown as a row of chips; two values off/on (or hide/show) are one switch. */
class ChoiceSetting<T>(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    val options: List<SettingOption<T>>,
    private val current: (LauncherSettings) -> StateFlow<T>,
    private val store: (LauncherSettings, T) -> Unit,
    more: Boolean = false,
    /** Why a value can't be picked now (a string), or null: e.g. hiding the last way to the notifications. */
    private val refuse: (LauncherSettings, T) -> Int? = { _, _ -> null },
) : SettingItem(key, category, title, description, more, Everywhere, null) {
    /**
     * Off and on (or hide and show): a switch, `true` meaning on. Two named choices that happen to be stored
     * as a boolean ("Where I left off" / "Home") stay chips: a switch would not say which one "on" is.
     */
    val isSwitch: Boolean get() = options.size == 2 && options.all { it.value is Boolean } &&
        options.all { it.label in SwitchLabels }

    fun state(settings: LauncherSettings): StateFlow<T> = current(settings)

    @StringRes
    fun label(value: T): Int? = options.firstOrNull { it.value == value }?.label

    /** Picks [value]; returns why it can't be (a string to show by the setting), or null when done. */
    @StringRes
    fun select(settings: LauncherSettings, value: T, from: SettingsSurface): Int? {
        if (current(settings).value == value) return null
        refuse(settings, value)?.let {
            EventLog.log("Setting $key: $value refused (from ${from.name.lowercase()})")
            return it
        }
        EventLog.log("Setting $key: ${current(settings).value} -> $value (from ${from.name.lowercase()})")
        store(settings, value)
        return null
    }
}

/**
 * The notifications screen always keeps a way in: the top bar's bell, or the app bar's Notifications
 * button (in the bar or a group). Hiding the last of the two is refused with the reason.
 */
object NotificationsWayIn {
    private fun buttonHidden(settings: LauncherSettings) =
        settings.railLayout.value.any { it.item == RailItem.NOTIFICATIONS && it.slot == RailSlot.HIDDEN }

    @StringRes
    fun refuseHidingBell(settings: LauncherSettings): Int? = R.string.notif_way_in_bell.takeIf { buttonHidden(settings) }

    @StringRes
    fun refuseHidingButton(settings: LauncherSettings): Int? = R.string.notif_way_in_button.takeIf { !settings.showBell.value }
}

/**
 * Things to tick (apps, calendars, chats, the bar's buttons, the home's widgets): a row with [summary]
 * that opens a list with one row per item. Each surface draws its own list.
 */
sealed class ListSetting(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    /** The row's value, e.g. "Shown: %1$d of %2$d": what is ticked, out of how many. */
    @param:StringRes val summary: Int,
    more: Boolean,
    widget: WidgetType?,
) : SettingItem(key, category, title, description, more, Everywhere, widget)

/** Pick apps from a list (pinned to the bar, allowed to notify...). */
class AppsSetting(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes summary: Int,
    /** Shown when there are no apps to pick yet. */
    @param:StringRes val empty: Int,
    private val candidates: (Context, LauncherSettings) -> List<String>,
    /** The stored list; [listedMeansSelected] says whether being in it means on or off. */
    private val stored: (LauncherSettings) -> StateFlow<List<String>>,
    private val listedMeansSelected: Boolean,
    private val toggleStored: (LauncherSettings, String) -> Unit,
    /** A line under the list, when picking needs explaining. */
    @param:StringRes val note: Int? = null,
) : ListSetting(key, category, title, description, summary, more = false, widget = null) {
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
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes summary: Int,
) : ListSetting(key, category, title, description, summary, more = false, widget = null) {
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

/** Order and place (bar, group, hidden) of the app bar's items; in the car, the bar's own editor. */
class RailLayoutSetting(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes summary: Int,
) : ListSetting(key, category, title, description, summary, more = false, widget = null) {
    fun move(settings: LauncherSettings, item: RailItem, delta: Int, from: SettingsSurface) {
        EventLog.log("Setting $key: move ${item.name} ${if (delta < 0) "up" else "down"} (from ${from.name.lowercase()})")
        settings.moveRailItem(item, delta)
    }

    /** Puts [item] in [slot]; returns why it can't be (a string to show), or null when done. */
    @StringRes
    fun place(settings: LauncherSettings, item: RailItem, slot: RailSlot, from: SettingsSurface): Int? {
        if (settings.railLayout.value.firstOrNull { it.item == item }?.slot == slot) return null
        if (item == RailItem.NOTIFICATIONS && slot == RailSlot.HIDDEN) {
            NotificationsWayIn.refuseHidingButton(settings)?.let {
                EventLog.log("Setting $key: hiding ${item.name} refused (from ${from.name.lowercase()})")
                return it
            }
        }
        EventLog.log("Setting $key: ${item.name} -> ${slot.name} (from ${from.name.lowercase()})")
        settings.placeRailItem(item, slot)
        return null
    }

    /** The default bar; with the bell hidden its Notifications button stays, the last way in. */
    fun reset(settings: LauncherSettings, from: SettingsSurface) {
        val default = if (settings.showBell.value) RailLayout.Default
        else RailLayout.Default.map { if (it.item == RailItem.NOTIFICATIONS) it.copy(slot = RailSlot.BAR) else it }
        if (settings.railLayout.value == default) return
        EventLog.log("Setting $key: default (from ${from.name.lowercase()})")
        settings.setRailLayout(default)
    }

    /** Buttons that show (in the bar or a group), out of all. */
    fun shown(layout: List<RailEntry>): Int = layout.count { it.slot != RailSlot.HIDDEN }
}

/**
 * Which widget each home slot of the current layout shows, offering what `ui/widgets/HomeWidgets` says
 * this phone can show; in the car, the home screen's own edit mode.
 */
class WidgetSlotsSetting(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes summary: Int,
) : ListSetting(key, category, title, description, summary, more = false, widget = null) {
    fun pick(settings: LauncherSettings, index: Int, choice: WidgetChoice, from: SettingsSurface) {
        if (settings.widgets.value.getOrNull(index) == choice) return
        EventLog.log("Setting $key: slot ${index + 1} -> ${choice.type.name} (from ${from.name.lowercase()})")
        settings.setWidget(index, choice)
    }
}

/** Which of the phone's calendars the calendar widget shows (all by default). */
class CalendarsSetting(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes summary: Int,
) : ListSetting(key, category, title, description, summary, more = false, widget = WidgetType.CALENDAR) {
    fun isShown(hidden: List<String>, calendarId: Long) = calendarId.toString() !in hidden

    fun toggle(settings: LauncherSettings, calendarId: Long, from: SettingsSurface) {
        val shown = !isShown(settings.hiddenCalendars.value, calendarId)
        EventLog.log("Setting $key: calendar $calendarId ${if (shown) "shown" else "hidden"} (from ${from.name.lowercase()})")
        settings.toggleCalendar(calendarId.toString())
    }
}

/** Set up on the phone only: in the car, a short line saying so where the driver would look for it. */
class PhoneNote(
    key: String,
    category: SettingsCategory,
    @StringRes title: Int,
    @StringRes description: Int,
    widget: WidgetType? = null,
) : SettingItem(key, category, title, description, more = false, CarOnly, widget)

object SettingsCatalog {
    private val onOff = listOf(SettingOption(false, R.string.option_off), SettingOption(true, R.string.option_on))

    private val shownHidden = listOf(SettingOption(false, R.string.option_hide), SettingOption(true, R.string.option_show))

    private val home = SettingsCategory.HOME_SCREEN
    private val display = SettingsCategory.DISPLAY
    private val music = SettingsCategory.MUSIC
    private val notifications = SettingsCategory.NOTIFICATIONS
    private val voice = SettingsCategory.VOICE
    private val vehicle = SettingsCategory.VEHICLE

    val all: List<SettingItem> = listOf(
        // Home screen
        ChoiceSetting(
            "start_section", home, R.string.settings_start_section, R.string.settings_start_section_desc,
            listOf(SettingOption(false, R.string.option_start_last), SettingOption(true, R.string.option_start_home)),
            { it.startOnHome }, LauncherSettings::setStartOnHome,
        ),
        ChoiceSetting(
            "home_layout", home, R.string.settings_home_layout, R.string.settings_home_layout_desc,
            listOf(
                SettingOption(HomeLayout.SINGLE, R.string.layout_one),
                SettingOption(HomeLayout.SPLIT, R.string.layout_two),
                SettingOption(HomeLayout.TRIO, R.string.layout_three),
            ),
            { it.layout }, LauncherSettings::setLayout,
        ),
        WidgetSlotsSetting("home_widgets", home, R.string.settings_home_widgets, R.string.settings_home_widgets_desc, R.string.settings_home_widgets_summary),
        CalendarsSetting(
            "widget_calendars", home, R.string.settings_widget_calendars, R.string.settings_widget_calendars_desc,
            R.string.settings_shown_summary,
        ),
        PhoneNote("weather_note", home, R.string.weather_title, R.string.settings_weather_car_desc, widget = WidgetType.WEATHER),

        // Display
        ChoiceSetting(
            "theme", display, R.string.settings_theme, R.string.settings_theme_desc,
            listOf(
                SettingOption(ThemeMode.AUTO, R.string.option_auto),
                SettingOption(ThemeMode.DAY, R.string.option_day),
                SettingOption(ThemeMode.NIGHT, R.string.option_night),
            ),
            { it.themeMode }, LauncherSettings::setThemeMode,
        ),
        ChoiceSetting(
            "app_bar", display, R.string.settings_app_bar, R.string.settings_app_bar_desc,
            listOf(
                SettingOption(BarPosition.AUTO, R.string.option_auto),
                SettingOption(BarPosition.LEFT, R.string.option_left),
                SettingOption(BarPosition.BOTTOM, R.string.option_bottom),
            ),
            { it.barPosition }, LauncherSettings::setBarPosition,
        ),
        RailLayoutSetting("rail_layout", display, R.string.settings_rail_layout, R.string.settings_rail_layout_desc, R.string.settings_shown_summary),
        ChoiceSetting(
            "clock", display, R.string.settings_clock, R.string.settings_clock_desc,
            listOf(
                SettingOption(ClockStyle.AUTO, R.string.option_clock_auto),
                SettingOption(ClockStyle.H24, R.string.option_clock_24),
                SettingOption(ClockStyle.H12, R.string.option_clock_12),
                SettingOption(ClockStyle.HIDDEN, R.string.option_hide),
            ),
            { it.clockStyle }, LauncherSettings::setClockStyle,
        ),
        ChoiceSetting(
            "date", display, R.string.settings_date, R.string.settings_date_desc,
            listOf(
                SettingOption(DateStyle.SHORT, R.string.option_date_short),
                SettingOption(DateStyle.LONG, R.string.option_date_long),
                SettingOption(DateStyle.NUMERIC, R.string.option_date_numeric),
                SettingOption(DateStyle.HIDDEN, R.string.option_hide),
            ),
            { it.dateStyle }, LauncherSettings::setDateStyle,
        ),
        ChoiceSetting(
            "bell", display, R.string.settings_bell, R.string.settings_bell_desc, shownHidden, { it.showBell }, LauncherSettings::setShowBell, more = true,
            refuse = { settings, show -> if (show) null else NotificationsWayIn.refuseHidingBell(settings) },
        ),
        ChoiceSetting(
            "phone_status", display, R.string.settings_phone_status, R.string.settings_phone_status_desc, shownHidden,
            { it.showPhoneStatus }, LauncherSettings::setShowPhoneStatus, more = true,
        ),

        // Music
        ChoiceSetting("auto_play", music, R.string.settings_auto_play, R.string.settings_auto_play_desc, onOff, { it.autoPlay }, LauncherSettings::setAutoPlay),
        AppsSetting(
            "bar_apps", music, R.string.settings_bar_apps, R.string.settings_bar_apps_desc, R.string.settings_pinned_summary,
            R.string.settings_no_apps,
            { context, _ -> MediaApps.installed(context) },
            { it.pinnedApps }, listedMeansSelected = true, LauncherSettings::togglePinned,
            note = R.string.settings_bar_apps_note,
        ),


        // Notifications and calls
        ChoiceSetting("quiet_mode", notifications, R.string.settings_quiet_mode, R.string.settings_quiet_mode_desc, onOff, { it.quietMode }, LauncherSettings::setQuietMode),
        ChoiceSetting(
            "notification_privacy", notifications, R.string.settings_notification_privacy, R.string.settings_notification_privacy_desc,
            listOf(
                SettingOption(NotificationPrivacy.AUTO, R.string.option_auto),
                SettingOption(NotificationPrivacy.OFF, R.string.option_off),
                SettingOption(NotificationPrivacy.ON, R.string.option_on),
            ),
            { it.notificationPrivacy }, LauncherSettings::setNotificationPrivacy,
        ),
        AppsSetting(
            "notification_apps", notifications, R.string.settings_notification_apps, R.string.settings_notification_apps_desc,
            R.string.settings_allowed_summary, R.string.settings_no_notification_apps,
            { _, settings -> settings.notificationApps.value },
            { it.notificationHiddenApps }, listedMeansSelected = false, LauncherSettings::toggleNotificationApp,
        ),
        MutedChatsSetting("muted_chats", notifications, R.string.settings_muted_chats, R.string.settings_muted_chats_desc, R.string.settings_muted_summary),
        ChoiceSetting(
            "contact_names", notifications, R.string.settings_contact_names, R.string.settings_contact_names_desc,
            listOf(
                SettingOption(NameOrder.FIRST_LAST, R.string.option_first_last),
                SettingOption(NameOrder.LAST_FIRST, R.string.option_last_first),
            ),
            { it.nameOrder }, LauncherSettings::setNameOrder,
        ),
        ChoiceSetting(
            "notification_groups", notifications, R.string.settings_notification_groups, R.string.settings_notification_groups_desc, shownHidden,
            { it.notificationGroups }, LauncherSettings::setNotificationGroups, more = true,
        ),

        // Voice
        ChoiceSetting("voice_enabled", voice, R.string.settings_voice, R.string.settings_voice_desc, onOff, { it.voiceEnabled }, LauncherSettings::setVoiceEnabled),
        ChoiceSetting(
            "voice_microphone", voice, R.string.settings_voice_microphone, R.string.settings_voice_microphone_desc,
            // CAR is stored as before: it already meant the car's microphone when there is one, else the phone's
            listOf(SettingOption(MicSource.CAR, R.string.option_auto), SettingOption(MicSource.PHONE, R.string.option_mic_phone)),
            { it.voiceMicrophone }, LauncherSettings::setVoiceMicrophone,
        ),
        ChoiceSetting("voice_replies", voice, R.string.settings_voice_replies, R.string.settings_voice_replies_desc, onOff, { it.voiceReplies }, LauncherSettings::setVoiceReplies),
        ChoiceSetting("mic_button", voice, R.string.settings_mic_button, R.string.settings_mic_button_desc, shownHidden, { it.showMicButton }, LauncherSettings::setShowMicButton),

        // Vehicle and units
        ChoiceSetting(
            "units", vehicle, R.string.settings_units, R.string.settings_units_desc,
            listOf(
                SettingOption(UnitSystem.AUTO, R.string.option_auto),
                SettingOption(UnitSystem.METRIC, R.string.option_metric),
                SettingOption(UnitSystem.IMPERIAL, R.string.option_imperial),
            ),
            { it.unitSystem }, LauncherSettings::setUnitSystem,
        ),
        ChoiceSetting(
            "temperature_unit", vehicle, R.string.settings_temperature_unit, R.string.settings_temperature_unit_desc,
            listOf(
                SettingOption(TemperatureUnit.AUTO, R.string.option_auto),
                SettingOption(TemperatureUnit.CELSIUS, R.string.option_celsius),
                SettingOption(TemperatureUnit.FAHRENHEIT, R.string.option_fahrenheit),
            ),
            { it.temperatureUnit }, LauncherSettings::setTemperatureUnit,
        ),
        ChoiceSetting("engine_light", vehicle, R.string.settings_engine_light, R.string.settings_engine_light_desc, shownHidden, { it.showEngineLight }, LauncherSettings::setShowEngineLight),

        // Smart home: linked and chosen on the phone
        PhoneNote("smart_home_note", SettingsCategory.SMART_HOME, R.string.smarthome_title, R.string.settings_smart_home_car_desc),

        // Advanced
        ChoiceSetting(
            "unknown_audio", SettingsCategory.ADVANCED, R.string.settings_unknown_audio, R.string.settings_unknown_audio_desc, onOff,
            { it.unknownAudio }, LauncherSettings::setUnknownAudio,
        ),
        ChoiceSetting("debug_overlay", SettingsCategory.ADVANCED, R.string.settings_debug, R.string.settings_debug_desc, onOff, { it.debugOverlay }, LauncherSettings::setDebugOverlay),
    )

    /** A category's settings on one surface, in the catalog's order. */
    fun items(category: SettingsCategory, surface: SettingsSurface): List<SettingItem> =
        all.filter { it.category == category && surface in it.surfaces }

    /** The categories with something to show in the car. */
    fun carCategories(): List<SettingsCategory> = SettingsCategory.entries.filter { items(it, SettingsSurface.CAR).isNotEmpty() }
}
