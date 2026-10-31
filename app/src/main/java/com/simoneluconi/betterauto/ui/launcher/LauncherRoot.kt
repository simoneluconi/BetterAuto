package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.Edges
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.ui.phone.CallScreen
import com.simoneluconi.betterauto.ui.vehicle.VehicleScreen
import com.simoneluconi.betterauto.ui.voice.VoiceOverlay
import com.simoneluconi.betterauto.ui.voice.VoiceScrim
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import com.simoneluconi.betterauto.ui.notifications.NotificationBanner
import com.simoneluconi.betterauto.ui.notifications.NotificationsWidget
import com.simoneluconi.betterauto.ui.phone.PhoneScreen
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.ui.smarthome.ArrivalPanel
import com.simoneluconi.betterauto.ui.smarthome.ArrivalPanelShown
import com.simoneluconi.betterauto.ui.smarthome.ArrivalScrim
import com.simoneluconi.betterauto.ui.smarthome.SmartHomeScreen
import com.simoneluconi.betterauto.settings.BarPosition
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.ThemeMode
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.CarBringIntoView
import com.simoneluconi.betterauto.ui.LocalFormatLocale
import com.simoneluconi.betterauto.ui.theme.CarTheme
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.widgets.MusicScreen
import com.simoneluconi.betterauto.ui.map.MapScreen
import com.simoneluconi.betterauto.ui.map.NavOverlayShown
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.ui.widgets.WidgetArea
import com.simoneluconi.betterauto.ui.widgets.HomeEdit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlin.math.min

/**
 * Design reference size: every dp/sp in the launcher is a pixel on a 1280x720 screen (720x1280 when
 * portrait), scaled to the real one. Text and buttons keep the same physical size whatever the orientation.
 */
private const val DESIGN_LONG = 1280f
private const val DESIGN_SHORT = 720f

/**
 * The whole car screen. Works on the projected display and in the on-phone preview.
 * [insets] (pixels) are edges that may be hidden: the background reaches them, the controls don't.
 */
// LocalBringIntoViewSpec: the knob's margin when lists scroll to the focused control
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherRoot(modifier: Modifier = Modifier, focus: LauncherFocus? = null, insets: Edges = Edges.None) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = (constraints.maxWidth - insets.left - insets.right).coerceAtLeast(1)
        val h = (constraints.maxHeight - insets.top - insets.bottom).coerceAtLeast(1)
        val scale = if (w >= h) min(w / DESIGN_LONG, h / DESIGN_SHORT) else min(w / DESIGN_SHORT, h / DESIGN_LONG)
        val padding = PaddingValues(
            start = (insets.left / scale).dp,
            top = (insets.top / scale).dp,
            end = (insets.right / scale).dp,
            bottom = (insets.bottom / scale).dp,
        )
        // Dates and numbers as the driver chose (Auto: the phone's language of the SIM's country)
        val context = LocalContext.current
        val formatChoice by remember { LauncherSettings.get(context).formatRegion }.collectAsStateWithLifecycle()
        val configuration = LocalConfiguration.current
        val formatLocale = remember(formatChoice, configuration) { AppLocales.formats(context) }
        CompositionLocalProvider(
            LocalDensity provides Density(scale, fontScale = 1f),
            LocalFormatLocale provides formatLocale,
            LocalBringIntoViewSpec provides CarBringIntoView,
        ) {
            LauncherContent(focus, padding)
        }
    }
}

