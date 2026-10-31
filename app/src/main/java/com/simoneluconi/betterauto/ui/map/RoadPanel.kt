package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.map.roads.RoadState
import com.simoneluconi.betterauto.map.roads.RouteRef
import com.simoneluconi.betterauto.map.roads.ShieldKind
import com.simoneluconi.betterauto.map.roads.SpeedLimit
import com.simoneluconi.betterauto.ui.theme.ShieldBlue
import com.simoneluconi.betterauto.ui.theme.ShieldGreen
import com.simoneluconi.betterauto.ui.theme.ShieldRed
import com.simoneluconi.betterauto.ui.theme.ShieldUkYellow
import com.simoneluconi.betterauto.ui.theme.ShieldYellow
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.SignInk
import com.simoneluconi.betterauto.ui.theme.SignRed
import com.simoneluconi.betterauto.ui.theme.SignWhite
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits

/** The speed limit as the road sign shows it: a white disc in a red ring, the number in the driver's units. */
@Composable
fun SpeedLimitSign(limit: SpeedLimit, size: Dp, modifier: Modifier = Modifier) {
    val units = rememberUnits()
    val value = limit.shown(inMph = units.imperial)
    Box(
        modifier
            .size(size)
            .shadow(6.dp, CircleShape)
            .background(SignWhite, CircleShape)
            .border(size * RING, SignRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(
            value.toString(),
            // Three digits take a little less, as on the real signs
            (size.value * if (value >= 100) 0.34f else 0.42f).sp,
            SignInk,
            weight = FontWeight.ExtraBold,
            tabularNumbers = true,
            shrinkToFit = true,
        )
    }
}

/** The road the car is on: its numbers in the colours of the country's signs, then its name. */
@Composable
fun RoadNamePill(road: RoadState, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val name = road.name
    if (name == null && road.refs.isEmpty()) return
    Row(
        modifier
            .widthIn(max = 560.dp)
            .shadow(6.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (ref in road.refs) RoadShield(ref)
        if (name != null) CarLabel(name, 22.sp, colors.text, weight = FontWeight.Bold, shrinkToFit = true)
    }
}

/** One road number as its sign shows it ("A1" white on green in Italy, "B27" black on yellow in Germany...). */
@Composable
fun RoadShield(ref: RouteRef, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val (background, ink) = when (ref.shield) {
        ShieldKind.GREEN -> ShieldGreen to SignWhite
        ShieldKind.BLUE -> ShieldBlue to SignWhite
        ShieldKind.RED -> ShieldRed to SignWhite
        ShieldKind.YELLOW -> ShieldYellow to SignInk
        ShieldKind.UK_PRIMARY -> ShieldGreen to ShieldUkYellow
        ShieldKind.WHITE -> SignWhite to SignInk
        ShieldKind.NEUTRAL -> colors.surface2 to colors.text
    }
    CarLabel(
        ref.ref, 18.sp, ink, weight = FontWeight.ExtraBold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            // A white sign needs its edge on a white pill
            .then(if (ref.shield == ShieldKind.WHITE) Modifier.border(1.5.dp, SignInk, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** How thick the red ring is, as a share of the sign's size. */
private const val RING = 0.11f
