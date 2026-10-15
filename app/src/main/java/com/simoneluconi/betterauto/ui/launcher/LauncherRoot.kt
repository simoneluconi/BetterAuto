package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
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
import com.simoneluconi.betterauto.ui.phone.CallScreen
import com.simoneluconi.betterauto.ui.notifications.NotificationBanner
import com.simoneluconi.betterauto.ui.notifications.NotificationsWidget
import com.simoneluconi.betterauto.ui.phone.PhoneScreen
import com.simoneluconi.betterauto.settings.BarPosition
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.ThemeMode
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.theme.CarTheme
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.widgets.MusicScreen
import com.simoneluconi.betterauto.ui.widgets.PlaceholderWidget
import com.simoneluconi.betterauto.ui.widgets.WidgetArea
import kotlinx.coroutines.flow.distinctUntilChanged
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
        CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) {
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
                    // Knob nudges: from the app bar straight to the screen's main control, and back
                    val toScreen = if (railVertical) Key.DirectionRight else Key.DirectionUp
                    val toBar = if (railVertical) Key.DirectionLeft else Key.DirectionDown
                    if (shownCall == null && hasFocus && (event.key == toScreen || event.key == toBar)) {
                        if (event.key == toScreen && railHasFocus) {
                            if (down && !contentFocus.requestFocus()) focusManager.moveFocus(if (railVertical) FocusDirection.Right else FocusDirection.Up)
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == toBar && !railHasFocus) {
                            if (down) {
                                // Moves within the screen first; leaving it always lands on the current section
                                val moved = focusManager.moveFocus(if (railVertical) FocusDirection.Left else FocusDirection.Down)
                                if (!moved || railHasFocus) railFocus.requestFocus()
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
                    if (event.type == KeyEventType.KeyUp && section != Section.HOME) settings.setSection(Section.HOME)
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
                    AppRail(settings, section, vertical = vertical, firstFocus = railFocus)
                }
            }
            val current = shownCall
            if (current != null) {
                // The call takes the whole screen, so the knob only moves between its buttons
                CallScreen(current, callFocus, Modifier.fillMaxSize())
            } else if (left) {
                Row(Modifier.fillMaxSize()) {
                    rail(true)
                    main(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f))
                    rail(false)
                }
            }
            // No banner over a call, nor on the notifications screen itself
            if (shownCall == null && section != Section.NOTIFICATIONS) {
                // At the very top, over the status bar, where it hides the least
                NotificationBanner(settings, Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 24.dp, end = 24.dp))
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
                Section.MAP -> PlaceholderWidget(WidgetType.MAP, Modifier.fillMaxSize())
                Section.PHONE -> PhoneScreen(settings, Modifier.fillMaxSize())
                Section.NOTIFICATIONS -> NotificationsWidget(settings, Modifier.fillMaxSize(), primary = true)
                Section.SETTINGS -> SettingsScreen(settings, Modifier.fillMaxSize())
            }
        }
    }
}

private val Nudges = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)
