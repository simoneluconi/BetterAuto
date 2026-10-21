package com.simoneluconi.betterauto

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.home.PhoneCalendar
import com.simoneluconi.betterauto.settings.CalendarsSetting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.home.CalendarEvents
import com.simoneluconi.betterauto.home.WeatherWidgetHost
import com.simoneluconi.betterauto.phone.PhoneBook
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.settings.WidgetChoice
import com.simoneluconi.betterauto.settings.WidgetSlotsSetting
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.widgets.HomeWidgets

/** Counts the app coming back to the front: permissions and bindings change in other screens. */
@Composable
private fun rememberResumes(): Int {
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return resumes
}

/**
 * What a widget needs, asked while the phone is in hand: calendar or contacts access, the system's OK to
 * show the weather app's widget. [prepare] asks for one widget type.
 */
@Composable
private fun rememberWidgetPrepare(onDone: () -> Unit): (WidgetType) -> Unit {
    val context = LocalContext.current
    val ask = rememberPermissionRequest(onDone)
    val setUpWeather = rememberWeatherSetup(onDone)
    return { type ->
        when (type) {
            WidgetType.CALENDAR -> if (!CalendarEvents.canRead(context)) ask(arrayOf(Manifest.permission.READ_CALENDAR))
            WidgetType.FAVORITES -> if (!PhoneBook.canRead(context)) ask(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
            WidgetType.WEATHER -> setUpWeather()
            else -> Unit
        }
    }
}

/**
 * The home slots on the phone: one row per slot the layout shows, opening its widget list. Below them,
 * what widgets picked in the car (where nothing can be asked) still need: one tap here.
 */
@Composable
internal fun PhoneWidgetSlots(item: WidgetSlotsSetting, settings: LauncherSettings, onOpenSlot: (Int) -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val layout by settings.layout.collectAsStateWithLifecycle()
    val slots by settings.widgets.collectAsStateWithLifecycle()
    val weatherChanges by WeatherWidgetHost.changes.collectAsStateWithLifecycle()
    val resumes = rememberResumes()
    var answers by remember { mutableIntStateOf(0) }
    val prepare = rememberWidgetPrepare { answers++ }
    val shown = slots.take(layout.slots)
    PhoneCard {
        shown.forEachIndexed { index, current ->
            if (index > 0) PhoneLine()
            PhoneLinkRow(stringResource(HomeWidgets.slotTitle(index)), HomeWidgets.label(context, current), null) { onOpenSlot(index) }
        }
    }
    val needs = remember(shown, resumes, answers, weatherChanges) { needs(context, shown) }
    for ((type, text) in needs) {
        PhoneCard {
            CarLabel(stringResource(text), 14.sp, colors.text2, maxLines = 3)
            PillButton(stringResource(if (type == WidgetType.WEATHER) R.string.weather_set_up else R.string.action_allow), primary = true) {
                prepare(type)
            }
        }
    }
    CarLabel(stringResource(R.string.settings_home_widgets_phone_note), 14.sp, colors.text2, Modifier.padding(horizontal = 4.dp), maxLines = 3)
}

/** The widgets one slot can hold; picking one already shown swaps the two, then asks what it needs. */
@Composable
internal fun PhoneWidgetPicker(item: WidgetSlotsSetting, settings: LauncherSettings, slot: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val layout by settings.layout.collectAsStateWithLifecycle()
    val slots by settings.widgets.collectAsStateWithLifecycle()
    val resumes = rememberResumes()
    val offered = remember(resumes) { HomeWidgets.choices(context) }
    val prepare = rememberWidgetPrepare { }
    val current = slots.getOrNull(slot)
    val choices = if (current == null || current in offered) offered else offered + current
    val colors = LocalCarColors.current
    PhoneCard {
        for (choice in choices) {
            val elsewhere = slots.take(layout.slots).indexOf(choice).takeIf { it >= 0 && it != slot }
            PhoneTickRow(
                HomeWidgets.label(context, choice), ticked = choice == current,
                leading = { HomeWidgets.kind(choice.type)?.let { CarIcon(it.icon, colors.text, Modifier.size(24.dp)) } },
                detail = elsewhere?.let { stringResource(R.string.widget_in_slot, stringResource(HomeWidgets.slotTitle(it))) },
            ) {
                item.pick(settings, slot, choice, SettingsSurface.PHONE)
                prepare(choice.type)
                onDone()
            }
        }
    }
}

/** The phone's calendars, one row each with its color: ticked = shown by the calendar widget. */
@Composable
internal fun PhoneCalendars(item: CalendarsSetting, settings: LauncherSettings) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val hidden by settings.hiddenCalendars.collectAsStateWithLifecycle()
    // Again on resume and after the answer: the calendar permission may just have been given
    val resumes = rememberResumes()
    var answers by remember { mutableIntStateOf(0) }
    val ask = rememberPermissionRequest { answers++ }
    val calendars by produceState<List<PhoneCalendar>?>(null, resumes, answers) { value = CalendarEvents.calendars(context) }
    val list = calendars ?: return
    val labels = remember(list) { PhoneCalendar.labels(list) }
    if (list.isEmpty()) {
        val readable = CalendarEvents.canRead(context)
        CarLabel(stringResource(if (readable) R.string.settings_no_calendars else R.string.widgets_need_calendar), 15.sp, colors.text2, maxLines = 3)
        if (!readable) PillButton(stringResource(R.string.action_allow), primary = true) { ask(arrayOf(Manifest.permission.READ_CALENDAR)) }
    }
    for (calendar in list) {
        PhoneTickRow(
            labels[calendar.id] ?: calendar.name, item.isShown(hidden, calendar.id),
            leading = { Box(Modifier.size(14.dp).background(calendar.color?.let { Color(it).copy(alpha = 1f) } ?: colors.text2, CircleShape)) },
        ) { item.toggle(settings, calendar.id, SettingsSurface.PHONE) }
    }
}

