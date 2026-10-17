package com.simoneluconi.betterauto.ui.vehicle

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.HeadLights
import com.simoneluconi.betterauto.car.TurnSignal
import com.simoneluconi.betterauto.car.Units
import com.simoneluconi.betterauto.car.VehicleSnapshot
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

private val OkGreen = Color(0xFF2E9E5B)

/** A big number on the vehicle overview. */
private class Tile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val detail: String?,
    val tint: Color? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * Vehicle overview, like a car's own vehicle status screen: the car seen from above with what the car
 * reports on it (tire pressures at the wheels, open doors, lights), and a few big tiles beside it.
 * Everything else is one tile away, in the full list.
 */
@Composable
internal fun VehicleOverview(s: VehicleSnapshot, onAllData: () -> Unit, modifier: Modifier = Modifier) {
    val tiles = tiles(s)
    val side = @Composable { mod: Modifier ->
        Column(mod, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TileGrid(tiles, Modifier.weight(1f).fillMaxWidth())
            AllDataButton(onAllData)
        }
    }
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CarDiagram(s, Modifier.weight(0.9f).fillMaxHeight())
                side(Modifier.weight(1.1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                CarDiagram(s, Modifier.weight(1f).fillMaxWidth())
                side(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The full list of values, as a slim bar under the tiles. */
@Composable
private fun AllDataButton(onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    val label = stringResource(R.string.vehicle_all_data)
    Row(
        Modifier
            .fillMaxWidth()
            .carClickable(shape, label, onClick = onClick)
            .background(colors.surface2, shape)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CarIcon(CarIcons.List, colors.text2, Modifier.size(24.dp))
        CarLabel(label, 20.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
    }
}

/** Two columns of tiles that share the whole height, so a car with little data has bigger tiles, not gaps. */
@Composable
private fun TileGrid(tiles: List<Tile>, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        // As many rows as fit with readable tiles (up to three); the rest is in "all data"
        val rows = ((maxHeight + GAP) / (MIN_TILE_HEIGHT + GAP)).toInt().coerceIn(1, 3)
        val shown = tiles.take(rows * 2)
        val rowCount = (shown.size + 1) / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP)) {
            shown.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    pair.forEachIndexed { column, tile ->
                        TileView(tile, Modifier.weight(1f).fillMaxHeight().contentFocus(enabled = row == 0 && column == 0))
                    }
                }
            }
            // Few tiles: they stay a readable size instead of stretching into empty boxes
            if (rowCount < rows) Spacer(Modifier.weight(if (rowCount == 1) 1f else (rows - rowCount).toFloat()))
        }
    }
}

@Composable
private fun TileView(tile: Tile, modifier: Modifier) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(26.dp)
    val tint = tile.tint
    BoxWithConstraints(
        modifier
            .carClickable(shape, "${tile.label}: ${tile.value}", onClick = tile.onClick ?: {})
            .background(if (tint != null) tint.copy(alpha = 0.16f) else colors.surface2, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        // Text sizes follow the room the tile has, so nothing gets cut at the bottom
        val compact = maxHeight < 130.dp
        val detailLines = ((maxHeight - (if (compact) 70.dp else 84.dp)) / 24.dp).toInt().coerceIn(0, 3)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CarIcon(tile.icon, tint ?: colors.text2, Modifier.size(22.dp))
                CarLabel(tile.label, 18.sp, colors.text2, Modifier.weight(1f), weight = FontWeight.Bold)
                if (tile.onClick != null) CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
            }
            Column {
                CarLabel(tile.value, if (compact) 32.sp else 40.sp, tint ?: colors.text, weight = FontWeight.ExtraBold, tabularNumbers = true)
                if (detailLines > 0) tile.detail?.let { CarLabel(it, 17.sp, colors.text2, maxLines = detailLines) }
            }
        }
    }
}

