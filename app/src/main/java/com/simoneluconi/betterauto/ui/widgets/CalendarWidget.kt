package com.simoneluconi.betterauto.ui.widgets

import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.home.Agenda
import com.simoneluconi.betterauto.home.AgendaDay
import com.simoneluconi.betterauto.home.AgendaEvent
import com.simoneluconi.betterauto.home.CalendarEvents
import com.simoneluconi.betterauto.home.CalendarInstance
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LocalFormatLocale
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Height of one event row (two lines and the gap), to show as many as the slot holds. */
private val ROW_HEIGHT = 74.dp
private val DAY_LABEL_HEIGHT = 30.dp

/**
 * What's next today and tomorrow, from every calendar on the phone. Look-only (there is no navigation in
 * the car yet, so a place is shown, not opened): the knob passes it by. Read again when a calendar
 * changes and as events end, minute by minute, without polling the provider.
 */
@Composable
fun CalendarWidget(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val now = rememberMinute()
    val demo by CalendarEvents.demo.collectAsStateWithLifecycle()
    val allowed = CalendarEvents.canRead(context)
    val version = rememberContentVersion(CalendarContract.CONTENT_URI, enabled = allowed && demo == null)
    val hidden by settings.hiddenCalendars.collectAsStateWithLifecycle()
    var instances by remember { mutableStateOf<List<CalendarInstance>?>(null) }
    // Read again on a change (debounced: a sync changes many rows at once) and when the day changes
    val day = now.toLocalDate()
    LaunchedEffect(version, day, allowed, hidden) {
        if (version > 0) delay(500)
        val (from, to) = Agenda.queryWindow(now)
        instances = CalendarEvents.instances(context, from, to, hidden)
    }
    val events = remember(instances, demo, now) { (demo ?: instances)?.let { Agenda.select(it, now) } }
    val h24 = is24Hours(settings.clockStyle.collectAsStateWithLifecycle().value, context, LocalFormatLocale.current)

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(24.dp)
    ) {
        val compact = maxHeight < 260.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarIcon(CarIcons.Calendar, colors.text2, Modifier.size(26.dp))
                CarLabel(stringResource(R.string.widget_calendar), if (compact) 22.sp else 26.sp, colors.text, weight = FontWeight.ExtraBold)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val list = events
                when {
                    demo == null && !allowed -> PanelMessage(stringResource(R.string.calendar_no_access))
                    list == null -> Unit
                    list.isEmpty() -> PanelMessage(stringResource(R.string.calendar_empty))
                    else -> AgendaList(list, maxHeight, h24)
                }
            }
        }
    }
}

/** Day labels and rows, as many as fit; the rest is counted at the bottom. */
@Composable
private fun AgendaList(list: List<AgendaEvent>, height: Dp, h24: Boolean) {
    val colors = LocalCarColors.current
    // How many rows fit, counting a label for each day shown and one line for "N more"
    var room = height
    var shown = 0
    var lastDay: AgendaDay? = null
    for (event in list) {
        val need = ROW_HEIGHT + if (event.day != lastDay) DAY_LABEL_HEIGHT else 0.dp
        val more = if (shown + 1 < list.size) DAY_LABEL_HEIGHT else 0.dp
        if (room < need + more) break
        room -= need
        lastDay = event.day
        shown++
    }
    val visible = list.take(shown.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var day: AgendaDay? = null
        for (event in visible) {
            if (event.day != day) {
                day = event.day
                CarLabel(
                    stringResource(if (event.day == AgendaDay.TODAY) R.string.calendar_today else R.string.calendar_tomorrow),
                    18.sp, colors.text2, Modifier.height(DAY_LABEL_HEIGHT - 6.dp), weight = FontWeight.Bold,
                )
            }
            EventRow(event, h24)
        }
        if (list.size > visible.size) {
            CarLabel(stringResource(R.string.calendar_more, list.size - visible.size), 18.sp, colors.text2, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EventRow(event: AgendaEvent, h24: Boolean) {
    val colors = LocalCarColors.current
    val instance = event.instance
    val locale = LocalFormatLocale.current
    val format = remember(h24, locale) { DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", locale) }
    fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(format)
    val (first, second) = when {
        instance.allDay -> stringResource(R.string.calendar_all_day) to null
        event.ongoing -> stringResource(R.string.calendar_now) to stringResource(R.string.calendar_until, time(instance.end))
        else -> time(instance.begin) to time(instance.end)
    }
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT - 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The calendar's own color, as every calendar app marks it
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight(0.8f)
                .background(instance.color?.let { Color(it).copy(alpha = 1f) } ?: colors.focus, RoundedCornerShape(50))
        )
        Column(Modifier.widthIn(min = 96.dp)) {
            CarLabel(first, 20.sp, colors.text, weight = FontWeight.Bold, tabularNumbers = true)
            second?.let { CarLabel(it, 17.sp, colors.text2, tabularNumbers = true) }
        }
        Column(Modifier.weight(1f)) {
            CarLabel(instance.title.ifEmpty { stringResource(R.string.calendar_untitled) }, 21.sp, colors.text, weight = FontWeight.Bold)
            instance.location?.let { place ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CarIcon(CarIcons.Map, colors.text2, Modifier.size(18.dp))
                    CarLabel(place, 17.sp, colors.text2)
                }
            }
        }
    }
}
