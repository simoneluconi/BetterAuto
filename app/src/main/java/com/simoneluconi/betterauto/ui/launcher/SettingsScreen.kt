package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.home.CalendarEvents
import com.simoneluconi.betterauto.home.PhoneCalendar
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.AppsSetting
import com.simoneluconi.betterauto.settings.CalendarsSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.ListSetting
import com.simoneluconi.betterauto.settings.MutedChatsSetting
import com.simoneluconi.betterauto.settings.PhoneNote
import com.simoneluconi.betterauto.settings.RailLayoutSetting
import com.simoneluconi.betterauto.settings.SettingItem
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.settings.WidgetSlotsSetting
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import com.simoneluconi.betterauto.ui.widgets.HomeEdit
import kotlinx.coroutines.flow.MutableStateFlow

/** Where the car's settings are: kept while the process lives, so coming back finds the same category. */
object CarSettingsNav {
    val category = MutableStateFlow(SettingsCategory.HOME_SCREEN)

    /** Narrow screens: the category's page is open (wide ones always show it beside the list). */
    val pageOpen = MutableStateFlow(false)
}

/** Above this width the categories and the open one's settings sit side by side. */
private val MasterDetailWidth = 860.dp

/**
 * The car's settings, MMI-style: the categories of [SettingsCatalog] on the left and the highlighted
 * one's settings on the right, following the knob as it turns. Pressing (or nudging right) enters them;
 * Back or a nudge left returns to the list, Back again goes home. Narrow screens: the list, then a page.
 */
@Composable
fun SettingsScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    // Changing settings takes the eyes off the road: only while the car stands (P, stopped, or no data)
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    if (motion == CarMotion.MOVING) StoppedOnly(modifier) else SettingsContent(settings, modifier)
}

/** What the settings screen shows while driving: why they are away, and that they come back by themselves. */
@Composable
private fun StoppedOnly(modifier: Modifier) {
    val colors = LocalCarColors.current
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(colors.surface).padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(72.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
                CarIcon(CarIcons.Car, colors.text2, Modifier.size(34.dp))
            }
            CarLabel(stringResource(R.string.settings_when_stopped), 26.sp, colors.text, weight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            CarLabel(stringResource(R.string.settings_when_stopped_hint), 19.sp, colors.text2, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

@Composable
private fun SettingsContent(settings: LauncherSettings, modifier: Modifier) {
    val categories = remember { SettingsCatalog.carCategories() }
    val selected by CarSettingsNav.category.collectAsStateWithLifecycle()
    val pageOpen by CarSettingsNav.pageOpen.collectAsStateWithLifecycle()
    val rowFocus = remember { categories.associateWith { FocusRequester() } }
    val paneFocus = remember { FocusRequester() }
    // Focus moves wait a frame: the target may only now be composed
    var toPane by remember { mutableIntStateOf(0) }
    var toList by remember { mutableIntStateOf(0) }
    LaunchedEffect(toPane) {
        if (toPane == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { paneFocus.requestFocus() }
    }
    LaunchedEffect(toList) {
        if (toList == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { rowFocus[CarSettingsNav.category.value]?.requestFocus() }
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= MasterDetailWidth
        val list = @Composable { mod: Modifier ->
            CategoryList(
                categories, selected, rowFocus, wide, mod,
                onOpen = { category, knob ->
                    CarSettingsNav.category.value = category
                    if (!wide) CarSettingsNav.pageOpen.value = true
                    if (knob) toPane++
                },
            )
        }
        val pane = @Composable { mod: Modifier ->
            CategoryPane(
                selected, settings, paneFocus, showBack = !wide, mod,
                onLeave = {
                    CarSettingsNav.pageOpen.value = false
                    toList++
                },
            )
        }
        when {
            wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                list(Modifier.width(380.dp).fillMaxHeight())
                pane(Modifier.weight(1f).fillMaxHeight())
            }
            pageOpen -> pane(Modifier.fillMaxSize())
            else -> list(Modifier.fillMaxSize())
        }
    }
}

/** The categories. Turning the knob stays in the list (the pane follows it); a press or a nudge right enters the pane. */
@Composable
private fun CategoryList(
    categories: List<SettingsCategory>,
    selected: SettingsCategory,
    rowFocus: Map<SettingsCategory, FocusRequester>,
    wide: Boolean,
    modifier: Modifier,
    onOpen: (SettingsCategory, knob: Boolean) -> Unit,
) {
    val colors = LocalCarColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Next || requestedFocusDirection == FocusDirection.Previous) cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (!wide || event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) onOpen(CarSettingsNav.category.value, true)
                true
            }
            .verticalScrollWithBar()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CarLabel(stringResource(R.string.settings_title), 26.sp, colors.text, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp), weight = FontWeight.ExtraBold)
        for (category in categories) {
            var focused by remember { mutableStateOf(false) }
            val isSelected = category == selected
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .contentFocus(enabled = isSelected)
                    .focusRequester(rowFocus.getValue(category))
                    .onFocusChanged {
                        focused = it.isFocused
                        // The pane follows the knob
                        if (it.isFocused && wide) CarSettingsNav.category.value = category
                    }
                    .carClickable(shape, stringResource(category.title)) { onOpen(category, focused) }
                    .background(if (isSelected && wide) colors.focus.copy(alpha = 0.18f) else Color.Transparent, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(48.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
                    CarIcon(category.icon, colors.text, Modifier.size(26.dp))
                }
                CarLabel(stringResource(category.title), 21.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold, shrinkToFit = true)
                CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
            }
        }
    }
}

