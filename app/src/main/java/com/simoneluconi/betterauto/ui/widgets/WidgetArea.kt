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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.WidgetType

private val Gap = 20.dp

/**
 * Home screen: one big widget, two side by side, or one big plus two stacked (AA-style).
 * On portrait screens the same layouts turn a quarter: the big widget on top, the others below it.
 */
@Composable
fun WidgetArea(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val layout by settings.layout.collectAsStateWithLifecycle()
    val widgets by settings.widgets.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier) {
        val portrait = maxWidth < maxHeight
        AnimatedContent(
            targetState = layout to widgets,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.fillMaxSize(),
            label = "homeLayout",
        ) { (currentLayout, order) ->
            val slots = order.take(currentLayout.slots)
            if (portrait) PortraitLayout(currentLayout, slots) else LandscapeLayout(currentLayout, slots)
        }
    }
}

@Composable
private fun PortraitLayout(layout: HomeLayout, slots: List<WidgetType>) {
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
private fun LandscapeLayout(layout: HomeLayout, slots: List<WidgetType>) {
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
private fun Widget(type: WidgetType, modifier: Modifier) {
    when (type) {
        WidgetType.MUSIC -> MusicWidget(modifier)
        WidgetType.MAP, WidgetType.NOTIFICATIONS -> PlaceholderWidget(type, modifier)
    }
}
