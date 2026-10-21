package com.simoneluconi.betterauto

import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.AppsSetting
import com.simoneluconi.betterauto.settings.CalendarsSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.ListSetting
import com.simoneluconi.betterauto.settings.MutedChatsSetting
import com.simoneluconi.betterauto.settings.PhoneNote
import com.simoneluconi.betterauto.settings.RailLayoutSetting
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.settings.SettingItem
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.settings.WidgetSlotsSetting
import com.simoneluconi.betterauto.ui.launcher.categorySummary
import com.simoneluconi.betterauto.ui.launcher.icon
import com.simoneluconi.betterauto.ui.launcher.label
import com.simoneluconi.betterauto.ui.launcher.settingValue
import com.simoneluconi.betterauto.ui.launcher.widgetPlaced
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** A page inside the phone's settings: a category, a list of one of its settings, or a home slot's widgets. */
private sealed interface SettingsPage {
    data class Category(val category: SettingsCategory) : SettingsPage
    data class Items(val key: String) : SettingsPage
    data class Slot(val index: Int) : SettingsPage
}

/**
 * The car screen's settings on the phone: a short home with one row per category, each opening its page
 * ([SettingsCatalog] plus the phone-only [PhoneSettingsSections]). Lists open their own page with one
 * row per item. [start] opens straight on a category (the home page's shortcuts): Back then leaves.
 */
@Composable
fun PhoneSettingsPage(onBack: () -> Unit, start: SettingsCategory? = null) {
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    // Saved as text: survives the activity being recreated
    var path by rememberSaveable { mutableStateOf(start?.let { "c:${it.name}" } ?: "") }
    val page = decode(path)
    fun up() {
        path = when (page) {
            null -> return onBack()
            is SettingsPage.Category -> if (start != null) return onBack() else ""
            is SettingsPage.Items -> categoryOf(page.key)?.let { "c:${it.name}" } ?: ""
            is SettingsPage.Slot -> "l:home_widgets"
        }
    }
    BackHandler { up() }
    // Each page keeps where it was scrolled to: back lands where the driver left it, not at the top
    val scrolls = remember { HashMap<String, ScrollState>() }
    val title = when (page) {
        null -> stringResource(R.string.phone_settings_title)
        is SettingsPage.Category -> stringResource(page.category.title)
        is SettingsPage.Items -> SettingsCatalog.all.firstOrNull { it.key == page.key }?.let { stringResource(it.title) }.orEmpty()
        is SettingsPage.Slot -> stringResource(com.simoneluconi.betterauto.ui.widgets.HomeWidgets.slotTitle(page.index))
    }
    key(path) {
        PhonePage(title, onBack = ::up, scroll = scrolls.getOrPut(path) { ScrollState(0) }) {
            when (page) {
                null -> SettingsHome(settings) { path = "c:${it.name}" }
                is SettingsPage.Category -> CategoryPage(page.category, settings) { path = "l:${it.key}" }
                is SettingsPage.Items -> when (val item = SettingsCatalog.all.firstOrNull { it.key == page.key }) {
                    is ListSetting -> ListPage(item, settings) { path = "s:$it" }
                    else -> Unit
                }
                is SettingsPage.Slot -> {
                    val item = SettingsCatalog.all.filterIsInstance<WidgetSlotsSetting>().first()
                    PhoneWidgetPicker(item, settings, page.index) { up() }
                }
            }
        }
    }
}

private fun decode(path: String): SettingsPage? = when {
    path.startsWith("c:") -> SettingsCategory.entries.firstOrNull { it.name == path.drop(2) }?.let { SettingsPage.Category(it) }
    path.startsWith("l:") -> SettingsPage.Items(path.drop(2))
    path.startsWith("s:") -> path.drop(2).toIntOrNull()?.let { SettingsPage.Slot(it) }
    else -> null
}

private fun categoryOf(key: String) = SettingsCatalog.all.firstOrNull { it.key == key }?.category

/** A page of the phone app: back button, title, then [content] scrolling. */
@Composable
internal fun PhonePage(
    title: String,
    onBack: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCarColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScrollWithBar(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarLabel(title, 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, shrinkToFit = true)
        }
        content()
    }
}

