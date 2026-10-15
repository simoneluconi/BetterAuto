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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val section by settings.section.collectAsStateWithLifecycle()
    val barSetting by settings.barPosition.collectAsStateWithLifecycle()
    val debug by settings.debugOverlay.collectAsStateWithLifecycle()

    val systemDark = isSystemInDarkTheme()
    val night = when (themeMode) {
        ThemeMode.AUTO -> carNight ?: systemDark
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    val railFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    DisposableEffect(focus) {
        focus?.clear = { focusManager.clearFocus(force = true) }
        focus?.ensure = {
            if (hasFocus) false else {
                runCatching { railFocus.requestFocus() }
                true
            }
        }
        onDispose {
            focus?.ensure = null
            focus?.clear = null
        }
    }

    CarTheme(night = night, album = track?.accent) {
        val colors = LocalCarColors.current
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .onFocusChanged { hasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
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
            val main = @Composable { mod: Modifier -> MainArea(section, settings, mod) }
            if (left) {
                Row(Modifier.fillMaxSize()) {
                    AppRail(settings, section, vertical = true, firstFocus = railFocus)
                    main(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f))
                    AppRail(settings, section, vertical = false, firstFocus = railFocus)
                }
            }
            if (debug) DebugOverlay(Modifier.align(Alignment.TopEnd).padding(top = 88.dp, end = 28.dp))
        }
    }
}

@Composable
private fun MainArea(section: Section, settings: LauncherSettings, modifier: Modifier) {
    Column(
        modifier
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
                Section.PHONE -> PlaceholderWidget(null, Modifier.fillMaxSize())
                Section.SETTINGS -> SettingsScreen(settings, Modifier.fillMaxSize())
            }
        }
    }
}
