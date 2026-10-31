package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.roads.RoadInfo
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The home's map: where the car is, following it; a tap or the knob opens the Map section. */
@Composable
fun MapWidget(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val orientation by settings.mapOrientation.collectAsStateWithLifecycle()
    val zoom by settings.mapZoom.collectAsStateWithLifecycle()
    val autoZoom by settings.mapAutoZoom.collectAsStateWithLifecycle()
    val showLimit by settings.showSpeedLimit.collectAsStateWithLifecycle()
    val limit = RoadInfo.state.collectAsStateWithLifecycle().value?.limit
    // A step farther than the Map section: the widget is smaller
    val state = remember { CarMapState(zoom.level - 1, tilt = WIDGET_TILT) }
    LaunchedEffect(zoom) { state.setDefaultZoom(zoom.level - 1) }
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier
            .clip(shape)
            .background(colors.map.land)
            .carClickable(shape, stringResource(R.string.widget_map)) { settings.setSection(Section.MAP) },
    ) {
        CarMap(state, orientation, interactive = false, Modifier.fillMaxSize(), arrow = 50.dp, autoZoom = autoZoom)
        // Over the map, so a tap reaches the widget and not the map under it
        Box(Modifier.matchParentSize().pointerInput(Unit) {})
        MapStatus(Modifier.align(Alignment.TopStart).padding(14.dp), compact = true)
        MapAttribution(Modifier.align(Alignment.BottomStart).padding(10.dp))
        if (showLimit && limit != null) SpeedLimitSign(limit, 60.dp, Modifier.align(Alignment.BottomEnd).padding(14.dp))
    }
}

private const val WIDGET_TILT = 30.0
