package com.simoneluconi.betterauto.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.delay
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.MapLocationProblem
import com.simoneluconi.betterauto.map.MapSource
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.map.roads.RoadInfo
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.ui.vehicle.WarningAmber
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.carZone
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * The Map section: the map following the car. The knob goes from the map to its buttons as on any screen (zoom by
 * pressing + or −; turning to zoom kept it on the map, a road test); a press on the map comes back to the car or
 * opens the destinations. Touch moves and pinches it, with "recenter" while it is moved away.
 */
@Composable
fun MapScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val orientation by settings.mapOrientation.collectAsStateWithLifecycle()
    val zoom by settings.mapZoom.collectAsStateWithLifecycle()
    val autoZoom by settings.mapAutoZoom.collectAsStateWithLifecycle()
    val showLimit by settings.showSpeedLimit.collectAsStateWithLifecycle()
    val road by RoadInfo.state.collectAsStateWithLifecycle()
    val nav by Navigation.state.collectAsStateWithLifecycle()
    val places by Trip.places.collectAsStateWithLifecycle()
    val offer by Trip.offer.collectAsStateWithLifecycle()
    val guidance by settings.guidanceVoice.collectAsStateWithLifecycle()
    val planning by Navigation.planning.collectAsStateWithLifecycle()
    val state = remember { CarMapState(zoom.level, tilt = SECTION_TILT) }
    LaunchedEffect(zoom) { state.setDefaultZoom(zoom.level) }
    var focused by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    val mapFocus = remember { FocusRequester() }
    // The knob's hint
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            delay(HINT_MS)
            hint = false
        }
    }
    val shape = RoundedCornerShape(32.dp)

    Box(
        modifier
            .clip(shape)
            .background(colors.map.land)
            .carZone()
            .contentFocus()
            .focusRequester(mapFocus)
            .onFocusChanged {
                focused = it.isFocused
                // What the knob does here, until the driver uses it on the map; then again only after a good rest.
                // Being given the focus is not using it: the map takes it by itself when a trip starts
                if (it.isFocused && SystemClock.elapsedRealtime() - mapKnobUsedAt > HINT_AGAIN_MS) hint = true
            }
            .onPreviewKeyEvent { event ->
                // The destinations panel and the stop-or-destination choice have the knob while they are open; the
                // buttons over the map take their own presses
                if (panelOpen || offer != null || !focused) return@onPreviewKeyEvent false
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    // A press: back to the car if the map was moved away, else where to go
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (down) {
                            mapKnobUsedAt = SystemClock.elapsedRealtime()
                            hint = false
                            if (!state.following) {
                                state.recenter()
                                EventLog.log("Map: back to the car")
                            } else {
                                panelOpen = true
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        CarMap(state, orientation, interactive = true, Modifier.fillMaxSize(), autoZoom = autoZoom)

        // The next maneuver while a route is on, the word on the position under it
        Column(Modifier.align(Alignment.TopStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A trip waiting for its route says why where the maneuver goes
            val waiting = planning
            if (waiting != null && places.isNotEmpty()) {
                PlanningCard(waiting, places, onRetry = { Navigation.retry() }, onEnd = { Navigation.end() })
            } else {
                nav?.let { ManeuverCard(it, onEnd = { Navigation.end() }) }
            }
            MapStatus()
        }
        // The credits up in the free corner: the bottom is for the road
        MapAttribution(Modifier.align(Alignment.TopEnd).padding(14.dp))

        // The road under the car: its limit at the bottom left, as on a factory display, its name in the middle
        road?.let { current ->
            if (showLimit) current.limit?.let { SpeedLimitSign(it, 84.dp, Modifier.align(Alignment.BottomStart).padding(18.dp)) }
            RoadNamePill(current, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
        }

        // What the knob does on the map, above the road's name
        AnimatedVisibility(
            visible = hint && focused,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            CarLabel(
                stringResource(R.string.map_knob_hint), 18.sp, colors.text, weight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.surface)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        // Zoom always in the same place; recenter comes and goes to its left, so nothing moves under the finger
        Row(
            Modifier.align(Alignment.BottomEnd).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AnimatedVisibility(
                visible = !state.following,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                MapButton(CarIcons.Recenter, stringResource(R.string.map_recenter), BUTTON_SIZE, CircleShape) { state.recenter() }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The voice guidance during a trip, above the rest so nothing moves when it comes
                if (places.isNotEmpty()) {
                    MapButton(guidance.icon(), stringResource(R.string.nav_voice), BUTTON_SIZE, CircleShape) {
                        settings.setGuidanceVoice(guidance.next())
                    }
                }
                MapButton(CarIcons.Search, stringResource(R.string.nav_destinations), BUTTON_SIZE, CircleShape) { panelOpen = true }
                // Not clipped: the focus ring of either button is drawn just outside the pill
                Column(
                    Modifier.background(colors.surface, RoundedCornerShape(BUTTON_SIZE / 2)),
                ) {
                    MapButton(CarIcons.Plus, stringResource(R.string.map_zoom_in), BUTTON_SIZE, null) { zoomBy(state, 1.0) }
                    Box(Modifier.width(BUTTON_SIZE).padding(horizontal = 12.dp).height(1.dp).background(colors.line))
                    MapButton(CarIcons.Minus, stringResource(R.string.map_zoom_out), BUTTON_SIZE, null) { zoomBy(state, -1.0) }
                }
            }
        }

        // The knob's focus on the whole map, drawn inside: the map fills the screen, there is no room around it
        if (focused) {
            Box(Modifier.matchParentSize().border(3.dp, colors.focus, shape))
        }

        if (panelOpen && offer == null) {
            DestinationPanel(onClose = {
                panelOpen = false
                // The knob goes back to the map
                runCatching { mapFocus.requestFocus() }
            })
        }
        offer?.let { place ->
            fun done() {
                Trip.clearOffer()
                panelOpen = false
                runCatching { mapFocus.requestFocus() }
            }
            TripChoice(
                place, places.lastOrNull(),
                onStop = {
                    val fix = MapLocation.fix.value
                    Trip.addStop(place, fix?.latitude, fix?.longitude)
                    done()
                },
                onNewDestination = {
                    Trip.newDestination(place)
                    done()
                },
                onCancel = { done() },
            )
        }
    }
}

/**
 * A word on where the car's arrow comes from when it is not the car's own GPS: missing, the phone's
 * (only for a car with a GPS of its own: otherwise the phone is the normal way), estimated, or stale.
 */
@Composable
internal fun MapStatus(modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LocalCarColors.current
    val problem by MapLocation.problem.collectAsStateWithLifecycle()
    val fix by MapLocation.fix.collectAsStateWithLifecycle()
    val lost by MapLocation.lost.collectAsStateWithLifecycle()
    val carHasGps by remember { VehicleData.snapshot.map { MapLocation.carHasGps(it) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(MapLocation.carHasGps(VehicleData.snapshot.value))
    val current = fix
    val text = when {
        current == null && problem == MapLocationProblem.NO_PERMISSION -> R.string.map_no_permission
        current == null -> R.string.map_searching
        lost -> R.string.map_no_signal
        current.source == MapSource.ESTIMATED -> R.string.map_estimated
        current.source == MapSource.PHONE && carHasGps -> R.string.map_phone_gps
        else -> null
    } ?: return
    // Searching is just waiting; the others are a warning about what the arrow shows
    val warning = current != null
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (warning) Box(Modifier.size(10.dp).background(WarningAmber, CircleShape))
        CarLabel(stringResource(text), if (compact) 16.sp else 18.sp, colors.text2, weight = FontWeight.Bold, maxLines = 2)
    }
}

/** The map data's credits, small in a corner as their licences ask. */
@Composable
internal fun MapAttribution(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    // With a route on the map, its source too
    val routed by remember { Navigation.state.map { it != null }.distinctUntilChanged() }.collectAsStateWithLifecycle(Navigation.state.value != null)
    CarLabel(
        stringResource(if (routed) R.string.map_attribution_route else R.string.map_attribution), 11.sp, colors.text2, maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface.copy(alpha = 0.75f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun zoomBy(state: CarMapState, step: Double) {
    state.zoomBy(step)
    EventLog.log("Map: zoom to ${"%.1f".format(state.zoom)}")
}

/** A button over the map, for touch and the knob alike. [shape]: its own round background, else none (in a pill). */
@Composable
private fun MapButton(icon: ImageVector, label: String, size: Dp, shape: Shape?, onTap: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .size(size)
            .carClickable(shape ?: CircleShape, label, onClick = onTap)
            .then(if (shape != null) Modifier.clip(shape).background(colors.surface) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, colors.text, Modifier.size(30.dp))
    }
}

/** Bigger than the usual round button: on a map in motion a finger aims less well (52 was small to the touch). */
private val BUTTON_SIZE = 64.dp

/** When the knob was last used on the map ([SystemClock.elapsedRealtime]): the hint comes back only after a rest. */
private var mapKnobUsedAt = 0L
private const val HINT_MS = 3_000L
private const val HINT_AGAIN_MS = 2 * 60_000L
private const val SECTION_TILT = 45.0
