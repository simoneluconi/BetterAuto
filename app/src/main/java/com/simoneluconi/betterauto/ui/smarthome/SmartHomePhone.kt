package com.simoneluconi.betterauto.ui.smarthome

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.Chip
import com.simoneluconi.betterauto.PhoneLine
import com.simoneluconi.betterauto.PhoneSwitchLine
import com.simoneluconi.betterauto.PillButton
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.rememberPermissionRequest
import com.simoneluconi.betterauto.smarthome.ArrivalCandidates
import com.simoneluconi.betterauto.smarthome.LinkState
import com.simoneluconi.betterauto.smarthome.PlaceArrival
import com.simoneluconi.betterauto.smarthome.SmartCatalog
import com.simoneluconi.betterauto.smarthome.SmartPlace
import com.simoneluconi.betterauto.smarthome.Preselection
import com.simoneluconi.betterauto.smarthome.ProviderChoice
import com.simoneluconi.betterauto.smarthome.SmartDevice
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.smarthome.SmartHomeProvider
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.CarText
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.launch

/**
 * The phone app's smart-home section, inside Settings › Smart home: one block per provider (its own
 * linking card), then, once linked, the devices for the car and the arrival buttons. Provider-agnostic.
 */
@Composable
fun SmartHomePhoneSettings() {
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val providers by SmartHome.providers.collectAsStateWithLifecycle()
    providers.forEachIndexed { i, provider ->
        if (i > 0) PhoneLine()
        ProviderBlock(provider)
    }
}

/** The line on the settings home: what is linked, and how many devices are in the car. */
@Composable
fun smartHomeSummary(): String {
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val linked by SmartHome.linked.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    if (linked.isEmpty()) return stringResource(R.string.settings_smart_home_none)
    return stringResource(R.string.smarthome_card_linked, linked.joinToString { it.name }, linked.sumOf { choices[it.id]?.selected?.size ?: 0 })
}

/**
 * On the phone app's home page, a shortcut to Settings › Smart home: "Connect Home Assistant" when its
 * app is on the phone and nothing is linked yet, or the linked services at a glance. Nothing otherwise.
 */
@Composable
fun SmartHomeCard(onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    remember { SmartHome.init(context) }
    val providers by SmartHome.providers.collectAsStateWithLifecycle()
    val linked by SmartHome.linked.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    val suggested = remember(providers) { providers.firstOrNull { it.appInstalled(context) } }
    val shown = linked.firstOrNull() ?: suggested ?: return
    val (title, text) = if (linked.isEmpty()) {
        stringResource(R.string.smarthome_card_connect, shown.name) to stringResource(R.string.smarthome_card_connect_text)
    } else {
        stringResource(R.string.smarthome_title) to stringResource(
            R.string.smarthome_card_linked, linked.joinToString { it.name }, linked.sumOf { choices[it.id]?.selected?.size ?: 0 },
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProviderBadge(shown)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(title, 18.sp, colors.text, weight = FontWeight.Bold)
            CarLabel(text, 15.sp, colors.text2, maxLines = 2)
        }
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
    }
}

@Composable
private fun ProviderBlock(provider: SmartHomeProvider) {
    val link by provider.link.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        provider.LinkCard()
        if (link != LinkState.NOT_LINKED) {
            DevicePicker(provider)
            ArrivalSetup(provider)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.smarthome_unlink), primary = false) { provider.unlink() }
            }
        }
    }
}

