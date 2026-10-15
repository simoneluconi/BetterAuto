package com.simoneluconi.betterauto.ui.launcher

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

private const val MAX_APPS = 3

/** Sections plus pinned/recent media apps, on the left (wide screens) or at the bottom. */
@Composable
fun AppRail(settings: LauncherSettings, section: Section, vertical: Boolean, firstFocus: FocusRequester) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val pinned by settings.pinnedApps.collectAsStateWithLifecycle()
    val recent by settings.recentApps.collectAsStateWithLifecycle()
    val apps = remember(pinned, recent) {
        val chosen = (pinned + recent.filter { it !in pinned }).ifEmpty { MediaApps.suggested(context) }
        chosen.mapNotNull { MediaApps.info(context, it) }.take(MAX_APPS)
    }

    // spacerModifier comes from the Column/Row scope (weight) and pushes Settings to the far end
    val items: @Composable (spacerModifier: Modifier) -> Unit = { spacerModifier ->
        for ((target, icon, label) in Destinations) {
            val selected = section == target
            RailButton(
                icon = icon,
                label = stringResource(label),
                selected = selected,
                // The knob starts from the current section, or from Home when it has no button here
                modifier = if (selected || (target == Section.HOME && Destinations.none { it.section == section } && section != Section.SETTINGS)) {
                    Modifier.focusRequester(firstFocus)
                } else {
                    Modifier
                },
            ) { settings.setSection(target) }
        }
        RailDivider(vertical)
        for (app in apps) {
            val isPinned = app.packageName in pinned
            Box(
                Modifier
                    .carClickable(
                        RoundedCornerShape(22.dp),
                        if (isPinned) stringResource(R.string.app_pinned, app.label) else app.label,
                        onLongClick = { settings.togglePinned(app.packageName) },
                    ) {
                        NowPlaying.open(app.packageName)
                        settings.setSection(Section.MUSIC)
                    }
                    .size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(app.icon, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
                if (isPinned) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(12.dp)
                            .background(colors.focus, CircleShape)
                    )
                }
            }
        }
        Spacer(spacerModifier)
        RailButton(
            CarIcons.Settings, stringResource(R.string.nav_settings), section == Section.SETTINGS,
            modifier = if (section == Section.SETTINGS) Modifier.focusRequester(firstFocus) else Modifier,
        ) { settings.setSection(Section.SETTINGS) }
    }

    val surface = Modifier
        .background(colors.surface)
        .drawBehind {
            val stroke = 1.dp.toPx()
            if (vertical) drawLine(colors.line, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
            else drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), stroke)
        }
        .padding(16.dp)
    if (vertical) {
        Column(
            surface.fillMaxHeight().width(112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { items(Modifier.weight(1f)) }
    } else {
        Row(
            surface.fillMaxWidth().height(104.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { items(Modifier.weight(1f)) }
    }
}

private data class Destination(val section: Section, val icon: ImageVector, @StringRes val label: Int)

private val Destinations = listOf(
    Destination(Section.HOME, CarIcons.Home, R.string.nav_home),
    Destination(Section.MUSIC, CarIcons.Music, R.string.nav_music),
    Destination(Section.MAP, CarIcons.Map, R.string.nav_map),
    Destination(Section.PHONE, CarIcons.Phone, R.string.nav_phone),
)

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .carClickable(shape, label, onClick = onClick)
            .background(if (selected) colors.focus.copy(alpha = 0.2f) else colors.surface, shape)
            .size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (selected) colors.text else colors.text2, Modifier.size(34.dp))
    }
}

@Composable
private fun RailDivider(vertical: Boolean) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .padding(if (vertical) 6.dp else 0.dp, if (vertical) 0.dp else 6.dp)
            .then(if (vertical) Modifier.width(44.dp).height(2.dp) else Modifier.width(2.dp).height(44.dp))
            .background(colors.line, RoundedCornerShape(1.dp))
    )
}
