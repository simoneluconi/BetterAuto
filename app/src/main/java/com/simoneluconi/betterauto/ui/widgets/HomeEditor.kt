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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.HomeSlots
import com.simoneluconi.betterauto.settings.LauncherSettings
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
 * The home screen's edit mode: the layout on top, each slot with "Change". Entered by holding a widget
 * (finger or knob center), holding the layout button, or from Settings › Home screen. Not saved: a new
 * session starts out of it. Main thread only.
 */
object HomeEdit {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _picking = MutableStateFlow<Int?>(null)
    /** The slot whose widget list is open. */
    val picking: StateFlow<Int?> = _picking

    /** Where the knob was before, for after Done. */
    private var returnFocus: FocusRequester? = null

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

    fun pick(slot: Int?) {
        _picking.value = slot
    }

    /** Back: closes the widget list, else leaves the edit mode; false when there was nothing to close. */
    fun back(): Boolean = when {
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
        _active.value = false
    }

    /** Gives the focus back to where it was before the edit mode, once; false when that control is gone. */
    internal fun restoreFocus(): Boolean = FocusMemory.restore(returnFocus).also { returnFocus = null }
}

private val Gap = 20.dp

/**
 * The edit mode itself. Knob: the first slot's Change is focused; turning goes title row (layout chips,
 * Done) then the slots in reading order; pressing a slot opens its widget list.
 */
@Composable
internal fun HomeEditor(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    // The car moved off while editing: the home comes back as it is
    LaunchedEffect(motion) { if (motion == CarMotion.MOVING) HomeEdit.stop() }
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val layout by settings.layout.collectAsStateWithLifecycle()
    val slots by settings.widgets.collectAsStateWithLifecycle()
    val picking by HomeEdit.picking.collectAsStateWithLifecycle()
    // Reads the installed apps (weather) and the smart-home groups: once per edit
    val offered = remember { HomeWidgets.choices(context) }
    val slotFocus = remember { List(HomeSlots.COUNT) { FocusRequester() } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { slotFocus[0].requestFocus() }
    }
    // The widget list closed (a pick, Close, Back or a tap outside): the knob goes back to its slot
    var lastPicked by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(picking) {
        val closed = lastPicked
        lastPicked = picking
        if (picking == null && closed != null) {
            withFrameNanos { }
            runCatching { slotFocus[closed].requestFocus() }
        }
    }

    BoxWithConstraints(modifier) {
        val portrait = maxWidth < maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EditorHeader(settings, layout)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val slot = @Composable { index: Int, mod: Modifier ->
                    EditSlot(index, slots.getOrNull(index), mod.focusRequester(slotFocus[index]))
                }
                if (portrait) PortraitSlots(layout, slot) else LandscapeSlots(layout, slot)
            }
        }
        picking?.let { index ->
            WidgetPicker(index, settings, offered) { HomeEdit.pick(null) }
        }
    }
}

@Composable
private fun EditorHeader(settings: LauncherSettings, layout: HomeLayout) {
    val colors = LocalCarColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarLabel(stringResource(R.string.settings_edit_home), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, shrinkToFit = true)
        for ((value, label) in listOf(HomeLayout.SINGLE to R.string.layout_one, HomeLayout.SPLIT to R.string.layout_two, HomeLayout.TRIO to R.string.layout_three)) {
            val shape = RoundedCornerShape(50)
            val text = stringResource(label)
            Box(
                Modifier
                    .carClickable(shape, stringResource(R.string.settings_option, stringResource(R.string.settings_home_layout), text)) {
                        if (layout != value) {
                            EventLog.log("Setting home_layout: $layout -> $value (from car)")
                            settings.setLayout(value)
                        }
                    }
                    .background(if (layout == value) colors.focus.copy(alpha = 0.22f) else colors.surface, shape)
                    .height(56.dp)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarLabel(text, 20.sp, colors.text, weight = FontWeight.Bold)
            }
        }
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .contentFocus()
                .carClickable(shape, stringResource(R.string.action_done)) { HomeEdit.stop() }
                .background(colors.focus, shape)
                .height(56.dp)
                .padding(horizontal = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            CarLabel(stringResource(R.string.action_done), 20.sp, colors.bg, weight = FontWeight.ExtraBold)
        }
    }
}

