package com.simoneluconi.betterauto.ui.map

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.NavRoute
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.RoutePreview
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LocalFormatLocale
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import com.simoneluconi.betterauto.ui.widgets.Clock12
import com.simoneluconi.betterauto.ui.widgets.Clock24
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.ceil

/**
 * A new trip's routes to choose from, where the maneuver card goes ([Navigation.preview]): one row per route (how
 * long, how far, tolls, the arrival time; the other ways say how much longer they take), the chosen one marked here
 * and drawn in colour on the map; under them chips to avoid tolls, motorways or ferries (the routes asked again at
 * once, the old ones faded meanwhile); at the bottom, never scrolled away on a small screen, Go (with when the
 * guidance starts by itself) and Cancel. Takes the knob on the
 * chosen route: turning goes through the routes (the one the knob is on is chosen) and on to Go and Cancel, a press
 * starts the route. A tap chooses a route, a second tap starts it. Back starts the guidance too, the trip stays: ending
 * it is Cancel.
 */
@Composable
fun RoutePreviewPanel(preview: RoutePreview, places: List<Destination>, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val first = remember { FocusRequester() }
    // The knob on the chosen route, and back on it when new routes come (a preference switched) if it was on a route:
    // the row it was on may be gone, or no longer the chosen one. On a chip it stays there, to switch the next one
    var knobOnRoutes by remember { mutableStateOf(true) }
    LaunchedEffect(preview.routes) {
        if (!knobOnRoutes && NavOverlayShown.hasFocus) return@LaunchedEffect
        // Once the new rows are drawn
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    // When it starts by itself, counted down in whole seconds
    var secondsLeft by remember { mutableIntStateOf(secondsTo(preview.startsAt)) }
    LaunchedEffect(preview.startsAt) {
        while (true) {
            secondsLeft = secondsTo(preview.startsAt)
            delay(TICK_MS)
        }
    }
    val fastest = preview.routes.minOf { it.durationS }
    Column(
        modifier
            .widthIn(max = 460.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .navOverlay(first) { Navigation.startNow() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarLabel(places.last().name, 24.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 1, shrinkToFit = true)
        val stops = places.size - 1
        if (stops > 0) CarLabel(pluralStringResource(R.plurals.nav_card_stops, stops, stops), 18.sp, colors.text2, weight = FontWeight.Bold)
        // Room around the rows for their focus ring, which the scrolling column would cut
        Column(
            Modifier.weight(1f, fill = false).verticalScrollWithBar().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            preview.routes.forEachIndexed { i, route ->
                RouteRow(
                    route, chosen = i == preview.chosen, slowerS = route.durationS - fastest,
                    // Being asked again: the routes there were, faded, until the new ones come
                    modifier = (if (i == preview.chosen) Modifier.focusRequester(first) else Modifier)
                        .alpha(if (preview.updating) UPDATING_ALPHA else 1f),
                    onFocused = {
                        knobOnRoutes = true
                        Navigation.choosePreview(i)
                    },
                    onClick = { if (i == preview.chosen) Navigation.startNow() else Navigation.choosePreview(i) },
                )
            }
            AvoidChips(Modifier.onFocusChanged { if (it.hasFocus) knobOnRoutes = false })
        }
        // Always in sight, whatever scrolls above on a small screen: Go, with when it starts by itself, and Cancel
        Row(
            Modifier.onFocusChanged { if (it.hasFocus) knobOnRoutes = false },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoButton(
                if (preview.updating) stringResource(R.string.nav_calculating) else stringResource(R.string.nav_preview_go_in, secondsLeft),
                stringResource(R.string.nav_preview_go), Modifier.weight(1f),
            ) { Navigation.startNow() }
            ChoicePill(stringResource(R.string.voice_call_cancel), primary = false, knob = true, Modifier.weight(0.7f)) { Navigation.end() }
        }
    }
}

/** Go: filled in the route's colour, the one thing to press, with [text] saying when it goes by itself. */
@Composable
private fun GoButton(text: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            // Before the background: the focus ring is drawn just outside the pill
            .carClickable(shape, label, onClick = onClick)
            .background(colors.focus, shape)
            .height(56.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(text, 20.sp, colors.bg, weight = FontWeight.ExtraBold, tabularNumbers = true, maxLines = 1, shrinkToFit = true)
    }
}

/**
 * The route's main preferences at hand where the routes are chosen: tolls, motorways, ferries, each a chip that is
 * on while kept off. The same settings as in Settings › Map; switched, the routes are asked again at once.
 */
@Composable
private fun AvoidChips(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    val tolls by settings.avoidTolls.collectAsStateWithLifecycle()
    val highways by settings.avoidHighways.collectAsStateWithLifecycle()
    val ferries by settings.avoidFerries.collectAsStateWithLifecycle()
    fun switch(key: String, now: Boolean, set: (Boolean) -> Unit) {
        EventLog.log("Setting $key: $now -> ${!now} (from the routes to choose from)")
        set(!now)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarLabel(stringResource(R.string.nav_preview_avoid), 17.sp, colors.text2, weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AvoidChip(stringResource(R.string.nav_preview_tolls), stringResource(R.string.settings_avoid_tolls), tolls, Modifier.weight(1f)) {
                switch("avoid_tolls", tolls, settings::setAvoidTolls)
            }
            AvoidChip(stringResource(R.string.nav_preview_motorways), stringResource(R.string.settings_avoid_highways), highways, Modifier.weight(1f)) {
                switch("avoid_highways", highways, settings::setAvoidHighways)
            }
            AvoidChip(stringResource(R.string.nav_preview_ferries), stringResource(R.string.settings_avoid_ferries), ferries, Modifier.weight(1f)) {
                switch("avoid_ferries", ferries, settings::setAvoidFerries)
            }
        }
    }
}

/** A chip on while [on], as the settings' chips are: tinted in the focus colour. [label]: what it does, for the knob. */
@Composable
private fun AvoidChip(text: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .carClickable(shape, label, onClick = onClick)
            .background(if (on) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
            .height(48.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(text, 17.sp, colors.text, weight = FontWeight.Bold, maxLines = 1, shrinkToFit = true)
    }
}

/** One route: how long and how far, tolls, how much longer than the quickest ([slowerS]), and the arrival time. */
@Composable
private fun RouteRow(route: NavRoute, chosen: Boolean, slowerS: Double, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val units = rememberUnits()
    val locale = LocalFormatLocale.current
    val shape = RoundedCornerShape(20.dp)
    val time = durationText(route.durationS)
    val details = listOfNotNull(
        distanceText(route.lengthM, units),
        stringResource(R.string.nav_preview_tolls).takeIf { route.hasToll },
        // A minute or two is no difference to speak of
        stringResource(R.string.nav_preview_slower, durationText(slowerS)).takeIf { slowerS >= SLOWER_SHOWN_S },
    ).joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .carClickable(shape, "$time · $details", onClick = onClick)
            .background(if (chosen) colors.surface2 else colors.surface, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The chosen one in the route's colour, as it is drawn on the map; the others in the grey they are drawn in
        Box(Modifier.width(6.dp).height(48.dp).background(if (chosen) colors.focus else colors.text2, RoundedCornerShape(50)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(time, 26.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true, maxLines = 1)
            CarLabel(details, 17.sp, colors.text2, weight = FontWeight.Bold, tabularNumbers = true, maxLines = 1, shrinkToFit = true)
        }
        val arrival = LocalTime.now().plusSeconds(route.durationS.toLong())
        // As the launcher's clocks show time: 24 h or 12 h by the formats setting
        val clock = if (AppLocales.is24Hours(LocalContext.current, locale)) Clock24 else Clock12
        CarLabel(arrival.format(clock), 20.sp, colors.text, weight = FontWeight.Bold, tabularNumbers = true)
    }
}

private fun secondsTo(at: Long): Int = ceil((at - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000.0).toInt()

private const val TICK_MS = 250L

/** The routes there were while new ones are asked. */
private const val UPDATING_ALPHA = 0.45f

/** Another way this much longer than the quickest says so. */
private const val SLOWER_SHOWN_S = 120.0
