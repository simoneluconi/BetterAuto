package com.simoneluconi.betterauto.ui.smarthome

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.smarthome.CommandFeedback
import com.simoneluconi.betterauto.smarthome.DeviceAction
import com.simoneluconi.betterauto.smarthome.DeviceCondition
import com.simoneluconi.betterauto.smarthome.DeviceKind
import com.simoneluconi.betterauto.smarthome.DeviceState
import com.simoneluconi.betterauto.smarthome.SmartDevice
import com.simoneluconi.betterauto.smarthome.SmartHomeProvider
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.vehicle.WarningAmber

val DeviceKind.icon: ImageVector
    get() = when (this) {
        DeviceKind.GATE -> CarIcons.Gate
        DeviceKind.GARAGE_DOOR -> CarIcons.Garage
        DeviceKind.DOOR -> CarIcons.Door
        DeviceKind.COVER -> CarIcons.Layout
        DeviceKind.LOCK -> CarIcons.Lock
        DeviceKind.LIGHT -> CarIcons.Light
        DeviceKind.SWITCH -> CarIcons.Power
        DeviceKind.BUTTON -> CarIcons.Check2
        DeviceKind.SCENE -> CarIcons.Scene
        DeviceKind.SCRIPT -> CarIcons.Play
        DeviceKind.SENSOR -> CarIcons.Thermometer
        DeviceKind.BINARY_SENSOR -> CarIcons.Door
    }

@StringRes
private fun DeviceCondition.label(): Int = when (this) {
    DeviceCondition.OPEN -> R.string.smarthome_state_open
    DeviceCondition.OPENING -> R.string.smarthome_state_opening
    DeviceCondition.CLOSED -> R.string.smarthome_state_closed
    DeviceCondition.CLOSING -> R.string.smarthome_state_closing
    DeviceCondition.LOCKED -> R.string.smarthome_state_locked
    DeviceCondition.UNLOCKED -> R.string.smarthome_state_unlocked
    DeviceCondition.LOCKING -> R.string.smarthome_state_locking
    DeviceCondition.UNLOCKING -> R.string.smarthome_state_unlocking
    DeviceCondition.JAMMED -> R.string.smarthome_state_jammed
    DeviceCondition.ON -> R.string.smarthome_state_on
    DeviceCondition.OFF -> R.string.smarthome_state_off
    DeviceCondition.UNAVAILABLE -> R.string.smarthome_state_unavailable
    DeviceCondition.VALUE, DeviceCondition.UNKNOWN -> R.string.smarthome_state_unknown
}

@StringRes
private fun DeviceAction.label(): Int = when (this) {
    DeviceAction.OPEN -> R.string.smarthome_action_open
    DeviceAction.STOP -> R.string.smarthome_action_stop
    DeviceAction.CLOSE -> R.string.smarthome_action_close
    DeviceAction.LOCK -> R.string.smarthome_action_lock
    DeviceAction.UNLOCK -> R.string.smarthome_action_unlock
    DeviceAction.TOGGLE -> R.string.smarthome_action_toggle
    DeviceAction.TURN_ON -> R.string.smarthome_action_on
    DeviceAction.TURN_OFF -> R.string.smarthome_action_off
    DeviceAction.RUN -> R.string.smarthome_action_run
}

/**
 * One device as a big tile: its icon, name and live state, and its actions. One tap acts: open, stop
 * and close as round buttons for things that open, lock/unlock for locks; lights, switches, scenes and
 * scripts are the tile itself. Sensors only show. [focus] goes to its main control (the knob's landing).
 */
