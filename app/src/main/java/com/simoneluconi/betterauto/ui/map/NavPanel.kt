package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.Units
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.ui.widgets.Clock12
import com.simoneluconi.betterauto.ui.widgets.Clock24
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.ManeuverKind
import com.simoneluconi.betterauto.map.nav.NavState
import com.simoneluconi.betterauto.map.nav.RoutePlanning
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.vehicle.WarningAmber
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LocalFormatLocale
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.SignWhite
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * The next maneuver, as a factory navigation shows it: its arrow, how far it is, the street it leads onto,
 * and under it the arrival time with what is left, and the way to end the navigation ([onEnd]).
 */
@Composable
fun ManeuverCard(nav: NavState, modifier: Modifier = Modifier, onEnd: (() -> Unit)? = null) {
    val colors = LocalCarColors.current
    val units = rememberUnits()
    val locale = LocalFormatLocale.current
    val next = nav.next
    Column(
        modifier
            .widthIn(max = 460.dp)
            // As wide as its widest line, so the hairline spans the card
            .width(IntrinsicSize.Max)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)).background(colors.focus),
                contentAlignment = Alignment.Center,
            ) {
                val kind = next?.kind ?: ManeuverKind.ARRIVE
                // Clockwise where traffic keeps left: the arrow drawn for the right, mirrored
                val mirrored = kind == ManeuverKind.ROUNDABOUT && roundaboutsClockwise()
                CarIcon(iconOf(kind), SignWhite, Modifier.size(52.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f })
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val distance = if (nav.rerouting) null else distanceText(nav.toNextM, units)
                CarLabel(distance ?: "…", 34.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true)
                val street = next?.street ?: next?.instruction
                if (street != null) CarLabel(street, 20.sp, colors.text2, weight = FontWeight.Bold, maxLines = 2, shrinkToFit = true)
            }
        }
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val arrival = LocalTime.now().plusSeconds(nav.remainingS.toLong())
            // The arrival time as the clocks of the launcher show time: 24 h or 12 h by the formats setting
            val clock = if (AppLocales.is24Hours(LocalContext.current, locale)) Clock24 else Clock12
            CarLabel(arrival.format(clock), 20.sp, colors.text, weight = FontWeight.Bold, tabularNumbers = true)
            Box(Modifier.width(1.dp).height(18.dp).background(colors.line))
            CarLabel(distanceText(nav.remainingM, units), 18.sp, colors.text2, weight = FontWeight.Bold, tabularNumbers = true)
            Box(Modifier.width(1.dp).height(18.dp).background(colors.line))
            CarLabel(durationText(nav.remainingS), 18.sp, colors.text2, weight = FontWeight.Bold, tabularNumbers = true)
            onEnd?.let {
                Box(Modifier.weight(1f))
                EndButton(it)
            }
        }
        // The next stop on the way, with how far it is; the line above is for the whole trip
        nav.stop?.let { stop ->
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(colors.line))
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CarIcon(CarIcons.Arrive, colors.text2, Modifier.size(20.dp))
                CarLabel(
                    stringResource(R.string.nav_card_stop, stop.name), 18.sp, colors.text, Modifier.weight(1f, fill = false),
                    weight = FontWeight.Bold, maxLines = 1, shrinkToFit = true,
                )
                CarLabel(distanceText(nav.toStopM, units), 18.sp, colors.text2, weight = FontWeight.Bold, tabularNumbers = true)
            }
        }
    }
}

/** Where traffic keeps left roundabouts turn clockwise: told by the steering wheel's side (the setting, else the car). */
@Composable
private fun roundaboutsClockwise(): Boolean {
    val settings = LauncherSettings.get(LocalContext.current)
    val side by settings.driverSide.collectAsStateWithLifecycle()
    val carSays by CarState.driverOnRight.collectAsStateWithLifecycle()
    return side.onRight(carSays)
}