/**
 * One category's settings: the main ones, its widgets' options, then "More options". A list setting
 * opens its list in the pane. Turning stays in the pane; Back (or a nudge left on wide screens) closes
 * an open list, else returns to the categories.
 */
@Composable
private fun CategoryPane(
    category: SettingsCategory,
    settings: LauncherSettings,
    entry: FocusRequester,
    showBack: Boolean,
    modifier: Modifier,
    onLeave: () -> Unit,
) = key(category) {
    val colors = LocalCarColors.current
    var list by remember { mutableStateOf<ListSetting?>(null) }
    var opened by remember { mutableStateOf<ListSetting?>(null) }
    var more by remember { mutableStateOf(false) }
    // The row that opened a list gets the knob back when it closes; an emptied list falls back on its back button
    val opener = remember { FocusRequester() }
    val listBack = remember { FocusRequester() }
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(refocus) {
        val target = refocus ?: return@LaunchedEffect
        withFrameNanos { }
        if (!runCatching { target.requestFocus() }.getOrDefault(false)) runCatching { listBack.requestFocus() }
        refocus = null
    }
    val openList = { item: ListSetting ->
        list = item
        opened = item
        refocus = entry
    }
    val back = {
        if (list != null) {
            list = null
            refocus = opener
        } else {
            onLeave()
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Next || requestedFocusDirection == FocusDirection.Previous) cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (showBack || event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) back()
                true
            }
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) back()
                true
            }
            .verticalScrollWithBar()
            .padding(horizontal = 26.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val open = list
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            if (showBack || open != null) {
                RoundButton(CarIcons.Back, stringResource(R.string.settings_back_to_list), if (open != null) Modifier.focusRequester(listBack) else Modifier) { back() }
            }
            CarLabel(stringResource(open?.title ?: category.title), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, shrinkToFit = true)
        }
        if (open != null) {
            ListPage(open, settings, entry) { refocus = entry }
            return@Column
        }
        val items = SettingsCatalog.items(category, SettingsSurface.CAR)
        val main = items.filter { !it.more && it.widget == null }
        val widgets = items.filter { !it.more && it.widget != null }
        val extra = items.filter { it.more }
        // The knob enters on the first setting, the likely target (the back button is one turn back)
        var entryUsed = false
        fun takeEntry(): FocusRequester? = if (entryUsed) null else entry.also { entryUsed = true }
        // A phone note is not a control: the entry waits for the next real one
        fun requesters(item: SettingItem): List<FocusRequester> =
            if (item is PhoneNote) emptyList() else listOfNotNull(takeEntry(), opener.takeIf { item == opened })
        Rows(main, settings, ::requesters, openList)
        if (widgets.isNotEmpty()) {
            CarLabel(stringResource(R.string.settings_widget_options), 21.sp, colors.text2, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp), weight = FontWeight.Bold)
            Rows(widgets, settings, ::requesters, openList)
        }
        if (extra.isNotEmpty()) {
            Line()
            MoreRow(more, takeEntry()) { more = !more }
            if (more) Rows(extra, settings, { item -> listOfNotNull(opener.takeIf { item == opened }) }, openList)
        }
    }
}

@Composable
private fun Rows(items: List<SettingItem>, settings: LauncherSettings, focus: (SettingItem) -> List<FocusRequester>, openList: (ListSetting) -> Unit) {
    items.forEachIndexed { i, item ->
        if (i > 0) Line()
        SettingRow(item, settings, focus(item).fold(Modifier as Modifier) { m, r -> m.focusRequester(r) }, openList)
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(LocalCarColors.current.line))
}

