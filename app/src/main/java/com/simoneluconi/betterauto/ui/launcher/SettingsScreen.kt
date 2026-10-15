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
import com.simoneluconi.betterauto.settings.ActionSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.PinnedAppsSetting
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The car's settings screen: everything in [SettingsCatalog], in the car's style. */
@Composable
fun SettingsScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        CarLabel(stringResource(R.string.settings_title), 36.sp, colors.text, weight = FontWeight.ExtraBold)
        for (item in SettingsCatalog.all) {
            when (item) {
                is ChoiceSetting<*> -> ChoiceRow(item, settings)
                is ActionSetting -> Setting(item.title, listOf(Unit), null, { stringResource(item.label) }) {
                    item.run(settings, SettingsSurface.CAR)
                }
                is PinnedAppsSetting -> BarApps(item, settings)
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(item: ChoiceSetting<T>, settings: LauncherSettings) {
    val selected by item.state(settings).collectAsStateWithLifecycle()
    Setting(item.title, item.options, item.options.firstOrNull { it.value == selected }, { stringResource(it.label) }) {
        item.select(settings, it.value, SettingsSurface.CAR)
    }
}

/** Media apps on the phone; the selected ones are pinned to the app bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarApps(item: PinnedAppsSetting, settings: LauncherSettings) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val pinned by settings.pinnedApps.collectAsStateWithLifecycle()
    val apps = remember { MediaApps.installed(context).mapNotNull { MediaApps.info(context, it) } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(stringResource(item.title), 26.sp, colors.text2, weight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (app in apps) {
                val shape = RoundedCornerShape(50)
                val isPinned = app.packageName in pinned
                Row(
                    Modifier
                        .carClickable(shape, if (isPinned) stringResource(R.string.app_pinned, app.label) else app.label) {
                            item.toggle(settings, app.packageName, SettingsSurface.CAR)
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

@OptIn(ExperimentalLayoutApi::class)
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
        // Wraps on narrow (portrait) screens
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
