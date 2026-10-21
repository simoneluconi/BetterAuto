package com.simoneluconi.betterauto.ui.widgets

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.ClockStyle
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LocalFormatLocale
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Big clock and date, 12 or 24 hours as the status bar's clock setting says (Auto, or hidden there,
 * follows the phone), with the outside temperature beside it whenever the car reports one.
 * Look-only: nothing to press, so the knob passes it by.
 */
@Composable
fun ClockWidget(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val now = rememberMinute()
    val style by settings.clockStyle.collectAsStateWithLifecycle()
    // Only the temperature: the snapshot itself changes with every sensor reading
    val outside by remember { VehicleData.snapshot.map { it.environment?.value?.temperatureC }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(VehicleData.snapshot.value.environment?.value?.temperatureC)
    val units = rememberUnits()
    val locale = LocalFormatLocale.current
    val h24 = is24Hours(style, context, locale)
    val time = now.format(if (h24) Clock24 else Clock12)
    val amPm = if (h24) null else now.format(DateTimeFormatter.ofPattern("a", locale))
    val date = now.format(remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) })
        .replaceFirstChar { it.titlecase(locale) }

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The time takes what the slot allows: tall in the big slot, still readable in a small one
        val timeSize = minOf(maxHeight.value * 0.42f, maxWidth.value * 0.26f).coerceIn(40f, 150f)
        val wide = maxWidth > maxHeight * 1.9f
        val temperature = outside?.let { units.temperature(it, decimals = 0) }

        @Composable
        fun Time() {
            Row(verticalAlignment = Alignment.Bottom) {
                CarLabel(time, timeSize.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true, shrinkToFit = true)
                amPm?.let {
                    CarLabel(it, (timeSize * 0.3f).sp, colors.text2, Modifier.padding(start = 8.dp, bottom = (timeSize * 0.16f).dp), weight = FontWeight.Bold)
                }
            }
        }

        @Composable
        fun Outside(text: String, size: Float) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarIcon(CarIcons.Thermometer, colors.text2, Modifier.size((size * 1.1f).dp))
                CarLabel(text, size.sp, colors.text, weight = FontWeight.Bold, tabularNumbers = true)
            }
        }

        val dateSize = (timeSize * 0.24f).coerceIn(19f, 30f)
        if (wide && temperature != null) {
            Row(Modifier.wrapContentSize(), verticalAlignment = Alignment.CenterVertically) {
                // Weighted: the temperature is measured first and never cut, the time shrinks instead
                Column(Modifier.weight(1f, fill = false)) {
                    Time()
                    CarLabel(date, dateSize.sp, colors.text2, weight = FontWeight.Bold, shrinkToFit = true)
                }
                Spacer(Modifier.width(32.dp))
                // A hairline between the clock and the car's reading
                Box(Modifier.width(1.dp).height((timeSize * 0.9f).dp).background(colors.line))
                Spacer(Modifier.width(32.dp))
                Outside(temperature, (timeSize * 0.36f).coerceIn(22f, 44f))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Time()
                CarLabel(date, dateSize.sp, colors.text2, weight = FontWeight.Bold, shrinkToFit = true)
                if (temperature != null) Outside(temperature, (dateSize * 1.05f).coerceAtLeast(20f))
            }
        }
    }
}

/** The status bar's clock setting; Auto, or the clock hidden there, follows the formats ([AppLocales.is24Hours]). */
internal fun is24Hours(style: ClockStyle, context: Context, formats: Locale) = when (style) {
    ClockStyle.H24 -> true
    ClockStyle.H12 -> false
    else -> AppLocales.is24Hours(context, formats)
}

internal val Clock24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val Clock12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