/** One slot as a big card: what it holds, and Change (the whole card). */
@Composable
private fun EditSlot(index: Int, choice: WidgetChoice?, modifier: Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(28.dp)
    val slotName = stringResource(HomeWidgets.slotTitle(index))
    val label = choice?.let { HomeWidgets.label(context, it) }.orEmpty()
    Column(
        modifier
            .carClickable(shape, stringResource(R.string.home_edit_change_slot, slotName, label)) { HomeEdit.pick(index) }
            .background(colors.surface, shape)
            .border(2.dp, colors.line, shape)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        CarLabel(slotName, 18.sp, colors.text2)
        choice?.let { HomeWidgets.kind(it.type) }?.let { CarIcon(it.icon, colors.text, Modifier.size(40.dp)) }
        CarLabel(label, 24.sp, colors.text, weight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, shrinkToFit = true)
        Box(Modifier.background(colors.surface2, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 10.dp)) {
            CarLabel(stringResource(R.string.home_edit_change), 19.sp, colors.text, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PortraitSlots(layout: HomeLayout, slot: @Composable (Int, Modifier) -> Unit) {
    when (layout) {
        HomeLayout.SINGLE -> slot(0, Modifier.fillMaxSize())
        HomeLayout.SPLIT -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            slot(0, Modifier.weight(3f).fillMaxWidth())
            slot(1, Modifier.weight(2f).fillMaxWidth())
        }
        HomeLayout.TRIO -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            slot(0, Modifier.weight(3f).fillMaxWidth())
            Row(Modifier.weight(2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                slot(1, Modifier.weight(1f).fillMaxHeight())
                slot(2, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun LandscapeSlots(layout: HomeLayout, slot: @Composable (Int, Modifier) -> Unit) {
    when (layout) {
        HomeLayout.SINGLE -> slot(0, Modifier.fillMaxSize())
        HomeLayout.SPLIT -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            slot(0, Modifier.weight(3f).fillMaxHeight())
            slot(1, Modifier.weight(2f).fillMaxHeight())
        }
        HomeLayout.TRIO -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            slot(0, Modifier.weight(3f).fillMaxHeight())
            Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                slot(1, Modifier.weight(3f).fillMaxWidth())
                slot(2, Modifier.weight(2f).fillMaxWidth())
            }
        }
    }
}

/**
 * The widgets a slot can hold, over the editor: picking one already shown swaps the two. The knob starts
 * on the current one and stays inside; Close, Back or a tap outside closes it.
 */
@Composable
private fun WidgetPicker(slot: Int, settings: LauncherSettings, offered: List<WidgetChoice>, onClose: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val layout by settings.layout.collectAsStateWithLifecycle()
    val slots by settings.widgets.collectAsStateWithLifecycle()
    val current = slots.getOrNull(slot)
    // What the slot holds stays listed even when this phone can't offer it any more
    val choices = if (current == null || current in offered) offered else offered + current
    val setting = remember { SettingsCatalog.all.filterIsInstance<WidgetSlotsSetting>().first() }
    val focus = remember { FocusRequester() }
    val slotName = stringResource(HomeWidgets.slotTitle(slot))
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(30.dp)
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .heightIn(max = 620.dp)
                .shadow(24.dp, shape)
                .clip(shape)
                .background(colors.surface)
                // A tap on the panel itself is not a tap outside
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
                .focusGroup()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CarLabel(slotName, 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold)
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
                    val elsewhere = slots.take(layout.slots).indexOf(choice).takeIf { it >= 0 && it != slot }
                    val selected = choice == current
                    val rowShape = RoundedCornerShape(22.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (selected || (current !in choices && choice == choices.first())) Modifier.focusRequester(focus) else Modifier)
                            .carClickable(rowShape, label) {
                                setting.pick(settings, slot, choice, SettingsSurface.CAR)
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
                                CarLabel(stringResource(R.string.widget_in_slot, stringResource(HomeWidgets.slotTitle(it))), 17.sp, colors.text2)
                            }
                        }
                        if (selected) CarIcon(CarIcons.Check, colors.focus, Modifier.size(28.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(slot) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}
