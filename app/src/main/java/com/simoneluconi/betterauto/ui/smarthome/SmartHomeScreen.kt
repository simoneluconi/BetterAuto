package com.simoneluconi.betterauto.ui.smarthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.smarthome.LinkState
import com.simoneluconi.betterauto.smarthome.ProviderChoice
import com.simoneluconi.betterauto.smarthome.SmartCatalog
import com.simoneluconi.betterauto.smarthome.SmartGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.smarthome.SmartHomeProvider
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LazyVerticalGridWithBar
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.LocalContentFocus
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * The car's smart-home screen for one provider: area chips on top (like the music tabs), then the
 * chosen devices as big tiles with live state. One tap acts. Problems with the service show as one
 * calm line; the tiles keep their last known state.
 */
@Composable
fun SmartHomeScreen(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val linked by SmartHome.linked.collectAsStateWithLifecycle()
    val screenId by SmartHome.screenProvider.collectAsStateWithLifecycle()
    val provider = linked.firstOrNull { it.id == screenId } ?: linked.firstOrNull()
    if (provider == null) {
        SmartHomeMessage(stringResource(R.string.smarthome_car_not_linked), modifier)
        return
    }
    // Live states while the screen is open, even without a car session (the preview on the phone)
    DisposableEffect(provider.id) {
        val release = SmartHome.hold()
        onDispose { release() }
    }
    val catalog by provider.catalog.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    val states by provider.states.collectAsStateWithLifecycle()
    val feedback by SmartHome.feedback.collectAsStateWithLifecycle()
    val link by provider.link.collectAsStateWithLifecycle()
    val devices = SmartHome.chosenDevices(catalog, choices[provider.id] ?: ProviderChoice())
    // Areas that hold chosen devices, in the service's order; "All" first when there are several
    val areas = catalog?.areas.orEmpty().filter { a -> devices.any { it.areaId == a.id } }
    var area by remember(provider.id) { mutableStateOf<String?>(null) }
    val shown = if (area == null) devices else devices.filter { it.areaId == area }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProviderIcon(provider, 34)
            CarLabel(provider.name, 26.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold)
            linkProblem(link)?.let { CarLabel(it, 18.sp, colors.text2, Modifier.weight(1f), maxLines = 2) }
        }
        if (areas.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { AreaChip(stringResource(R.string.smarthome_area_all), area == null, Modifier.contentFocus()) { area = null } }
                items(areas, key = { it.id }) { a -> AreaChip(a.name, area == a.id) { area = a.id } }
            }
        }
        when {
            devices.isEmpty() -> SmartHomeMessage(stringResource(if (catalog == null) R.string.smarthome_car_loading else R.string.smarthome_car_none_chosen))
            else -> {
                // Without area chips the first tile's main control takes the knob's nudge
                val main = LocalContentFocus.current
                LazyVerticalGridWithBar(
                    GridCells.Adaptive(270.dp),
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(shown, key = { it.key }) { device ->
                        SmartDeviceTile(
                            provider, device, states[device.id], feedback[device.key],
                            onAction = { SmartHome.perform(provider, device, it) },
                            focus = if (areas.size <= 1 && device == shown.first()) main else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .carClickable(shape, text, onClick = onClick)
            .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface, shape)
            .height(60.dp)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(text, 21.sp, colors.text, weight = FontWeight.Bold)
    }
}

@Composable
internal fun linkProblem(link: LinkState): String? = when (link) {
    LinkState.CONNECTING -> stringResource(R.string.smarthome_car_connecting)
    LinkState.OFFLINE -> stringResource(R.string.smarthome_car_offline)
    LinkState.AUTH_FAILED -> stringResource(R.string.smarthome_car_auth)
    else -> null
}

private val NoCatalog: StateFlow<SmartCatalog?> = MutableStateFlow(null)

/**
 * The home widget of one smart-home group, by its slot parameter ("provider/group"): the group's card,
 * or a calm line when the group is gone (service unlinked, area emptied) saying how to change it.
 */
@Composable
fun SmartHomeGroupWidget(param: String?, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val linked by SmartHome.linked.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    val providerId = param?.substringBefore('/')
    val provider = linked.firstOrNull { it.id == providerId }
    val catalog by (provider?.catalog ?: NoCatalog).collectAsStateWithLifecycle()
    val other = stringResource(R.string.smarthome_no_area)
    val group = remember(linked, choices, catalog, param) {
        providerId?.let { SmartHome.groups(it, other) }?.firstOrNull { "${it.providerId}/${it.id}" == param }
    }
    if (group == null) {
        Column(
            modifier.clip(RoundedCornerShape(28.dp)).background(colors.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CarLabel(stringResource(R.string.widget_smart_home), 21.sp, colors.text, weight = FontWeight.Bold)
            SmartHomeMessage(stringResource(if (provider == null) R.string.smarthome_car_not_linked else R.string.smarthome_group_gone))
        }
        return
    }
    SmartHomeGroupCard(group, modifier)
}

/**
 * One group of smart-home devices as a card, for any provider: its title and its devices as compact
 * tiles with live state, one tap to act. Meant to be wrapped as a home-screen widget; it keeps the
 * service connected while shown. Groups come from [SmartHome.groups].
 */
@Composable
fun SmartHomeGroupCard(group: SmartGroup, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val providers by SmartHome.providers.collectAsStateWithLifecycle()
    val provider: SmartHomeProvider? = providers.firstOrNull { it.id == group.providerId }
    DisposableEffect(group.providerId) {
        val release = SmartHome.hold()
        onDispose { release() }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            provider?.let { ProviderIcon(it, 26) }
            CarLabel(group.title, 21.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
        }
        if (provider == null) {
            SmartHomeMessage(stringResource(R.string.smarthome_car_not_linked))
            return@Column
        }
        val catalog by provider.catalog.collectAsStateWithLifecycle()
        val states by provider.states.collectAsStateWithLifecycle()
        val feedback by SmartHome.feedback.collectAsStateWithLifecycle()
        val link by provider.link.collectAsStateWithLifecycle()
        linkProblem(link)?.let { CarLabel(it, 17.sp, colors.text2, maxLines = 2) }
        val devices = group.deviceIds.mapNotNull { id -> catalog?.device(id) }
        LazyVerticalGridWithBar(
            GridCells.Adaptive(230.dp),
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(devices, key = { it.key }) { device ->
                SmartDeviceTile(
                    provider, device, states[device.id], feedback[device.key],
                    onAction = { SmartHome.perform(provider, device, it) },
                    container = colors.surface2,
                )
            }
        }
    }
}