@Composable
private fun LauncherContent(focus: LauncherFocus?, insets: PaddingValues) {
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val carNight by CarState.nightMode.collectAsStateWithLifecycle()
    // Only the accent: a new Track arrives on every position or queue update, the root must not recompose for those
    val accent by remember { NowPlaying.track.map { it?.accent }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
    val section by settings.section.collectAsStateWithLifecycle()
    val barSetting by settings.barPosition.collectAsStateWithLifecycle()
    val debug by settings.debugOverlay.collectAsStateWithLifecycle()
    val call by Calls.current.collectAsStateWithLifecycle()
    val callMinimized by Calls.minimized.collectAsStateWithLifecycle()
    val shownCall = call?.takeUnless { callMinimized }

    val systemDark = isSystemInDarkTheme()
    val night = when (themeMode) {
        ThemeMode.AUTO -> carNight ?: systemDark
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    val railFocus = remember { FocusRequester() }
    val callFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    var railVertical by remember { mutableStateOf(true) }
    // Right-hand drive: the bar beside the screen sits on the right
    val driverSide by settings.driverSide.collectAsStateWithLifecycle()
    val carDriverOnRight by CarState.driverOnRight.collectAsStateWithLifecycle()
    val railRight = driverSide.onRight(carDriverOnRight)
    var openGroup by remember { mutableStateOf<RailSlot?>(null) }
    // The trip (set on the phone, said, picked in the panel, or left from before a break) is guided while the car's
    // screen is up (focus is only given there: never for the preview on the phone, which may sit in the background);
    // the map comes forward when one starts, or when a place waits for the driver's choice
    val projected = focus != null
    DisposableEffect(projected) {
        if (projected) Navigation.screenShown(context)
        onDispose { if (projected) Navigation.screenGone() }
    }
    // The map brought forward takes the knob, unless the driver is on the bar
    var focusMap by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusMap) {
        if (focusMap == 0) return@LaunchedEffect
        // Once the map is drawn
        withFrameNanos { }
        withFrameNanos { }
        if (!railHasFocus) runCatching { contentFocus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        var had = false
        Trip.places.collect { places ->
            if (places.isNotEmpty() && !had) {
                settings.setSection(Section.MAP)
                focusMap++
            }
            had = places.isNotEmpty()
        }
    }
    LaunchedEffect(Unit) {
        Trip.offer.filterNotNull().collect { settings.setSection(Section.MAP) }
    }
    // After a group closes, the focus goes back to the bar once it shows the new section
    var refocusRail by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocusRail) {
        if (refocusRail == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { railFocus.requestFocus() }
    }
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Read by the bridge below long after composition: always the latest value
    val callShown by rememberUpdatedState(shownCall != null)
    DisposableEffect(focus) {
        focus?.clear = { focusManager.clearFocus(force = true) }
        focus?.ensure = {
            when {
                hasFocus -> false
                // On the call screen the first knob press must act (answer, hang up), not just show the focus
                callShown -> {
                    runCatching { callFocus.requestFocus() }
                    false
                }
                // The arrival panel waits for the driver: the first knob move lands in it, not on the bar behind
                ArrivalPanelShown.value && ArrivalPanelShown.first != null -> {
                    runCatching { ArrivalPanelShown.first?.requestFocus() }
                    true
                }
                // So do the map's destinations panel and its stop-or-destination choice
                NavOverlayShown.value && NavOverlayShown.first != null -> {
                    runCatching { NavOverlayShown.first?.requestFocus() }
                    true
                }
                else -> {
                    runCatching { railFocus.requestFocus() }
                    true
                }
            }
        }
        onDispose {
            focus?.ensure = null
            focus?.clear = null
        }
    }

    CarTheme(night = night, album = accent) {
        val colors = LocalCarColors.current
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .onFocusChanged { hasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    // Back stops listening before anything else
                    if ((event.key == Key.Back || event.key == Key.Escape) && VoiceAssistant.isBusy) {
                        if (!down) VoiceAssistant.cancel()
                        return@onPreviewKeyEvent true
                    }
                    // Then the editors, one level at a time, wherever the focus is
                    if ((event.key == Key.Back || event.key == Key.Escape) && (BarEditor.open.value || HomeEdit.active.value)) {
                        if (!down) {
                            if (BarEditor.open.value) BarEditor.close() else HomeEdit.back()
                        }
                        return@onPreviewKeyEvent true
                    }
                    // The arrival panel, also when something took the focus out of it
                    if ((event.key == Key.Back || event.key == Key.Escape) && ArrivalPanelShown.value) {
                        if (!down) SmartHome.arrival?.closePanel()
                        return@onPreviewKeyEvent true
                    }
                    // The arrival panel waits for the driver: a knob move while the focus is elsewhere (the screen
                    // took it back as the session started) first brings it to the panel
                    if (ArrivalPanelShown.value && !ArrivalPanelShown.hasFocus && event.key in KnobKeys) {
                        if (down) ArrivalPanelShown.first?.let { runCatching { it.requestFocus() } }
                        return@onPreviewKeyEvent true
                    }
                    // The map's overlays the same way: Back goes one level back in them (a place put down, the trip
                    // page, the overlay), wherever the focus is; a knob move from outside lands in them
                    if (NavOverlayShown.value && shownCall == null) {
                        if (event.key == Key.Back || event.key == Key.Escape) {
                            if (!down) NavOverlayShown.back?.invoke()
                            return@onPreviewKeyEvent true
                        }
                        if (!NavOverlayShown.hasFocus && event.key in KnobKeys) {
                            if (down) NavOverlayShown.first?.let { runCatching { it.requestFocus() } }
                            return@onPreviewKeyEvent true
                        }
                    }
                    // Knob nudges move between zones (the bar, each widget or panel of the screen); turning goes
                    // through every control. Not out of a panel that waits for the driver: there they move inside it
                    val toScreen = when {
                        !railVertical -> Key.DirectionUp
                        railRight -> Key.DirectionLeft
                        else -> Key.DirectionRight
                    }
                    val toBar = when {
                        !railVertical -> Key.DirectionDown
                        railRight -> Key.DirectionRight
                        else -> Key.DirectionLeft
                    }
                    val nudge = when (event.key) {
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> null
                    }
                    val panelOpen = ArrivalPanelShown.value || NavOverlayShown.value || VoiceAssistant.isBusy || BarEditor.open.value || HomeEdit.active.value
                    if (shownCall == null && hasFocus && !panelOpen && nudge != null) {
                        if (railHasFocus) {
                            if (event.key == toScreen) {
                                if (down && !contentFocus.requestFocus()) focusManager.moveFocus(nudge)
                                return@onPreviewKeyEvent true
                            }
                            // Along the bar: from section to section
                        } else {
                            if (down) {
                                when {
                                    CarZones.moveBeside(nudge) -> Unit
                                    // From the last zone toward the bar: the current section
                                    event.key == toBar -> railFocus.requestFocus()
                                    // Across the bar's axis with no zone that way: within the screen
                                    event.key != toScreen -> focusManager.moveFocus(nudge)
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    when (event.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            if (down) NowPlaying.playPause()
                            true
                        }
                        Key.MediaNext -> {
                            if (down) NowPlaying.next()
                            true
                        }
                        Key.MediaPrevious -> {
                            if (down) NowPlaying.previous()
                            true
                        }
                        else -> false
                    }
                }
                .onKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyUp && section != Section.HOME) {
                        // The notifications are a detour: back to where the driver came from; elsewhere, home
                        if (section == Section.NOTIFICATIONS) settings.closeNotifications() else settings.setSection(Section.HOME)
                        // The ring follows to the bar's entry of the section now shown, not left on the old one or lost
                        if (hasFocus) refocusRail++
                    }
                    section != Section.HOME
                }
                .padding(insets),
        ) {
            val left = when (barSetting) {
                BarPosition.LEFT -> true
                BarPosition.BOTTOM -> false
                BarPosition.AUTO -> maxWidth / maxHeight >= 1.3f
            }
            SideEffect { railVertical = left }
            val main = @Composable { mod: Modifier ->
                CompositionLocalProvider(LocalContentFocus provides contentFocus) { MainArea(section, settings, mod) }
            }
            val rail = @Composable { vertical: Boolean ->
                Box(Modifier.onFocusChanged { railHasFocus = it.hasFocus }) {
                    AppRail(settings, section, vertical = vertical, firstFocus = railFocus, onOpenGroup = { openGroup = it }, onRight = vertical && railRight)
                }
            }
            val current = shownCall
            if (current != null) {
                // The call takes the whole screen, so the knob only moves between its buttons
                CallScreen(current, callFocus, Modifier.fillMaxSize())
            } else if (left) {
                Row(Modifier.fillMaxSize()) {
                    if (!railRight) rail(true)
                    main(Modifier.weight(1f))
                    if (railRight) rail(true)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f))
                    rail(false)
                }
            }
            // The bar's editor: after it closes, back where the knob was, else on the bar
            val arranging by BarEditor.open.collectAsStateWithLifecycle()
            var wasArranging by remember { mutableStateOf(false) }
            LaunchedEffect(arranging) {
                if (wasArranging && !arranging) {
                    withFrameNanos { }
                    if (!BarEditor.restoreFocus()) refocusRail++
                }
                wasArranging = arranging
            }
            if (current == null) {
                BarEditorScrim(Modifier.fillMaxSize())
                BarEditorPanel(
                    settings,
                    maxHeight = maxHeight - 48.dp,
                    modifier = if (left) Modifier.align(Alignment.CenterStart).padding(start = 124.dp, end = 24.dp)
                    else Modifier.align(Alignment.BottomCenter).padding(bottom = 116.dp, start = 24.dp, end = 24.dp),
                )
            }
            openGroup?.let { slot ->
                RailGroupPanel(
                    settings, slot, section,
                    onClose = {
                        openGroup = null
                        refocusRail++
                    },
                    modifier = if (left) Modifier.align(Alignment.CenterStart).padding(start = 124.dp)
                    else Modifier.align(Alignment.BottomCenter).padding(bottom = 116.dp),
                )
            }
            VoiceScrim(Modifier.fillMaxSize())
            if (current == null) ArrivalScrim(Modifier.fillMaxSize())
            // Centered over the screen beside the app bar, where the driver looks, not over the whole frame
            val besideRail = when {
                current != null -> Modifier
                left -> Modifier.padding(start = RAIL_WIDTH)
                else -> Modifier.padding(bottom = RAIL_HEIGHT)
            }
            // Arrival buttons where the voice panel goes; never over a call
            if (current == null) ArrivalPanel(Modifier.align(Alignment.BottomCenter).then(besideRail).padding(bottom = 28.dp, start = 24.dp, end = 24.dp))
            VoiceOverlay(Modifier.align(Alignment.BottomCenter).then(besideRail).padding(bottom = 28.dp, start = 24.dp, end = 24.dp))
            // No banner over a call, nor on the notifications screen itself
            if (shownCall == null && section != Section.NOTIFICATIONS) {
                // At the very top, over the status bar, where it hides the least
                NotificationBanner(
                    settings,
                    Modifier.align(Alignment.TopCenter).then(if (left) Modifier.padding(start = RAIL_WIDTH) else Modifier)
                        .padding(top = 10.dp, start = 24.dp, end = 24.dp),
                )
            }
            if (debug) DebugOverlay(Modifier.align(Alignment.TopEnd).padding(top = 88.dp, end = 28.dp))
        }
    }
}