@Composable
private fun SettingRow(item: SettingItem, settings: LauncherSettings, focus: Modifier, openList: (ListSetting) -> Unit) {
    val widget = item.widget
    val note = if (widget != null && !widgetPlaced(settings, widget)) stringResource(R.string.settings_widget_not_placed) else null
    when (item) {
        is ChoiceSetting<*> -> if (item.isSwitch) SwitchRow(item, settings, focus) else ChoiceRow(item, settings, focus)
        is WidgetSlotsSetting -> LinkRow(R.string.settings_edit_home, item, settings, focus, note) { HomeEdit.start(settings) }
        is RailLayoutSetting -> LinkRow(R.string.settings_arrange_bar, item, settings, focus, note) { BarEditor.open(null) }
        is ListSetting -> LinkRow(item.title, item, settings, focus, note) { openList(item) }
        is PhoneNote -> NoteRow(item, note)
    }
}

/** Title in Bold, the description under it, and a line saying why it may not matter now. */
@Composable
private fun TitleBlock(title: String, description: String, note: String?, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CarLabel(title, 21.sp, colors.text, weight = FontWeight.Bold, maxLines = 2)
        CarLabel(description, 17.sp, colors.text2, maxLines = 4, lineHeight = 23.sp)
        note?.let { CarLabel(it, 17.sp, colors.focus, weight = FontWeight.Bold, maxLines = 2) }
    }
}

private val RowShape = RoundedCornerShape(22.dp)

