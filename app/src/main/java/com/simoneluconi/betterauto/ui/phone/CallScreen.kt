package com.simoneluconi.betterauto.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.phone.CallInfo
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.delay

/**
 * Full-screen call: who is calling, the timer and big buttons. The main button (answer, or hang up)
 * takes the focus so the knob press acts on it. Back puts the screen away; the status bar keeps a pill.
 */
@Composable
fun CallScreen(call: CallInfo, primaryFocus: FocusRequester, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    var keypad by rememberSaveable(call.id) { mutableStateOf(false) }
    val ringing = call.phase == CallPhase.RINGING

    BoxWithConstraints(
        modifier
            .background(colors.bg)
            .padding(32.dp)
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    if (keypad) keypad = false else Calls.minimized.value = true
                }
                true
            },
    ) {
        val wide = maxWidth > maxHeight
        val info = @Composable { mod: Modifier -> CallerInfo(call, mod) }
        val buttons = @Composable { CallButtons(call, primaryFocus, keypad) { keypad = !keypad } }
        if (keypad && !ringing) {
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                        info(Modifier.weight(1f))
                        buttons()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { Keypad(onCall = null, onDigit = Calls::dtmf) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f)) { Keypad(onCall = null, onDigit = Calls::dtmf) }
                    buttons()
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                MinimizeButton(Modifier.align(Alignment.Start))
                info(Modifier.weight(1f))
                buttons()
            }
        }
    }
    LaunchedEffect(call.id, call.phase) { runCatching { primaryFocus.requestFocus() } }
}

@Composable
private fun CallerInfo(call: CallInfo, modifier: Modifier) {
    val colors = LocalCarColors.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val avatar = (maxHeight * 0.5f).coerceIn(96.dp, 200.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(call.name, call.photo, avatar)
            CarLabel(
                call.name ?: call.number ?: stringResource(R.string.phone_unknown),
                48.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 2, textAlign = TextAlign.Center,
            )
            call.company?.let { CarLabel(it, 24.sp, colors.text2, weight = FontWeight.Bold) }
            if (call.name != null && call.number != null) {
                CarLabel(listOfNotNull(call.numberLabel, call.number).joinToString(" · "), 24.sp, colors.text2)
            }
            CallStatus(call)
        }
    }
}

@Composable
private fun CallStatus(call: CallInfo) {
    val colors = LocalCarColors.current
    val text = when (call.phase) {
        CallPhase.RINGING -> stringResource(R.string.call_incoming)
        CallPhase.DIALING -> stringResource(R.string.call_dialing)
        CallPhase.HOLDING -> stringResource(R.string.call_on_hold)
        CallPhase.ENDED -> stringResource(R.string.call_ended)
        CallPhase.ACTIVE -> callTimer(call.connectedAt)
    }
    val others = if (call.others > 0) "  ·  " + stringResource(R.string.call_others, call.others) else ""
    CarLabel(text + others, 26.sp, if (call.phase == CallPhase.ACTIVE) colors.text else colors.text2, weight = FontWeight.Bold, tabularNumbers = true)
}

/** mm:ss (h:mm:ss after an hour), ticking every second. */
@Composable
internal fun callTimer(connectedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    if (connectedAt <= 0) return "0:00"
    val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun CallButtons(call: CallInfo, primaryFocus: FocusRequester, keypad: Boolean, onKeypad: () -> Unit) {
    val colors = LocalCarColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Top) {
        when (call.phase) {
            CallPhase.RINGING -> {
                RoundButton(CarIcons.CallEnd, stringResource(R.string.call_decline), CallRed, Color.White, 120.dp, showLabel = true) { Calls.end() }
                Box(Modifier.focusRequester(primaryFocus)) {
                    RoundButton(CarIcons.CallAnswer, stringResource(R.string.call_answer), CallGreen, Color.White, 120.dp, showLabel = true) { Calls.answer() }
                }
            }
            CallPhase.ENDED -> Unit
            else -> {
                val mute = stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute)
                RoundButton(
                    if (call.muted) CarIcons.MicOff else CarIcons.Mic, mute,
                    if (call.muted) colors.text else colors.surface2, if (call.muted) colors.bg else colors.text, 96.dp, showLabel = true,
                ) { Calls.toggleMute() }
                RoundButton(
                    CarIcons.Dialpad, stringResource(R.string.call_keypad),
                    if (keypad) colors.text else colors.surface2, if (keypad) colors.bg else colors.text, 96.dp, showLabel = true,
                    onClick = onKeypad,
                )
                if (call.canHold) {
                    val holding = call.phase == CallPhase.HOLDING
                    RoundButton(
                        if (holding) CarIcons.Play else CarIcons.Pause,
                        stringResource(if (holding) R.string.call_resume else R.string.call_hold),
                        if (holding) colors.text else colors.surface2, if (holding) colors.bg else colors.text, 96.dp, showLabel = true,
                    ) { Calls.toggleHold() }
                }
                Box(Modifier.focusRequester(primaryFocus)) {
                    RoundButton(CarIcons.CallEnd, stringResource(R.string.call_hang_up), CallRed, Color.White, 96.dp, showLabel = true) { Calls.end() }
                }
            }
        }
    }
}

@Composable
private fun MinimizeButton(modifier: Modifier) {
    val colors = LocalCarColors.current
    Box(
        modifier
            .carClickable(CircleShape, stringResource(R.string.call_minimize)) { Calls.minimized.value = true }
            .background(colors.surface, CircleShape)
            .size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(CarIcons.Back, colors.text, Modifier.size(26.dp))
    }
}

/** Status bar pill for a call whose screen was put away: shows who and how long, brings the screen back. */
@Composable
fun CallPill(call: CallInfo) {
    val text = (call.name ?: call.number ?: "") + "  " + when (call.phase) {
        CallPhase.ACTIVE -> callTimer(call.connectedAt)
        CallPhase.RINGING -> stringResource(R.string.call_incoming)
        CallPhase.HOLDING -> stringResource(R.string.call_on_hold)
        CallPhase.DIALING -> stringResource(R.string.call_dialing)
        CallPhase.ENDED -> stringResource(R.string.call_ended)
    }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .carClickable(shape, stringResource(R.string.call_show, text)) { Calls.minimized.value = false }
            .background(CallGreen, shape)
            .padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.CallAnswer, Color.White, Modifier.size(18.dp))
        }
        CarLabel(text, 19.sp, Color.White, weight = FontWeight.Bold, tabularNumbers = true)
    }
}
