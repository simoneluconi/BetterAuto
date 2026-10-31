package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.settings.GuidanceVoice
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * The map's overlays that wait for the driver (the destinations panel, the stop-or-destination choice), for the
 * launcher's keys: while one is open, the knob's nudges move inside it and Back goes one level back in it,
 * wherever the focus is; a knob move with the focus outside brings it back in.
 */
object NavOverlayShown {
    var value by mutableStateOf(false)
        internal set

    /** The focus is inside the overlay. */
    var hasFocus = false
        internal set

    /** The overlay's first control. */
    var first: FocusRequester? = null
        internal set

    /** One level back: a place put down where it was, the trip page, then the overlay itself. */
    internal var back: (() -> Unit)? = null
}

/**
 * Marks the overlay this modifier sits on as open, for the launcher ([NavOverlayShown]), and keeps the knob in it:
 * turning past its first or last control stays put, a nudge never leaves it.
 */
@Composable
internal fun Modifier.navOverlay(first: FocusRequester, onBack: () -> Unit): Modifier {
    val back by rememberUpdatedState(onBack)
    DisposableEffect(first) {
        NavOverlayShown.value = true
        NavOverlayShown.first = first
        NavOverlayShown.back = { back() }
        onDispose {
            if (NavOverlayShown.first === first) {
                NavOverlayShown.value = false
                NavOverlayShown.first = null
                NavOverlayShown.back = null
                NavOverlayShown.hasFocus = false
            }
        }
    }
    return this
        .onFocusChanged { NavOverlayShown.hasFocus = it.hasFocus }
        .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
        .focusGroup()
}

/**
 * A trip place picked up with the knob: while it moves only the order shown changes; put down, the trip changes
 * once (one new route, not one per knob step); cancelled (Back), it stays where it was.
 */
@Stable
class TripPick {
    var place by mutableStateOf<Destination?>(null)
        private set

    /** The order shown while a place is picked up. */
    var order by mutableStateOf<List<Destination>?>(null)
        private set

    val active: Boolean get() = place != null

    fun pick(place: Destination, places: List<Destination>) {
        this.place = place
        order = places
    }

    /** One step up (-1) or down (+1) in the order shown. */
    fun step(delta: Int) {
        val list = order ?: return
        Trip.moved(list, list.indexOf(place), delta)?.let { order = it }
    }

    /** Puts the place down where it is shown: the trip changes now. */
    fun drop() {
        val moving = place ?: return
        val shown = order
        cancel()
        val to = shown?.indexOf(moving) ?: return
        val from = Trip.places.value.indexOf(moving)
        if (from >= 0 && to >= 0) Trip.moveTo(from, to)
    }

    /** Back where it was picked up. */
    fun cancel() {
        place = null
        order = null
    }
}

/**
 * The trip's places in the order they are driven to, the destination last, to remove and reorder when [editable].
 * Each row has a handle: dragged with a finger, or with the knob ([knob], in the car) pressed to pick the place up,
 * turned (or nudged up and down) to move it, pressed again to put it down, Back to leave it where it was ([pick]).
 * The same list in the car's panel and on the phone's page (smaller controls there, the screen is narrow). The rows
 * follow their place, so the knob stays on the place it moves and a drag goes on across the moves.
 */