@Composable
private fun tiles(s: VehicleSnapshot): List<Tile> {
    val units = rememberUnits()
    val list = ArrayList<Tile>()
    s.diagnostics?.let { d ->
        list += if (d.value.isEmpty()) {
            Tile(CarIcons.Engine, stringResource(R.string.vehicle_diagnostics), stringResource(R.string.vehicle_ok), stringResource(R.string.vehicle_no_faults_long), OkGreen)
        } else {
            Tile(
                CarIcons.Engine, stringResource(R.string.vehicle_diagnostics), d.value.size.toString(),
                d.value.take(3).joinToString(", "), WarningAmber,
            ) { VehicleScreenState.showDiagnostics.value = true }
        }
    }
    s.fuel?.value?.let { f ->
        val value = f.rangeKm?.let { units.distance(it.toDouble()) } ?: f.levelPercent?.let { "$it%" }
        if (value != null) {
            val detail = listOfNotNull(
                f.levelPercent?.takeIf { f.rangeKm != null }?.let { stringResource(R.string.vehicle_fuel_detail, it) },
                stringResource(R.string.vehicle_low_fuel).takeIf { f.low == true },
            ).joinToString(" · ").ifEmpty { null }
            list += Tile(CarIcons.Fuel, stringResource(if (f.rangeKm != null) R.string.vehicle_range else R.string.vehicle_fuel), value, detail, WarningAmber.takeIf { f.low == true })
        }
    }
    s.environment?.value?.temperatureC?.let { t ->
        val e = s.environment.value
        val detail = listOfNotNull(
            e.rain?.takeIf { it > 0 }?.let { stringResource(R.string.vehicle_rain) },
            e.pressureKpa?.let { units.airPressure(it) },
        ).joinToString(" · ").ifEmpty { null }
        list += Tile(CarIcons.Thermometer, stringResource(R.string.vehicle_outside), units.temperature(t, decimals = 0), detail)
    }
    s.odometer?.value?.let { o ->
        list += Tile(
            CarIcons.Gauge, stringResource(R.string.vehicle_distance), units.distance(o.totalKm),
            o.tripKm?.let { stringResource(R.string.vehicle_trip_detail, units.distance(it, decimals = 1)) },
        )
    }
    s.hvac?.value?.let { h ->
        val value = h.targetC ?: h.currentC
        if (value != null) {
            list += Tile(
                CarIcons.Thermometer, stringResource(R.string.vehicle_climate), units.temperature(value),
                h.currentC?.takeIf { h.targetC != null }?.let { stringResource(R.string.vehicle_cabin_detail, units.temperature(it)) },
            )
        }
    }
    s.gear?.value?.let { g ->
        val value = when (g.position) {
            GearPosition.PARK -> "P"
            GearPosition.REVERSE -> "R"
            GearPosition.NEUTRAL -> "N"
            GearPosition.DRIVE -> "D"
            GearPosition.MANUAL -> g.manualGear?.toString() ?: "?"
        }
        list += Tile(
            CarIcons.Car, stringResource(R.string.vehicle_gear), value,
            s.parkingBrake?.value?.let { stringResource(if (it) R.string.vehicle_parking_brake_on else R.string.vehicle_parking_brake_off) },
        )
    }
    s.satellites?.value?.let { sat ->
        list += Tile(
            CarIcons.Map, stringResource(R.string.vehicle_gps), stringResource(R.string.vehicle_satellites_value, sat.inUse),
            s.position?.value?.accuracyM?.let { stringResource(R.string.vehicle_accuracy_detail, units.length(it)) },
        )
    }
    // A car that shares little still says what it is, and that it shares little
    if (list.size < 3) {
        list += Tile(
            CarIcons.Settings, stringResource(R.string.vehicle_info), s.info.model ?: s.info.make ?: "—",
            listOfNotNull(
                listOfNotNull(s.info.make?.takeIf { s.info.model != null }, s.info.year).joinToString(" · ").ifEmpty { null },
                s.info.headUnit,
                stringResource(R.string.vehicle_little_data).takeIf { list.isEmpty() },
            ).joinToString("\n").ifEmpty { null },
        )
    }
    return list
}

