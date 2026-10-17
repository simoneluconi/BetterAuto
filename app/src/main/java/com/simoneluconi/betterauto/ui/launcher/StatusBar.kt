package com.simoneluconi.betterauto.ui.launcher

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import com.simoneluconi.betterauto.settings.ClockStyle
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import com.simoneluconi.betterauto.settings.DateStyle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.ui.vehicle.VehicleScreenState
import com.simoneluconi.betterauto.ui.vehicle.WarningAmber
import com.simoneluconi.betterauto.ui.vehicle.hasFaults
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.notifications.rememberVisibleNotifications
import com.simoneluconi.betterauto.ui.phone.CallPill
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.phone.rememberPhoneStatus
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

@Composable
fun StatusBar(settings: LauncherSettings, showLayoutButton: Boolean) {
    val colors = LocalCarColors.current
    val phone = rememberPhoneStatus()
    val call by Calls.current.collectAsStateWithLifecycle()
    val callMinimized by Calls.minimized.collectAsStateWithLifecycle()
    val section by settings.section.collectAsStateWithLifecycle()
    val clockStyle by settings.clockStyle.collectAsStateWithLifecycle()
    val dateStyle by settings.dateStyle.collectAsStateWithLifecycle()
    val showEngine by settings.showEngineLight.collectAsStateWithLifecycle()
    val showBell by settings.showBell.collectAsStateWithLifecycle()
    val showSignal by settings.showSignal.collectAsStateWithLifecycle()
    val showBattery by settings.showBattery.collectAsStateWithLifecycle()
    val showLayout by settings.showLayoutButton.collectAsStateWithLifecycle()
    val voiceEnabled by settings.voiceEnabled.collectAsStateWithLifecycle()
    val showMic by settings.showMicButton.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        clockText(context, phone.at, clockStyle)?.let { CarLabel(it, 32.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true) }
        dateText(phone.at, dateStyle)?.let { CarLabel(it, 20.sp, colors.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
        Spacer(Modifier.weight(1f))

        // The row hangs from the right edge: what comes and goes (a call, the engine light, the layout button on
        // the home screen) sits on the left, so the buttons that are always there never move under the finger
        call?.takeIf { callMinimized }?.let { CallPill(it) }
        if (showEngine) EngineLight(settings)
        if (showLayoutButton && showLayout) {
            val layoutDescription = stringResource(R.string.change_layout)
            Box(
                Modifier
                    .carClickable(CircleShape, layoutDescription) { settings.cycleLayout() }
                    .background(colors.surface, CircleShape)
                    .size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Layout, colors.text, Modifier.size(24.dp))
            }
        }
        if (voiceEnabled && showMic) MicButton()
        // Stays on the notifications screen too, lit like the app bar's current section: tapped again, it goes back
        if (showBell) NotificationsButton(settings, open = section == Section.NOTIFICATIONS)
        Spacer(Modifier.width(6.dp))
        // A steady width, so Wi-Fi coming and going or the battery reaching 100% does not push the buttons
        if (showSignal || showBattery) {
            Row(
                Modifier.widthIn(min = STATUS_MIN_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSignal) {
                    if (phone.onWifi) CarLabel(stringResource(R.string.wifi), 19.sp, colors.text, weight = FontWeight.Bold)
                    phone.signalLevel?.let { SignalBars(it) }
                }
                if (showBattery) phone.batteryPercent?.let { Battery(it, phone.charging) }
            }
        }
    }
}

/** Room for Wi-Fi, the signal bars and a three-digit battery. */
private val STATUS_MIN_WIDTH = 176.dp

private fun clockText(context: Context, at: LocalDateTime, style: ClockStyle): String? = when (style) {
    ClockStyle.HIDDEN -> null
    ClockStyle.H24 -> at.format(Clock24)
    ClockStyle.H12 -> at.format(Clock12)
    ClockStyle.AUTO -> at.format(if (DateFormat.is24HourFormat(context)) Clock24 else Clock12)
}

