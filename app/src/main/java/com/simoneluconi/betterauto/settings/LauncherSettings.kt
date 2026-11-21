package com.simoneluconi.betterauto.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.simoneluconi.betterauto.car.TemperatureUnit
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.projection.PhoneAppChoices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

enum class ThemeMode { AUTO, DAY, NIGHT }

/** LEFT: beside the screen, on the driver's side (the name stays from before right-hand drive was handled). */
enum class BarPosition { AUTO, LEFT, BOTTOM }

/** Which side the driver sits: Auto follows the car (left when it says nothing). */
enum class DriverSide {
    AUTO, LEFT, RIGHT;

    /** True when the bar and the big home widget go on the right. */
    fun onRight(carSays: Boolean?): Boolean = when (this) {
        AUTO -> carSays == true
        LEFT -> false
        RIGHT -> true
    }
}

/** SMART_HOME shows the provider in [com.simoneluconi.betterauto.smarthome.SmartHome.screenProvider]. */
/** [APPS]: the grid of every app (ours and the phone's); [PHONE_APP]: a phone app open inside the launcher. */
enum class Section { HOME, MUSIC, MAP, PHONE, VEHICLE, NOTIFICATIONS, SETTINGS, SMART_HOME, APPS, PHONE_APP }

/**
 * Kinds of home widget; what each one shows and when it is offered is in `ui/widgets/HomeWidgets`.
 * [takesParam]: only meaningful with a parameter (which smart-home group), never a blank default.
 */
enum class WidgetType(val takesParam: Boolean = false) { MUSIC, MAP, NOTIFICATIONS, CLOCK, CALENDAR, FAVORITES, WEATHER, SMART_HOME(takesParam = true) }

/** How contact names are written and sorted: "Mario Rossi" or "Rossi Mario". */
enum class NameOrder { FIRST_LAST, LAST_FIRST }

/** Whether notifications hide what they say: Auto follows who the car says is aboard. */
enum class NotificationPrivacy { AUTO, OFF, ON }

/** The map turns with the car (tilted, looking ahead) or keeps north at the top. */
enum class MapOrientation { HEADING, NORTH }

/** How close the map starts and comes back to (MapLibre zoom levels); the knob still zooms from there. */
enum class MapZoom(val level: Double) { CLOSE(19.0), MEDIUM(17.0), FAR(16.0) }

/** What the navigation says: every instruction, only the arrivals (stops, destination), or nothing. */
enum class GuidanceVoice { FULL, ALERTS, MUTE }

/**
 * How the driver wants the route: the fastest or the shortest, and the kinds of road to keep off where there is
 * another way (never "no route" because of them).
 */
data class RoutePreferences(
    val shortest: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val avoidCashTolls: Boolean = false,
    val avoidBorders: Boolean = false,
) {
    val isDefault: Boolean get() = this == RoutePreferences()

    /** For the log: "shortest, no tolls, no ferries" (empty when nothing is asked). */
    fun describe(): String = listOfNotNull(
        "shortest".takeIf { shortest }, "no tolls".takeIf { avoidTolls }, "no motorways".takeIf { avoidHighways },
        "no ferries".takeIf { avoidFerries }, "no unpaved".takeIf { avoidUnpaved },
        "no cash-only tolls".takeIf { avoidCashTolls }, "no borders".takeIf { avoidBorders },
    ).joinToString(", ")
}

/** How big the car screen is drawn: [scale] times the density the car states (Auto: as it states). */
enum class UiSize(val scale: Double) { SMALLER(0.9), AUTO(1.0), LARGER(1.1), LARGEST(1.2) }

/** When a phone app opens on the car's whole screen: only once chosen in its panel, as last chosen there, or always. */
enum class AppFullscreenMode { WHEN_CHOSEN, PER_APP, ALWAYS }

/**
 * Launcher preferences, persisted so the car screen comes back exactly as it was left.
 * Every setter saves immediately.
 */
class LauncherSettings private constructor(private val prefs: SharedPreferences) {
    private val _themeMode = MutableStateFlow(enumOf(KEY_THEME, ThemeMode.AUTO))
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _barPosition = MutableStateFlow(enumOf(KEY_BAR, BarPosition.AUTO))
    val barPosition: StateFlow<BarPosition> = _barPosition

