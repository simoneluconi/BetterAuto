package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.simoneluconi.betterauto.media.MediaApps
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        CarLabel("Settings", 36.sp, colors.text, weight = FontWeight.ExtraBold)
        Setting("Theme", ThemeMode.entries, theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
            settings.setThemeMode(it)
        }
        Setting("App bar", BarPosition.entries, bar, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
            settings.setBarPosition(it)
        }
        Setting("Home layout", HomeLayout.entries, layout, {
            when (it) {
                HomeLayout.SINGLE -> "One widget"
                HomeLayout.SPLIT -> "Two widgets"
                HomeLayout.TRIO -> "Three widgets"
            }
        }) { settings.setLayout(it) }
        Setting("Widget order", listOf(Unit), null, { "Rotate widgets" }) { settings.rotateWidgets() }
        Setting("Debug overlay", listOf(false, true), debug, { if (it) "On" else "Off" }) { settings.setDebugOverlay(it) }
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
        CarLabel("Apps in the bar", 26.sp, colors.text2, weight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (app in apps) {
                val shape = RoundedCornerShape(50)
                val isPinned = app.packageName in pinned
                Row(
                    Modifier
                        .carClickable(shape, if (isPinned) "${app.label}, pinned" else app.label) {
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
private fun <T> Setting(title: String, options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    val colors = LocalCarColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CarLabel(title, 26.sp, colors.text2, weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (option in options) {
                val shape = RoundedCornerShape(50)
                val isSelected = option == selected
                Box(
                    Modifier
                        .carClickable(shape, "$title: ${label(option)}") { onSelect(option) }
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
