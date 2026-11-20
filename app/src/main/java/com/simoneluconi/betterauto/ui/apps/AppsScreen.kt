package com.simoneluconi.betterauto.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.audio.ShizukuAudio
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.projection.PhoneAppChoices
import com.simoneluconi.betterauto.projection.PhoneAppDisplay
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.RailLayout
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LazyVerticalGridWithBar
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.smarthome.ProviderIcon
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * Every app in one place, for when the bar has no room for them all: ours first (the media apps our player
 * opens, the linked smart-home services), then the phone's, which open inside the launcher ([PhoneAppScreen])
 * and carry the phone badge.
 */
@Composable
fun AppsScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val chosen by settings.phoneApps.collectAsStateWithLifecycle()
    val homes by SmartHome.linked.collectAsStateWithLifecycle()
    val shizuku by ShizukuAudio.changes.collectAsStateWithLifecycle()
    // The media apps of the bar (pinned, then played lately), the one Music follows too: not every app that offers
    // a media service (assistants, chats do). One chosen as a phone app shows once, as that (YouTube: its videos)
    val pinned by settings.pinnedApps.collectAsStateWithLifecycle()
    val recent by settings.recentApps.collectAsStateWithLifecycle()
    // Package queries and icons off the main thread; the tiles of the last visit meanwhile, so the knob has a place
    val lists by produceState(LastLists.value, pinned, recent, chosen) {
        value = withContext(Dispatchers.IO) {
            val media = RailLayout.mediaApps(pinned, recent, followed = null) { MediaApps.suggested(context) }
                .filter { it !in chosen }
                .mapNotNull { MediaApps.info(context, it) }
            media to PhoneAppChoices.available(context, chosen).mapNotNull { MediaApps.info(context, it) }
        }
        LastLists.value = value
    }
    val (media, phone) = lists
    val supported = remember(shizuku) { PhoneAppDisplay.supported(context) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CarLabel(stringResource(R.string.nav_apps), 26.sp, colors.text, weight = FontWeight.ExtraBold)
        LazyVerticalGridWithBar(
            GridCells.Adaptive(170.dp),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The main control: a tile that is drawn (no phone tiles without Shizuku)
            val first = homes.firstOrNull()?.id ?: media.firstOrNull()?.packageName ?: phone.firstOrNull()?.packageName?.takeIf { supported }
            if (homes.isNotEmpty() || media.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle(stringResource(R.string.apps_native)) }
                items(homes, key = { "home:" + it.id }) { home ->
                    AppTile(home.name, isFirst = home.id == first, icon = { ProviderIcon(home, 64) }) {
                        SmartHome.screenProvider.value = home.id
                        settings.setSection(Section.SMART_HOME)
                    }
                }
                items(media, key = { "media:" + it.packageName }) { app ->
                    AppTile(app.label, isFirst = app.packageName == first, icon = { AppIcon(app.icon, phone = false) }) {
                        NowPlaying.open(app.packageName)
                        settings.setSection(Section.MUSIC)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle(stringResource(R.string.apps_phone)) }
            when {
                !supported -> item(span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.apps_phone_needs_shizuku)) }
                phone.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.apps_phone_none)) }
                else -> items(phone, key = { "phone:" + it.packageName }) { app ->
                    AppTile(
                        app.label, isFirst = app.packageName == first, label = stringResource(R.string.app_from_phone, app.label),
                        icon = { AppIcon(app.icon, phone = true) },
                    ) { byKnob ->
                        PhoneAppOpening.takeKnob = byKnob
                        PhoneAppDisplay.open(app.packageName)
                        settings.setSection(Section.PHONE_APP)
                    }
                }
            }
        }
    }
}

/** The Apps screen's tiles as last loaded (media apps, phone apps), shown while the next load runs. Main thread. */
private object LastLists {
    var value: Pair<List<MediaApps.App>, List<MediaApps.App>> = emptyList<MediaApps.App>() to emptyList()
}

@Composable
private fun GroupTitle(text: String) {
    CarLabel(text, 21.sp, LocalCarColors.current.text2, Modifier.padding(top = 6.dp, start = 4.dp), weight = FontWeight.Bold)
}

@Composable
private fun Note(text: String) {
    val colors = LocalCarColors.current
    Box(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp)).padding(18.dp)) {
        CarLabel(text, 18.sp, colors.text2, maxLines = 3)
    }
}

/** A tile; [onClick] is told whether the knob pressed it (a touch drops the focus first). */
@Composable
private fun AppTile(name: String, isFirst: Boolean, label: String = name, icon: @Composable () -> Unit, onClick: (byKnob: Boolean) -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(24.dp)
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .contentFocus(isFirst)
            .onFocusChanged { focused = it.hasFocus }
            .carClickable(shape, label) { onClick(focused) }
            .background(colors.surface, shape)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        CarLabel(name, 18.sp, colors.text, Modifier.fillMaxWidth(), weight = FontWeight.Bold, textAlign = TextAlign.Center, shrinkToFit = true)
    }
}

/**
 * An app's icon: round for ours, which open in a screen of BetterAuto; a rounded square with the phone badge for
 * the phone's, which show the app itself. The same in the bar and here.
 */
@Composable
fun AppIcon(icon: ImageBitmap, phone: Boolean, size: Int = 64) {
    val colors = LocalCarColors.current
    if (!phone) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape))
        return
    }
    Box(Modifier.size(size.dp)) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)))
        val badge = (size * 0.42f).dp
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(badge)
                .background(colors.surface2, CircleShape)
                .padding(badge * 0.18f),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Smartphone, colors.text, Modifier.fillMaxSize())
        }
    }
}
