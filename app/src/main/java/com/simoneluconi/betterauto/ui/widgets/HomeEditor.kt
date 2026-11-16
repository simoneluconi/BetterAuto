package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.settings.HomeArrangement
import com.simoneluconi.betterauto.settings.HomeDrop
import com.simoneluconi.betterauto.settings.HomePlace
import com.simoneluconi.betterauto.settings.HomeRoom
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.ScreenShape
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.settings.WidgetChoice
import com.simoneluconi.betterauto.settings.WidgetSlotsSetting
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.FocusMemory
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The home screen's edit mode: how many widgets, rows or columns, each place with its widget, moved and resized.
 * Entered by holding a widget (finger or knob center), the layout button, or from Settings › Home screen. Not
 * saved: a new session starts out of it. Main thread only.
 */
object HomeEdit {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _picking = MutableStateFlow<Int?>(null)
    /** The place whose widget list is open. */
    val picking: StateFlow<Int?> = _picking

    private val _moving = MutableStateFlow<Int?>(null)
    /** The place being moved: the editor shows where it can go. */
    val moving: StateFlow<Int?> = _moving

    /** Where the knob was before, for after Done. */
    private var returnFocus: FocusRequester? = null

    /** Where the first knob move lands while the editor is up (nothing focused yet): set by the editor. */
    internal var entry: FocusRequester? = null

    fun start(settings: LauncherSettings) {
        if (_active.value) return
        // Like the settings: only while the car stands
        if (CarMotionState.moving) {
            EventLog.log("Home: edit mode not while driving")
            return
        }
        returnFocus = FocusMemory.last
        EventLog.log("Home: edit mode")
        settings.setSection(Section.HOME)
        _active.value = true
    }

    fun pick(place: Int?) {
        _picking.value = place
    }

    fun move(place: Int?) {
        _picking.value = null
        _moving.value = place
    }

    /** Back: stops a move, closes the widget list, else leaves the edit mode; false when there was nothing to close. */
    fun back(): Boolean = when {
        _moving.value != null -> {
            _moving.value = null
            true
        }
        _picking.value != null -> {
            _picking.value = null
            true
        }
        _active.value -> {
            stop()
            true
        }
        else -> false
    }

    fun stop() {
        if (!_active.value) return
        EventLog.log("Home: edit mode done")
        _picking.value = null
        _moving.value = null
        _active.value = false
    }

    /** Gives the focus back to where it was before the edit mode, once; false when that control is gone. */
    internal fun restoreFocus(): Boolean = FocusMemory.restore(returnFocus).also { returnFocus = null }
}

/** The home setting, for its logged changes. */
internal val homeSetting: WidgetSlotsSetting get() = SettingsCatalog.all.filterIsInstance<WidgetSlotsSetting>().first()

/** A widget for one more place: one not on the home yet, else one held a swipe away. */
internal fun nextWidget(home: HomeArrangement, offered: List<WidgetChoice>): WidgetChoice? =
    offered.firstOrNull { it !in home.widgets } ?: offered.firstOrNull { it !in home.mains }

/**
 * The edit mode itself, for the home of this kind of screen ([shape]). Knob: the first place is focused; turning
 * goes through the header (fewer, more, rows or columns, Done) and the places in reading order; pressing a place
 * opens its widget list (with Move and Remove), holding it moves it. Moving, the knob turns through where it can
 * go and a press drops it there; a finger holds a place and drags it, or taps where it goes.
 */
