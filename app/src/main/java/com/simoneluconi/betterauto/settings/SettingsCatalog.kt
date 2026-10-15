package com.simoneluconi.betterauto.settings

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
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

/** The media apps pinned to the app bar: each surface draws its own app picker. */
class PinnedAppsSetting(
    override val key: String,
    @param:StringRes override val title: Int,
) : SettingItem {
    fun toggle(settings: LauncherSettings, packageName: String, from: SettingsSurface) {
        val pinned = packageName !in settings.pinnedApps.value
        EventLog.log("Setting $key: ${if (pinned) "pin" else "unpin"} $packageName (from ${from.name.lowercase()})")
        settings.togglePinned(packageName)
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
        PinnedAppsSetting("bar_apps", R.string.settings_bar_apps),
    )
}