/** The categories, each with what it holds now. */
@Composable
private fun SettingsHome(settings: LauncherSettings, onOpen: (SettingsCategory) -> Unit) {
    val colors = LocalCarColors.current
    CarLabel(stringResource(R.string.phone_settings_hint), 15.sp, colors.text2, Modifier.padding(horizontal = 4.dp), maxLines = 3)
    val categories = SettingsCategory.entries.filter { c ->
        SettingsCatalog.items(c, SettingsSurface.PHONE).isNotEmpty() || PhoneSettingsSections.all.any { it.category == c }
    }
    PhoneCard {
        categories.forEachIndexed { i, category ->
            if (i > 0) PhoneLine()
            // Categories without catalog values to sum up say what their phone-only sections hold
            val extra = if (category.summaryKeys.isEmpty()) {
                PhoneSettingsSections.all.filter { it.category == category }.mapNotNull { it.summary() }
            } else {
                emptyList()
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(category) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
                    CarIcon(category.icon, colors.text2, Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CarLabel(stringResource(category.title), 18.sp, colors.text, weight = FontWeight.Bold)
                    categorySummary(category, settings, extra).takeIf { it.isNotEmpty() }?.let {
                        CarLabel(it, 14.sp, colors.text2, maxLines = 2)
                    }
                }
                CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
            }
        }
    }
}

/**
 * One category: its settings in a card, its phone-only sections, the options of its widgets (saying so
 * when a widget isn't on the home screen), then the rarely used ones under "More options".
 */
@Composable
private fun CategoryPage(category: SettingsCategory, settings: LauncherSettings, onOpenList: (ListSetting) -> Unit) {
    val colors = LocalCarColors.current
    val items = SettingsCatalog.items(category, SettingsSurface.PHONE)
    val sections = PhoneSettingsSections.all.filter { it.category == category }
    val main = items.filter { !it.more && it.widget == null }
    if (main.isNotEmpty()) PhoneCard { SettingRows(main, settings, onOpenList) }
    for (section in sections.filter { !it.more && it.widget == null }) SectionCard(section)

    val widgetItems = items.filter { !it.more && it.widget != null }
    val widgetSections = sections.filter { !it.more && it.widget != null }
    if (widgetItems.isNotEmpty() || widgetSections.isNotEmpty()) {
        CarLabel(stringResource(R.string.settings_widget_options), 17.sp, colors.text, Modifier.padding(start = 4.dp, top = 6.dp), weight = FontWeight.Bold)
        if (widgetItems.isNotEmpty()) PhoneCard { SettingRows(widgetItems, settings, onOpenList) }
        for (section in widgetSections) SectionCard(section, notPlaced = section.widget?.let { !widgetPlaced(settings, it) } == true)
    }

    val extraItems = items.filter { it.more }
    val extraSections = sections.filter { it.more }
    if (extraItems.isNotEmpty() || extraSections.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .clickable { open = !open }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarLabel(stringResource(if (open) R.string.settings_less else R.string.settings_more), 16.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
            CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp).rotate(if (open) -90f else 90f))
        }
        if (open) {
            if (extraItems.isNotEmpty()) PhoneCard { SettingRows(extraItems, settings, onOpenList) }
            for (section in extraSections) SectionCard(section)
        }
    }
}

@Composable
private fun SettingRows(items: List<SettingItem>, settings: LauncherSettings, onOpenList: (ListSetting) -> Unit) {
    items.forEachIndexed { i, item ->
        if (i > 0) PhoneLine()
        val widget = item.widget
        val note = if (widget != null && !widgetPlaced(settings, widget)) stringResource(R.string.settings_widget_not_placed) else null
        when (item) {
            is ChoiceSetting<*> -> if (item.isSwitch) PhoneSwitchRow(item, settings) else PhoneChoiceRow(item, settings)
            is ListSetting -> PhoneLinkRow(stringResource(item.title), stringResource(item.description), settingValue(item, settings), note) { onOpenList(item) }
            is PhoneNote -> Unit
        }
    }
}

/** A phone-only section: its title and description, then its own content, in a card. */
@Composable
private fun SectionCard(section: PhoneSettingsSection, notPlaced: Boolean = false) {
    PhoneCard {
        PhoneTitleBlock(
            stringResource(section.title), stringResource(section.description),
            if (notPlaced) stringResource(R.string.settings_widget_not_placed) else null,
        )
        section.Content()
    }
}

/** Title, the plain description under it, and a highlighted line when it doesn't apply right now. */
@Composable
internal fun PhoneTitleBlock(title: String, description: String, note: String? = null, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        CarLabel(title, 16.sp, colors.text, weight = FontWeight.Bold, maxLines = 2)
        CarLabel(description, 14.sp, colors.text2, maxLines = 5, lineHeight = 19.sp)
        note?.let { CarLabel(it, 14.sp, colors.focus, weight = FontWeight.Bold, maxLines = 2) }
    }
}

