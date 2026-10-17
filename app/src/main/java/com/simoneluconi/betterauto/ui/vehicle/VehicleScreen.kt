package com.simoneluconi.betterauto.ui.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.FaultCode
import com.simoneluconi.betterauto.car.FaultCodes
import com.simoneluconi.betterauto.car.FaultSeverity
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.HeadLights
import com.simoneluconi.betterauto.car.TurnSignal
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleSnapshot
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.widgets.PanelMessage
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Amber of the instrument cluster's engine warning light. */
internal val WarningAmber = Color(0xFFF5A524)
private val StopRed = Color(0xFFE5484D)

/** Opens the vehicle screen straight on its diagnostics (from the status bar engine light). */
object VehicleScreenState {
    val showDiagnostics = MutableStateFlow(false)
}

private class VehicleCard(
    val icon: ImageVector,
    val title: String,
    val rows: List<Pair<String, String>>,
    val at: Long?,
    val warning: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * Everything the car shares, one card per kind of data, only for what arrived. Values the instrument
 * cluster already shows live here and nowhere else in the launcher.
 */
@Composable
fun VehicleScreen(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val snapshot by VehicleData.snapshot.collectAsStateWithLifecycle()
    val diagnosticsOpen by VehicleScreenState.showDiagnostics.collectAsStateWithLifecycle()
    var allData by remember { mutableStateOf(false) }

    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (allData || (diagnosticsOpen && snapshot.diagnostics?.value.orEmpty().isNotEmpty())) Header(snapshot)
        val codes = snapshot.diagnostics?.value.orEmpty()
        if (diagnosticsOpen && codes.isNotEmpty()) {
            Diagnostics(codes, onClose = { VehicleScreenState.showDiagnostics.value = false })
        } else if (allData) {
            AllData(snapshot, onClose = { allData = false })
        } else if (snapshot.info.name == null && !snapshot.connected && !snapshot.hasData) {
            PanelMessage(stringResource(R.string.vehicle_not_connected))
        } else {
            VehicleOverview(snapshot, onAllData = { allData = true }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun Header(snapshot: VehicleSnapshot) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(56.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Car, colors.text, Modifier.size(28.dp))
        }
        Column {
            CarLabel(snapshot.info.name ?: stringResource(R.string.nav_vehicle), 28.sp, colors.text, weight = FontWeight.ExtraBold)
            val state = stringResource(if (snapshot.connected) R.string.vehicle_connected else R.string.vehicle_last_known)
            CarLabel(listOfNotNull(snapshot.info.year, snapshot.info.headUnit, state).joinToString(" · "), 18.sp, colors.text2)
        }
    }
}

/** Every value the car shares, grouped; Back returns to the overview. */
@Composable
private fun AllData(snapshot: VehicleSnapshot, onClose: () -> Unit) {
    val colors = LocalCarColors.current
    val cards = cards(snapshot)
    Column(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .carClickable(CircleShape, stringResource(R.string.music_back), onClick = onClose)
                    .background(colors.surface2, CircleShape)
                    .size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarLabel(stringResource(R.string.vehicle_all_data), 26.sp, colors.text, weight = FontWeight.ExtraBold)
        }
        if (!snapshot.hasData) PanelMessage(stringResource(R.string.vehicle_no_data))
        LazyVerticalGrid(
            GridCells.Adaptive(420.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.title }) { index, card ->
                Card(card, Modifier.contentFocus(enabled = index == 0))
            }
        }
    }
}

