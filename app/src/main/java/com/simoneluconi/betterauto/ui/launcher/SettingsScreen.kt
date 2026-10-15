package com.simoneluconi.betterauto.ui.launcher

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.BarPosition
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.ThemeMode
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

@Composable
fun SettingsScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val theme by settings.themeMode.collectAsStateWithLifecycle()
    val bar by settings.barPosition.collectAsStateWithLifecycle()
    val layout by settings.layout.collectAsStateWithLifecycle()
    val debug by settings.debugOverlay.collectAsStateWithLifecycle()
    val autoPlay by settings.autoPlay.collectAsStateWithLifecycle()

    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        CarLabel(stringResource(R.string.settings_title), 36.sp, colors.text, weight = FontWeight.ExtraBold)
        Setting(R.string.settings_theme, ThemeMode.entries, theme, {
            stringResource(
                when (it) {
                    ThemeMode.AUTO -> R.string.option_auto
                    ThemeMode.DAY -> R.string.option_day
                    ThemeMode.NIGHT -> R.string.option_night
                }
            )
        }) { settings.setThemeMode(it) }
        Setting(R.string.settings_app_bar, BarPosition.entries, bar, {
            stringResource(
                when (it) {
                    BarPosition.AUTO -> R.string.option_auto
                    BarPosition.LEFT -> R.string.option_left
                    BarPosition.BOTTOM -> R.string.option_bottom
                }
            )
        }) { settings.setBarPosition(it) }
        Setting(R.string.settings_home_layout, HomeLayout.entries, layout, {
            stringResource(
                when (it) {
                    HomeLayout.SINGLE -> R.string.layout_one
                    HomeLayout.SPLIT -> R.string.layout_two
                    HomeLayout.TRIO -> R.string.layout_three
                }
            )
        }) { settings.setLayout(it) }
        Setting(R.string.settings_widget_order, listOf(Unit), null, { stringResource(R.string.rotate_widgets) }) {
            settings.rotateWidgets()
        }
        Setting(R.string.settings_auto_play, listOf(false, true), autoPlay, {
            stringResource(if (it) R.string.option_on else R.string.option_off)
        }) { settings.setAutoPlay(it) }
        Setting(R.string.settings_debug, listOf(false, true), debug, {
            stringResource(if (it) R.string.option_on else R.string.option_off)
        }) { settings.setDebugOverlay(it) }
        BarApps(settings)
    }
}

/** Media apps on the phone; the selected ones are pinned to the app bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarApps(settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val pinned by settings.pinnedApps.collectAsStateWithLifecycle()
    val apps = remember { MediaApps.installed(context).mapNotNull { MediaApps.info(context, it) } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(stringResource(R.string.settings_bar_apps), 26.sp, colors.text2, weight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (app in apps) {
                val shape = RoundedCornerShape(50)
                val isPinned = app.packageName in pinned
                Row(
                    Modifier
                        .carClickable(shape, if (isPinned) stringResource(R.string.app_pinned, app.label) else app.label) {
                            settings.togglePinned(app.packageName)
                        }
                        .background(if (isPinned) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(64.dp)
                        .padding(start = 12.dp, end = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
                    CarLabel(app.label, 22.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun <T> Setting(
    @StringRes titleRes: Int,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = LocalCarColors.current
    val title = stringResource(titleRes)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(title, 26.sp, colors.text2, weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (option in options) {
                val shape = RoundedCornerShape(50)
                val isSelected = option == selected
                Box(
                    Modifier
                        .carClickable(shape, stringResource(R.string.settings_option, title, label(option))) { onSelect(option) }
                        .background(if (isSelected) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(64.dp)
                        .padding(horizontal = 30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarLabel(label(option), 22.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
    }
}
