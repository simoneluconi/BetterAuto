package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import com.simoneluconi.betterauto.ui.LocalHoldAction
import com.simoneluconi.betterauto.ui.launcher.LocalContentFocus
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.WidgetChoice

private val Gap = 20.dp

private val LocalSettings = staticCompositionLocalOf<LauncherSettings?> { null }

/**
 * Home screen: one big widget, two side by side, or one big plus two stacked (AA-style).
 * On portrait screens the same layouts turn a quarter: the big widget on top, the others below it.
 * Holding a widget (finger or knob center) opens the edit mode ([HomeEdit]).
 */
@Composable
fun WidgetArea(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val layout by settings.layout.collectAsStateWithLifecycle()
    val widgets by settings.widgets.collectAsStateWithLifecycle()
    val editing by HomeEdit.active.collectAsStateWithLifecycle()
    // Leaving the home screen ends the edit mode
    DisposableEffect(Unit) { onDispose { HomeEdit.stop() } }
    // After Done: back where the knob was, else on the screen's main control
    val main = LocalContentFocus.current
    var wasEditing by remember { mutableStateOf(editing) }
    LaunchedEffect(editing) {
        if (wasEditing && !editing) {
            withFrameNanos { }
            if (!HomeEdit.restoreFocus()) runCatching { main?.requestFocus() }
        }
        wasEditing = editing
    }
    if (editing) {
        HomeEditor(settings, modifier)
        return
    }

    BoxWithConstraints(modifier) {
        val portrait = maxWidth < maxHeight
        AnimatedContent(
            targetState = layout to widgets,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.fillMaxSize(),
            label = "homeLayout",
        ) { (currentLayout, order) ->
            val slots = order.take(currentLayout.slots)
            CompositionLocalProvider(LocalSettings provides settings) {
                if (portrait) PortraitLayout(currentLayout, slots) else LandscapeLayout(currentLayout, slots)
            }
        }
    }
}

@Composable
private fun PortraitLayout(layout: HomeLayout, slots: List<WidgetChoice>) {
    when (layout) {
        HomeLayout.SINGLE -> Widget(slots[0], Modifier.fillMaxSize())
        HomeLayout.SPLIT -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            Widget(slots[0], Modifier.weight(3f).fillMaxWidth())
            Widget(slots[1], Modifier.weight(2f).fillMaxWidth())
        }
        HomeLayout.TRIO -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            Widget(slots[0], Modifier.weight(3f).fillMaxWidth())
            Row(Modifier.weight(2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                Widget(slots[1], Modifier.weight(1f).fillMaxHeight())
                Widget(slots[2], Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun LandscapeLayout(layout: HomeLayout, slots: List<WidgetChoice>) {
    when (layout) {
        HomeLayout.SINGLE -> Widget(slots[0], Modifier.fillMaxSize())
        HomeLayout.SPLIT -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Widget(slots[0], Modifier.weight(3f).fillMaxHeight())
            Widget(slots[1], Modifier.weight(2f).fillMaxHeight())
        }
        HomeLayout.TRIO -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Widget(slots[0], Modifier.weight(3f).fillMaxHeight())
            Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                Widget(slots[1], Modifier.weight(3f).fillMaxWidth())
                Widget(slots[2], Modifier.weight(2f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Widget(choice: WidgetChoice, modifier: Modifier) {
    val settings = LocalSettings.current ?: return
    val hold = remember(settings) { { HomeEdit.start(settings) } }
    // Its buttons take the hold through LocalHoldAction; the rest of the card here
    Box(modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { hold() }) }) {
        CompositionLocalProvider(LocalHoldAction provides hold) {
            HomeWidget(choice, settings, Modifier.fillMaxSize())
        }
    }
}