@Composable
internal fun PhoneCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalCarColors.current.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
internal fun PhoneLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalCarColors.current.line))
}

@Composable
private fun <T> PhoneSwitchRow(item: ChoiceSetting<T>, settings: LauncherSettings) {
    val value by item.state(settings).collectAsStateWithLifecycle()
    // Why the last tap did nothing, until the next one
    var refused by remember { mutableStateOf<Int?>(null) }
    PhoneSwitchLine(stringResource(item.title), stringResource(item.description), on = value == true, note = refused?.let { stringResource(it) }) {
        refused = item.options.firstOrNull { it.value != value }?.let { item.select(settings, it.value, SettingsSurface.PHONE) }
    }
}

/**
 * A whole row that reacts to a tap. Its highlight reaches [RowBleed] past the text on both sides: clipped
 * flush with the text, the rounded corners would cut the first letter of the last line.
 */
private fun Modifier.rowHighlight(onClick: () -> Unit): Modifier = this
    .layout { measurable, constraints ->
        val bleed = RowBleed.roundToPx()
        val wide = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = constraints.minWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed)
        } else {
            constraints
        }
        val placeable = measurable.measure(wide)
        layout((placeable.width - 2 * bleed).coerceAtLeast(0), placeable.height) { placeable.place(-bleed, 0) }
    }
    .clip(RoundedCornerShape(16.dp))
    .clickable(onClick = onClick)
    .padding(horizontal = RowBleed)

private val RowBleed = 10.dp

/** On/off as one row: the whole row toggles. */
@Composable
internal fun PhoneSwitchLine(title: String, description: String, on: Boolean, note: String? = null, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().rowHighlight(onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PhoneTitleBlock(title, description, note, Modifier.weight(1f))
        PhoneSwitch(on)
    }
}

@Composable
internal fun PhoneSwitch(on: Boolean) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .size(width = 52.dp, height = 30.dp)
            .background(if (on) colors.focus else colors.surface2, RoundedCornerShape(50))
            .border(1.dp, if (on) colors.focus else colors.line, RoundedCornerShape(50))
            .padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(if (on) Color.White else colors.text2, CircleShape))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> PhoneChoiceRow(item: ChoiceSetting<T>, settings: LauncherSettings) {
    val selected by item.state(settings).collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneTitleBlock(stringResource(item.title), stringResource(item.description))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in item.options) {
                Chip(stringResource(option.label), selected = option.value == selected) {
                    item.select(settings, option.value, SettingsSurface.PHONE)
                }
            }
        }
    }
}

/** A row that opens a page, with its current value on the right. */
@Composable
internal fun PhoneLinkRow(title: String, description: String, value: String?, note: String? = null, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Row(
        Modifier.fillMaxWidth().rowHighlight(onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhoneTitleBlock(title, description, note, Modifier.weight(1f))
        value?.let { CarLabel(it, 15.sp, colors.text, Modifier.widthIn(max = 140.dp), weight = FontWeight.Bold, maxLines = 2) }
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
    }
}

/** One item of a list: icon (or [leading]), name, a check when ticked; the whole row toggles. */
@Composable
internal fun PhoneTickRow(
    label: String,
    ticked: Boolean,
    icon: ImageBitmap? = null,
    leading: (@Composable () -> Unit)? = null,
    detail: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    Row(
        Modifier.fillMaxWidth().rowHighlight(onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(CircleShape))
            leading != null -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f)) {
            CarLabel(label, 16.sp, colors.text, weight = FontWeight.SemiBold, maxLines = 2)
            detail?.let { CarLabel(it, 13.sp, colors.text2, maxLines = 2) }
        }
        Box(
            Modifier
                .size(26.dp)
                .background(if (ticked) colors.focus else Color.Transparent, CircleShape)
                .border(2.dp, if (ticked) colors.focus else colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) CarIcon(CarIcons.Check, Color.White, Modifier.size(16.dp))
        }
    }
}

