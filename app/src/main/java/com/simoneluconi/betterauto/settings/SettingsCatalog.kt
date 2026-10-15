package com.simoneluconi.betterauto.settings

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.EventLog
import android.content.Context
import com.simoneluconi.betterauto.R
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

object SettingsCatalog {
    private val onOff = listOf(SettingOption(false, R.string.option_off), SettingOption(true, R.string.option_on))

    val all: List<SettingItem> = listOf(
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
            "app_bar", R.string.settings_app_bar,
            listOf(
                SettingOption(BarPosition.AUTO, R.string.option_auto),
                SettingOption(BarPosition.LEFT, R.string.option_left),
                SettingOption(BarPosition.BOTTOM, R.string.option_bottom),
            ),
            { it.barPosition }, LauncherSettings::setBarPosition,
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
            "music_view", R.string.settings_music_view,
            listOf(
                SettingOption(false, R.string.option_music_fullscreen),
                SettingOption(true, R.string.option_music_panel),
            ),
            { it.musicPanel }, LauncherSettings::setMusicPanel,
        ),
        ChoiceSetting(
            "contact_names", R.string.settings_contact_names,
            listOf(
                SettingOption(NameOrder.FIRST_LAST, R.string.option_first_last),
                SettingOption(NameOrder.LAST_FIRST, R.string.option_last_first),
            ),
            { it.nameOrder }, LauncherSettings::setNameOrder,
        ),
        ChoiceSetting("auto_play", R.string.settings_auto_play, onOff, { it.autoPlay }, LauncherSettings::setAutoPlay),
        ChoiceSetting("debug_overlay", R.string.settings_debug, onOff, { it.debugOverlay }, LauncherSettings::setDebugOverlay),
        AppsSetting(
            "bar_apps", R.string.settings_bar_apps, R.string.settings_no_apps,
            { context, _ -> MediaApps.installed(context) },
            { it.pinnedApps }, listedMeansSelected = true, LauncherSettings::togglePinned,
        ),
        ChoiceSetting("quiet_mode", R.string.settings_quiet_mode, onOff, { it.quietMode }, LauncherSettings::setQuietMode),
        ChoiceSetting(
            "notification_groups", R.string.settings_notification_groups,
            listOf(SettingOption(true, R.string.option_show), SettingOption(false, R.string.option_hide)),
            { it.notificationGroups }, LauncherSettings::setNotificationGroups,
        ),
        AppsSetting(
            "notification_apps", R.string.settings_notification_apps, R.string.settings_no_notification_apps,
            { _, settings -> settings.notificationApps.value },
            { it.notificationHiddenApps }, listedMeansSelected = false, LauncherSettings::toggleNotificationApp,
        ),
        MutedChatsSetting("muted_chats", R.string.settings_muted_chats),
    )
}
