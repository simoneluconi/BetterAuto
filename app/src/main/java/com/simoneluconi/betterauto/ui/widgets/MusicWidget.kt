package com.simoneluconi.betterauto.ui.widgets

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.media.Track
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.delay

/** Below this width/height ratio the player stacks its parts instead of putting the text beside the cover. */
private const val NARROW_RATIO = 1.45f

/** Below this width/height ratio (portrait screens) the cover gets the whole width on top. */
private const val TALL_RATIO = 0.9f

/**
 * Now playing card. Wide: big cover on the left, everything else in a column beside it.
 * Narrow: cover and title on top, progress and transport below, secondary actions at the bottom.
 * Tall: a big cover on top and everything else below it, for portrait screens.
 */
@Composable
fun MusicWidget(modifier: Modifier = Modifier, extended: Boolean = false, onShowPanel: (() -> Unit)? = null) {
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val hasAccess by NowPlaying.hasAccess.collectAsStateWithLifecycle()
    val colors = LocalCarColors.current

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(if (track != null) colors.albumCard else colors.surface)
            .padding(28.dp)
    ) {
        val current = track
        when {
            current == null -> EmptyPlayer(hasAccess)
            maxWidth / maxHeight < TALL_RATIO -> TallPlayer(current, maxWidth, maxHeight, extended, onShowPanel)
            maxWidth / maxHeight < NARROW_RATIO -> NarrowPlayer(current, maxWidth, maxHeight, extended, onShowPanel)
            else -> WidePlayer(current, maxWidth, maxHeight, extended, onShowPanel)
        }
    }
}

@Composable
private fun WidePlayer(track: Track, width: Dp, height: Dp, extended: Boolean, onShowPanel: (() -> Unit)?) {
    val colors = LocalCarColors.current
    Row(
        Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Cover(track, size = minOf(height, width * 0.46f), badge = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SourceRow(track, extended, onShowPanel)
            TitleBlock(track, titleSize = 44.sp, maxLines = 2)
            Progress(track)
            Transport(track, playSize = (height * 0.25f).coerceIn(104.dp, 160.dp))
            track.upNext?.let { UpNext(it) }
        }
    }
}

@Composable
private fun NarrowPlayer(track: Track, width: Dp, height: Dp, extended: Boolean, onShowPanel: (() -> Unit)?) {
    val colors = LocalCarColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Cover(track, size = minOf(height * 0.34f, width * 0.34f), badge = true)
            TitleBlock(track, titleSize = 40.sp, maxLines = 2, modifier = Modifier.weight(1f))
        }
        Progress(track)
        Transport(track, playSize = (height * 0.19f).coerceIn(96.dp, 124.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { track.upNext?.let { UpNext(it) } }
            if (extended) CustomActions(track)
            ShuffleRepeat(track)
            onShowPanel?.let { ShowPanelButton(it) }
        }
    }
}

@Composable
private fun TallPlayer(track: Track, width: Dp, height: Dp, extended: Boolean, onShowPanel: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        SourceRow(track, extended, onShowPanel)
        Cover(track, size = minOf(width, height * 0.5f), badge = false, modifier = Modifier.align(Alignment.CenterHorizontally))
        TitleBlock(track, titleSize = 42.sp, maxLines = 2)
        Progress(track)
        Transport(track, playSize = (height * 0.13f).coerceIn(104.dp, 150.dp))
        track.upNext?.let { UpNext(it) }
    }
}

@Composable
private fun EmptyPlayer(hasAccess: Boolean) {
    val colors = LocalCarColors.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Music, colors.text2, Modifier.size(44.dp))
        }
        CarLabel(stringResource(R.string.music_nothing_playing), 34.sp, colors.text, weight = FontWeight.ExtraBold)
        CarLabel(
            stringResource(if (hasAccess) R.string.music_start_hint else R.string.music_access_hint),
            22.sp, colors.text2, maxLines = 2, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Cover(track: Track, size: Dp, badge: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.size(size)) {
        val art = track.art
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
        } else {
            Box(Modifier.fillMaxSize().clip(shape).background(colors.surface2), contentAlignment = Alignment.Center) {
                CarIcon(CarIcons.Music, colors.text2, Modifier.size(size * 0.36f))
            }
        }
        val icon = track.appIcon
        if (badge && icon != null) {
            Image(
                icon,
                contentDescription = stringResource(R.string.music_playing_from, track.appLabel),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(3.dp, colors.bg, CircleShape),
            )
        }
    }
}

@Composable
private fun SourceRow(track: Track, extended: Boolean, onShowPanel: (() -> Unit)?) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .background(colors.text.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            track.appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape)) }
            CarLabel(track.appLabel, 18.sp, colors.text, weight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        if (extended) CustomActions(track)
        ShuffleRepeat(track)
        onShowPanel?.let { ShowPanelButton(it) }
    }
}