/** A list setting's page: its description, then one row per item. */
@Composable
private fun ListPage(item: ListSetting, settings: LauncherSettings, onOpenSlot: (Int) -> Unit) {
    val colors = LocalCarColors.current
    CarLabel(stringResource(item.description), 15.sp, colors.text2, Modifier.padding(horizontal = 4.dp), maxLines = 5)
    when (item) {
        is AppsSetting -> PhoneCard { PhoneApps(item, settings) }
        is CalendarsSetting -> PhoneCard { PhoneCalendars(item, settings) }
        is MutedChatsSetting -> PhoneCard { PhoneMutedChats(item, settings) }
        is RailLayoutSetting -> PhoneRailEditor(item, settings)
        is WidgetSlotsSetting -> PhoneWidgetSlots(item, settings, onOpenSlot)
    }
}

/** The app bar's buttons on the phone: place chips and up/down arrows for each, and back to the default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneRailEditor(item: RailLayoutSetting, settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val layout by settings.railLayout.collectAsStateWithLifecycle()
    var refused by remember { mutableStateOf<Int?>(null) }
    refused?.let { CarLabel(stringResource(it), 14.sp, colors.focus, Modifier.padding(horizontal = 4.dp), weight = FontWeight.Bold, maxLines = 3) }
    PhoneCard {
        for ((index, entry) in layout.withIndex()) {
            key(entry.item) {
                if (index > 0) PhoneLine()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val hidden = entry.slot == RailSlot.HIDDEN
                        CarIcon(entry.item.icon, if (hidden) colors.text2 else colors.text, Modifier.size(22.dp))
                        CarLabel(stringResource(entry.item.label), 16.sp, if (hidden) colors.text2 else colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
                        ArrowButton(CarIcons.Back, 90f, enabled = index > 0) { item.move(settings, entry.item, -1, SettingsSurface.PHONE) }
                        ArrowButton(CarIcons.Back, -90f, enabled = index < layout.lastIndex) { item.move(settings, entry.item, 1, SettingsSurface.PHONE) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (slot in RailSlot.entries) {
                            Chip(stringResource(slot.label), selected = entry.slot == slot) {
                                refused = item.place(settings, entry.item, slot, SettingsSurface.PHONE)
                            }
                        }
                    }
                }
            }
        }
        CarLabel(stringResource(R.string.rail_settings_note), 14.sp, colors.text2, maxLines = 2)
    }
    PillButton(stringResource(R.string.settings_default), primary = false) {
        refused = null
        item.reset(settings, SettingsSurface.PHONE)
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, rotate: Float, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (enabled) colors.text else colors.text2.copy(alpha = 0.4f), Modifier.size(20.dp).rotate(rotate))
    }
}

@Composable
private fun PhoneApps(item: AppsSetting, settings: LauncherSettings) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val stored by item.state(settings).collectAsStateWithLifecycle()
    // Read once per visit: ticking must not reorder the rows under the finger
    val apps = remember { item.apps(context, settings).mapNotNull { MediaApps.info(context, it) } }
    if (apps.isEmpty()) CarLabel(stringResource(item.empty), 15.sp, colors.text2, maxLines = 2)
    for (app in apps) {
        PhoneTickRow(app.label, item.isSelected(stored, app.packageName), icon = app.icon) {
            item.toggle(settings, app.packageName, SettingsSurface.PHONE)
        }
    }
    item.note?.let { CarLabel(stringResource(it), 14.sp, colors.text2, maxLines = 3) }
}

@Composable
private fun PhoneMutedChats(item: MutedChatsSetting, settings: LauncherSettings) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val muted by settings.mutedChats.collectAsStateWithLifecycle()
    if (muted.isEmpty()) CarLabel(stringResource(R.string.settings_no_muted_chats), 15.sp, colors.text2)
    for (chat in muted) {
        key(chat) {
            val app = remember(chat) { MediaApps.info(context, MutedChatsSetting.packageOf(chat)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                app?.let { Image(it.icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(CircleShape)) }
                CarLabel(MutedChatsSetting.chatName(chat), 16.sp, colors.text, Modifier.weight(1f), weight = FontWeight.SemiBold, maxLines = 2)
                PillButton(stringResource(R.string.settings_unmute), primary = false) { item.unmute(settings, chat, SettingsSurface.PHONE) }
            }
        }
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, icon: ImageBitmap? = null, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface2)
            .clickable(onClick = onClick)
            .padding(start = if (icon != null) 6.dp else 16.dp, end = 16.dp, top = if (icon != null) 6.dp else 10.dp, bottom = if (icon != null) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape)) }
        CarLabel(label, 15.sp, colors.text, weight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}