private fun dateText(at: LocalDateTime, style: DateStyle): String? {
    val pattern = when (style) {
        DateStyle.HIDDEN -> return null
        DateStyle.SHORT -> "EEE d MMM"
        DateStyle.LONG -> "EEEE d MMMM"
        // Day and month in the order and with the separator of the phone's language
        DateStyle.NUMERIC -> DateFormat.getBestDateTimePattern(Locale.getDefault(), "dM")
    }
    return at.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private val Clock24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val Clock12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")

private val LowBattery = Color(0xFFE5484D)

/**
 * The engine warning light, only while the car reports fault codes: the cluster lights its own, this
 * one opens the codes with their meaning.
 */
@Composable
private fun EngineLight(settings: LauncherSettings) {
    val vehicle by VehicleData.snapshot.collectAsStateWithLifecycle()
    if (!hasFaults(vehicle)) return
    val count = vehicle.diagnostics?.value?.size ?: 0
    Box(
        Modifier
            .carClickable(CircleShape, stringResource(R.string.vehicle_faults_open, count)) {
                VehicleScreenState.showDiagnostics.value = true
                settings.setSection(Section.VEHICLE)
            }
            .background(WarningAmber.copy(alpha = 0.22f), CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(CarIcons.Engine, WarningAmber, Modifier.size(28.dp))
    }
}

/** Starts voice commands, and becomes a stop button while one runs; the steering wheel's voice button does the same. */
@Composable
private fun MicButton() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val voice by VoiceAssistant.state.collectAsStateWithLifecycle()
    val active = voice is VoiceState.Listening || voice is VoiceState.Choosing || voice is VoiceState.Calling
    Box(
        Modifier
            .carClickable(CircleShape, stringResource(if (active) R.string.voice_stop else R.string.voice_start)) { VoiceAssistant.toggle(context) }
            .background(if (active) colors.album else colors.surface, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(if (active) CarIcons.Close else CarIcons.Mic, if (active) colors.onAlbum else colors.text, Modifier.size(24.dp))
    }
}

/** Bell with the number of notifications; opens the notifications section, and closes it when [open]. */
@Composable
private fun NotificationsButton(settings: LauncherSettings, open: Boolean) {
    val colors = LocalCarColors.current
    val count = rememberVisibleNotifications(settings).size
    val quiet by settings.quietMode.collectAsStateWithLifecycle()
    val label = if (open) stringResource(R.string.notif_close) else stringResource(R.string.notif_badge, count)
    Box(
        Modifier
            .carClickable(CircleShape, label) { if (open) settings.closeNotifications() else settings.setSection(Section.NOTIFICATIONS) }
            .background(if (open) colors.focus.copy(alpha = 0.2f) else colors.surface, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(if (quiet) CarIcons.BellOff else CarIcons.Bell, colors.text, Modifier.size(24.dp))
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .background(colors.focus, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarLabel(if (count > 9) "9+" else count.toString(), 13.sp, colors.bg, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SignalBars(level: Int) {
    val colors = LocalCarColors.current
    Canvas(Modifier.size(26.dp)) {
        val bar = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (0.3f + 0.7f * (i + 1) / 4f)
            drawRoundRect(
                color = if (i < level) colors.text else colors.text.copy(alpha = 0.3f),
                topLeft = Offset(i * bar * 1.9f, size.height - h),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 3),
            )
        }
    }
}

@Composable
private fun Battery(percent: Int, charging: Boolean) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 30.dp, height = 16.dp)) {
            val stroke = 2.dp.toPx()
            val tip = 3.dp.toPx()
            val body = Size(size.width - tip - stroke, size.height - stroke)
            drawRoundRect(colors.text, Offset(stroke / 2, stroke / 2), body, CornerRadius(4.dp.toPx()), style = Stroke(stroke))
            drawRoundRect(
                color = colors.text,
                topLeft = Offset(size.width - tip, size.height * 0.3f),
                size = Size(tip, size.height * 0.4f),
                cornerRadius = CornerRadius(tip / 2),
            )
            val inset = stroke * 2
            drawRoundRect(
                color = if (percent <= 15 && !charging) LowBattery else colors.text,
                topLeft = Offset(inset, inset),
                size = Size((body.width - inset * 1.5f) * percent / 100f, size.height - inset * 2),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        CarLabel("$percent%", 19.sp, colors.text, weight = FontWeight.Bold, tabularNumbers = true)
    }
}
