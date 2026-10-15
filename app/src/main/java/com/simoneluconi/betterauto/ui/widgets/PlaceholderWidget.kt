package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.settings.WidgetType
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** Reserved slot for a widget that is not built yet: today only the map. */
@Composable
fun PlaceholderWidget(type: WidgetType, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val (title, icon) = when (type) {
        WidgetType.MAP -> R.string.widget_map to CarIcons.Map
        WidgetType.MUSIC -> R.string.nav_music to CarIcons.Music
        WidgetType.NOTIFICATIONS -> R.string.widget_notifications to CarIcons.Bell
    }
    val caption = R.string.widget_slot_next
    val grid = type == WidgetType.MAP
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .drawBehind {
                if (!grid) return@drawBehind
                val step = 56.dp.toPx()
                val stroke = 1.dp.toPx()
                var x = step
                while (x < size.width) {
                    drawLine(colors.line, Offset(x, 0f), Offset(x, size.height), stroke)
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(colors.line, Offset(0f, y), Offset(size.width, y), stroke)
                    y += step
                }
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(80.dp)
                .background(colors.surface, CircleShape)
                .border(2.dp, colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(icon, colors.text, Modifier.size(38.dp))
        }
        CarLabel(
            stringResource(title), 30.sp, colors.text, weight = FontWeight.ExtraBold,
            modifier = Modifier.background(colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
        )
        CarLabel(
            stringResource(caption), 19.sp, colors.text2, textAlign = TextAlign.Center,
            modifier = Modifier.background(colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
        )
    }
}