/** Next track in the queue: an icon instead of a label, to leave room for title and artist. */
@Composable
private fun UpNext(text: String, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CarIcon(CarIcons.UpNext, colors.text2, Modifier.size(24.dp))
        CarLabel(text, 19.sp, colors.text2)
    }
}

/** Brings back the library panel next to a full-screen player. */
@Composable
private fun ShowPanelButton(onClick: () -> Unit) {
    ToggleButton(CarIcons.List, stringResource(R.string.music_show_panel), active = false, onClick = onClick)
}

/** The player's own buttons, except shuffle/repeat which our toggles already cover. */
@Composable
private fun CustomActions(track: Track) {
    val colors = LocalCarColors.current
    for (action in track.customActions) {
        val name = action.name.lowercase()
        if ("shuffle" in name || "repeat" in name) continue
        Box(
            Modifier
                .carClickable(CircleShape, action.name) { NowPlaying.sendCustomAction(action.action) }
                .background(colors.text.copy(alpha = 0.08f), CircleShape)
                .size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            val icon = action.icon
            if (icon != null) {
                Image(icon, contentDescription = null, colorFilter = ColorFilter.tint(colors.text2), modifier = Modifier.size(24.dp))
            } else {
                CarLabel(action.name.take(1), 20.sp, colors.text2, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ShuffleRepeat(track: Track) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleButton(
            CarIcons.Shuffle,
            stringResource(if (track.shuffle) R.string.music_shuffle_on else R.string.music_shuffle_off),
            track.shuffle,
        ) { NowPlaying.toggleShuffle() }
        ToggleButton(
            CarIcons.Repeat,
            stringResource(if (track.repeat) R.string.music_repeat_on else R.string.music_repeat_off),
            track.repeat,
        ) { NowPlaying.toggleRepeat() }
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, label, onClick = onClick)
            .background(if (active) colors.album.copy(alpha = 0.3f) else colors.text.copy(alpha = 0.08f), CircleShape)
            .size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(icon, if (active) colors.text else colors.text2, Modifier.size(24.dp))
    }
}

@Composable
private fun TitleBlock(track: Track, titleSize: TextUnit, maxLines: Int, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    Column(modifier) {
        CarLabel(
            track.title.ifBlank { stringResource(R.string.music_unknown_title) }, titleSize, colors.text,
            weight = FontWeight.ExtraBold, maxLines = maxLines, lineHeight = 1.24.em,
        )
        if (track.artist.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            InfoLine(CarIcons.Artist, track.artist, 26.sp)
        }
        if (track.album.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            InfoLine(CarIcons.Album, track.album, 23.sp)
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, size: TextUnit) {
    val colors = LocalCarColors.current
    val iconSize = with(LocalDensity.current) { size.toDp() } * 0.95f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarIcon(icon, colors.text2, Modifier.size(iconSize))
        CarLabel(text, size, colors.text2, lineHeight = 1.3.em)
    }
}

@Composable
private fun Progress(track: Track) {
    val colors = LocalCarColors.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.playing, track.positionUpdatedAt) {
        now = SystemClock.elapsedRealtime()
        while (track.playing) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val position = track.positionAt(now)
    val fraction = if (track.durationMs > 0) position.toFloat() / track.durationMs else 0f

    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(colors.text.copy(alpha = 0.16f), RoundedCornerShape(50)))
            if (track.durationMs > 0) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(colors.album, RoundedCornerShape(50)))
                // Knob centred on the playback position, positioned on the full-width track so it never gets squeezed
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction.coerceIn(0f, 1f) - 10.dp)
                        .size(20.dp)
                        .background(colors.album, CircleShape)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            CarLabel(formatTime(position), 19.sp, colors.text2, tabularNumbers = true)
            if (track.durationMs > 0) CarLabel(formatTime(track.durationMs), 19.sp, colors.text2, tabularNumbers = true)
        }
    }
}

@Composable
private fun Transport(track: Track, playSize: Dp) {
    val colors = LocalCarColors.current
    val skipSize = playSize * 0.78f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.carClickable(CircleShape, stringResource(R.string.music_previous)) { NowPlaying.previous() }.size(skipSize),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Previous, colors.text, Modifier.size(skipSize * 0.5f))
        }
        // Circle while playing, rounded square while paused
        val corner by animateIntAsState(
            targetValue = if (track.playing) 50 else 28,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
            label = "playCorner",
        )
        val playShape = RoundedCornerShape(corner)
        Box(
            Modifier
                .carClickable(playShape, stringResource(if (track.playing) R.string.music_pause else R.string.music_play)) {
                    NowPlaying.playPause()
                }
                .background(colors.album, playShape)
                .size(playSize),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(track.playing, animationSpec = tween(180), label = "playIcon") { playing ->
                CarIcon(if (playing) CarIcons.Pause else CarIcons.Play, colors.onAlbum, Modifier.size(playSize * 0.4f))
            }
        }
        Box(
            Modifier.carClickable(CircleShape, stringResource(R.string.music_next)) { NowPlaying.next() }.size(skipSize),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Next, colors.text, Modifier.size(skipSize * 0.5f))
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
