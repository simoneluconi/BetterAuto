package com.simoneluconi.betterauto.ui.widgets

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.home.WeatherWidgetHost
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.WidgetChoice
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.notifications.NotificationsWidget
import com.simoneluconi.betterauto.smarthome.SmartGroup
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.ui.smarthome.SmartHomeGroupWidget

/**
 * One kind of home widget: its name and icon in the slot picker, what it offers there and how it is drawn.
 * A kind that takes a parameter (which smart-home group...) returns one [WidgetChoice] per value from
 * [choices] and names each in [label]; [content] gets the choice back, parameter included.
 */
class HomeWidgetKind(
    val type: WidgetType,
    @param:StringRes val title: Int,
    val icon: ImageVector,
    /** What the picker offers on this phone: usually the one choice, none when it can't be shown here. */
    val choices: (Context) -> List<WidgetChoice> = { listOf(WidgetChoice(type)) },
    /** The name of a choice in the picker; the title unless the parameter needs naming. */
    val label: (Context, WidgetChoice) -> String = { context, _ -> context.getString(title) },
    val content: @Composable (choice: WidgetChoice, settings: LauncherSettings, modifier: Modifier) -> Unit,
)

/** Every home widget, in the picker's order. A new kind is one more entry here and one [WidgetType]. */
object HomeWidgets {
    val all: List<HomeWidgetKind> = listOf(
        HomeWidgetKind(WidgetType.MUSIC, R.string.nav_music, CarIcons.Music) { _, _, modifier -> MusicWidget(modifier) },
        HomeWidgetKind(WidgetType.NOTIFICATIONS, R.string.widget_notifications, CarIcons.Bell) { _, settings, modifier ->
            NotificationsWidget(settings, modifier)
        },
        HomeWidgetKind(WidgetType.FAVORITES, R.string.widget_favorites, CarIcons.Star) { _, settings, modifier ->
            FavoritesWidget(settings, modifier)
        },
        HomeWidgetKind(WidgetType.CLOCK, R.string.widget_clock, CarIcons.Clock) { _, settings, modifier -> ClockWidget(settings, modifier) },
        HomeWidgetKind(WidgetType.CALENDAR, R.string.widget_calendar, CarIcons.Calendar) { _, settings, modifier ->
            CalendarWidget(settings, modifier)
        },
        HomeWidgetKind(
            WidgetType.WEATHER, R.string.widget_weather, CarIcons.Weather,
            // Only with a weather app that has a widget: there is no weather of our own
            choices = { context -> if (WeatherWidgetHost.candidates(context).isEmpty()) emptyList() else listOf(WidgetChoice(WidgetType.WEATHER)) },
        ) { _, _, modifier -> WeatherWidget(modifier) },
        HomeWidgetKind(WidgetType.MAP, R.string.widget_map, CarIcons.Map) { _, _, modifier -> PlaceholderWidget(WidgetType.MAP, modifier) },
        // One per group (an area of the devices chosen for the car) of every linked service; the slot keeps "provider/group"
        HomeWidgetKind(
            WidgetType.SMART_HOME, R.string.widget_smart_home, CarIcons.SmartHome,
            choices = { context -> smartHomeGroups(context).map { WidgetChoice(WidgetType.SMART_HOME, groupKey(it)) } },
            label = { context, choice ->
                smartHomeGroups(context).firstOrNull { groupKey(it) == choice.param }?.title ?: context.getString(R.string.widget_smart_home)
            },
        ) { choice, _, modifier -> SmartHomeGroupWidget(choice.param, modifier) },
    )

    private fun smartHomeGroups(context: Context): List<SmartGroup> {
        SmartHome.init(context)
        val other = context.getString(R.string.smarthome_no_area)
        return SmartHome.linked.value.flatMap { SmartHome.groups(it.id, other) }
    }

    /** A group's slot parameter: provider ids hold no '/'. */
    fun groupKey(group: SmartGroup) = "${group.providerId}/${group.id}"

    fun kind(type: WidgetType): HomeWidgetKind? = all.firstOrNull { it.type == type }

    /** What the slot picker offers on this phone (reads the installed apps: not on every recomposition). */
    fun choices(context: Context): List<WidgetChoice> = all.flatMap { it.choices(context) }

    fun label(context: Context, choice: WidgetChoice): String =
        kind(choice.type)?.label?.invoke(context, choice) ?: choice.type.name

    /** The picker's name for a slot: the big one, then the others. */
    @StringRes
    fun slotTitle(index: Int): Int = when (index) {
        0 -> R.string.widget_slot_1
        1 -> R.string.widget_slot_2
        else -> R.string.widget_slot_3
    }
}

/** The widget a home slot holds. */
@Composable
fun HomeWidget(choice: WidgetChoice, settings: LauncherSettings, modifier: Modifier) {
    HomeWidgets.kind(choice.type)?.content?.invoke(choice, settings, modifier)
}
