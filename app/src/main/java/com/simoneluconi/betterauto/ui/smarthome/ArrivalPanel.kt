package com.simoneluconi.betterauto.ui.smarthome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.simoneluconi.betterauto.ui.FocusMemory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.smarthome.ArrivalState
import com.simoneluconi.betterauto.smarthome.ProviderChoice
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.smarthome.SmartHomeProvider
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NoArrival: StateFlow<ArrivalState> = MutableStateFlow(ArrivalState())

@Composable
private fun rememberArrival(): ArrivalState {
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val state by (SmartHome.arrival?.state ?: NoArrival).collectAsStateWithLifecycle()
    return state
}

/** Behind the arrival panel: the screen dims a little, and a tap anywhere else closes it. */
@Composable
fun ArrivalScrim(modifier: Modifier = Modifier) {
    val arrival = rememberArrival()
    AnimatedVisibility(visible = arrival.panelOpen, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .pointerInput(Unit) { detectTapGestures { SmartHome.arrival?.closePanel() } }
        )
    }
}

/**
 * The arrival buttons: when the car reaches one of the places with buttons (or starts there), this slides
 * in with that place's devices as big tiles, titled with the place's own name. The knob lands on the
 * first device's first action (a gate's Open) and stays inside; turning goes tile by tile, each tile's
 * buttons together, then Close; Back, Close or a tap outside closes it and the knob goes back where it
 * was. It can be reopened from the status bar while still there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrivalPanel(modifier: Modifier = Modifier) {
    val arrival = rememberArrival()
    val providers by SmartHome.linked.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    // Places of linked providers only, in a steady order
    val inside = arrival.inside.sorted().mapNotNull { key -> SmartHome.arrivalPlace(key)?.takeIf { it.first in providers } }
    // Where the knob was before the panel took it
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var wasOpen by remember { mutableStateOf(false) }
    val open = arrival.panelOpen && inside.isNotEmpty()
    LaunchedEffect(open) {
        if (wasOpen && !open) {
            withFrameNanos { }
            FocusMemory.restore(returnFocus)
            returnFocus = null
        }
        wasOpen = open
    }
    AnimatedVisibility(
        visible = open,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val colors = LocalCarColors.current
        val shape = RoundedCornerShape(32.dp)
        val first = remember { FocusRequester() }
        Column(
            Modifier
                .widthIn(min = 600.dp, max = 1000.dp)
                .shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.line, shape)
                // A tap on the panel itself is not a tap outside
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
                .focusGroup()
                .onKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyUp) SmartHome.arrival?.closePanel()
                    true
                }
                .padding(horizontal = 26.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            var firstUsed = false
            inside.forEach { (provider, place) ->
                val catalog by provider.catalog.collectAsStateWithLifecycle()
                val states by provider.states.collectAsStateWithLifecycle()
                val feedback by SmartHome.feedback.collectAsStateWithLifecycle()
                val link by provider.link.collectAsStateWithLifecycle()
                val homeName by provider.homeName.collectAsStateWithLifecycle()
                val ids = (choices[provider.id] ?: ProviderChoice()).arrivalAt(place)?.devices.orEmpty()
                val devices = catalog?.devices.orEmpty().filter { it.id in ids }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProviderIcon(provider, 34)
                    Column(Modifier.weight(1f)) {
                        // The place's own name (home, office...), then whose it is
                        CarLabel(place.name, 26.sp, colors.text, weight = FontWeight.ExtraBold, shrinkToFit = true)
                        CarLabel(linkProblem(link) ?: homeName?.takeIf { it != place.name } ?: provider.name, 18.sp, colors.text2, maxLines = 2)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (device in devices) {
                        val focus = if (!firstUsed) first.also { firstUsed = true } else null
                        SmartDeviceTile(
                            provider, device, states[device.id], feedback[device.key],
                            onAction = { SmartHome.perform(provider, device, it) },
                            modifier = Modifier.width(290.dp),
                            container = colors.surface2,
                            focus = focus,
                        )
                    }
                }
            }
            // Last in the knob's order, after every device: the likely action is a device, not Close
            Row(Modifier.align(Alignment.End)) { CloseButton(if (firstUsed) null else first) }
        }
        LaunchedEffect(Unit) {
            returnFocus = FocusMemory.last
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

@Composable
private fun CloseButton(focus: FocusRequester?) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .carClickable(CircleShape, stringResource(R.string.smarthome_arrival_close)) { SmartHome.arrival?.closePanel() }
            .background(colors.surface2, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(CarIcons.Close, colors.text, Modifier.size(24.dp))
    }
}

/**
 * In the status bar while the car is at a provider's place: reopens the arrival panel. It comes and
 * goes, so it sits left of the buttons that are always there.
 */
@Composable
fun ArrivalStatusButton() {
    val arrival = rememberArrival()
    val providers by SmartHome.linked.collectAsStateWithLifecycle()
    val (provider, place) = arrival.inside.sorted().firstNotNullOfOrNull { key -> SmartHome.arrivalPlace(key)?.takeIf { it.first in providers } } ?: return
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, stringResource(R.string.smarthome_arrival_reopen, place.name)) { SmartHome.arrival?.openPanel() }
            .background(if (arrival.panelOpen) colors.focus.copy(alpha = 0.2f) else colors.surface, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProviderIcon(provider, 28)
    }
}

/** A linked provider's button in the app bar, beside the media apps: opens its screen. */
@Composable
fun SmartHomeRailButton(provider: SmartHomeProvider, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .carClickable(shape, provider.name) {
                SmartHome.screenProvider.value = provider.id
                onClick()
            }
            .background(if (selected) colors.focus.copy(alpha = 0.2f) else colors.surface, shape)
            .size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProviderIcon(provider, 44, tint = if (selected) colors.text else colors.text2)
    }
}