@Composable
fun SmartDeviceTile(
    provider: SmartHomeProvider,
    device: SmartDevice,
    state: DeviceState?,
    feedback: CommandFeedback?,
    onAction: (DeviceAction) -> Unit,
    modifier: Modifier = Modifier,
    container: Color = LocalCarColors.current.surface,
    focus: FocusRequester? = null,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(26.dp)
    val current = state ?: DeviceState.Unknown
    val unavailable = current.condition == DeviceCondition.UNAVAILABLE
    val lit = current.condition == DeviceCondition.ON || current.condition == DeviceCondition.OPEN || current.condition == DeviceCondition.UNLOCKED
    val tileAction = when (device.kind) {
        DeviceKind.LIGHT, DeviceKind.SWITCH -> DeviceAction.TOGGLE.takeIf { it in device.actions }
        DeviceKind.BUTTON, DeviceKind.SCENE, DeviceKind.SCRIPT -> DeviceAction.RUN.takeIf { it in device.actions }
        else -> null
    }
    val background = if (lit && !device.kind.readOnly && device.kind != DeviceKind.LOCK) colors.focus.copy(alpha = 0.18f) else container
    val stateText = stateLine(device, current, feedback)

    val base = modifier.heightIn(min = 168.dp)
    val tileModifier = if (tileAction != null) {
        base.then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .carClickable(shape, "${device.name}, ${stringResource(tileAction.label())}") { onAction(tileAction) }
    } else base
    Column(
        tileModifier
            .clip(shape)
            .background(background)
            .alpha(if (unavailable) 0.55f else 1f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).background(if (lit) colors.focus else colors.surface2.takeIf { container != it } ?: colors.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(device.kind.icon, if (lit) Color.White else colors.text, Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CarLabel(device.name, 21.sp, colors.text, weight = FontWeight.Bold, maxLines = 2, lineHeight = 25.sp)
                if (device.kind != DeviceKind.SENSOR) {
                    CarLabel(stateText.first, 18.sp, stateText.second ?: colors.text2, weight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
        when {
            device.kind == DeviceKind.SENSOR -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CarLabel(current.value ?: "–", 36.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true, shrinkToFit = true)
                device.unit?.let { CarLabel(it, 19.sp, colors.text2, Modifier.padding(bottom = 5.dp), weight = FontWeight.Bold) }
            }
            device.kind.opens -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val ordered = listOf(DeviceAction.OPEN, DeviceAction.STOP, DeviceAction.CLOSE).filter { it in device.actions }
                ordered.forEachIndexed { i, action ->
                    RoundAction(
                        icon = when (action) {
                            DeviceAction.OPEN -> CarIcons.Up
                            DeviceAction.CLOSE -> CarIcons.Down
                            else -> CarIcons.Stop
                        },
                        label = "${device.name}, ${stringResource(action.label())}",
                        container = if (container == colors.surface) colors.surface2 else colors.surface,
                        focus = focus.takeIf { i == 0 },
                    ) { onAction(action) }
                }
            }
            device.kind == DeviceKind.LOCK -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val locked = current.condition == DeviceCondition.LOCKED
                // The useful one first: unlock a locked door, lock an open one
                val ordered = listOf(DeviceAction.UNLOCK, DeviceAction.LOCK, DeviceAction.OPEN).filter { it in device.actions }
                    .sortedBy { if (locked) it != DeviceAction.UNLOCK else it != DeviceAction.LOCK }
                ordered.forEachIndexed { i, action ->
                    PillAction(
                        text = stringResource(action.label()),
                        label = "${device.name}, ${stringResource(action.label())}",
                        icon = if (action == DeviceAction.LOCK) CarIcons.Lock else CarIcons.LockOpen,
                        container = if (container == colors.surface) colors.surface2 else colors.surface,
                        focus = focus.takeIf { i == 0 },
                    ) { onAction(action) }
                }
            }
            tileAction == DeviceAction.RUN -> CarLabel(stringResource(R.string.smarthome_tap_to_run), 17.sp, colors.text2)
        }
    }
}

/** The state under the name, or the last command's outcome while it shows; a color for failures. */
@Composable
private fun stateLine(device: SmartDevice, state: DeviceState, feedback: CommandFeedback?): Pair<String, Color?> {
    when (feedback) {
        CommandFeedback.SENDING -> return stringResource(R.string.smarthome_sending) to null
        CommandFeedback.FAILED -> return stringResource(R.string.smarthome_failed) to WarningAmber
        CommandFeedback.DONE -> if (device.kind == DeviceKind.BUTTON || device.kind == DeviceKind.SCENE || device.kind == DeviceKind.SCRIPT && state.condition != DeviceCondition.ON) {
            return stringResource(R.string.smarthome_done) to null
        }
        null -> Unit
    }
    if (state.condition == DeviceCondition.JAMMED) return stringResource(R.string.smarthome_state_jammed) to WarningAmber
    val text = when {
        device.kind == DeviceKind.SCRIPT && state.condition == DeviceCondition.ON -> stringResource(R.string.smarthome_state_running)
        device.kind == DeviceKind.BUTTON || device.kind == DeviceKind.SCENE || device.kind == DeviceKind.SCRIPT ->
            if (state.condition == DeviceCondition.UNAVAILABLE) stringResource(R.string.smarthome_state_unavailable) else stringResource(kindLabel(device.kind))
        state.position != null && state.condition == DeviceCondition.OPEN && state.position in 1..99 -> stringResource(R.string.smarthome_state_open_percent, state.position)
        else -> stringResource(state.condition.label())
    }
    return text to null
}

@StringRes
private fun kindLabel(kind: DeviceKind): Int = when (kind) {
    DeviceKind.SCENE -> R.string.smarthome_kind_scene
    DeviceKind.SCRIPT -> R.string.smarthome_kind_script
    else -> R.string.smarthome_kind_button
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, container: Color, focus: FocusRequester?, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .carClickable(CircleShape, label, onClick = onClick)
            .background(container, CircleShape)
            .size(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, colors.text, Modifier.size(28.dp))
    }
}

@Composable
private fun PillAction(text: String, label: String, icon: ImageVector, container: Color, focus: FocusRequester?, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .carClickable(shape, label, onClick = onClick)
            .background(container, shape)
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CarIcon(icon, colors.text, Modifier.size(22.dp))
        CarLabel(text, 19.sp, colors.text, weight = FontWeight.Bold)
    }
}

/** The provider's own icon (e.g. the companion app's), or our smart-home icon, for the app bar and the status bar. */
@Composable
fun ProviderIcon(provider: SmartHomeProvider, size: Int, tint: Color = LocalCarColors.current.text) {
    val context = LocalContext.current
    val icon = remember(provider.id) { provider.icon(context) }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape))
    else CarIcon(CarIcons.SmartHome, tint, Modifier.size(size.dp))
}

/** Full width row helper for messages on the smart-home screens. */
@Composable
internal fun SmartHomeMessage(text: String, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CarLabel(text, 22.sp, colors.text2, maxLines = 3, lineHeight = 28.sp)
    }
}
