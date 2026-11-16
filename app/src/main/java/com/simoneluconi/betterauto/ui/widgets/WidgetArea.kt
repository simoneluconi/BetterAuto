package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.settings.HomeArrangement
import com.simoneluconi.betterauto.settings.HomeRoom
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.ScreenShape
import com.simoneluconi.betterauto.settings.WidgetChoice
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LocalHoldAction
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.LocalContentFocus
import com.simoneluconi.betterauto.ui.launcher.carZone
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal val HomeGap = 20.dp

private val LocalSettings = staticCompositionLocalOf<LauncherSettings?> { null }

/** True on the car's own screen, false in the phone's preview of it: only the car's size says what the car is. */
val LocalProjected = staticCompositionLocalOf { false }

/**
 * Home screen: the widgets as the driver arranged them ([HomeArrangement]), the one kept for this kind of screen.
 * A screen too small for all of them puts the ones that don't fit a swipe away, never cut. Holding a widget
 * (finger or knob center) opens the edit mode ([HomeEdit]).
 */
@Composable
fun WidgetArea(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val touch by CarState.touchscreen.collectAsStateWithLifecycle()
    val driverSide by settings.driverSide.collectAsStateWithLifecycle()
    val carDriverOnRight by CarState.driverOnRight.collectAsStateWithLifecycle()
    val mirrored = driverSide.onRight(carDriverOnRight)
    val editing by HomeEdit.active.collectAsStateWithLifecycle()
    val projected = LocalProjected.current
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

    BoxWithConstraints(modifier) {
        val width = maxWidth.value
        val height = maxHeight.value
        val shape = ScreenShape.of(width, height)
        if (projected) LaunchedEffect(width, height) { settings.noteHomeArea(width, height) }
        if (editing) {
            HomeEditor(settings, shape, mirrored, Modifier.fillMaxSize())
            return@BoxWithConstraints
        }
        val saved by settings.home(shape).collectAsStateWithLifecycle()
        // While the finger drags a line: saved when it lifts
        var dragged by remember { mutableStateOf<HomeArrangement?>(null) }
        val home = dragged ?: saved
        val fitted = remember(home, width, height) {
            val (lines, perLine) = HomeRoom.of(home.rows, width, height, HomeGap.value)
            home.fit(lines, perLine)
        }
        // A new arrangement fades in; a line dragged only moves the sizes
        AnimatedContent(
            targetState = fitted.evenSizes(),
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.fillMaxSize(),
            label = "homeLayout",
        ) { structure ->
            val drawn = if (structure == fitted.evenSizes()) fitted else structure
            val geometry = HomeGeometry(drawn, width, height, HomeGap.value, mirrored)
            CompositionLocalProvider(LocalSettings provides settings) {
                Box(Modifier.fillMaxSize()) {
                    drawn.places.forEachIndexed { index, place ->
                        val area = geometry.places[index]
                        key(index, place.pages) {
                            Widget(index, place.pages, Modifier.offset(area.x.dp, area.y.dp).size(area.width.dp, area.height.dp))
                        }
                    }
                }
            }
        }
        // Touchscreens resize the widgets by the lines between them; knob-only cars do it on the phone. Not while
        // some widgets are a swipe away for want of room: the lines would not be the driver's own
        if (touch && fitted == home) {
            HomeDividers(
                HomeGeometry(home, width, height, HomeGap.value, mirrored),
                onMove = { divider, share -> dragged = (dragged ?: saved).resized(divider, share) },
            ) {
                dragged?.let { settings.setHome(shape, it) }
                dragged = null
            }
        }
    }
}

/** The home of the last car's kind of screen: what the car shows, for whatever depends on what is on it. */
@Composable
fun currentHome(settings: LauncherSettings): HomeArrangement {
    val shape by settings.homeShape.collectAsStateWithLifecycle()
    val home by settings.home(shape).collectAsStateWithLifecycle()
    return home
}

/** [divider] dragged to [share]. */
internal fun HomeArrangement.resized(divider: HomeDivider, share: Float): HomeArrangement =
    if (divider.place == null) withLineShare(divider.line, share) else withPlaceShare(divider.line, divider.place, share)

/**
 * The lines between the widgets, dragged with a finger (never the knob's: knob-only cars set them on the phone).
 * They get the new share as the finger moves, [onDone] when it lifts. Laid over the places, in the same box.
 */
@Composable
internal fun HomeDividers(geometry: HomeGeometry, onMove: (HomeDivider, Float) -> Unit, onDone: () -> Unit) {
    for (divider in geometry.dividers) {
        key(divider.line, divider.place) { DividerHandle(divider, onMove, onDone) }
    }
}