    private val _driverSide = MutableStateFlow(enumOf(KEY_DRIVER_SIDE, DriverSide.AUTO))
    val driverSide: StateFlow<DriverSide> = _driverSide

    private val _uiSize = MutableStateFlow(enumOf(KEY_UI_SIZE, UiSize.AUTO))
    val uiSize: StateFlow<UiSize> = _uiSize

    init {
        // The fixed layouts (one, two or three widgets) before the free arrangement: started over, not carried on
        prefs.edit { for (key in OLD_HOME_KEYS) remove(key) }
    }

    private val _homes = ScreenShape.entries.associateWith { MutableStateFlow(loadHome(it)) }

    /**
     * The home's widgets for each kind of car screen, as the driver arranged them: a car with the other kind of
     * screen has its own, so changing car doesn't upset either. One never arranged follows the other, turned.
     */
    fun home(shape: ScreenShape): StateFlow<HomeArrangement> = _homes.getValue(shape)

    private val _homeShape = MutableStateFlow(enumOf(KEY_HOME_SHAPE, ScreenShape.LANDSCAPE))
    /** The kind of screen of the last car: what the phone edits first. */
    val homeShape: StateFlow<ScreenShape> = _homeShape

    private val _homeAreas = ScreenShape.entries.associateWith { MutableStateFlow(loadHomeArea(it)) }
    /** The home's area (design units) in the last car with each kind of screen: the phone draws it that shape. */
    fun homeArea(shape: ScreenShape): StateFlow<Pair<Float, Float>> = _homeAreas.getValue(shape)

    private val _section = MutableStateFlow(enumOf(KEY_SECTION, Section.HOME))
    val section: StateFlow<Section> = _section

    private val _musicPanel = MutableStateFlow(prefs.getBoolean(KEY_MUSIC_PANEL, false))
    /** Music section: player plus library panel (true) or full-screen player (false, the default); the screen remembers how it was left. */
    val musicPanel: StateFlow<Boolean> = _musicPanel

    private val _musicTab = MutableStateFlow(prefs.getString(KEY_MUSIC_TAB, null))
    val musicTab: StateFlow<String?> = _musicTab

    private val _nameOrder = MutableStateFlow(enumOf(KEY_NAME_ORDER, NameOrder.FIRST_LAST))
    val nameOrder: StateFlow<NameOrder> = _nameOrder

    private val _phoneTab = MutableStateFlow(prefs.getString(KEY_PHONE_TAB, null))
    val phoneTab: StateFlow<String?> = _phoneTab

    private val _railLayout = MutableStateFlow(RailLayout.decode(prefs.getString(KEY_RAIL, null)))
    /** Order and place (bar, group, hidden) of the app bar's items. */
    val railLayout: StateFlow<List<RailEntry>> = _railLayout

    private val _clockStyle = MutableStateFlow(enumOf(KEY_CLOCK, ClockStyle.AUTO))
    val clockStyle: StateFlow<ClockStyle> = _clockStyle

    private val _dateStyle = MutableStateFlow(enumOf(KEY_DATE, DateStyle.SHORT))
    val dateStyle: StateFlow<DateStyle> = _dateStyle

    private val _mapOrientation = MutableStateFlow(enumOf(KEY_MAP_ORIENTATION, MapOrientation.HEADING))
    val mapOrientation: StateFlow<MapOrientation> = _mapOrientation

    private val _mapZoom = MutableStateFlow(enumOf(KEY_MAP_ZOOM, MapZoom.MEDIUM))
    val mapZoom: StateFlow<MapZoom> = _mapZoom

    /** The map zooms out as the car goes faster. */
    private val _mapAutoZoom = boolFlow(KEY_MAP_AUTO_ZOOM, true)
    val mapAutoZoom: StateFlow<Boolean> = _mapAutoZoom