@Composable
private fun Card(card: VehicleCard, modifier: Modifier) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            // Every card takes the knob focus, so turning it scrolls through all of them
            .carClickable(shape, card.title, onClick = card.onClick ?: {})
            .background(if (card.warning) WarningAmber.copy(alpha = 0.18f) else colors.surface2, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CarIcon(card.icon, if (card.warning) WarningAmber else colors.text2, Modifier.size(24.dp))
            CarLabel(card.title, 21.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
            if (card.onClick != null) CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
        }
        for ((label, value) in card.rows) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CarLabel(label, 18.sp, colors.text2)
                CarLabel(value, 19.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold, tabularNumbers = true, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun cards(s: VehicleSnapshot): List<VehicleCard> {
    val context = LocalContext.current
    val yes = stringResource(R.string.vehicle_yes)
    val no = stringResource(R.string.vehicle_no)
    val units = rememberUnits()
    fun yn(value: Boolean?) = value?.let { if (it) yes else no }
    val list = ArrayList<VehicleCard>()

    // Faults first: the reason to come here from the engine light
    s.diagnostics?.let { d ->
        val codes = d.value
        list += if (codes.isEmpty()) {
            VehicleCard(CarIcons.Engine, stringResource(R.string.vehicle_diagnostics), listOf(stringResource(R.string.vehicle_faults) to stringResource(R.string.vehicle_no_faults)), d.at)
        } else {
            val described = codes.map { FaultCodes.describe(context, it) }
            VehicleCard(
                CarIcons.Engine, stringResource(R.string.vehicle_diagnostics),
                described.take(3).map { it.code to (it.title ?: stringResource(it.system)) } +
                    listOfNotNull(if (codes.size > 3) stringResource(R.string.vehicle_more_faults, codes.size - 3) to "" else null),
                d.at, warning = true,
            ) { VehicleScreenState.showDiagnostics.value = true }
        }
    }
    s.fuel?.let { f ->
        list += VehicleCard(
            CarIcons.Fuel, stringResource(R.string.vehicle_fuel),
            listOfNotNull(
                f.value.levelPercent?.let { stringResource(R.string.vehicle_fuel_level) to "$it%" },
                f.value.rangeKm?.let { stringResource(R.string.vehicle_range) to units.distance(it.toDouble()) },
                yn(f.value.low)?.let { stringResource(R.string.vehicle_low_fuel) to it },
            ),
            f.at,
        )
    }
    s.odometer?.let { o ->
        list += VehicleCard(
            CarIcons.Gauge, stringResource(R.string.vehicle_distance),
            listOfNotNull(
                stringResource(R.string.vehicle_odometer) to units.distance(o.value.totalKm),
                o.value.tripKm?.let { stringResource(R.string.vehicle_trip) to units.distance(it, decimals = 1) },
            ),
            o.at,
        )
    }
    s.tires?.let { t ->
        val names = listOf(R.string.vehicle_tire_fl, R.string.vehicle_tire_fr, R.string.vehicle_tire_rl, R.string.vehicle_tire_rr).map { stringResource(it) }
        list += VehicleCard(
            CarIcons.Tire, stringResource(R.string.vehicle_tires),
            t.value.bar.mapIndexed { i, bar ->
                (if (t.value.bar.size == 4) names[i] else stringResource(R.string.vehicle_tire_n, i + 1)) to units.tirePressure(bar)
            },
            t.at,
        )
    }
    s.environment?.let { e ->
        list += VehicleCard(
            CarIcons.Thermometer, stringResource(R.string.vehicle_outside),
            listOfNotNull(
                e.value.temperatureC?.let { stringResource(R.string.vehicle_temperature) to units.temperature(it) },
                e.value.pressureKpa?.let { stringResource(R.string.vehicle_pressure) to units.airPressure(it) },
                e.value.rain?.let { stringResource(R.string.vehicle_rain) to if (it > 0) yes else no },
            ),
            e.at,
        )
    }
    s.hvac?.let { h ->
        list += VehicleCard(
            CarIcons.Thermometer, stringResource(R.string.vehicle_climate),
            listOfNotNull(
                h.value.targetC?.let { stringResource(R.string.vehicle_climate_set) to units.temperature(it) },
                h.value.currentC?.let { stringResource(R.string.vehicle_climate_cabin) to units.temperature(it) },
            ),
            h.at,
        )
    }
    s.doors?.let { d ->
        val open = d.value.doorsOpen.count { it }
        list += VehicleCard(
            CarIcons.Door, stringResource(R.string.vehicle_doors),
            listOfNotNull(
                stringResource(R.string.vehicle_doors_open) to if (open == 0) stringResource(R.string.vehicle_all_closed) else open.toString(),
                yn(d.value.hoodOpen)?.let { stringResource(R.string.vehicle_hood_open) to it },
                yn(d.value.trunkOpen)?.let { stringResource(R.string.vehicle_trunk_open) to it },
            ),
            d.at,
        )
    }
    s.lights?.let { l ->
        list += VehicleCard(
            CarIcons.Light, stringResource(R.string.vehicle_lights),
            listOfNotNull(
                l.value.head?.let {
                    stringResource(R.string.vehicle_headlights) to stringResource(
                        when (it) {
                            HeadLights.OFF -> R.string.vehicle_off
                            HeadLights.ON -> R.string.vehicle_on
                            HeadLights.HIGH -> R.string.vehicle_high_beam
                        }
                    )
                },
                l.value.turn?.let {
                    stringResource(R.string.vehicle_turn_signal) to stringResource(
                        when (it) {
                            TurnSignal.NONE -> R.string.vehicle_off
                            TurnSignal.LEFT -> R.string.vehicle_left
                            TurnSignal.RIGHT -> R.string.vehicle_right
                        }
                    )
                },
                yn(l.value.hazard)?.let { stringResource(R.string.vehicle_hazard) to it },
            ),
            l.at,
        )
    }
    if (s.gear != null || s.parkingBrake != null || s.drivingStatus != null || s.speed != null || s.rpm != null) {
        list += VehicleCard(
            CarIcons.Car, stringResource(R.string.vehicle_driving),
            listOfNotNull(
                s.drivingStatus?.let { stringResource(R.string.vehicle_state) to stringResource(if (it.value == 0) R.string.vehicle_unrestricted else R.string.vehicle_restricted) },
                s.gear?.let { g ->
                    stringResource(R.string.vehicle_gear) to when (g.value.position) {
                        GearPosition.PARK -> "P"
                        GearPosition.REVERSE -> "R"
                        GearPosition.NEUTRAL -> "N"
                        GearPosition.DRIVE -> "D"
                        GearPosition.MANUAL -> g.value.manualGear?.toString() ?: "?"
                    }
                },
                yn(s.parkingBrake?.value)?.let { stringResource(R.string.vehicle_parking_brake) to it },
                s.speed?.let { stringResource(R.string.vehicle_speed) to units.speed(it.value.kmh) },
                s.speed?.value?.cruiseOn?.let { on ->
                    stringResource(R.string.vehicle_cruise) to if (on) (s.speed.value.cruiseSetKmh?.let { units.speed(it.toDouble()) } ?: yes) else no
                },
                s.rpm?.let { stringResource(R.string.vehicle_rpm) to "%,d".format(Locale.getDefault(), it.value) },
            ),
            listOfNotNull(s.gear?.at, s.parkingBrake?.at, s.drivingStatus?.at, s.speed?.at, s.rpm?.at).maxOrNull(),
        )
    }
    if (s.position != null || s.satellites != null) {
        val p = s.position?.value
        list += VehicleCard(
            CarIcons.Map, stringResource(R.string.vehicle_position),
            listOfNotNull(
                p?.let { stringResource(R.string.vehicle_coordinates) to "%.5f, %.5f".format(Locale.ROOT, it.latitude, it.longitude) },
                p?.accuracyM?.let { stringResource(R.string.vehicle_accuracy) to "± ${units.length(it)}" },
                p?.altitudeM?.let { stringResource(R.string.vehicle_altitude) to units.length(it) },
                p?.bearing?.let { stringResource(R.string.vehicle_heading) to "%.0f°".format(Locale.getDefault(), it) },
                s.satellites?.let { sat -> stringResource(R.string.vehicle_satellites) to (sat.value.inView?.let { "${sat.value.inUse} / $it" } ?: sat.value.inUse.toString()) },
            ),
            listOfNotNull(s.position?.at, s.satellites?.at).maxOrNull(),
        )
    }
    s.motion?.let { m ->
        list += VehicleCard(
            CarIcons.Gauge, stringResource(R.string.vehicle_motion),
            listOfNotNull(
                m.value.compassBearing?.let { stringResource(R.string.vehicle_compass) to "%.0f°".format(Locale.getDefault(), it) },
                m.value.steeringAngle?.let { stringResource(R.string.vehicle_steering) to "%.0f°".format(Locale.getDefault(), it) },
                m.value.acceleration?.let { (x, y, z) -> stringResource(R.string.vehicle_acceleration) to "%.1f / %.1f / %.1f".format(Locale.getDefault(), x, y, z) },
                m.value.rotation?.let { (x, y, z) -> stringResource(R.string.vehicle_rotation) to "%.2f / %.2f / %.2f".format(Locale.getDefault(), x, y, z) },
            ),
            m.at,
        )
    }
    if (s.passengerPresent != null || s.tollCardPresent != null) {
        list += VehicleCard(
            CarIcons.Contacts, stringResource(R.string.vehicle_other),
            listOfNotNull(
                yn(s.passengerPresent?.value)?.let { stringResource(R.string.vehicle_passenger) to it },
                yn(s.tollCardPresent?.value)?.let { stringResource(R.string.vehicle_toll_card) to it },
            ),
            listOfNotNull(s.passengerPresent?.at, s.tollCardPresent?.at).maxOrNull(),
        )
    }
    // What the car says about itself, always available once connected
    list += VehicleCard(
        CarIcons.Settings, stringResource(R.string.vehicle_info),
        listOfNotNull(
            s.info.name?.let { stringResource(R.string.vehicle_model) to it },
            s.info.year?.let { stringResource(R.string.vehicle_year) to it },
            s.info.fuelTypes.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.vehicle_fuel_types) to it.map { f -> fuelName(f) }.joinToString() },
            s.info.evConnectors.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.vehicle_ev_connectors) to it.joinToString() },
            s.info.headUnit?.let { stringResource(R.string.vehicle_head_unit) to it },
            s.info.headUnitSoftware?.let { stringResource(R.string.vehicle_software) to it },
            stringResource(R.string.vehicle_data_offered) to s.offered.size.toString(),
        ),
        null,
    )
    return list.filter { it.rows.isNotEmpty() }
}