@Composable
private fun DividerHandle(divider: HomeDivider, onMove: (HomeDivider, Float) -> Unit, onDone: () -> Unit) {
    val colors = LocalCarColors.current
    val travelPx = with(LocalDensity.current) { divider.travel.dp.toPx() }.coerceAtLeast(1f)
    val latest by rememberUpdatedState(divider)
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onDone)
    val area = divider.area
    Box(
        Modifier
            .offset(area.x.dp, area.y.dp)
            .size(area.width.dp, area.height.dp)
            .pointerInput(divider.acrossX, travelPx, divider.reversed) {
                detectDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { change, amount ->
                    change.consume()
                    val now = latest
                    val delta = (if (now.acrossX) amount.x else amount.y) / travelPx * (if (now.reversed) -1 else 1)
                    move(now, (now.share + delta).coerceIn(HomeArrangement.MIN_SHARE, 1f - HomeArrangement.MIN_SHARE))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (divider.acrossX) GripWidth else GripLength, if (divider.acrossX) GripLength else GripWidth)
                .background(colors.text2.copy(alpha = 0.5f), RoundedCornerShape(50)),
        )
    }
}

private val GripLength = 56.dp
private val GripWidth = 6.dp

@Composable
private fun Widget(slot: Int, pages: List<WidgetChoice>, modifier: Modifier) {
    val settings = LocalSettings.current ?: return
    val hold = remember(settings) { { HomeEdit.start(settings) } }
    // Its buttons take the hold through LocalHoldAction; the rest of the card here
    // Each widget is one zone for the knob's nudges; a stack has two, its card and its dots under it
    val stack = pages.size > 1
    Box(modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { hold() }) }.then(if (stack) Modifier else Modifier.carZone())) {
        CompositionLocalProvider(LocalHoldAction provides hold) {
            if (stack) WidgetStack(slot, pages, settings)
            else pages.firstOrNull()?.let { HomeWidget(it, settings, Modifier.fillMaxSize()) }
        }
    }
}

/**
 * A place holding several widgets: one shown, the others a swipe away, or a press of the dots (the knob stops on
 * them). Only the page on screen exists, so a map out of sight does nothing. During a navigation the map comes
 * forward by itself, and the widget that was there comes back when it ends. The page stays as it was left.
 */
@Composable
private fun WidgetStack(slot: Int, pages: List<WidgetChoice>, settings: LauncherSettings) {
    val pager = rememberPagerState(initialPage = StackMemory.page(slot, pages)) { pages.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { pages.getOrNull(pager.settledPage)?.let { StackMemory.shown[slot] = it } }
    val guiding by remember { Navigation.state.map { it != null }.distinctUntilChanged() }.collectAsStateWithLifecycle(Navigation.state.value != null)
    LaunchedEffect(guiding, pages) {
        val map = pages.indexOfFirst { it.type == WidgetType.MAP }
        if (map < 0) return@LaunchedEffect
        // Only when the navigation starts or ends: swiped away during it, the place stays as the driver left it
        if (StackMemory.guiding[slot] == guiding) return@LaunchedEffect
        StackMemory.guiding[slot] = guiding
        if (guiding) {
            if (pager.currentPage != map) {
                StackMemory.beforeNavigation[slot] = pages[pager.currentPage]
                pager.animateScrollToPage(map)
            }
        } else {
            StackMemory.beforeNavigation.remove(slot)?.let { before ->
                pages.indexOf(before).takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) }
            }
        }
    }
    // The dots under the card, in a strip of their own: over it they would cover a widget's own corners
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The card and the dots are two zones: a nudge down from the card lands on the dots, one more goes on below
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().carZone(), pageSpacing = HomeGap, key = { pages[it].encode() }) { page ->
            HomeWidget(pages[page], settings, Modifier.fillMaxSize())
        }
        val next = (pager.currentPage + 1) % pages.size
        val nextName = HomeWidgets.label(LocalContext.current, pages[next])
        PageDots(
            pages.size, pager.currentPage,
            stringResource(R.string.home_stack_next, nextName),
            stringResource(R.string.home_stack_next_short, nextName),
            Modifier.carZone(),
        ) { scope.launch { pager.animateScrollToPage(next) } }
    }
}

/**
 * Which dot is the page shown, in a pill a press on which goes to the next page (round to the first). Under the
 * knob it widens to say which widget comes next ([nextText]).
 */
@Composable
private fun PageDots(count: Int, current: Int, label: String, nextText: String, modifier: Modifier, onNext: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .carClickable(shape, label, onClick = onNext)
            .background(colors.surface2, shape)
            .animateContentSize()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(Modifier.size(10.dp).background(if (i == current) colors.text else colors.text2.copy(alpha = 0.4f), CircleShape))
        }
        if (focused) CarLabel(nextText, 17.sp, colors.text, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** The page each place shows, kept while the home is away; the one a navigation pushed aside. */
private object StackMemory {
    val shown = HashMap<Int, WidgetChoice>()
    val beforeNavigation = HashMap<Int, WidgetChoice>()

    /** Whether a navigation was on when each place last looked: the map comes forward only on a change. */
    val guiding = HashMap<Int, Boolean>()

    fun page(slot: Int, pages: List<WidgetChoice>): Int = pages.indexOf(shown[slot]).coerceAtLeast(0)
}