/** The devices shown in the car, grouped by area, ticks already set by the pre-selection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePicker(provider: SmartHomeProvider) {
    val colors = LocalCarColors.current
    val scope = rememberCoroutineScope()
    val catalog by provider.catalog.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    val choice = choices[provider.id] ?: ProviderChoice()
    var open by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val selected = choice.selected.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CarLabel(stringResource(R.string.smarthome_devices), 16.sp, colors.text2, weight = FontWeight.Bold)
        val current = catalog
        if (current == null) {
            CarLabel(stringResource(if (refreshing) R.string.smarthome_loading else R.string.smarthome_no_catalog), 15.sp, colors.text2, maxLines = 3)
        } else {
            CarLabel(stringResource(R.string.smarthome_devices_count, selected.size, current.devices.size), 15.sp, colors.text, maxLines = 2)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current != null) Chip(stringResource(if (open) R.string.smarthome_devices_hide else R.string.smarthome_devices_choose), selected = open) { open = !open }
            // Undoes any ticking: what the pre-selection picks, as right after linking
            if (current != null) Chip(stringResource(R.string.smarthome_back_to_suggested), selected = false) { SmartHome.resetSelected(provider.id) }
            Chip(stringResource(R.string.smarthome_refresh), selected = false) {
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        provider.refreshCatalog()
                        refreshing = false
                    }
                }
            }
        }
        if (open && current != null) {
            val byArea = current.devices.groupBy { it.areaId }
            val areas = current.areas.filter { byArea.containsKey(it.id) }.map { it.name to byArea.getValue(it.id) } +
                listOfNotNull(byArea[null]?.let { stringResource(R.string.smarthome_no_area) to it })
            for ((areaName, devices) in areas) {
                CarLabel(areaName, 15.sp, colors.text2, Modifier.padding(top = 4.dp), weight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (device in Preselection.order(devices)) {
                        TickChip(device, ticked = device.id in selected) { SmartHome.toggleSelected(provider.id, device.id) }
                    }
                }
            }
            CarLabel(stringResource(R.string.smarthome_devices_note), 14.sp, colors.text2, maxLines = 3)
        }
    }
}

/**
 * The arrival buttons: one main switch for all, then each of the service's places (home, office...)
 * with its own switch, devices and distance. Switching off keeps the choices; switching a place on is
 * also when the phone's location is asked for.
 */