    /** The speed limit of the road, on the map. */
    private val _showSpeedLimit = boolFlow(KEY_SHOW_SPEED_LIMIT, true)
    val showSpeedLimit: StateFlow<Boolean> = _showSpeedLimit

    /** The navigation's voice, kept as the driver last left it (on the map or here). */
    private val _guidanceVoice = MutableStateFlow(enumOf(KEY_GUIDANCE_VOICE, GuidanceVoice.FULL))
    val guidanceVoice: StateFlow<GuidanceVoice> = _guidanceVoice

    // The route's preferences, one setting each
    private val _routeShortest = boolFlow(KEY_ROUTE_SHORTEST, false)
    val routeShortest: StateFlow<Boolean> = _routeShortest
    private val _avoidTolls = boolFlow(KEY_AVOID_TOLLS, false)
    val avoidTolls: StateFlow<Boolean> = _avoidTolls
    private val _avoidHighways = boolFlow(KEY_AVOID_HIGHWAYS, false)
    val avoidHighways: StateFlow<Boolean> = _avoidHighways
    private val _avoidFerries = boolFlow(KEY_AVOID_FERRIES, false)
    val avoidFerries: StateFlow<Boolean> = _avoidFerries
    private val _avoidUnpaved = boolFlow(KEY_AVOID_UNPAVED, false)
    val avoidUnpaved: StateFlow<Boolean> = _avoidUnpaved
    private val _avoidCashTolls = boolFlow(KEY_AVOID_CASH_TOLLS, false)
    val avoidCashTolls: StateFlow<Boolean> = _avoidCashTolls
    private val _avoidBorders = boolFlow(KEY_AVOID_BORDERS, false)
    val avoidBorders: StateFlow<Boolean> = _avoidBorders

    /** The route's preferences as they are now. */
    fun routePreferences() = RoutePreferences(
        _routeShortest.value, _avoidTolls.value, _avoidHighways.value, _avoidFerries.value,
        _avoidUnpaved.value, _avoidCashTolls.value, _avoidBorders.value,
    )

    /** The route's preferences, again at every change. */
    val routePreferencesFlow: Flow<RoutePreferences> =
        combine(listOf(_routeShortest, _avoidTolls, _avoidHighways, _avoidFerries, _avoidUnpaved, _avoidCashTolls, _avoidBorders)) {
            routePreferences()
        }.distinctUntilChanged()

    fun setRouteShortest(value: Boolean) = saveBool(KEY_ROUTE_SHORTEST, value, _routeShortest)

    fun setAvoidTolls(value: Boolean) = saveBool(KEY_AVOID_TOLLS, value, _avoidTolls)

    fun setAvoidHighways(value: Boolean) = saveBool(KEY_AVOID_HIGHWAYS, value, _avoidHighways)

    fun setAvoidFerries(value: Boolean) = saveBool(KEY_AVOID_FERRIES, value, _avoidFerries)

    fun setAvoidUnpaved(value: Boolean) = saveBool(KEY_AVOID_UNPAVED, value, _avoidUnpaved)

    fun setAvoidCashTolls(value: Boolean) = saveBool(KEY_AVOID_CASH_TOLLS, value, _avoidCashTolls)

    fun setAvoidBorders(value: Boolean) = saveBool(KEY_AVOID_BORDERS, value, _avoidBorders)

    private val _routeServer = MutableStateFlow(prefs.getString(KEY_ROUTE_SERVER, null))
    /** The driver's own Valhalla server (a base URL); null for the public demonstration one. */
    val routeServer: StateFlow<String?> = _routeServer

    fun setRouteServer(value: String?) {
        _routeServer.value = value
        prefs.edit { if (value == null) remove(KEY_ROUTE_SERVER) else putString(KEY_ROUTE_SERVER, value) }
    }

    private val _showEngineLight = boolFlow(KEY_SHOW_ENGINE, true)
    val showEngineLight: StateFlow<Boolean> = _showEngineLight

    private val _showBell = boolFlow(KEY_SHOW_BELL, true)
    val showBell: StateFlow<Boolean> = _showBell

    // Signal and battery were two settings: each keeps its saved value until the one setting changes both
    private val _showSignal = boolFlow(KEY_SHOW_SIGNAL, true)
    val showSignal: StateFlow<Boolean> = _showSignal

