package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.DestinationActivity
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.distanceM
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.Destinations
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.RoutePlanning
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits
import kotlinx.coroutines.delay

/**
 * "Where to?" over the map, on two pages. The main one, harmless choices first and the ones that end something
 * last (each asks for a second press): the voice guidance, search on the phone (typing stays on the phone), the
 * route asked again when none came, "Edit trip", skip the next stop, end the navigation; then the recent
 * destinations (not those already in the trip), which during a trip ask whether they are a stop or the new
 * destination. "Edit trip" shows the trip's places instead, moved and removed only with the car standing (on the
 * way that is for the voice or a passenger with the phone; the list still shows). Opened by pressing the knob on
 * the map or the destinations button; takes the knob on its first row and keeps it; Back goes one level back,
 * a tap outside closes it.
 */
@Composable
fun DestinationPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val units = rememberUnits()
    val places by Trip.places.collectAsStateWithLifecycle()
    val recents by Destinations.recents.collectAsStateWithLifecycle()
    val planning by Navigation.planning.collectAsStateWithLifecycle()
    val settings = remember { LauncherSettings.get(context) }
    val guidance by settings.guidanceVoice.collectAsStateWithLifecycle()
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    var searchSent by remember { mutableStateOf(false) }
    var tripPage by remember { mutableStateOf(false) }
    val pick = remember { TripPick() }
    val first = remember { FocusRequester() }
    val editRow = remember { FocusRequester() }
    val tripBack = remember { FocusRequester() }
    // Which page the focus goes to once it is drawn: the first row, the trip page's back, or "Edit trip" again
    var focusTarget by remember { mutableStateOf<FocusRequester?>(first) }
    LaunchedEffect(Unit) { Destinations.init(context) }
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        // The page just changed: the control that had the focus went with it, and the focus is dropped a frame
        // later; asked too soon, it would be dropped too. Asked twice (again on a focused control changes nothing)
        repeat(2) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
        focusTarget = null
    }
    // No trip left to edit: back to the main page
    LaunchedEffect(places.isEmpty()) {
        if (places.isEmpty() && tripPage) {
            tripPage = false
            focusTarget = first
        }
    }
    fun back() {
        when {
            pick.active -> pick.cancel()
            tripPage -> {
                tripPage = false
                focusTarget = editRow
            }
            else -> onClose()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // A tap outside the card closes it
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .padding(18.dp)
                .widthIn(max = 520.dp)
                .fillMaxHeight()
                .shadow(10.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                // Taps on the card stay on the card
                .pointerInput(Unit) { detectTapGestures { } }
                .navOverlay(if (tripPage) tripBack else first, ::back)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tripPage) {
                TripPage(places, standing = motion != CarMotion.MOVING, pick, tripBack, ::back)
                return@Column
            }
            // During a trip it is the navigation's own menu, not just a search
            CarLabel(
                stringResource(if (places.isEmpty()) R.string.nav_where_to else R.string.nav_card_title), 26.sp, colors.text,
                weight = FontWeight.ExtraBold,
            )
            // Room around the rows for their focus ring, which the scrolling column would cut
            Column(Modifier.weight(1f).verticalScrollWithBar().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var focusGiven = false
                fun firstFocus(): Modifier = if (focusGiven) Modifier else Modifier.focusRequester(first).also { focusGiven = true }

                if (places.isNotEmpty()) {
                    PanelRow(guidance.icon(), stringResource(R.string.nav_voice), stringResource(guidance.label()), firstFocus()) {
                        settings.setGuidanceVoice(guidance.next())
                    }
                }
                PanelRow(
                    CarIcons.Phone, stringResource(R.string.nav_search_on_phone),
                    if (searchSent) stringResource(R.string.nav_search_on_phone_desc) else null, firstFocus(),
                ) {
                    DestinationActivity.notifySearch(context)
                    searchSent = true
                }
                if (places.isNotEmpty()) {
                    // No route came: asked again here, with the knob
                    if (planning == RoutePlanning.NO_ROUTE || planning == RoutePlanning.OFFLINE) {
                        PanelRow(CarIcons.Recenter, stringResource(R.string.nav_retry), planningText(planning, places), Modifier) {
                            Navigation.retry()
                        }
                    }
                    val standing = motion != CarMotion.MOVING
                    val stops = places.size - 1
                    val summary = listOfNotNull(
                        if (stops > 0) pluralStringResource(R.plurals.nav_card_stops, stops, stops) else null,
                        stringResource(R.string.nav_card_trip, places.last().name),
                    ).joinToString(" · ")
                    PanelRow(
                        CarIcons.Map, stringResource(R.string.nav_edit_trip_open),
                        if (standing) summary else stringResource(R.string.nav_edit_when_standing), Modifier.focusRequester(editRow),
                    ) {
                        tripPage = true
                        focusTarget = tripBack
                    }
                    if (places.size > 1) {
                        val stop = places.first()
                        ConfirmRow(CarIcons.Next, stringResource(R.string.nav_skip_stop), stop.name, stringResource(R.string.nav_confirm_skip, stop.name)) {
                            Trip.remove(stop)
                        }
                    }
                    ConfirmRow(CarIcons.Close, stringResource(R.string.nav_stop), null, stringResource(R.string.nav_confirm_end)) {
                        Navigation.end()
                        onClose()
                    }
                }
                // The recent places, not the ones already in the trip
                val shownRecents = recents.filter { recent -> places.none { same(it, recent) } }
                CarLabel(stringResource(R.string.nav_recents), 18.sp, colors.text2, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                if (shownRecents.isEmpty()) {
                    CarLabel(stringResource(R.string.nav_no_recents), 18.sp, colors.text2)
                }
                for (place in shownRecents) {
                    val distance = MapLocation.fix.value?.let { distanceM(it.latitude, it.longitude, place.latitude, place.longitude) }
                    PanelRow(
                        CarIcons.Map, place.name,
                        listOfNotNull(place.address, distance?.let { distanceText(it, units) }).joinToString(" · ").ifBlank { null },
                        Modifier,
                    ) {
                        Trip.choose(place)
                        onClose()
                    }
                }
            }
        }
    }
}

