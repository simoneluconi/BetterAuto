package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.settings.HomeSlots
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import com.simoneluconi.betterauto.ui.launcher.carZone
import com.simoneluconi.betterauto.ui.LocalHoldAction
import com.simoneluconi.betterauto.ui.launcher.LocalContentFocus
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.animateContentSize
import com.simoneluconi.betterauto.ui.CarLabel
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
    val stacks by settings.stacks.collectAsStateWithLifecycle()
    val saved by settings.homeSplit.collectAsStateWithLifecycle()
    val savedSide by settings.homeSplitSide.collectAsStateWithLifecycle()
    val touch by CarState.touchscreen.collectAsStateWithLifecycle()
    val driverSide by settings.driverSide.collectAsStateWithLifecycle()
    val carDriverOnRight by CarState.driverOnRight.collectAsStateWithLifecycle()
    val mirrored = driverSide.onRight(carDriverOnRight)
    // While the finger drags a line: saved when it lifts
    var dragging by remember { mutableStateOf<Float?>(null) }
    var draggingSide by remember { mutableStateOf<Float?>(null) }
    val split = dragging ?: saved
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
        val side = draggingSide ?: sideShare(savedSide, portrait)
        AnimatedContent(
            targetState = Triple(layout, widgets, stacks),
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.fillMaxSize(),
            label = "homeLayout",
        ) { (currentLayout, mains, currentStacks) ->
            CompositionLocalProvider(LocalSettings provides settings) {
                HomePlaces(currentLayout, split, side, portrait, Gap, mirrored) { index, slotModifier ->
                    Widget(index, HomeSlots.pages(mains, currentStacks, index, currentLayout.slots), slotModifier)
                }
            }
        }
        // Touchscreens resize the widgets by their dividing line; knob-only cars do it on the phone
        if (touch) {
            SplitHandles(layout, split, side, portrait, Gap, mirrored, onSplit = { dragging = it }, onSide = { draggingSide = it }) {
                dragging?.let(settings::setHomeSplit)
                draggingSide?.let(settings::setHomeSplitSide)
                dragging = null
                draggingSide = null
            }
        }
    }
}

/** The first small widget's share of the two: as dragged, else 3:2 stacked (landscape), 1:1 side by side (portrait). */
internal fun sideShare(saved: Float?, portrait: Boolean): Float = saved ?: if (portrait) 0.5f else 0.6f

/**
 * The home's places laid out: one big widget, two side by side, or the big one beside two stacked; on portrait
 * screens turned a quarter, the big one on top. [split] is the big one's share, [side] the first small one's
 * share of the two; [mirrored] (right-hand drive) puts the big one on the right. The same for the home, its
 * editor and the phone's preview.
 */
@Composable
internal fun HomePlaces(
    layout: HomeLayout,
    split: Float,
    side: Float,
    portrait: Boolean,
    gap: Dp,
    mirrored: Boolean = false,
    slot: @Composable (Int, Modifier) -> Unit,
) {
    val rest = 1f - split
    if (portrait) {
        when (layout) {
            HomeLayout.SINGLE -> slot(0, Modifier.fillMaxSize())
            HomeLayout.SPLIT -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                slot(0, Modifier.weight(split).fillMaxWidth())
                slot(1, Modifier.weight(rest).fillMaxWidth())
            }
            HomeLayout.TRIO -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                slot(0, Modifier.weight(split).fillMaxWidth())
                Row(Modifier.weight(rest).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    slot(1, Modifier.weight(side).fillMaxHeight())
                    slot(2, Modifier.weight(1f - side).fillMaxHeight())
                }
            }
        }
    } else {
        if (layout == HomeLayout.SINGLE) {
            slot(0, Modifier.fillMaxSize())
            return
        }
        // The big widget on the driver's side: on the right in right-hand drive
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            val big = @Composable { slot(0, Modifier.weight(split).fillMaxHeight()) }
            val others = @Composable {
                if (layout == HomeLayout.SPLIT) {
                    slot(1, Modifier.weight(rest).fillMaxHeight())
                } else {
                    Column(Modifier.weight(rest).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        slot(1, Modifier.weight(side).fillMaxWidth())
                        slot(2, Modifier.weight(1f - side).fillMaxWidth())
                    }
                }
            }
            if (mirrored) {
                others()
                big()
            } else {
                big()
                others()
            }
        }
    }
}

/**
 * The lines between the widgets, dragged with a finger (never the knob's: knob-only cars set them on the phone):
 * the one beside the big widget ([onSplit]), and with three widgets the one between the two small ones
 * ([onSide]). They get the new share as the finger moves, [onDone] when it lifts. Laid over [HomePlaces].
 */
@Composable
internal fun BoxWithConstraintsScope.SplitHandles(
    layout: HomeLayout,
    split: Float,
    side: Float,
    portrait: Boolean,
    gap: Dp,
    mirrored: Boolean = false,
    onSplit: (Float) -> Unit,
    onSide: (Float) -> Unit,
    onDone: () -> Unit,
) {
    if (layout == HomeLayout.SINGLE) return
    // Along the screen's long way: past the big widget, in the middle of the gap
    val length = if (portrait) maxHeight else maxWidth
    val across = if (portrait) maxWidth else maxHeight
    val big = (length - gap) * split
    // Mirrored (landscape only) the big one is on the right: its line counted from the right, dragged the other way
    val flip = mirrored && !portrait
    DividerHandle(
        lineAcrossX = !portrait, at = if (flip) length - big - gap / 2 else big + gap / 2, from = 0.dp, span = across,
        travel = length - gap, share = split, reversed = flip, onMove = onSplit, onDone = onDone,
    )
    if (layout == HomeLayout.TRIO) {
        // The two small ones share what is past the big one, the other way
        DividerHandle(
            lineAcrossX = portrait, at = (across - gap) * side + gap / 2, from = if (flip) 0.dp else big + gap, span = length - big - gap,
            travel = across - gap, share = side, reversed = false, onMove = onSide, onDone = onDone,
        )
    }
}

/**
 * One line: vertical when [lineAcrossX] (it moves along x), else horizontal. [at] is where it is along the way it
 * moves, [from] and [span] where it runs the other way, [travel] the length its [share] is of; [reversed] when
 * the share grows toward the start.
 */
@Composable
private fun DividerHandle(
    lineAcrossX: Boolean,
    at: Dp,
    from: Dp,
    span: Dp,
    travel: Dp,
    share: Float,
    reversed: Boolean,
    onMove: (Float) -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalCarColors.current
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    val latest by rememberUpdatedState(share)
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier
            .offset(x = if (lineAcrossX) at - HandleSize / 2 else from, y = if (lineAcrossX) from else at - HandleSize / 2)
            .size(width = if (lineAcrossX) HandleSize else span, height = if (lineAcrossX) span else HandleSize)
            .pointerInput(lineAcrossX, travelPx, reversed) {
                detectDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { change, amount ->
                    change.consume()
                    val delta = (if (lineAcrossX) amount.x else amount.y) / travelPx * (if (reversed) -1 else 1)
                    move((latest + delta).coerceIn(LauncherSettings.MIN_HOME_SPLIT, LauncherSettings.MAX_HOME_SPLIT))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (lineAcrossX) GripWidth else GripLength, if (lineAcrossX) GripLength else GripWidth)
                .background(colors.text2.copy(alpha = 0.5f), RoundedCornerShape(50)),
        )
    }
}

private val HandleSize = 44.dp
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
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().carZone(), pageSpacing = Gap, key = { pages[it].encode() }) { page ->
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