@Composable
fun TripPlaces(
    places: List<Destination>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    knob: Boolean = true,
    pick: TripPick = remember { TripPick() },
    first: FocusRequester? = null,
) {
    val gap = 10.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    var dragged by remember { mutableStateOf<Destination?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // The trip changed under a picked place (a stop reached): the pick is off
    LaunchedEffect(places) { if (pick.place?.let { it !in places } == true) pick.cancel() }
    LaunchedEffect(editable) { if (!editable) pick.cancel() }
    val shown = pick.order ?: places
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        shown.forEachIndexed { index, place ->
            key(place) {
                val moving = dragged == place
                var step by remember { mutableFloatStateOf(0f) }
                val drag = Modifier.pointerInput(place) {
                    detectDragGestures(
                        onDragStart = {
                            dragged = place
                            offset = 0f
                        },
                        onDragEnd = { dragged = null },
                        onDragCancel = { dragged = null },
                    ) { change, amount ->
                        change.consume()
                        offset += amount.y
                        // Past half a row: the place swaps with its neighbour, the row stays under the finger
                        val i = Trip.places.value.indexOf(place)
                        if (step > 0 && offset > step / 2 && i in 0 until Trip.places.value.size - 1) {
                            Trip.move(i, 1)
                            offset -= step
                        } else if (step > 0 && offset < -step / 2 && i > 0) {
                            Trip.move(i, -1)
                            offset += step
                        }
                    }
                }
                TripRow(
                    place, index, shown.size, editable, knob, drag,
                    picked = pick.place == place,
                    pick = pick,
                    places = places,
                    first = first.takeIf { index == 0 },
                    modifier = Modifier
                        .onSizeChanged { step = it.height + gapPx }
                        // The dragged row follows the finger, over the others
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer { translationY = if (moving) offset else 0f }
                        .then(if (moving) Modifier.shadow(8.dp, RoundedCornerShape(20.dp)) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun TripRow(
    place: Destination,
    index: Int,
    count: Int,
    editable: Boolean,
    knob: Boolean,
    drag: Modifier,
    picked: Boolean,
    pick: TripPick,
    places: List<Destination>,
    first: FocusRequester?,
    modifier: Modifier,
) {
    val colors = LocalCarColors.current
    val last = index == count - 1
    val reorder = editable && count > 1
    // On the phone (touch only, a narrow screen) smaller controls, so the name keeps its room
    val compact = !knob
    val badge = if (compact) 32.dp else 40.dp
    val spacing = if (compact) 10.dp else 12.dp
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (picked) colors.focus.copy(alpha = 0.18f) else colors.surface2)
            .padding(if (compact) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        // The stop's number, or the flag of the destination
        Box(Modifier.size(badge).clip(CircleShape).background(if (last) colors.focus else colors.surface), contentAlignment = Alignment.Center) {
            if (last) CarIcon(CarIcons.Arrive, colors.bg, Modifier.size(badge * 0.55f))
            else CarLabel((index + 1).toString(), if (compact) 16.sp else 18.sp, colors.text, weight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            CarLabel(place.name, if (compact) 17.sp else 19.sp, colors.text, weight = FontWeight.Bold, maxLines = 2, shrinkToFit = true)
            val label = when {
                picked -> stringResource(R.string.nav_move_hint)
                last -> stringResource(R.string.nav_destination_label)
                else -> listOfNotNull(stringResource(R.string.nav_stop_label, index + 1), place.address).joinToString(" · ")
            }
            CarLabel(label, if (compact) 14.sp else 15.sp, if (picked) colors.text else colors.text2, maxLines = 1)
        }
        if (editable) {
            val size = if (compact) 40.dp else 52.dp
            Box(
                Modifier
                    .then(if (first != null) Modifier.focusRequester(first) else Modifier)
                    // By the place, not the row's index: the trip may have changed since it was drawn
                    .carClickable(CircleShape, stringResource(R.string.nav_remove, place.name)) { Trip.remove(place) }
                    .background(colors.surface, CircleShape)
                    .size(size),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Close, colors.text, Modifier.size(size * 0.48f))
            }
        }
        if (reorder) {
            val handle = if (knob) {
                Modifier
                    // Picked up: the knob's turns and up/down nudges move the place instead of the focus
                    .onPreviewKeyEvent { event ->
                        if (!picked) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.Tab -> if (event.isShiftPressed) -1 else 1
                            Key.DirectionDown -> 1
                            Key.DirectionUp -> -1
                            else -> 0
                        }
                        when {
                            delta != 0 -> {
                                if (event.type == KeyEventType.KeyDown) pick.step(delta)
                                true
                            }
                            // Sideways the place has nowhere to go: the knob stays on it
                            event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> true
                            else -> false
                        }
                    }
                    // The focus taken away (a touch elsewhere): the place stays where it is shown
                    .onFocusChanged { if (!it.isFocused && picked) pick.drop() }
                    .carClickable(CircleShape, stringResource(R.string.nav_move, place.name)) {
                        if (picked) pick.drop() else pick.pick(place, places)
                    }
                    .background(if (picked) colors.focus else colors.surface, CircleShape)
                    .size(52.dp)
            } else {
                Modifier.size(32.dp, 52.dp)
            }
            Box(drag.then(handle), contentAlignment = Alignment.Center) {
                CarIcon(
                    if (picked) CarIcons.MoveVertical else CarIcons.DragHandle,
                    if (picked) colors.bg else colors.text2,
                    Modifier.size(if (compact) 24.dp else 26.dp),
                )
            }
        }
    }
}

/**
 * A place picked during a trip: a stop on the way, or the new destination instead of [current]. Takes the knob
 * on "Add as a stop" and keeps it ([knob], in the car); Back or a tap outside cancels.
 */
@Composable
fun TripChoice(
    place: Destination,
    current: Destination?,
    onStop: () -> Unit,
    onNewDestination: () -> Unit,
    onCancel: () -> Unit,
    knob: Boolean = true,
) {
    val colors = LocalCarColors.current
    val first = remember { FocusRequester() }
    LaunchedEffect(place) { runCatching { first.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onCancel() } }
            // In the car the launcher sends Back here (NavOverlayShown); on the phone the page's own Back does
            .then(if (knob) Modifier else Modifier.onPreviewKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyUp) onCancel()
                    true
                } else {
                    false
                }
            }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(18.dp)
                .widthIn(max = 560.dp)
                .shadow(10.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                // Taps on the card stay on the card
                .pointerInput(Unit) { detectTapGestures { } }
                .then(if (knob) Modifier.navOverlay(first, onCancel) else Modifier)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            current?.let { CarLabel(stringResource(R.string.nav_choice_during, it.name), 18.sp, colors.text2, maxLines = 2) }
            CarLabel(place.name, 26.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 2, shrinkToFit = true)
            place.address?.let { CarLabel(it, 18.sp, colors.text2, maxLines = 2) }
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoicePill(stringResource(R.string.nav_add_stop), primary = true, knob, Modifier.focusRequester(first), onStop)
                ChoicePill(stringResource(R.string.nav_new_destination), primary = false, knob, Modifier, onNewDestination)
                ChoicePill(stringResource(R.string.voice_call_cancel), primary = false, knob, Modifier, onCancel)
            }
        }
    }
}

