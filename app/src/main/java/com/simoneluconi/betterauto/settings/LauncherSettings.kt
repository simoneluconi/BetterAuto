package com.simoneluconi.betterauto.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { AUTO, DAY, NIGHT }

enum class BarPosition { AUTO, LEFT, BOTTOM }

enum class HomeLayout(val slots: Int) { SINGLE(1), SPLIT(2), TRIO(3) }

enum class Section { HOME, MUSIC, MAP, PHONE, NOTIFICATIONS, SETTINGS }

enum class WidgetType { MUSIC, MAP, NOTIFICATIONS }

/** How contact names are written and sorted: "Mario Rossi" or "Rossi Mario". */
enum class NameOrder { FIRST_LAST, LAST_FIRST }

/**
 * Launcher preferences, persisted so the car screen comes back exactly as it was left.
 * Every setter saves immediately.
 */
class LauncherSettings private constructor(private val prefs: SharedPreferences) {
    private val _themeMode = MutableStateFlow(enumOf(KEY_THEME, ThemeMode.AUTO))
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _barPosition = MutableStateFlow(enumOf(KEY_BAR, BarPosition.AUTO))
    val barPosition: StateFlow<BarPosition> = _barPosition

    private val _layout = MutableStateFlow(enumOf(KEY_LAYOUT, HomeLayout.SPLIT))
    val layout: StateFlow<HomeLayout> = _layout

    private val _section = MutableStateFlow(enumOf(KEY_SECTION, Section.HOME))
    val section: StateFlow<Section> = _section

    private val _widgets = MutableStateFlow(loadWidgets())
    val widgets: StateFlow<List<WidgetType>> = _widgets

    private val _musicPanel = MutableStateFlow(prefs.getBoolean(KEY_MUSIC_PANEL, false))
    /** Music section: player plus library panel (true) or full-screen player (false, the default). */
    val musicPanel: StateFlow<Boolean> = _musicPanel

    private val _musicTab = MutableStateFlow(prefs.getString(KEY_MUSIC_TAB, null))
    val musicTab: StateFlow<String?> = _musicTab

    private val _nameOrder = MutableStateFlow(enumOf(KEY_NAME_ORDER, NameOrder.FIRST_LAST))
    val nameOrder: StateFlow<NameOrder> = _nameOrder

    private val _phoneTab = MutableStateFlow(prefs.getString(KEY_PHONE_TAB, null))
    val phoneTab: StateFlow<String?> = _phoneTab

    private val _quietMode = MutableStateFlow(prefs.getBoolean(KEY_QUIET, false))
    /** No notification banners; notifications still collect in the widget. */
    val quietMode: StateFlow<Boolean> = _quietMode

    private val _notificationGroups = MutableStateFlow(prefs.getBoolean(KEY_NOTIF_GROUPS, true))
    /** Show notifications from group chats. */
    val notificationGroups: StateFlow<Boolean> = _notificationGroups

    private val _notificationApps = MutableStateFlow(loadList(KEY_NOTIF_APPS))
    /** Apps that sent notifications, so they can be filtered. */
    val notificationApps: StateFlow<List<String>> = _notificationApps

    private val _notificationHiddenApps = MutableStateFlow(loadList(KEY_NOTIF_HIDDEN))
    val notificationHiddenApps: StateFlow<List<String>> = _notificationHiddenApps

    private val _mutedChats = MutableStateFlow(loadList(KEY_MUTED_CHATS, separator = "\n"))
    /** Muted chats, as "package|chat name". */
    val mutedChats: StateFlow<List<String>> = _mutedChats