@Composable
private fun fuelName(type: String): String = when (type) {
    "UNLEADED", "LEADED" -> stringResource(R.string.vehicle_fuel_petrol)
    "DIESEL_1", "DIESEL_2", "BIODIESEL" -> stringResource(R.string.vehicle_fuel_diesel)
    "ELECTRIC" -> stringResource(R.string.vehicle_fuel_electric)
    "LPG" -> stringResource(R.string.vehicle_fuel_lpg)
    "CNG", "LNG" -> stringResource(R.string.vehicle_fuel_methane)
    "HYDROGEN" -> stringResource(R.string.vehicle_fuel_hydrogen)
    else -> type.lowercase().replaceFirstChar { it.uppercase() }
}

/** The fault codes, explained: what it is, which system, how urgent. Back returns to the cards. */
@Composable
private fun Diagnostics(codes: List<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val described = remember(codes) { codes.map { FaultCodes.describe(context, it) } }
    Column(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .carClickable(CircleShape, stringResource(R.string.music_back), onClick = onClose)
                    .background(colors.surface2, CircleShape)
                    .size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarIcon(CarIcons.Engine, WarningAmber, Modifier.size(30.dp))
            CarLabel(stringResource(R.string.vehicle_diagnostics), 26.sp, colors.text, weight = FontWeight.ExtraBold)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(described, key = { it.code }) { fault -> FaultRow(fault, primary = fault === described.first()) }
        }
    }
}