/** Widgets on the home screen that can't show anything yet, with the line saying why. */
private fun needs(context: Context, shown: List<WidgetChoice>): List<Pair<WidgetType, Int>> =
    shown.map { it.type }.distinct().mapNotNull { type ->
        when (type) {
            WidgetType.CALENDAR -> (type to R.string.widgets_need_calendar).takeIf { !CalendarEvents.canRead(context) }
            WidgetType.FAVORITES -> (type to R.string.widgets_need_contacts).takeIf { !PhoneBook.canRead(context) }
            WidgetType.WEATHER -> WeatherWidgetHost.provider(context)
                ?.takeIf { WeatherWidgetHost.boundId(context, it) == null }
                ?.let { type to R.string.widgets_need_weather }
            else -> null
        }
    }

/**
 * Binds the weather widget: silently when the driver once ticked "always allow", otherwise through the
 * system dialog; then the weather app's own setup screen, if its widget has one.
 */
@Composable
private fun rememberWeatherSetup(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    // The id waiting for the dialog's answer; saved, as the activity may be recreated meanwhile
    var pending by rememberSaveable { mutableIntStateOf(-1) }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pending
        pending = -1
        if (id >= 0) {
            if (result.resultCode == Activity.RESULT_OK) {
                WeatherWidgetHost.onBound(context, id)
                configureIfNeeded(context, id)
            } else {
                WeatherWidgetHost.onBindRefused(context, id)
            }
        }
        onDone()
    }
    return setup@{
        val info = WeatherWidgetHost.provider(context) ?: return@setup
        if (WeatherWidgetHost.boundId(context, info) != null) return@setup
        val id = WeatherWidgetHost.bindIfAllowed(context, info)
        if (id != null) {
            configureIfNeeded(context, id)
            onDone()
            return@setup
        }
        val (newId, intent) = WeatherWidgetHost.bindRequest(context, info)
        pending = newId
        runCatching { bind.launch(intent) }.onFailure {
            EventLog.log("Weather widget: no bind dialog ($it)")
            pending = -1
            WeatherWidgetHost.onBindRefused(context, newId)
        }
    }
}

private fun configureIfNeeded(context: Context, id: Int) {
    val info = WeatherWidgetHost.provider(context) ?: return
    if (!WeatherWidgetHost.needsConfigure(info)) return
    context.findActivity()?.let { WeatherWidgetHost.configure(it, id) }
}