    private val _showBattery = boolFlow(KEY_SHOW_BATTERY, true)
    val showBattery: StateFlow<Boolean> = _showBattery

    private val _showPhoneStatus = MutableStateFlow(_showSignal.value || _showBattery.value)
    /** The phone's network, signal and battery in the top bar: on while either shows. */
    val showPhoneStatus: StateFlow<Boolean> = _showPhoneStatus

    fun setShowPhoneStatus(value: Boolean) {
        saveBool(KEY_SHOW_SIGNAL, value, _showSignal)
        saveBool(KEY_SHOW_BATTERY, value, _showBattery)
        _showPhoneStatus.value = value
    }

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

    private val _hiddenCalendars = MutableStateFlow(loadList(KEY_HIDDEN_CALENDARS))
    /** Calendars the calendar widget leaves out, by the provider's id: a new calendar shows up by default. */
    val hiddenCalendars: StateFlow<List<String>> = _hiddenCalendars

    fun toggleCalendar(id: String) {
        val list = hiddenCalendars.value
        saveList(KEY_HIDDEN_CALENDARS, if (id in list) list - id else list + id, _hiddenCalendars)
    }

    private val _mutedChats = MutableStateFlow(loadList(KEY_MUTED_CHATS, separator = "\n"))
    /** Muted chats, as "package|chat name". */
    val mutedChats: StateFlow<List<String>> = _mutedChats

