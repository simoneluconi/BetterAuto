package com.simoneluconi.betterauto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
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
fun PhoneSettingsPage(onBack: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarLabel(stringResource(R.string.phone_settings_title), 26.sp, colors.text, weight = FontWeight.ExtraBold)
        }
        CarLabel(stringResource(R.string.phone_settings_hint), 15.sp, colors.text2, Modifier.padding(horizontal = 4.dp), maxLines = 3)
        SettingsList(settings)
    }
}

@Composable
private fun SettingsList(settings: LauncherSettings) {
    val colors = LocalCarColors.current
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