@Composable
private fun MainArea(section: Section, settings: LauncherSettings, modifier: Modifier) {
    Column(
        modifier
            // Nudges never slip out of the screen sideways into the app bar: only the nudge toward the bar
            // leaves it (handled above, landing on the current section). Turning the knob is not affected.
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection in Nudges) cancelFocusChange()
                }
            }
            .focusGroup()
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusBar(settings, showLayoutButton = section == Section.HOME)
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith
                    fadeOut(tween(140))
            },
            modifier = Modifier.weight(1f),
            label = "section",
        ) { current ->
            when (current) {
                Section.HOME -> WidgetArea(settings, Modifier.fillMaxSize())
                Section.MUSIC -> MusicScreen(settings, Modifier.fillMaxSize())
                Section.MAP -> MapScreen(settings, Modifier.fillMaxSize())
                Section.PHONE -> PhoneScreen(settings, Modifier.fillMaxSize())
                Section.VEHICLE -> VehicleScreen(Modifier.fillMaxSize())
                Section.NOTIFICATIONS -> NotificationsWidget(settings, Modifier.fillMaxSize(), primary = true)
                Section.SETTINGS -> SettingsScreen(settings, Modifier.fillMaxSize())
                Section.SMART_HOME -> SmartHomeScreen(Modifier.fillMaxSize())
            }
        }
    }
}

private val Nudges = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)

/** The knob's moves and presses: what a panel waiting for the driver must get first. */
private val KnobKeys = setOf(
    Key.Tab, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)