@Composable
private fun <T> SwitchRow(item: ChoiceSetting<T>, settings: LauncherSettings, focus: Modifier) {
    val value by item.state(settings).collectAsStateWithLifecycle()
    val on = value == true
    val title = stringResource(item.title)
    // Why the last press did nothing, until the next one
    var refused by remember { mutableStateOf<Int?>(null) }
    // The reason makes the row taller: scroll it back fully into view, it may sit at the bottom edge
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(refused) {
        if (refused == null) return@LaunchedEffect
        withFrameNanos { }
        bringIntoView.bringIntoView()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .then(focus)
            .carClickable(RowShape, stringResource(R.string.settings_option, title, stringResource(if (on) R.string.option_on else R.string.option_off))) {
                refused = item.options.firstOrNull { it.value != value }?.let { item.select(settings, it.value, SettingsSurface.CAR) }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TitleBlock(title, stringResource(item.description), refused?.let { stringResource(it) }, Modifier.weight(1f))
        Switch(on)
    }
}

/** A pill switch, lit when on. */
@Composable
internal fun Switch(on: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Box(
        modifier
            .size(width = 66.dp, height = 38.dp)
            .background(if (on) colors.focus else colors.surface2, RoundedCornerShape(50))
            .border(1.dp, if (on) colors.focus else colors.line, RoundedCornerShape(50))
            .padding(5.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(28.dp).background(if (on) colors.bg else colors.text2, CircleShape))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(item: ChoiceSetting<T>, settings: LauncherSettings, focus: Modifier) {
    val colors = LocalCarColors.current
    val selected by item.state(settings).collectAsStateWithLifecycle()
    val title = stringResource(item.title)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TitleBlock(title, stringResource(item.description), null)
        // Wraps on narrow (portrait) screens
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in item.options) {
                val shape = RoundedCornerShape(50)
                val isSelected = option.value == selected
                val label = stringResource(option.label)
                Box(
                    Modifier
                        .then(if (isSelected) focus else Modifier)
                        .carClickable(shape, stringResource(R.string.settings_option, title, label)) { item.select(settings, option.value, SettingsSurface.CAR) }
                        .background(if (isSelected) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(58.dp)
                        .padding(horizontal = 26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarLabel(label, 20.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
    }
}

/** A row that opens something (a list, the home's edit mode, the bar's editor), with the current value. */
@Composable
private fun LinkRow(title: Int, item: ListSetting, settings: LauncherSettings, focus: Modifier, note: String?, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val text = stringResource(title)
    val value = settingValue(item, settings)
    Row(
        Modifier
            .fillMaxWidth()
            .then(focus)
            .carClickable(RowShape, if (value != null) stringResource(R.string.settings_option, text, value) else text, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitleBlock(text, stringResource(item.description), note, Modifier.weight(1f))
        value?.let { CarLabel(it, 19.sp, colors.text, Modifier.widthIn(max = 260.dp), weight = FontWeight.Bold, maxLines = 2) }
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(24.dp))
    }
}

/** Set up on the phone: a calm line, not a control. */
@Composable
private fun NoteRow(item: PhoneNote, note: String?) {
    val colors = LocalCarColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(48.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Phone, colors.text2, Modifier.size(24.dp))
        }
        TitleBlock(stringResource(item.title), stringResource(item.description), note, Modifier.weight(1f))
        CarLabel(stringResource(R.string.settings_set_up_on_phone), 17.sp, colors.text2, Modifier.widthIn(max = 200.dp), maxLines = 2)
    }
}

@Composable
private fun MoreRow(open: Boolean, entry: FocusRequester?, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val text = stringResource(if (open) R.string.settings_less else R.string.settings_more)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
            .carClickable(RowShape, text, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CarLabel(text, 21.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(24.dp).rotate(if (open) -90f else 90f))
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        modifier
            .carClickable(CircleShape, label, onClick = onClick)
            .background(colors.surface2, CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, colors.text, Modifier.size(24.dp))
    }
}

/** A list setting's items, one row each (icon, name, a check when ticked); the knob enters on the first. */
@Composable
private fun ListPage(item: ListSetting, settings: LauncherSettings, entry: FocusRequester, onRemoved: () -> Unit) {
    val colors = LocalCarColors.current
    CarLabel(stringResource(item.description), 17.sp, colors.text2, Modifier.padding(start = 4.dp, bottom = 10.dp), maxLines = 4, lineHeight = 23.sp)
    when (item) {
        is AppsSetting -> AppsList(item, settings, entry)
        is CalendarsSetting -> CalendarsList(item, settings, entry)
        is MutedChatsSetting -> MutedList(item, settings, entry, onRemoved)
        // Opened as their own editors, never as a list here
        is RailLayoutSetting, is WidgetSlotsSetting -> Unit
    }
}

@Composable
private fun TickRow(label: String, ticked: Boolean, entry: FocusRequester?, leading: @Composable () -> Unit, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
            .carClickable(RowShape, stringResource(R.string.settings_option, label, stringResource(if (ticked) R.string.option_on else R.string.option_off)), onClick = onClick)
            .background(if (ticked) colors.focus.copy(alpha = 0.14f) else colors.surface2, RowShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading()
        CarLabel(label, 21.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
        if (trailing != null) {
            trailing()
        } else {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (ticked) colors.focus else Color.Transparent, CircleShape)
                    .border(2.dp, if (ticked) colors.focus else colors.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (ticked) CarIcon(CarIcons.Check, colors.bg, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun AppsList(item: AppsSetting, settings: LauncherSettings, entry: FocusRequester) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val stored by item.state(settings).collectAsStateWithLifecycle()
    // The list itself only when coming in: ticking must not reorder or drop rows under the knob
    val apps = remember { item.apps(context, settings).mapNotNull { MediaApps.info(context, it) } }
    if (apps.isEmpty()) CarLabel(stringResource(item.empty), 19.sp, colors.text2, Modifier.padding(4.dp), maxLines = 2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        apps.forEachIndexed { i, app ->
            TickRow(app.label, item.isSelected(stored, app.packageName), if (i == 0) entry else null, leading = {
                Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
            }) { item.toggle(settings, app.packageName, SettingsSurface.CAR) }
        }
    }
    item.note?.let { CarLabel(stringResource(it), 17.sp, colors.text2, Modifier.padding(4.dp), maxLines = 3) }
}

@Composable
private fun CalendarsList(item: CalendarsSetting, settings: LauncherSettings, entry: FocusRequester) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val hidden by settings.hiddenCalendars.collectAsStateWithLifecycle()
    val calendars by produceState<List<PhoneCalendar>?>(null) { value = CalendarEvents.calendars(context) }
    val list = calendars ?: return
    val labels = remember(list) { PhoneCalendar.labels(list) }
    if (list.isEmpty()) {
        val text = if (CalendarEvents.canRead(context)) R.string.settings_no_calendars else R.string.calendar_no_access
        CarLabel(stringResource(text), 19.sp, colors.text2, Modifier.padding(4.dp), maxLines = 2)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEachIndexed { i, calendar ->
            TickRow(labels[calendar.id] ?: calendar.name, item.isShown(hidden, calendar.id), if (i == 0) entry else null, leading = {
                // The calendar's own color, as the phone's calendar app shows it
                Box(Modifier.size(18.dp).background(calendar.color?.let { Color(it).copy(alpha = 1f) } ?: colors.text2, CircleShape))
            }) { item.toggle(settings, calendar.id, SettingsSurface.CAR) }
        }
    }
}

@Composable
private fun MutedList(item: MutedChatsSetting, settings: LauncherSettings, entry: FocusRequester, onRemoved: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val muted by settings.mutedChats.collectAsStateWithLifecycle()
    if (muted.isEmpty()) CarLabel(stringResource(R.string.settings_no_muted_chats), 19.sp, colors.text2, Modifier.padding(4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        muted.forEachIndexed { i, chat ->
            key(chat) {
                val app = remember(chat) { MediaApps.info(context, MutedChatsSetting.packageOf(chat)) }
                TickRow(
                    MutedChatsSetting.chatName(chat), ticked = false, if (i == 0) entry else null,
                    leading = { app?.let { Image(it.icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape)) } },
                    trailing = { CarLabel(stringResource(R.string.settings_unmute), 18.sp, colors.text2, weight = FontWeight.Bold) },
                ) {
                    item.unmute(settings, chat, SettingsSurface.CAR)
                    // The row goes away under the knob: on to the first one left
                    onRemoved()
                }
            }
        }
    }
}
