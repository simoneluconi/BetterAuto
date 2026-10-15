package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.foundation.Canvas
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

    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarLabel(phone.time, 32.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true)
        CarLabel(phone.date, 20.sp, colors.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Spacer(Modifier.weight(1f))

        if (showLayoutButton) {
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
        Spacer(Modifier.width(6.dp))
        if (phone.onWifi) CarLabel(stringResource(R.string.wifi), 19.sp, colors.text, weight = FontWeight.Bold)
        phone.signalLevel?.let { SignalBars(it) }
        phone.batteryPercent?.let { Battery(it, phone.charging) }
    }
}

private val LowBattery = Color(0xFFE5484D)

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