@Composable
private fun FaultRow(fault: FaultCode, primary: Boolean) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    val (severityText, severityColor) = when (fault.severity) {
        FaultSeverity.STOP -> stringResource(R.string.vehicle_severity_stop) to StopRed
        FaultSeverity.CHECK -> stringResource(R.string.vehicle_severity_check) to WarningAmber
        FaultSeverity.MINOR -> stringResource(R.string.vehicle_severity_minor) to colors.text2
    }
    val title = fault.title ?: listOfNotNull(stringResource(fault.system), fault.area?.let { stringResource(it) }).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .contentFocus(enabled = primary)
            .carClickable(shape, "${fault.code}, $title, $severityText") {}
            .background(colors.surface2, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CarLabel(fault.code, 26.sp, colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(title, 21.sp, colors.text, weight = FontWeight.Bold, maxLines = 2)
            val detail = listOfNotNull(
                stringResource(fault.system).takeIf { fault.title != null },
                stringResource(R.string.vehicle_manufacturer_code).takeIf { fault.manufacturerSpecific },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) CarLabel(detail, 17.sp, colors.text2)
        }
        Box(Modifier.background(severityColor.copy(alpha = 0.2f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)) {
            CarLabel(severityText, 17.sp, if (severityColor == colors.text2) colors.text else severityColor, weight = FontWeight.Bold)
        }
    }
}


/** True when the car reports fault codes now; drives the engine light in the status bar. */
fun hasFaults(snapshot: VehicleSnapshot): Boolean = snapshot.diagnostics?.value?.isNotEmpty() == true