@Composable
internal fun HomeEditor(settings: LauncherSettings, shape: ScreenShape, mirrored: Boolean, modifier: Modifier = Modifier) {
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    // The car moved off while editing: the home comes back as it is
    LaunchedEffect(motion) { if (motion == CarMotion.MOVING) HomeEdit.stop() }
    val context = LocalContext.current
    val touch by CarState.touchscreen.collectAsStateWithLifecycle()
    val home by settings.home(shape).collectAsStateWithLifecycle()
    val area by settings.homeArea(shape).collectAsStateWithLifecycle()
    val picking by HomeEdit.picking.collectAsStateWithLifecycle()
    val moving by HomeEdit.moving.collectAsStateWithLifecycle()
    // Reads the installed apps (weather) and the smart-home groups: once per edit
    val offered = remember { HomeWidgets.choices(context) }
    val placeFocus = remember(home.count) { List(home.count) { FocusRequester() } }
    DisposableEffect(placeFocus) {
        HomeEdit.entry = placeFocus.first()
        onDispose { HomeEdit.entry = null }
    }
    // The place the knob goes back to once a list closes or a move ends
    var refocus by remember { mutableStateOf<Int?>(0) }
    LaunchedEffect(refocus, placeFocus) {
        val index = refocus ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { placeFocus.getOrNull(index)?.requestFocus() }
        refocus = null
    }
    var lastPicked by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(picking) {
        if (picking == null && lastPicked != null && moving == null) refocus = lastPicked
        lastPicked = picking
    }
    var dragged by remember { mutableStateOf<HomeArrangement?>(null) }

    fun change(what: String, transform: (HomeArrangement) -> HomeArrangement) =
        homeSetting.change(settings, shape, what, SettingsSurface.CAR, transform)

    fun drop(index: Int, target: HomeDrop) {
        val pages = home.place(index)?.pages
        change("place ${index + 1} moved") { it.move(index, target) }
        HomeEdit.move(null)
        refocus = settings.home(shape).value.places.indexOfFirst { it.pages == pages }.takeIf { it >= 0 } ?: 0
    }

    Column(
        modifier
            // Moving, the knob stays on where the place can go and Cancel
            .focusProperties { onExit = { if (moving != null && requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val capacity = HomeRoom.capacity(area.first, area.second, HomeGap.value)
        EditorHeader(
            home, moving?.let { home.place(it) }, canAdd = home.count < capacity && nextWidget(home, offered) != null,
            onFewer = {
                change("fewer widgets") { it.remove(it.count - 1) }
                refocus = null
            },
            onMore = { nextWidget(home, offered)?.let { choice -> change("more widgets") { it.add(choice) } } },
            onRows = { rows -> change(if (rows) "rows" else "columns") { it.withRows(rows) } },
            onCancel = {
                val index = moving
                HomeEdit.move(null)
                refocus = index
            },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = maxWidth.value
            val height = maxHeight.value
            val shown = dragged ?: home
            val geometry = editGeometry(shown, width, height, mirrored, moving != null)
            val drops = remember(shown, moving, width, height) { moving?.let { geometry.drops(it) }.orEmpty() }
            // A finger that held a place and drags it: where it is, in dp, and the drop under it
            var finger by remember { mutableStateOf<Offset?>(null) }
            val hover = finger?.let { geometry.dropAt(drops, it.x, it.y) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Seen before the places' own handling, which takes the finger once held
                        awaitPointerEventScope {
                            var down: Offset? = null
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                val at = Offset(change.position.x / density, change.position.y / density)
                                if (change.pressed) {
                                    val start = down ?: at.also { down = it }
                                    if (HomeEdit.moving.value != null && (at - start).getDistance() > DRAG_START) dragging = true
                                    if (dragging) finger = at
                                } else if (down != null) {
                                    val index = HomeEdit.moving.value
                                    if (dragging && index != null) {
                                        val now = editGeometry(settings.home(shape).value, width, height, mirrored, moving = true)
                                        val target = now.dropAt(now.drops(index), at.x, at.y)
                                        if (target != null) drop(index, target) else HomeEdit.move(null)
                                    }
                                    down = null
                                    dragging = false
                                    finger = null
                                }
                            }
                        }
                    },
            ) {
                shown.places.forEachIndexed { index, place ->
                    val box = geometry.places[index]
                    key(index) {
                        EditPlace(
                            index, place, moving, compact = box.height < COMPACT_HEIGHT,
                            Modifier.offset(box.x.dp, box.y.dp).size(box.width.dp, box.height.dp).focusRequester(placeFocus[index]),
                        )
                    }
                }
                moving?.let { index ->
                    DropTargets(index, home, drops, hover) { drop(index, it) }
                }
                if (touch && moving == null) {
                    HomeDividers(geometry, onMove = { divider, share -> dragged = (dragged ?: home).resized(divider, share) }) {
                        dragged?.let { result -> change("sizes") { result } }
                        dragged = null
                    }
                }
            }
        }
    }
    picking?.let { index ->
        WidgetPicker(index, shape, settings, offered) { HomeEdit.pick(null) }
    }
}

/** Below this height (dp) a place's card leaves out its "Place n" line, so Change keeps its size. */
private const val COMPACT_HEIGHT = 320f

/** How far a held finger goes before it drags the place, in dp. */
private const val DRAG_START = 12f

/** The editor's places; [moving] makes room for the drop bars between and around them. */
private fun editGeometry(home: HomeArrangement, width: Float, height: Float, mirrored: Boolean, moving: Boolean) =
    if (moving) HomeGeometry(home, width, height, HomeGeometry.MOVE_GAP, mirrored, HomeGeometry.MOVE_INSET)
    else HomeGeometry(home, width, height, HomeGap.value, mirrored)

/**
 * Fewer and more widgets, rows or columns, Done; moving a place, what to do and Cancel. On a narrow screen the
 * title gets a row of its own.
 */
@Composable
private fun EditorHeader(
    home: HomeArrangement,
    moving: HomePlace?,
    canAdd: Boolean,
    onFewer: () -> Unit,
    onMore: () -> Unit,
    onRows: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    if (moving != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarLabel(
                stringResource(R.string.home_moving_hint, HomeWidgets.label(context, moving.main)), 21.sp, colors.text, Modifier.weight(1f),
                weight = FontWeight.Bold, maxLines = 2, shrinkToFit = true,
            )
            Pill(stringResource(R.string.home_move_cancel), primary = true, onClick = onCancel)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 1000.dp
        val title = @Composable { mod: Modifier ->
            CarLabel(stringResource(R.string.settings_edit_home), 26.sp, colors.text, mod, weight = FontWeight.ExtraBold, shrinkToFit = true)
        }
        val controls = @Composable {
            RoundButton(CarIcons.Minus, stringResource(R.string.home_fewer_widgets), enabled = home.count > 1, onClick = onFewer)
            CarLabel(
                pluralStringResource(R.plurals.home_widget_count, home.count, home.count), 20.sp, colors.text,
                weight = FontWeight.Bold, tabularNumbers = true, maxLines = 1,
            )
            RoundButton(CarIcons.Plus, stringResource(R.string.home_more_widgets), enabled = canAdd, onClick = onMore)
            Box(Modifier.size(8.dp))
            Chip(stringResource(R.string.home_rows), selected = home.rows) { onRows(true) }
            Chip(stringResource(R.string.home_columns), selected = !home.rows) { onRows(false) }
        }
        val done = @Composable { Pill(stringResource(R.string.action_done), primary = true, Modifier.contentFocus()) { HomeEdit.stop() } }
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    title(Modifier.weight(1f))
                    done()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { controls() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                title(Modifier.weight(1f))
                controls()
                Box(Modifier.size(8.dp))
                done()
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, label) { if (enabled) onClick() }
            .background(colors.surface, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (enabled) colors.text else colors.text2.copy(alpha = 0.4f), Modifier.size(24.dp))
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .carClickable(shape, text, onClick = onClick)
            .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface, shape)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(text, 19.sp, colors.text, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .carClickable(shape, text, onClick = onClick)
            .background(if (primary) colors.focus else colors.surface2, shape)
            .height(56.dp)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(text, 20.sp, if (primary) colors.bg else colors.text, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/**
 * One place as a big card: what it holds, and Change (the whole card). Held, it moves. While a place moves the
 * cards are only a picture (the drops take the knob), the moving one marked.
 */
@Composable
private fun EditPlace(index: Int, place: HomePlace, moving: Int?, compact: Boolean, modifier: Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(28.dp)
    val placeName = HomeWidgets.placeTitle(context, index)
    val label = HomeWidgets.label(context, place.main)
    val more = place.pages.size - 1
    val content = @Composable {
        if (!compact) CarLabel(placeName, 18.sp, colors.text2, maxLines = 1)
        HomeWidgets.kind(place.main.type)?.let { CarIcon(it.icon, colors.text, Modifier.size(if (compact) 32.dp else 40.dp)) }
        CarLabel(label, 24.sp, colors.text, weight = FontWeight.Bold, maxLines = if (compact) 1 else 2, textAlign = TextAlign.Center, shrinkToFit = true)
        if (more > 0) CarLabel(stringResource(R.string.home_stack_more, more), 17.sp, colors.text2, weight = FontWeight.Bold)
        if (moving == null) {
            Box(Modifier.background(colors.surface2, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 10.dp)) {
                CarLabel(stringResource(R.string.home_edit_change), 19.sp, colors.text, weight = FontWeight.Bold)
            }
        }
    }
    val card = Modifier
        .background(colors.surface, shape)
        .border(if (moving == index) 4.dp else 2.dp, if (moving == index) colors.focus else colors.line, shape)
        .padding(18.dp)
    Column(
        if (moving == null) {
            modifier.carClickable(shape, stringResource(R.string.home_edit_change_slot, placeName, label), onLongClick = { HomeEdit.move(index) }) {
                HomeEdit.pick(index)
            }.then(card)
        } else {
            modifier.alpha(if (moving == index) 1f else 0.55f).then(card)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) { content() }
}

/**
 * Where place [index] can go, over the cards: each other place (swap), a bar in the gaps of a line with room
 * (beside), a bar across the gaps between lines (a line of its own). The knob starts on the first; [hover] is the
 * one under a dragging finger.
 */
@Composable
private fun DropTargets(index: Int, home: HomeArrangement, drops: List<Pair<HomeDrop, Area>>, hover: HomeDrop?, onDrop: (HomeDrop) -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val first = remember(index) { FocusRequester() }
    DisposableEffect(first) {
        val before = HomeEdit.entry
        HomeEdit.entry = first
        onDispose { if (HomeEdit.entry === first) HomeEdit.entry = before }
    }
    for ((i, entry) in drops.withIndex()) {
        val (drop, box) = entry
        val lit = drop == hover
        val modifier = Modifier
            .offset(box.x.dp, box.y.dp)
            .size(box.width.dp, box.height.dp)
            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
        when (drop) {
            is HomeDrop.Swap -> {
                val shape = RoundedCornerShape(28.dp)
                val other = home.place(drop.place)?.let { HomeWidgets.label(context, it.main) }.orEmpty()
                // The card itself, tinted: the hint above says a widget dropped on another swaps with it
                Box(
                    modifier
                        .carClickable(shape, stringResource(R.string.home_drop_swap, other)) { onDrop(drop) }
                        .background(colors.focus.copy(alpha = if (lit) 0.35f else 0.1f), shape),
                )
            }
            is HomeDrop.Beside, is HomeDrop.NewLine -> {
                val shape = RoundedCornerShape(50)
                val label = when (drop) {
                    is HomeDrop.NewLine -> stringResource(if (home.rows) R.string.home_drop_new_row else R.string.home_drop_new_column)
                    else -> stringResource(R.string.home_drop_beside)
                }
                Box(
                    modifier
                        .carClickable(shape, label) { onDrop(drop) }
                        .background(if (lit) colors.focus else colors.surface, shape)
                        .border(2.dp, colors.focus, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CarIcon(CarIcons.Plus, if (lit) colors.bg else colors.focus, Modifier.size(24.dp))
                        // Only along a bar long enough to hold it
                        if (drop is HomeDrop.NewLine && box.width > box.height && box.width > 260f) {
                            CarLabel(label, 18.sp, if (lit) colors.bg else colors.text, weight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(index) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/**
 * What a place can hold, over the editor: Move and Remove, then the widgets (picking one already shown swaps the
 * two). The knob starts on the current one and stays inside; Close, Back or a tap outside closes it.
 */
@Composable
private fun WidgetPicker(index: Int, shape: ScreenShape, settings: LauncherSettings, offered: List<WidgetChoice>, onClose: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val home by settings.home(shape).collectAsStateWithLifecycle()
    val current = home.place(index)?.main
    // What the place holds stays listed even when this phone can't offer it any more
    val choices = if (current == null || current in offered) offered else offered + current
    val focus = remember { FocusRequester() }
    val placeName = HomeWidgets.placeTitle(context, index)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        val panelShape = RoundedCornerShape(30.dp)
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .heightIn(max = 620.dp)
                .shadow(24.dp, panelShape)
                .clip(panelShape)
                .background(colors.surface)
                // A tap on the panel itself is not a tap outside
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
                .focusGroup()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CarLabel(placeName, 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, maxLines = 1, shrinkToFit = true)
                if (home.count > 1) {
                    Pill(stringResource(R.string.home_move), primary = false) { HomeEdit.move(index) }
                    Pill(stringResource(R.string.home_remove), primary = false) {
                        homeSetting.change(settings, shape, "place ${index + 1} removed", SettingsSurface.CAR) { it.remove(index) }
                        onClose()
                    }
                }
                Box(
                    Modifier
                        .carClickable(CircleShape, stringResource(R.string.smarthome_arrival_close), onClick = onClose)
                        .background(colors.surface2, CircleShape)
                        .size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarIcon(CarIcons.Close, colors.text, Modifier.size(24.dp))
                }
            }
            Column(Modifier.verticalScrollWithBar(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (choice in choices) {
                    val label = HomeWidgets.label(context, choice)
                    val elsewhere = home.mains.indexOf(choice).takeIf { it >= 0 && it != index }
                    val selected = choice == current
                    val rowShape = RoundedCornerShape(22.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (selected || (current !in choices && choice == choices.first())) Modifier.focusRequester(focus) else Modifier)
                            .carClickable(rowShape, label) {
                                homeSetting.pick(settings, shape, index, choice, SettingsSurface.CAR)
                                onClose()
                            }
                            .background(if (selected) colors.focus.copy(alpha = 0.18f) else colors.surface2, rowShape)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        HomeWidgets.kind(choice.type)?.let { CarIcon(it.icon, colors.text, Modifier.size(30.dp)) }
                        Column(Modifier.weight(1f)) {
                            CarLabel(label, 21.sp, colors.text, weight = FontWeight.Bold)
                            elsewhere?.let {
                                CarLabel(stringResource(R.string.widget_in_place, it + 1), 17.sp, colors.text2)
                            }
                        }
                        if (selected) CarIcon(CarIcons.Check, colors.focus, Modifier.size(28.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(index) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}
