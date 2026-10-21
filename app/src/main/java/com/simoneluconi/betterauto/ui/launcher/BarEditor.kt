package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.RailEntry
import com.simoneluconi.betterauto.settings.RailItem
import com.simoneluconi.betterauto.settings.RailLayoutSetting
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.FocusMemory
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app bar's editor, next to the bar: opened by holding one of its section buttons (finger or knob
 * center) or from Settings › Display. The bar changes live behind it. Main thread only.
 */
object BarEditor {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** The button that was held: the knob starts on its row. */
    var held: RailItem? = null
        private set

    private var returnFocus: FocusRequester? = null

    fun open(item: RailItem?) {
        if (_open.value) return
        // Like the settings: only while the car stands
        if (CarMotionState.moving) {
            EventLog.log("App bar: arranging not while driving")
            return
        }
        held = item
        returnFocus = FocusMemory.last
        EventLog.log("App bar: arranging")
        _open.value = true
    }

    fun close() {
        if (!_open.value) return
        _open.value = false
    }

    /** Back where the knob was before; false when that control is gone. */
    internal fun restoreFocus(): Boolean = FocusMemory.restore(returnFocus).also { returnFocus = null }
}

/** Behind the editor: a tap outside closes it. */
@Composable
fun BarEditorScrim(modifier: Modifier = Modifier) {
    val open by BarEditor.open.collectAsStateWithLifecycle()
    if (!open) return
    Box(modifier.background(Color.Black.copy(alpha = 0.25f)).pointerInput(Unit) { detectTapGestures { BarEditor.close() } })
}

/**
 * One row per button of the bar (hidden ones greyed, so they can come back): where it goes and its
 * place in the order. The knob starts on the held button's place and stays inside; turning reads the
 * header (Default, Done), then row by row its places and arrows. Done, Back or a tap outside closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarEditorPanel(settings: LauncherSettings, maxHeight: Dp, modifier: Modifier = Modifier) {
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    LaunchedEffect(motion) { if (motion == CarMotion.MOVING) BarEditor.close() }
    val open by BarEditor.open.collectAsStateWithLifecycle()
    if (!open) return
    val colors = LocalCarColors.current
    val layout by settings.railLayout.collectAsStateWithLifecycle()
    val setting = remember { SettingsCatalog.all.filterIsInstance<RailLayoutSetting>().first() }
    val start = remember { FocusRequester() }
    val startItem = remember { BarEditor.held ?: layout.first().item }
    var refused by remember { mutableStateOf<Int?>(null) }
    val shape = RoundedCornerShape(30.dp)
    BoxWithConstraints(modifier) {
        val wide = this.maxWidth >= 900.dp
        Column(
            Modifier
                .widthIn(max = 1000.dp)
                .heightIn(max = maxHeight)
                .shadow(24.dp, shape)
                .clip(shape)
                .background(colors.surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
                .focusGroup()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarLabel(stringResource(R.string.settings_arrange_bar), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold)
                Pill(stringResource(R.string.settings_default), primary = false) {
                    refused = null
                    setting.reset(settings, SettingsSurface.CAR)
                }
                Pill(stringResource(R.string.action_done), primary = true) { BarEditor.close() }
            }
            CarLabel(stringResource(R.string.rail_settings_note), 17.sp, colors.text2)
            // Why the last place picked was refused (hiding the last way to the notifications)
            refused?.let { CarLabel(stringResource(it), 17.sp, colors.focus, weight = FontWeight.Bold, maxLines = 2) }
            Column(Modifier.verticalScrollWithBar(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((index, entry) in layout.withIndex()) {
                    // Keyed: a moved row takes the knob's focus along
                    key(entry.item) {
                        EditorRow(entry, index, layout.size, wide, setting, settings, if (entry.item == startItem) start else null) { refused = it }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { start.requestFocus() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorRow(
    entry: RailEntry,
    index: Int,
    count: Int,
    wide: Boolean,
    setting: RailLayoutSetting,
    settings: LauncherSettings,
    start: FocusRequester?,
    onPlaced: (refused: Int?) -> Unit,
) {
    val colors = LocalCarColors.current
    val name = stringResource(entry.item.label)
    val hidden = entry.slot == RailSlot.HIDDEN
    val places = @Composable {
        for (slot in RailSlot.entries) {
            val chipShape = RoundedCornerShape(50)
            val selected = entry.slot == slot
            val label = stringResource(slot.label)
            Box(
                Modifier
                    .then(if (selected && start != null) Modifier.focusRequester(start) else Modifier)
                    .carClickable(chipShape, stringResource(R.string.settings_option, name, label)) {
                        onPlaced(setting.place(settings, entry.item, slot, SettingsSurface.CAR))
                    }
                    .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface, chipShape)
                    .height(52.dp)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarLabel(label, 19.sp, colors.text, weight = FontWeight.Bold)
            }
        }
    }
    val arrows = @Composable {
        MoveButton(CarIcons.Back, stringResource(R.string.rail_move_up, name), enabled = index > 0, rotate = 90f) {
            setting.move(settings, entry.item, -1, SettingsSurface.CAR)
        }
        MoveButton(CarIcons.Back, stringResource(R.string.rail_move_down, name), enabled = index < count - 1, rotate = -90f) {
            setting.move(settings, entry.item, 1, SettingsSurface.CAR)
        }
    }
    val title = @Composable { mod: Modifier ->
        Row(mod.alpha(if (hidden) 0.45f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarIcon(entry.item.icon, colors.text, Modifier.size(28.dp))
            CarLabel(name, 21.sp, colors.text, weight = FontWeight.Bold)
        }
    }
    val rowModifier = Modifier.fillMaxWidth().background(colors.surface2, RoundedCornerShape(22.dp)).padding(12.dp)
    if (wide) {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            title(Modifier.width(190.dp))
            places()
            Box(Modifier.weight(1f))
            arrows()
        }
    } else {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                title(Modifier.weight(1f))
                arrows()
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { places() }
        }
    }
}

@Composable
private fun Pill(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .carClickable(shape, label, onClick = onClick)
            .background(if (primary) colors.focus else colors.surface2, shape)
            .height(56.dp)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(label, 20.sp, if (primary) colors.bg else colors.text, weight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun MoveButton(icon: ImageVector, label: String, enabled: Boolean, rotate: Float, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, label) { if (enabled) onClick() }
            .background(colors.surface, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (enabled) colors.text else colors.text2.copy(alpha = 0.4f), Modifier.size(26.dp).rotate(rotate))
    }
}
