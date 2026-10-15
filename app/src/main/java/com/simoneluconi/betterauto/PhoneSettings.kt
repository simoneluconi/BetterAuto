package com.simoneluconi.betterauto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.settings.ActionSetting
import com.simoneluconi.betterauto.settings.ChoiceSetting
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.PinnedAppsSetting
import com.simoneluconi.betterauto.settings.SettingsCatalog
import com.simoneluconi.betterauto.settings.SettingsSurface
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The car screen's settings on the phone: the same [SettingsCatalog] the car shows, in the phone's style. */
@Composable
fun PhoneSettings() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CarLabel(stringResource(R.string.phone_settings_title), 22.sp, colors.text, weight = FontWeight.Bold)
        CarLabel(stringResource(R.string.phone_settings_hint), 15.sp, colors.text2, maxLines = 3)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        for (item in SettingsCatalog.all) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CarLabel(stringResource(item.title), 16.sp, colors.text2, weight = FontWeight.Bold)
                when (item) {
                    is ChoiceSetting<*> -> PhoneChoice(item, settings)
                    is ActionSetting -> Chip(stringResource(item.label), selected = false) {
                        item.run(settings, SettingsSurface.PHONE)
                    }
                    is PinnedAppsSetting -> PhonePinnedApps(item, settings)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> PhoneChoice(item: ChoiceSetting<T>, settings: LauncherSettings) {
    val selected by item.state(settings).collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in item.options) {
            Chip(stringResource(option.label), selected = option.value == selected) {
                item.select(settings, option.value, SettingsSurface.PHONE)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhonePinnedApps(item: PinnedAppsSetting, settings: LauncherSettings) {
    val context = LocalContext.current
    val pinned by settings.pinnedApps.collectAsStateWithLifecycle()
    val apps = remember { MediaApps.installed(context).mapNotNull { MediaApps.info(context, it) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (app in apps) {
            Chip(app.label, selected = app.packageName in pinned, icon = app.icon) {
                item.toggle(settings, app.packageName, SettingsSurface.PHONE)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, icon: ImageBitmap? = null, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) colors.focus.copy(alpha = 0.22f) else colors.surface2)
            .clickable(onClick = onClick)
            .padding(start = if (icon != null) 6.dp else 16.dp, end = 16.dp, top = if (icon != null) 6.dp else 10.dp, bottom = if (icon != null) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape)) }
        CarLabel(label, 15.sp, colors.text, weight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}
