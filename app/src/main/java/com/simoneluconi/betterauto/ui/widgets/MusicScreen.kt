package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.LibraryState
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.MediaLibrary
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.media.Track
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.AlphabetButton
import com.simoneluconi.betterauto.ui.AlphabetJumpEffect
import com.simoneluconi.betterauto.ui.AlphabetPicker
import com.simoneluconi.betterauto.ui.firstOfLetter
import com.simoneluconi.betterauto.ui.rememberAlphabetJump
import androidx.compose.foundation.lazy.itemsIndexed
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

private enum class MusicTab { QUEUE, LIBRARY, APPS }

/**
 * Full music section: the player plus up next, library and sources (side by side, or stacked on tall
 * screens), or the player alone full screen. The choice and the open tab are remembered.
 */
@Composable
fun MusicScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val showPanel by settings.musicPanel.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier) {
        val stacked = maxWidth / maxHeight < 1.25f
        val playerToggle = if (showPanel) null else ({ settings.setMusicPanel(true) })
        when {
            !showPanel -> MusicWidget(Modifier.fillMaxSize(), extended = true, onShowPanel = playerToggle)
            stacked -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                MusicWidget(Modifier.weight(1f).fillMaxWidth(), extended = true)
                MusicPanel(settings, Modifier.weight(1f).fillMaxWidth())
            }
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MusicWidget(Modifier.weight(1.1f).fillMaxSize(), extended = true)
                MusicPanel(settings, Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun MusicPanel(settings: LauncherSettings, modifier: Modifier) {
    val colors = LocalCarColors.current
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val savedTab by settings.musicTab.collectAsStateWithLifecycle()
    // "Up next" only exists when the player shares its queue
    val tabs = buildList {
        if (track?.queue.orEmpty().isNotEmpty()) add(MusicTab.QUEUE to R.string.music_tab_queue)
        add(MusicTab.LIBRARY to R.string.music_tab_library)
        add(MusicTab.APPS to R.string.music_tab_apps)
    }
    val tab = tabs.firstOrNull { it.first.name == savedTab }?.first ?: tabs.first().first

    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Tabs share the row equally, the full-screen button keeps its size
            for ((value, label) in tabs) {
                val shape = RoundedCornerShape(50)
                val text = stringResource(label)
                Box(
                    Modifier
                        .weight(1f)
                        .carClickable(shape, text) { settings.setMusicTab(value.name) }
                        .background(if (tab == value) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(60.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarLabel(text, 21.sp, colors.text, weight = FontWeight.Bold)
                }
            }
            val fullScreen = stringResource(R.string.music_fullscreen)
            Box(
                Modifier
                    .carClickable(CircleShape, fullScreen) { settings.setMusicPanel(false) }
                    .background(colors.surface2, CircleShape)
                    .size(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Expand, colors.text2, Modifier.size(26.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MusicTab.QUEUE -> QueueList(track)
                MusicTab.LIBRARY -> LibraryList(track?.packageName)
                MusicTab.APPS -> AppsList(track?.packageName)
            }
        }
    }
}

@Composable
private fun QueueList(track: Track?) {
    val queue = track?.queue.orEmpty()
    if (queue.isEmpty()) {
        PanelMessage(stringResource(if (track == null) R.string.music_nothing_playing else R.string.music_queue_empty))
        return
    }
    val listState = rememberLazyListState()
    val activeIndex = queue.indexOfFirst { it.id == track?.activeQueueId }
    LaunchedEffect(track?.packageName, activeIndex) {
        if (activeIndex > 0) listState.scrollToItem(activeIndex - 1)
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(queue, key = { it.id }) { entry ->
            ListRow(
                icon = entry.icon,
                fallback = CarIcons.Music,
                title = entry.title,
                subtitle = entry.subtitle,
                highlighted = entry.id == track?.activeQueueId,
            ) { NowPlaying.skipToQueueItem(entry.id) }
        }
    }
}

@Composable
private fun LibraryList(packageName: String?) {
    if (packageName == null) {
        PanelMessage(stringResource(R.string.music_pick_app))
        return
    }
    val context = LocalContext.current
    val library = remember(packageName) { MediaLibrary(context, packageName).also { it.connect() } }
    DisposableEffect(library) { onDispose { library.close() } }
    val state by library.state.collectAsStateWithLifecycle()
    val appLabel = remember(packageName) { MediaApps.info(context, packageName)?.label ?: packageName }

    when (val current = state) {
        LibraryState.Connecting -> PanelMessage(stringResource(R.string.music_loading))
        LibraryState.Unavailable -> PanelMessage(stringResource(R.string.music_library_unavailable, appLabel))
        is LibraryState.Loaded -> Column(
            // The car's back key goes up one folder before leaving the music section
            Modifier.onKeyEvent { event ->
                if (event.key != Key.Back || !current.canGoBack) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) library.back()
                true
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A–Z only where it helps: long folders
            val jump = rememberAlphabetJump()
            val listState = rememberLazyListState()
            AlphabetJumpEffect(jump, listState)
            val firstOfLetter = remember(current.items) { firstOfLetter(current.items) { it.title } }
            val showJump = current.items.size >= AZ_MIN_ITEMS && firstOfLetter.size > 1
            if (current.canGoBack || showJump) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (current.canGoBack) {
                        ListRow(
                            icon = null,
                            modifier = Modifier.weight(1f),
                            fallback = CarIcons.Back,
                            title = current.title ?: stringResource(R.string.music_back),
                            subtitle = stringResource(R.string.music_back),
                            highlighted = false,
                        ) { library.back() }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (showJump && !jump.open) AlphabetButton(jump)
                }
            }
            if (current.items.isEmpty()) {
                PanelMessage(stringResource(R.string.music_library_empty))
            } else if (jump.open) {
                AlphabetPicker(firstOfLetter.keys.toList(), onPick = { jump.jumpTo(firstOfLetter.getValue(it)) }, onClose = { jump.open = false })
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(current.items, key = { _, it -> it.id }) { index, item ->
                        ListRow(
                            icon = item.icon,
                            modifier = jump.focusTarget(index),
                            fallback = if (item.browsable) CarIcons.Album else CarIcons.Music,
                            tintIcon = item.glyph,
                            title = item.title,
                            subtitle = item.subtitle,
                            highlighted = false,
                            trailing = if (item.browsable) CarIcons.Chevron else null,
                        ) { library.select(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsList(current: String?) {
    val context = LocalContext.current
    val apps = remember { MediaApps.installed(context).mapNotNull { MediaApps.info(context, it) } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(apps, key = { it.packageName }) { app ->
            ListRow(
                icon = app.icon,
                fallback = CarIcons.Music,
                title = app.label,
                subtitle = "",
                highlighted = app.packageName == current,
                roundIcon = true,
            ) { NowPlaying.open(app.packageName) }
        }
    }
}

@Composable
internal fun ListRow(
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
    fallback: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    trailing: ImageVector? = null,
    roundIcon: Boolean = false,
    tintIcon: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .carClickable(shape, if (subtitle.isEmpty()) title else "$title, $subtitle", onClick = onClick)
            .background(if (highlighted) colors.album.copy(alpha = 0.22f) else colors.surface2, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val iconShape = if (roundIcon) CircleShape else RoundedCornerShape(12.dp)
        if (icon != null) {
            if (tintIcon) {
                Box(Modifier.size(60.dp).background(colors.surface, iconShape), contentAlignment = Alignment.Center) {
                    Image(icon, null, colorFilter = ColorFilter.tint(colors.text2), modifier = Modifier.size(34.dp))
                }
            } else {
                Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clip(iconShape))
            }
        } else {
            Box(Modifier.size(60.dp).background(colors.surface, iconShape), contentAlignment = Alignment.Center) {
                CarIcon(fallback, colors.text2, Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            CarLabel(title, 23.sp, colors.text, weight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) CarLabel(subtitle, 19.sp, colors.text2)
        }
        trailing?.let { CarIcon(it, colors.text2, Modifier.size(28.dp)) }
    }
}

@Composable
internal fun PanelMessage(text: String) {
    val colors = LocalCarColors.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CarLabel(text, 22.sp, colors.text2, maxLines = 3, textAlign = TextAlign.Center)
    }
}

/** Folders shorter than this are quick enough to scroll with the knob. */
private const val AZ_MIN_ITEMS = 20