@Composable
private fun ArrivalSetup(provider: SmartHomeProvider) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val catalog by provider.catalog.collectAsStateWithLifecycle()
    val choices by SmartHome.choices.collectAsStateWithLifecycle()
    val enabled by SmartHome.arrivalEnabled.collectAsStateWithLifecycle()
    val choice = choices[provider.id] ?: ProviderChoice()
    val current = catalog ?: return
    // Permissions change in the system's screens: check again when coming back
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val askLocation = rememberPermissionRequest { resumes++ }
    val hasLocation = remember(resumes) {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    fun askIfMissing() {
        if (!hasLocation) askLocation(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PhoneSwitchLine(stringResource(R.string.smarthome_arrival_title), stringResource(R.string.smarthome_arrival_desc), enabled) {
            SmartHome.setArrivalEnabled(!enabled)
        }
        if (!enabled) return@Column
        if (current.places.isEmpty()) {
            CarLabel(stringResource(R.string.smarthome_arrival_no_home), 15.sp, colors.text2, maxLines = 3)
            return@Column
        }
        // The home first, then the other places in the service's order
        for (place in current.places.sortedBy { !it.home }) {
            PhoneLine()
            PlaceArrivalSetup(provider, current, choice, place) { askIfMissing() }
        }
        if (choice.activePlaces.isNotEmpty()) {
            CarLabel(stringResource(R.string.smarthome_arrival_location_why), 14.sp, colors.text2, maxLines = 4)
            if (!hasLocation) {
                CarLabel(stringResource(R.string.smarthome_arrival_location_missing), 14.sp, colors.text2, maxLines = 4)
                PillButton(stringResource(R.string.action_allow), primary = true) { askIfMissing() }
            }
        }
    }
}

/** One place's arrival buttons: its switch, then (while on) the devices, "Add another device" and the distance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceArrivalSetup(provider: SmartHomeProvider, catalog: SmartCatalog, choice: ProviderChoice, place: SmartPlace, onTurnedOn: () -> Unit) {
    val colors = LocalCarColors.current
    val arrival = choice.arrivalAt(place) ?: PlaceArrival()
    // Saved under the "home" stand-in until the catalog moved it to the place's own id
    val placeId = if (choice.places.containsKey(place.id) || !choice.places.containsKey(ProviderChoice.HOME_PLACE) || !place.home) place.id else ProviderChoice.HOME_PLACE
    var adding by rememberSaveable(place.id) { mutableStateOf(false) }
    val ticked = arrival.devices
    PhoneSwitchLine(place.name, stringResource(R.string.smarthome_arrival_place_desc), arrival.on) {
        val on = !arrival.on
        SmartHome.updatePlace(provider.id, placeId) { it.copy(on = on) }
        if (on) onTurnedOn()
    }
    if (!arrival.on) {
        // Off keeps everything: say what comes back
        if (ticked.isNotEmpty()) {
            val names = catalog.devices.filter { it.id in ticked }.joinToString { it.name }
            CarLabel(stringResource(R.string.smarthome_arrival_kept, names), 14.sp, colors.text2, maxLines = 3)
        }
        return
    }
    // The home's proposal, plus whatever the driver ticked or is adding from the car's devices
    val proposal = remember(catalog) { ArrivalCandidates.propose(catalog) }
    val base = (if (place.home) proposal.devices else emptyList()) + catalog.devices.filter { it.id in ticked }
    val carDevices = SmartHome.chosenDevices(catalog, choice)
    val shown = (base + if (adding) carDevices else emptyList()).distinctBy { it.id }
    if (shown.isEmpty()) CarLabel(stringResource(R.string.smarthome_arrival_pick), 15.sp, colors.text2, maxLines = 3)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (device in shown) TickChip(device, ticked = device.id in ticked) { SmartHome.toggleArrival(provider.id, placeId, device.id) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val more = carDevices.any { d -> base.none { it.id == d.id } }
        if (adding) {
            Chip(stringResource(R.string.smarthome_devices_hide), selected = true) { adding = false }
        } else if (more) {
            Chip(stringResource(R.string.smarthome_arrival_add), selected = false) { adding = true }
        }
        if (place.home) Chip(stringResource(R.string.smarthome_back_to_suggested), selected = false) { SmartHome.resetArrival(provider.id, placeId) }
    }
    if (adding && !carDevices.any { d -> base.none { it.id == d.id } }) {
        CarLabel(stringResource(R.string.smarthome_arrival_add_none), 14.sp, colors.text2, maxLines = 3)
    }
    // The distance is rarely changed: behind this place's "More options", saying the current one
    var more by rememberSaveable(place.id) { mutableStateOf(false) }
    Chip(
        if (more) stringResource(R.string.settings_less) else stringResource(R.string.smarthome_arrival_more, distanceLabel(arrival.offsetM)),
        selected = more,
    ) { more = !more }
    if (more) {
        CarLabel(stringResource(R.string.smarthome_arrival_distance, place.radiusM.toInt()), 15.sp, colors.text2, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in ProviderChoice.OFFSETS_M) {
                Chip(distanceLabel(m), selected = arrival.offsetM == m) { SmartHome.updatePlace(provider.id, placeId) { it.copy(offsetM = m) } }
            }
        }
    }
}

@Composable
private fun distanceLabel(m: Int): String =
    if (m >= 1000) stringResource(R.string.smarthome_km, m / 1000) else stringResource(R.string.smarthome_m, m)

/** A device to tick: its kind icon, its name, a check when chosen. */
@Composable
private fun TickChip(device: SmartDevice, ticked: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (ticked) colors.focus.copy(alpha = 0.22f) else colors.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CarIcon(if (ticked) CarIcons.Check else device.kind.icon, if (ticked) colors.text else colors.text2, Modifier.size(18.dp))
        CarLabel(device.name, 15.sp, colors.text, weight = if (ticked) FontWeight.Bold else FontWeight.SemiBold)
    }
}

/** A one-line text field in the phone's card style. */
@Composable
internal fun PhoneTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Uri,
) {
    val colors = LocalCarColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = CarText.copy(color = colors.text, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.focus),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface2)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) CarLabel(hint, 16.sp, colors.text2.copy(alpha = 0.7f))
                inner()
            }
        },
    )
}

/** The provider's icon in a circle, or our own smart-home icon. */
@Composable
internal fun ProviderBadge(provider: SmartHomeProvider, size: Int = 44) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val icon = remember(provider.id) { provider.icon(context) }
    Box(Modifier.size(size.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size((size * 0.75f).dp).clip(CircleShape))
        else CarIcon(CarIcons.SmartHome, colors.text2, Modifier.size((size / 2).dp))
    }
}