/** One style of a weather app: its preview image (or icon) and name, framed when it is the one shown. */
@Composable
private fun StylePreview(info: android.appwidget.AppWidgetProviderInfo, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val preview = remember(info.provider) {
        runCatching {
            val drawable = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0) ?: return@runCatching null
            // Its own proportions, only scaled down: a fixed size would stretch it
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = minOf(1f, PREVIEW_W.toFloat() / w, PREVIEW_H.toFloat() / h)
            drawable.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
        }.getOrNull()
    }
    val label = remember(info.provider) { info.loadLabel(context.packageManager).orEmpty() }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(150.dp)
            .clip(shape)
            .background(colors.surface2)
            .border(2.dp, if (selected) colors.focus else colors.line, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        CarLabel(label, 13.sp, if (selected) colors.text else colors.text2, maxLines = 2)
    }
}

private const val PREVIEW_W = 300
private const val PREVIEW_H = 152

/** The weather section's line on the settings home: the app in the car. */
@Composable
internal fun weatherSummary(): String? {
    val context = LocalContext.current
    val changes by WeatherWidgetHost.changes.collectAsStateWithLifecycle()
    val provider = remember(changes) { WeatherWidgetHost.provider(context) } ?: return null
    return MediaApps.info(context, provider.provider.packageName)?.label
}

/**
 * Phone-only: which weather app's widget the car shows. Auto picks it; the chips are there only when
 * there is more than one to choose from. Without a weather app, a line says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherPhoneSettings() {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val changes by WeatherWidgetHost.changes.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val candidates = remember(resumes, changes) { WeatherWidgetHost.candidates(context) }
    if (candidates.isEmpty()) {
        CarLabel(stringResource(R.string.weather_none), 15.sp, colors.text2, maxLines = 2)
        return
    }
    val setUp = rememberWeatherSetup { resumes++ }
    val chosen = remember(resumes, changes) { WeatherWidgetHost.chosen(context) }
    val provider = remember(resumes, changes) { WeatherWidgetHost.provider(context) }
    val bound = remember(resumes, changes) { provider?.let { WeatherWidgetHost.boundId(context, it) } != null }
    val pm = context.packageManager
    fun name(info: android.appwidget.AppWidgetProviderInfo): String {
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrNull()
        val label = info.loadLabel(pm) ?: ""
        return if (app == null || label.contains(app, ignoreCase = true)) label else "$app · $label"
    }

    if (candidates.size > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CarLabel(stringResource(R.string.weather_app), 15.sp, colors.text2, weight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.option_auto), selected = chosen == null) { WeatherWidgetHost.choose(context, null) }
                // One chip per app (its icon and name): the widget of it is picked for the car's card
                for (info in candidates) {
                    val key = WeatherWidgetHost.providerKey(info)
                    val app = MediaApps.info(context, info.provider.packageName)
                    val picked = chosen != null && chosen.substringBefore('/') == info.provider.packageName
                    Chip(app?.label ?: name(info), selected = picked, icon = app?.icon) {
                        WeatherWidgetHost.choose(context, key)
                        setUp() // the phone is in hand: bind it and let the weather app ask its questions now
                    }
                }
            }
        }
    }
    // The chosen app's styles, as the previews a launcher shows when adding a widget
    val styles = remember(resumes, changes, provider) {
        provider?.let { WeatherWidgetHost.stylesOf(context, it.provider.packageName) }.orEmpty()
    }
    if (styles.size > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CarLabel(stringResource(R.string.weather_style), 15.sp, colors.text2, weight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (style in styles) {
                    StylePreview(style, selected = style.provider == provider?.provider) {
                        WeatherWidgetHost.choose(context, WeatherWidgetHost.providerKey(style))
                        setUp() // the phone is in hand: bind it and let the weather app ask its questions now
                    }
                }
            }
        }
    }
    provider?.let {
        CarLabel(
            if (bound) stringResource(R.string.weather_status_ready, name(it)) else stringResource(R.string.weather_status_not_set_up),
            15.sp, colors.text2, maxLines = 2,
        )
    }
    when {
        !bound -> PillButton(stringResource(R.string.weather_set_up), primary = true) { setUp() }
        // Its place, units and look are the weather app's own settings: open them again
        provider?.configure != null -> PillButton(stringResource(R.string.weather_change), primary = false) {
            val id = WeatherWidgetHost.boundId(context, provider)
            val activity = context.findActivity()
            if (id != null && activity != null && WeatherWidgetHost.reconfigurable(provider)) {
                WeatherWidgetHost.configure(activity, id)
            } else {
                WeatherWidgetHost.forget(context)
                setUp()
            }
        }
    }
}