    private val _voiceEnabled = boolFlow(KEY_VOICE, true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled

    private val _voiceReplies = boolFlow(KEY_VOICE_REPLIES, true)
    /** Spoken confirmations ("Calling Mario Rossi") through the car's speakers. */
    val voiceReplies: StateFlow<Boolean> = _voiceReplies

    private val _showMicButton = boolFlow(KEY_SHOW_MIC, true)
    val showMicButton: StateFlow<Boolean> = _showMicButton

    private val _voiceMicrophone = MutableStateFlow(enumOf(KEY_VOICE_MIC, MicSource.CAR))
    val voiceMicrophone: StateFlow<MicSource> = _voiceMicrophone

    fun setVoiceMicrophone(value: MicSource) = saveEnum(KEY_VOICE_MIC, value, _voiceMicrophone)

    fun setVoiceEnabled(value: Boolean) = saveBool(KEY_VOICE, value, _voiceEnabled)

    fun setVoiceReplies(value: Boolean) = saveBool(KEY_VOICE_REPLIES, value, _voiceReplies)

    fun setShowMicButton(value: Boolean) = saveBool(KEY_SHOW_MIC, value, _showMicButton)

    private val _unitSystem = MutableStateFlow(enumOf(KEY_UNITS, UnitSystem.AUTO))
    /** Kilometres and bar, or miles and psi, on the vehicle screens. */
    val unitSystem: StateFlow<UnitSystem> = _unitSystem

    private val _temperatureUnit = MutableStateFlow(enumOf(KEY_TEMPERATURE, TemperatureUnit.AUTO))
    val temperatureUnit: StateFlow<TemperatureUnit> = _temperatureUnit

    fun setUnitSystem(value: UnitSystem) = saveEnum(KEY_UNITS, value, _unitSystem)

    fun setTemperatureUnit(value: TemperatureUnit) = saveEnum(KEY_TEMPERATURE, value, _temperatureUnit)

    private val _voiceLanguage = MutableStateFlow(enumOf(KEY_VOICE_LANGUAGE, VoiceLanguage.AUTO))
    /** What voice commands are heard and answered in; see [AppLocales]. */
    val voiceLanguage: StateFlow<VoiceLanguage> = _voiceLanguage

    fun setVoiceLanguage(value: VoiceLanguage) = saveEnum(KEY_VOICE_LANGUAGE, value, _voiceLanguage)

    private val _formatRegion = MutableStateFlow(enumOf(KEY_FORMAT_REGION, FormatRegion.AUTO))
    /** How dates, times and numbers are written in the car; see [AppLocales]. */
    val formatRegion: StateFlow<FormatRegion> = _formatRegion

    fun setFormatRegion(value: FormatRegion) = saveEnum(KEY_FORMAT_REGION, value, _formatRegion)

    private val _autoPlay = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PLAY, true))
    val autoPlay: StateFlow<Boolean> = _autoPlay

    private val _unknownAudio = boolFlow(KEY_UNKNOWN_AUDIO, false)
    /** Also send the car the sound of apps that don't say what kind it is (some players do so); off by default. */
    val unknownAudio: StateFlow<Boolean> = _unknownAudio

    fun setUnknownAudio(value: Boolean) = saveBool(KEY_UNKNOWN_AUDIO, value, _unknownAudio)

    private val _debugOverlay = MutableStateFlow(prefs.getBoolean(KEY_DEBUG, false))
    val debugOverlay: StateFlow<Boolean> = _debugOverlay

    private val _pinnedApps = MutableStateFlow(loadList(KEY_PINNED))
    val pinnedApps: StateFlow<List<String>> = _pinnedApps

    private val _recentApps = MutableStateFlow(loadList(KEY_RECENT))
    val recentApps: StateFlow<List<String>> = _recentApps

    private val _phoneApps = MutableStateFlow(
        if (prefs.contains(KEY_PHONE_APPS)) loadList(KEY_PHONE_APPS) else PhoneAppChoices.DEFAULTS,
    )
    /**
     * Phone apps the driver chose to open in the car, in the order they were picked; until the list is first
     * changed, the likely ones ([PhoneAppChoices.DEFAULTS], those not installed are left out where shown).
     */
    val phoneApps: StateFlow<List<String>> = _phoneApps

    fun togglePhoneApp(packageName: String) {
        val list = phoneApps.value
        saveList(KEY_PHONE_APPS, if (packageName in list) list - packageName else list + packageName, _phoneApps)
    }

    /** Back to the likely apps, as before any change (bench and tests). */
    fun resetPhoneApps() {
        prefs.edit().remove(KEY_PHONE_APPS).apply()
        _phoneApps.value = PhoneAppChoices.DEFAULTS
    }

    private val _phoneAppsWhileMoving = boolFlow(KEY_PHONE_APPS_MOVING, false)
    /** Phone apps stay on while the car moves; off (the default), they pause until it stops. */
    val phoneAppsWhileMoving: StateFlow<Boolean> = _phoneAppsWhileMoving

    fun setPhoneAppsWhileMoving(value: Boolean) = saveBool(KEY_PHONE_APPS_MOVING, value, _phoneAppsWhileMoving)

    private val _phoneAppSize = MutableStateFlow(enumOf(KEY_PHONE_APP_SIZE, UiSize.AUTO))
    /** How big phone apps are drawn in the car, on top of what fits the screen (`projection/AppDensity`). */
    val phoneAppSize: StateFlow<UiSize> = _phoneAppSize

    fun setPhoneAppSize(value: UiSize) = saveEnum(KEY_PHONE_APP_SIZE, value, _phoneAppSize)

    private val _phonePointerApps = MutableStateFlow(loadList(KEY_PHONE_POINTER_APPS))
    /**
     * Phone apps the knob drives with a pointer (nudges move it, turns scroll, a press taps), chosen in the app's
     * hold panel; the others get the knob as keys (focus mode, the default).
     */
    val phonePointerApps: StateFlow<List<String>> = _phonePointerApps

    fun togglePhonePointer(packageName: String) {
        val list = phonePointerApps.value
        saveList(KEY_PHONE_POINTER_APPS, if (packageName in list) list - packageName else list + packageName, _phonePointerApps)
    }

    private val _phoneAppFullscreen = MutableStateFlow(enumOf(KEY_PHONE_APP_FULLSCREEN, AppFullscreenMode.WHEN_CHOSEN))
    /** Whether phone apps open on the car's whole screen (the panel's choice works in every mode). */
    val phoneAppFullscreen: StateFlow<AppFullscreenMode> = _phoneAppFullscreen

    fun setPhoneAppFullscreen(value: AppFullscreenMode) = saveEnum(KEY_PHONE_APP_FULLSCREEN, value, _phoneAppFullscreen)

    private val _phoneFullscreenApps = MutableStateFlow(loadList(KEY_PHONE_FULLSCREEN_APPS))
    /** Phone apps last put on the whole screen from their panel, for [AppFullscreenMode.PER_APP]. */
    val phoneFullscreenApps: StateFlow<List<String>> = _phoneFullscreenApps

    /** The panel's choice for [packageName], kept when the mode remembers it per app. */
    fun choosePhoneAppFullscreen(packageName: String, on: Boolean) {
        if (phoneAppFullscreen.value != AppFullscreenMode.PER_APP) return
        val list = phoneFullscreenApps.value - packageName
        saveList(KEY_PHONE_FULLSCREEN_APPS, if (on) list + packageName else list, _phoneFullscreenApps)
    }

    /** Whether [packageName] opens on the whole screen. */
    fun opensFullscreen(packageName: String): Boolean = when (phoneAppFullscreen.value) {
        AppFullscreenMode.WHEN_CHOSEN -> false
        AppFullscreenMode.PER_APP -> packageName in phoneFullscreenApps.value
        AppFullscreenMode.ALWAYS -> true
    }

    fun setThemeMode(value: ThemeMode) = saveEnum(KEY_THEME, value, _themeMode)

    fun setBarPosition(value: BarPosition) = saveEnum(KEY_BAR, value, _barPosition)

    fun setDriverSide(value: DriverSide) = saveEnum(KEY_DRIVER_SIDE, value, _driverSide)

    fun setUiSize(value: UiSize) = saveEnum(KEY_UI_SIZE, value, _uiSize)

    /** The home for [shape] becomes [value]; the other kind of screen follows while never arranged itself. */
    fun setHome(shape: ScreenShape, value: HomeArrangement) {
        _homes.getValue(shape).value = value
        prefs.edit { putString(homeKey(shape), value.encode()) }
        for (other in ScreenShape.entries) {
            if (other != shape && prefs.getString(homeKey(other), null) == null) _homes.getValue(other).value = value.turned()
        }
    }

    fun updateHome(shape: ScreenShape, change: (HomeArrangement) -> HomeArrangement) = setHome(shape, change(home(shape).value))

    /** Back to the home out of the box, for [shape] only. */
    fun resetHome(shape: ScreenShape) {
        prefs.edit { remove(homeKey(shape)) }
        _homes.getValue(shape).value = loadHome(shape)
    }

    /** The car's home area measured, in design units: which kind of screen this car has, and its proportions. */
    fun noteHomeArea(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val shape = ScreenShape.of(width, height)
        if (_homeShape.value != shape) saveEnum(KEY_HOME_SHAPE, shape, _homeShape)
        val area = _homeAreas.getValue(shape)
        val (w, h) = area.value
        if (abs(w - width) < 2f && abs(h - height) < 2f) return
        area.value = width to height
        prefs.edit { putString(homeAreaKey(shape), "$width,$height") }
    }

    /** The section shown before the current one (not saved): where the notifications screen goes back to. */
    private var previousSection = Section.HOME

    fun setSection(value: Section) {
        if (value != _section.value) previousSection = _section.value
        saveEnum(KEY_SECTION, value, _section)
    }

    private val _startOnHome = boolFlow(KEY_START_HOME, false)
    /** A new car session opens the home screen; otherwise the section left last time (the default). */
    val startOnHome: StateFlow<Boolean> = _startOnHome

    fun setStartOnHome(value: Boolean) = saveBool(KEY_START_HOME, value, _startOnHome)

    /**
     * The section a new car session opens: home, or where the driver left off. Settings and the notifications
     * are passing visits, not a place to come back to: the section before them, else home. A phone app does not
     * outlive the session: its section comes back as the apps.
     */
    fun onSessionStart() {
        val left = _section.value
        val next = when {
            _startOnHome.value -> Section.HOME
            left == Section.PHONE_APP -> Section.APPS
            left == Section.SETTINGS || left == Section.NOTIFICATIONS ->
                previousSection.takeIf { it != Section.SETTINGS && it != Section.NOTIFICATIONS } ?: Section.HOME
            else -> left
        }
        if (next != left) setSection(next)
    }

    /** Back from the notifications to wherever the driver was. */
    fun closeNotifications() = setSection(previousSection.takeIf { it != Section.NOTIFICATIONS } ?: Section.HOME)

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

    fun moveRailItem(item: RailItem, delta: Int) = saveRail(RailLayout.move(railLayout.value, item, delta))

    fun placeRailItem(item: RailItem, slot: RailSlot) = saveRail(RailLayout.place(railLayout.value, item, slot))

    fun setRailLayout(value: List<RailEntry>) = saveRail(value)

    private fun saveRail(value: List<RailEntry>) {
        _railLayout.value = value
        prefs.edit { putString(KEY_RAIL, RailLayout.encode(value)) }
    }

    fun setClockStyle(value: ClockStyle) = saveEnum(KEY_CLOCK, value, _clockStyle)

    fun setDateStyle(value: DateStyle) = saveEnum(KEY_DATE, value, _dateStyle)

    fun setMapOrientation(value: MapOrientation) = saveEnum(KEY_MAP_ORIENTATION, value, _mapOrientation)

    fun setMapZoom(value: MapZoom) = saveEnum(KEY_MAP_ZOOM, value, _mapZoom)

    fun setMapAutoZoom(value: Boolean) = saveBool(KEY_MAP_AUTO_ZOOM, value, _mapAutoZoom)

    fun setShowSpeedLimit(value: Boolean) = saveBool(KEY_SHOW_SPEED_LIMIT, value, _showSpeedLimit)

    fun setGuidanceVoice(value: GuidanceVoice) = saveEnum(KEY_GUIDANCE_VOICE, value, _guidanceVoice)

    fun setShowEngineLight(value: Boolean) = saveBool(KEY_SHOW_ENGINE, value, _showEngineLight)

    fun setShowBell(value: Boolean) = saveBool(KEY_SHOW_BELL, value, _showBell)


    private fun boolFlow(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private fun saveBool(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = value
        prefs.edit { putBoolean(key, value) }
    }

    private val _notificationPrivacy = MutableStateFlow(loadNotificationPrivacy())
    /**
     * Notifications show who and which app, not what they say (passengers, screen recordings): always,
     * never, or (Auto) while the car says someone besides the driver is aboard.
     */
    val notificationPrivacy: StateFlow<NotificationPrivacy> = _notificationPrivacy

    fun setNotificationPrivacy(value: NotificationPrivacy) = saveEnum(KEY_NOTIFICATION_PRIVACY_MODE, value, _notificationPrivacy)

    /**
     * Auto, unless the driver turned the earlier on/off setting on. Its off was the default, and Auto shows
     * contents just the same while the car doesn't say who is aboard.
     */
    private fun loadNotificationPrivacy(): NotificationPrivacy {
        if (prefs.contains(KEY_NOTIFICATION_PRIVACY_MODE)) return enumOf(KEY_NOTIFICATION_PRIVACY_MODE, NotificationPrivacy.AUTO)
        return if (prefs.getBoolean(KEY_NOTIFICATION_PRIVACY, false)) NotificationPrivacy.ON else NotificationPrivacy.AUTO
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

    private fun homeKey(shape: ScreenShape) = "home_${shape.name.lowercase()}"

    private fun homeAreaKey(shape: ScreenShape) = "home_area_${shape.name.lowercase()}"

    private fun loadHome(shape: ScreenShape): HomeArrangement =
        HomeArrangement.decode(prefs.getString(homeKey(shape), null))
            ?: ScreenShape.entries.filter { it != shape }.firstNotNullOfOrNull { HomeArrangement.decode(prefs.getString(homeKey(it), null)) }?.turned()
            ?: HomeArrangement.default(shape)

    private fun loadHomeArea(shape: ScreenShape): Pair<Float, Float> {
        val saved = prefs.getString(homeAreaKey(shape), null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        if (saved != null && saved.size == 2 && saved.all { it > 0f }) return saved[0] to saved[1]
        // A usual car screen's home beside the app bar, under the status bar
        return if (shape == ScreenShape.PORTRAIT) 680f to 1080f else 1100f to 600f
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
        private const val KEY_DRIVER_SIDE = "driver_side"
        private const val KEY_UI_SIZE = "ui_size"
        private const val KEY_HOME_SHAPE = "home_shape"
        private val OLD_HOME_KEYS = listOf("home_layout", "home_split", "home_split_side", "home_stacks", "home_widgets", "widgets")
        private const val KEY_SECTION = "section"
        private const val KEY_START_HOME = "start_on_home"
        private const val KEY_DEBUG = "debug_overlay"
        private const val KEY_AUTO_PLAY = "auto_play"
        private const val KEY_UNKNOWN_AUDIO = "unknown_audio"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_VOICE_MIC = "voice_microphone"
        private const val KEY_VOICE_REPLIES = "voice_replies"
        private const val KEY_SHOW_MIC = "show_mic_button"
        private const val KEY_MUSIC_PANEL = "music_panel"
        private const val KEY_MUSIC_TAB = "music_tab"
        private const val KEY_NAME_ORDER = "name_order"
        private const val KEY_QUIET = "quiet_mode"
        /** The earlier on/off privacy setting, read only to keep an explicit choice. */
        private const val KEY_NOTIFICATION_PRIVACY = "notification_privacy"
        private const val KEY_NOTIFICATION_PRIVACY_MODE = "notification_privacy_mode"
        private const val KEY_RAIL = "rail_layout"
        private const val KEY_CLOCK = "clock_style"
        private const val KEY_DATE = "date_style"
        private const val KEY_MAP_ORIENTATION = "map_orientation"
        private const val KEY_MAP_ZOOM = "map_zoom"
        private const val KEY_MAP_AUTO_ZOOM = "map_auto_zoom"
        private const val KEY_SHOW_SPEED_LIMIT = "show_speed_limit"
        private const val KEY_GUIDANCE_VOICE = "guidance_voice"
        private const val KEY_ROUTE_SERVER = "route_server"
        private const val KEY_ROUTE_SHORTEST = "route_shortest"
        private const val KEY_AVOID_TOLLS = "avoid_tolls"
        private const val KEY_AVOID_HIGHWAYS = "avoid_highways"
        private const val KEY_AVOID_FERRIES = "avoid_ferries"
        private const val KEY_AVOID_UNPAVED = "avoid_unpaved"
        private const val KEY_AVOID_CASH_TOLLS = "avoid_cash_tolls"
        private const val KEY_AVOID_BORDERS = "avoid_borders"
        private const val KEY_UNITS = "unit_system"
        private const val KEY_TEMPERATURE = "temperature_unit"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_FORMAT_REGION = "format_region"
        private const val KEY_SHOW_ENGINE = "show_engine_light"

        private const val KEY_SHOW_BELL = "show_bell"
        private const val KEY_SHOW_SIGNAL = "show_signal"
        private const val KEY_SHOW_BATTERY = "show_battery"

        private const val KEY_NOTIF_GROUPS = "notification_groups"
        private const val KEY_NOTIF_APPS = "notification_apps"
        private const val KEY_NOTIF_HIDDEN = "notification_hidden_apps"
        private const val KEY_HIDDEN_CALENDARS = "hidden_calendars"
        private const val KEY_MUTED_CHATS = "muted_chats"
        private const val MAX_NOTIF_APPS = 40
        private const val KEY_PHONE_TAB = "phone_tab"
        private const val KEY_PINNED = "pinned_apps"
        private const val KEY_PHONE_APPS = "phone_apps"
        private const val KEY_PHONE_APPS_MOVING = "phone_apps_while_moving"
        private const val KEY_PHONE_APP_SIZE = "phone_app_size"
        private const val KEY_PHONE_POINTER_APPS = "phone_pointer_apps"
        private const val KEY_PHONE_APP_FULLSCREEN = "phone_app_fullscreen"
        private const val KEY_PHONE_FULLSCREEN_APPS = "phone_fullscreen_apps"
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