/**
 * One of the choice's answers. With the knob ([knob]) only the focused one is filled, so the fill never says
 * "selected" where the focus is not; on the phone (touch) the main answer is.
 */
@Composable
private fun ChoicePill(label: String, primary: Boolean, knob: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    val filled = if (knob) focused else primary
    Box(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            // Before the background: the focus ring is drawn just outside the pill
            .carClickable(shape, label, onClick = onClick)
            .background(if (filled) colors.focus else colors.surface2, shape)
            .height(56.dp)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(label, 20.sp, if (filled) colors.bg else colors.text, weight = FontWeight.ExtraBold)
    }
}

/** The voice guidance's icon and name, and the next one a press goes to (full, alerts only, mute, full...). */
internal fun GuidanceVoice.icon(): ImageVector = when (this) {
    GuidanceVoice.FULL -> CarIcons.Speaker
    GuidanceVoice.ALERTS -> CarIcons.SpeakerAlert
    GuidanceVoice.MUTE -> CarIcons.SpeakerMute
}

internal fun GuidanceVoice.label(): Int = when (this) {
    GuidanceVoice.FULL -> R.string.option_guidance_full
    GuidanceVoice.ALERTS -> R.string.option_guidance_alerts
    GuidanceVoice.MUTE -> R.string.option_guidance_mute
}

internal fun GuidanceVoice.next(): GuidanceVoice = GuidanceVoice.entries[(ordinal + 1) % GuidanceVoice.entries.size]