/** The trip's places, to move and remove with the car standing; read only on the way (said on the page). */
@Composable
private fun TripPage(places: List<Destination>, standing: Boolean, pick: TripPick, backFocus: FocusRequester, onBack: () -> Unit) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Back to the main page, for a finger (the knob's Back does the same)
        Box(
            Modifier
                .focusRequester(backFocus)
                .carClickable(CircleShape, stringResource(R.string.music_back)) { onBack() }
                .background(colors.surface2, CircleShape)
                .size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
        }
        CarLabel(stringResource(R.string.nav_edit_trip), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, shrinkToFit = true)
    }
    Column(Modifier.verticalScrollWithBar().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TripPlaces(places, editable = standing && places.size > 1, pick = pick)
        if (!standing && places.size > 1) CarLabel(stringResource(R.string.nav_edit_when_standing), 16.sp, colors.text2, maxLines = 3)
    }
}

/** Why there is no route, in words: being calculated, none to [places]' destination, or no server. */
@Composable
internal fun planningText(planning: RoutePlanning?, places: List<Destination>): String? = when (planning) {
    RoutePlanning.CALCULATING -> stringResource(R.string.nav_calculating)
    RoutePlanning.NO_ROUTE -> stringResource(R.string.nav_no_route, places.lastOrNull()?.name.orEmpty())
    RoutePlanning.OFFLINE -> stringResource(R.string.nav_offline)
    null -> null
}

/** Within about 50 m: the same place, whatever it was called. */
private fun same(a: Destination, b: Destination) = distanceM(a.latitude, a.longitude, b.latitude, b.longitude) < SAME_PLACE_M

/** One choice in the panel: an icon in a circle, its title and a line under it. */
@Composable
private fun PanelRow(icon: ImageVector, title: String, detail: String?, modifier: Modifier, asking: Boolean = false, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            // Before the clip: the focus ring is drawn just outside the row
            .carClickable(shape, title, onClick = onClick)
            .clip(shape)
            .background(if (asking) colors.focus else colors.surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
            CarIcon(icon, colors.text, Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            CarLabel(title, 20.sp, if (asking) colors.bg else colors.text, weight = FontWeight.Bold, maxLines = 2, shrinkToFit = true)
            detail?.let { CarLabel(it, 16.sp, if (asking) colors.bg else colors.text2, maxLines = 2) }
        }
    }
}

/**
 * A choice that ends something: the first press turns it into the question ([confirm]), a second one within a few
 * seconds acts, so the knob never ends a trip by a slip; left alone, it goes back by itself.
 */
@Composable
private fun ConfirmRow(icon: ImageVector, title: String, detail: String?, confirm: String, onConfirmed: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            delay(CONFIRM_MS)
            asking = false
        }
    }
    PanelRow(icon, if (asking) confirm else title, if (asking) null else detail, Modifier, asking = asking) {
        if (asking) {
            asking = false
            onConfirmed()
        } else {
            asking = true
        }
    }
}

private const val CONFIRM_MS = 4_000L
private const val SAME_PLACE_M = 50.0