/**
 * The car from above, drawn to fill its space, with tire pressures beside the wheels and what's open
 * or lit on the car itself. With no data it's just the car.
 */
@Composable
private fun CarDiagram(s: VehicleSnapshot, modifier: Modifier) {
    val colors = LocalCarColors.current
    val units = rememberUnits()
    val tires = s.tires?.value?.bar?.takeIf { it.size == 4 }
    val low = tires?.let { values -> values.indices.filter { values.max() - values[it] >= LOW_TIRE_BAR } }.orEmpty()
    Column(
        modifier
            .background(colors.surface2, RoundedCornerShape(28.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarLabel(s.info.name ?: stringResource(R.string.nav_vehicle), 24.sp, colors.text, weight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TireColumn(tires, units, left = true, low = low, modifier = Modifier.weight(1f).fillMaxHeight())
            CarDrawing(s, Modifier.fillMaxHeight().aspectRatio(0.5f, matchHeightConstraintsFirst = true))
            TireColumn(tires, units, left = false, low = low, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        CarLabel(diagramCaption(s), 19.sp, if (anythingOpen(s)) WarningAmber else colors.text2, weight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** Front and rear pressure on one side, level with the wheels of the drawing. */
@Composable
private fun TireColumn(tires: List<Double>?, units: Units, left: Boolean, low: List<Int>, modifier: Modifier) {
    val colors = LocalCarColors.current
    val front = if (left) 0 else 1
    val rear = if (left) 2 else 3
    Column(modifier, horizontalAlignment = if (left) Alignment.End else Alignment.Start) {
        Spacer(Modifier.weight(0.15f))
        TireLabel(tires?.getOrNull(front), units, front in low, colors.text)
        Spacer(Modifier.weight(0.52f))
        TireLabel(tires?.getOrNull(rear), units, rear in low, colors.text)
        Spacer(Modifier.weight(0.15f))
    }
}

@Composable
private fun TireLabel(bar: Double?, units: Units, low: Boolean, color: Color) {
    val colors = LocalCarColors.current
    if (bar == null) {
        Spacer(Modifier.size(1.dp))
        return
    }
    Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CarLabel(units.tirePressureValue(bar), 32.sp, if (low) WarningAmber else color, weight = FontWeight.ExtraBold, tabularNumbers = true)
        CarLabel(units.tirePressureUnit, 16.sp, colors.text2)
    }
}

@Composable
private fun diagramCaption(s: VehicleSnapshot): String {
    val d = s.doors?.value
    val open = buildList {
        if (d?.hoodOpen == true) add(stringResource(R.string.vehicle_hood))
        if (d?.trunkOpen == true) add(stringResource(R.string.vehicle_trunk))
        val doors = d?.doorsOpen?.count { it } ?: 0
        if (doors > 0) add(stringResource(R.string.vehicle_open_doors_count, doors))
    }
    return when {
        open.isNotEmpty() -> stringResource(R.string.vehicle_open_now, open.joinToString(", "))
        d != null -> stringResource(R.string.vehicle_all_closed_long)
        s.tires != null -> stringResource(R.string.vehicle_tires)
        else -> listOfNotNull(s.info.year, s.info.headUnit).joinToString(" · ").ifEmpty { stringResource(R.string.nav_vehicle) }
    }
}

private fun anythingOpen(s: VehicleSnapshot): Boolean {
    val d = s.doors?.value ?: return false
    return d.hoodOpen == true || d.trunkOpen == true || d.doorsOpen.any { it }
}

/** Drawn on a 100 x 200 grid, front up. */
@Composable
private fun CarDrawing(s: VehicleSnapshot, modifier: Modifier) {
    val colors = LocalCarColors.current
    val blink by rememberInfiniteTransition(label = "turn").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "turnBlink",
    )
    val doors = s.doors?.value
    val lights = s.lights?.value
    Canvas(modifier) {
        val u = size.width / 100f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val line = colors.text2
        val stroke = Stroke(width = 2.6f * u, cap = StrokeCap.Round)

        // Wheels under the body
        for ((x, y) in listOf(14f to 30f, 78f to 30f, 14f to 140f, 78f to 140f)) {
            drawRoundRect(colors.text, p(x, y), Size(8f * u, 28f * u), CornerRadius(3f * u))
        }
        // Body
        drawRoundRect(colors.surface, p(20f, 8f), Size(60f * u, 184f * u), CornerRadius(22f * u))
        drawRoundRect(line, p(20f, 8f), Size(60f * u, 184f * u), CornerRadius(22f * u), style = stroke)
        // Hood and trunk, amber when open
        if (doors?.hoodOpen == true) drawPanel(p(20f, 8f), 60f * u, 44f * u, 22f * u, top = true, WarningAmber.copy(alpha = 0.45f))
        if (doors?.trunkOpen == true) drawPanel(p(20f, 158f), 60f * u, 34f * u, 22f * u, top = false, WarningAmber.copy(alpha = 0.45f))
        drawLine(line, p(24f, 52f), p(76f, 52f), stroke.width)
        // Glass
        val glass = colors.line
        drawPath(trapezoid(p(27f, 58f), p(73f, 58f), p(68f, 80f), p(32f, 80f)), glass)
        drawRoundRect(glass.copy(alpha = 0.5f), p(32f, 84f), Size(36f * u, 50f * u), CornerRadius(6f * u))
        drawPath(trapezoid(p(32f, 138f), p(68f, 138f), p(73f, 154f), p(27f, 154f)), glass)
        // Doors: an open door swings out from its hinge
        val doorOpen = doors?.doorsOpen.orEmpty()
        val hinges = listOf(
            Triple(p(20f, 82f), p(20f, 112f), -1f), Triple(p(80f, 82f), p(80f, 112f), 1f),
            Triple(p(20f, 113f), p(20f, 142f), -1f), Triple(p(80f, 113f), p(80f, 142f), 1f),
        )
        hinges.forEachIndexed { i, (hinge, end, side) ->
            if (doorOpen.getOrNull(i) == true) {
                val length = end.y - hinge.y
                drawLine(WarningAmber, hinge, Offset(hinge.x + side * length * 0.55f, hinge.y + length * 0.8f), 4f * u, StrokeCap.Round)
            } else {
                drawLine(line, Offset(hinge.x + -side * 1.5f * u, end.y), Offset(hinge.x + -side * 6f * u, end.y), 1.6f * u)
            }
        }
        // Headlights
        val head = when (lights?.head) {
            HeadLights.HIGH -> Color(0xFF9CC8FF)
            HeadLights.ON -> Color(0xFFFFF3C4)
            else -> null
        }
        for (x in listOf(27f, 63f)) {
            drawRoundRect(head ?: line.copy(alpha = 0.5f), p(x, 11f), Size(10f * u, 4f * u), CornerRadius(2f * u))
        }
        // Turn signals and hazards, blinking at the corners
        val left = lights?.hazard == true || lights?.turn == TurnSignal.LEFT
        val right = lights?.hazard == true || lights?.turn == TurnSignal.RIGHT
        for ((on, x) in listOf(left to 22f, right to 78f)) {
            if (!on) continue
            drawCircle(WarningAmber.copy(alpha = blink), 4f * u, p(x, 22f))
            drawCircle(WarningAmber.copy(alpha = blink), 4f * u, p(x, 178f))
        }
    }
}

private fun trapezoid(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}

/** Highlights the hood or trunk area, following the body's rounded end. */
private fun DrawScope.drawPanel(topLeft: Offset, width: Float, height: Float, radius: Float, top: Boolean, color: Color) {
    withTransform({ clipRect(topLeft.x, topLeft.y, topLeft.x + width, topLeft.y + height) }) {
        val y = if (top) topLeft.y else topLeft.y + height - height * 2
        drawRoundRect(color, Offset(topLeft.x, y), Size(width, height * 2), CornerRadius(radius))
    }
}

private val GAP = 14.dp
private val MIN_TILE_HEIGHT = 112.dp
private const val LOW_TIRE_BAR = 0.25