    private val _autoPlay = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PLAY, true))
    val autoPlay: StateFlow<Boolean> = _autoPlay

    private val _debugOverlay = MutableStateFlow(prefs.getBoolean(KEY_DEBUG, false))
    val debugOverlay: StateFlow<Boolean> = _debugOverlay

    private val _pinnedApps = MutableStateFlow(loadList(KEY_PINNED))
    val pinnedApps: StateFlow<List<String>> = _pinnedApps

    private val _recentApps = MutableStateFlow(loadList(KEY_RECENT))
    val recentApps: StateFlow<List<String>> = _recentApps

    fun setThemeMode(value: ThemeMode) = saveEnum(KEY_THEME, value, _themeMode)

    fun setBarPosition(value: BarPosition) = saveEnum(KEY_BAR, value, _barPosition)

    fun setLayout(value: HomeLayout) = saveEnum(KEY_LAYOUT, value, _layout)

    fun setSection(value: Section) = saveEnum(KEY_SECTION, value, _section)

    fun cycleLayout() = setLayout(HomeLayout.entries[(layout.value.ordinal + 1) % HomeLayout.entries.size])

    /** Rotates widget order, so each widget can take the big slot. */
    fun rotateWidgets() {
        val list = widgets.value
        val rotated = list.drop(1) + list.first()
        _widgets.value = rotated
        prefs.edit { putString(KEY_WIDGETS, rotated.joinToString(",") { it.name }) }
    }

    fun setMusicPanel(value: Boolean) {
        _musicPanel.value = value
        prefs.edit { putBoolean(KEY_MUSIC_PANEL, value) }
    }

    fun setMusicTab(value: String) {
        _musicTab.value = value
        prefs.edit { putString(KEY_MUSIC_TAB, value) }
    }

    fun setNameOrder(value: NameOrder) = saveEnum(KEY_NAME_ORDER, value, _nameOrder)

    fun setPhoneTab(value: String) {
        _phoneTab.value = value
        prefs.edit { putString(KEY_PHONE_TAB, value) }
    }

    fun setQuietMode(value: Boolean) {
        _quietMode.value = value
        prefs.edit { putBoolean(KEY_QUIET, value) }
    }

    fun setNotificationGroups(value: Boolean) {
        _notificationGroups.value = value
        prefs.edit { putBoolean(KEY_NOTIF_GROUPS, value) }
    }

    fun markNotificationApp(packageName: String) {
        val list = notificationApps.value
        if (packageName in list) return
        saveList(KEY_NOTIF_APPS, (list + packageName).takeLast(MAX_NOTIF_APPS), _notificationApps)
    }

    fun toggleNotificationApp(packageName: String) {
        val list = notificationHiddenApps.value
        saveList(KEY_NOTIF_HIDDEN, if (packageName in list) list - packageName else list + packageName, _notificationHiddenApps)
    }

    fun toggleMutedChat(conversationKey: String) {
        val list = mutedChats.value
        saveList(
            KEY_MUTED_CHATS, if (conversationKey in list) list - conversationKey else list + conversationKey,
            _mutedChats, separator = "\n",
        )
    }

    fun setAutoPlay(value: Boolean) {
        _autoPlay.value = value
        prefs.edit { putBoolean(KEY_AUTO_PLAY, value) }
    }

    fun setDebugOverlay(value: Boolean) {
        _debugOverlay.value = value
        prefs.edit { putBoolean(KEY_DEBUG, value) }
    }

    fun togglePinned(packageName: String) {
        val list = pinnedApps.value
        val updated = if (packageName in list) list - packageName else list + packageName
        saveList(KEY_PINNED, updated, _pinnedApps)
    }

    /** Remembers the media app that was last playing, most recent first. */
    fun markRecent(packageName: String) {
        val list = recentApps.value
        if (list.firstOrNull() == packageName) return
        saveList(KEY_RECENT, (listOf(packageName) + (list - packageName)).take(MAX_RECENT), _recentApps)
    }

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun <T : Enum<T>> saveEnum(key: String, value: T, flow: MutableStateFlow<T>) {
        flow.value = value
        prefs.edit { putString(key, value.name) }
    }

    private fun loadWidgets(): List<WidgetType> {
        val saved = prefs.getString(KEY_WIDGETS, null)?.split(",")
            ?.mapNotNull { name -> WidgetType.entries.firstOrNull { it.name == name } }
        // Keep every widget exactly once, even if new ones were added since the last save
        return ((saved ?: emptyList()) + WidgetType.entries).distinct()
    }

    private fun loadList(key: String, separator: String = ","): List<String> =
        prefs.getString(key, null)?.split(separator)?.filter { it.isNotBlank() } ?: emptyList()

    private fun saveList(key: String, value: List<String>, flow: MutableStateFlow<List<String>>, separator: String = ",") {
        flow.value = value
        prefs.edit { putString(key, value.joinToString(separator)) }
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_BAR = "bar_position"
        private const val KEY_LAYOUT = "home_layout"
        private const val KEY_SECTION = "section"
        private const val KEY_WIDGETS = "widgets"
        private const val KEY_DEBUG = "debug_overlay"
        private const val KEY_AUTO_PLAY = "auto_play"
        private const val KEY_MUSIC_PANEL = "music_panel"
        private const val KEY_MUSIC_TAB = "music_tab"
        private const val KEY_NAME_ORDER = "name_order"
        private const val KEY_QUIET = "quiet_mode"
        private const val KEY_NOTIF_GROUPS = "notification_groups"
        private const val KEY_NOTIF_APPS = "notification_apps"
        private const val KEY_NOTIF_HIDDEN = "notification_hidden_apps"
        private const val KEY_MUTED_CHATS = "muted_chats"
        private const val MAX_NOTIF_APPS = 40
        private const val KEY_PHONE_TAB = "phone_tab"
        private const val KEY_PINNED = "pinned_apps"
        private const val KEY_RECENT = "recent_apps"
        private const val MAX_RECENT = 4

        @Volatile
        private var instance: LauncherSettings? = null

        fun get(context: Context): LauncherSettings = instance ?: synchronized(this) {
            instance ?: LauncherSettings(
                context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