/**
 * A trip without a route to follow, where the maneuver card goes: being calculated, or why none came, with a way
 * to ask again and to end it (for touch; the knob has both in the destinations panel).
 */
@Composable
fun PlanningCard(planning: RoutePlanning, places: List<Destination>, onRetry: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val failed = planning != RoutePlanning.CALCULATING
    Column(
        modifier
            .widthIn(max = 460.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (failed) Box(Modifier.size(12.dp).background(WarningAmber, CircleShape))
            CarLabel(planningText(planning, places).orEmpty(), 20.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.Bold, maxLines = 3)
        }
        if (planning == RoutePlanning.NO_ROUTE) CarLabel(stringResource(R.string.nav_no_route_hint), 16.sp, colors.text2, maxLines = 2)
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val label = stringResource(R.string.nav_retry)
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.surface2)
                        .semantics { contentDescription = label }
                        .pointerInput(Unit) { detectTapGestures { onRetry() } }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarLabel(label, 17.sp, colors.text, weight = FontWeight.Bold, maxLines = 1)
                }
                Box(Modifier.weight(1f))
                EndButton(onEnd)
            }
        }
    }
}

/**
 * The end of the navigation, for touch (the knob ends it from the panel): a first tap asks, showing what it does,
 * a second one ends it, so a trip with its stops is not lost to a slip of the finger. The question goes away by
 * itself after a while.
 */
@Composable
private fun EndButton(onEnd: () -> Unit) {
    val colors = LocalCarColors.current
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            delay(END_ASK_MS)
            asking = false
        }
    }
    val label = stringResource(R.string.nav_stop)
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (asking) colors.focus else colors.surface2)
            .semantics { contentDescription = label }
            .pointerInput(Unit) { detectTapGestures { if (asking) onEnd() else asking = true } }
            .padding(horizontal = if (asking) 16.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (asking) CarLabel(label, 17.sp, colors.bg, weight = FontWeight.Bold, maxLines = 1)
        else CarIcon(CarIcons.Close, colors.text, Modifier.size(20.dp))
    }
}

private const val END_ASK_MS = 4_000L

/** A maneuver's arrow: on the card, and drawn for the car's cluster. */
internal fun iconOf(kind: ManeuverKind): ImageVector = when (kind) {
    ManeuverKind.STRAIGHT -> CarIcons.TurnStraight
    ManeuverKind.SLIGHT_RIGHT -> CarIcons.TurnSlightRight
    ManeuverKind.RIGHT -> CarIcons.TurnRight
    ManeuverKind.SHARP_RIGHT -> CarIcons.TurnSharpRight
    ManeuverKind.SLIGHT_LEFT -> CarIcons.TurnSlightLeft
    ManeuverKind.LEFT -> CarIcons.TurnLeft
    ManeuverKind.SHARP_LEFT -> CarIcons.TurnSharpLeft
    ManeuverKind.U_TURN -> CarIcons.UTurn
    ManeuverKind.ROUNDABOUT -> CarIcons.Roundabout
    ManeuverKind.ARRIVE -> CarIcons.Arrive
}

/** "350 m", "1,2 km": rounded as a driver reads it, in the driver's units. */
@Composable
internal fun distanceText(metres: Double, units: Units): String {
    if (units.imperial) {
        val miles = metres / 1609.344
        return if (miles < 0.1) stringResource(R.string.nav_distance_ft, (metres * 3.28084 / 50).roundToInt() * 50)
        else units.distance(metres / 1000, decimals = if (miles < 10) 1 else 0)
    }
    if (metres < 1000) {
        val step = if (metres < 300) 10 else 50
        return stringResource(R.string.nav_distance_m, (metres / step).roundToInt() * step)
    }
    return units.distance(metres / 1000, decimals = if (metres < 10_000) 1 else 0)
}

/** "8 min", "1 h 20". */
@Composable
private fun durationText(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) stringResource(R.string.nav_duration_min, minutes)
    else stringResource(R.string.nav_duration_hours, minutes / 60, minutes % 60)
}
