package com.simoneluconi.betterauto.ui.widgets

import android.os.SystemClock
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/**
 * Now playing card. Wide: big cover on the left, everything else in a column beside it.
 * Narrow: cover and title on top, progress and transport below, secondary actions at the bottom.
 */
@Composable
fun MusicWidget(modifier: Modifier = Modifier) {
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
            maxWidth / maxHeight < NARROW_RATIO -> NarrowPlayer(current, maxWidth, maxHeight)
            else -> WidePlayer(current, maxWidth, maxHeight)
        }
    }
}

@Composable
private fun WidePlayer(track: Track, width: Dp, height: Dp) {
    val colors = LocalCarColors.current
    Row(
        Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Cover(track, size = minOf(height, width * 0.46f), badge = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SourceRow(track)
            TitleBlock(track, titleSize = 44.sp, maxLines = 2)
            Progress(track)
            Transport(track, playSize = (height * 0.25f).coerceIn(104.dp, 160.dp))
            track.upNext?.let { CarLabel("Up next · $it", 19.sp, colors.text2) }
        }
    }
}

@Composable
private fun NarrowPlayer(track: Track, width: Dp, height: Dp) {
    val colors = LocalCarColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Cover(track, size = minOf(height * 0.34f, width * 0.34f), badge = true)
            TitleBlock(track, titleSize = 40.sp, maxLines = 3, modifier = Modifier.weight(1f))
        }
        Progress(track)
        Transport(track, playSize = (height * 0.19f).coerceIn(96.dp, 124.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CarLabel(track.upNext?.let { "Up next · $it" } ?: "", 19.sp, colors.text2, Modifier.weight(1f))
            ShuffleRepeat(track)
        }
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
        CarLabel("Nothing playing", 34.sp, colors.text, weight = FontWeight.ExtraBold)
        CarLabel(
            if (hasAccess) "Start music on the phone or pick an app from the bar"
            else "Allow notification access for BetterAuto on the phone to show your music",
            22.sp, colors.text2, maxLines = 2, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Cover(track: Track, size: Dp, badge: Boolean) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.size(size)) {
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
                contentDescription = "Playing from ${track.appLabel}",
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
private fun SourceRow(track: Track) {
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
        ShuffleRepeat(track)
    }
}

@Composable
private fun ShuffleRepeat(track: Track) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleButton(CarIcons.Shuffle, "Shuffle", track.shuffle) { NowPlaying.toggleShuffle() }
        ToggleButton(CarIcons.Repeat, "Repeat", track.repeat) { NowPlaying.toggleRepeat() }
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, if (active) "$label on" else "$label off", onClick = onClick)
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
            track.title.ifBlank { "Unknown title" }, titleSize, colors.text,
            weight = FontWeight.ExtraBold, maxLines = maxLines, lineHeight = 1.24.em,
        )
        val subtitle = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            CarLabel(subtitle, 26.sp, colors.text2, lineHeight = 1.3.em)
        }
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
        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(colors.text.copy(alpha = 0.16f), RoundedCornerShape(50)))
            if (track.durationMs > 0) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(colors.album, RoundedCornerShape(50)))
                Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.001f))) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = 10.dp)
                            .size(20.dp)
                            .background(colors.album, CircleShape)
                    )
                }
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
            Modifier.carClickable(CircleShape, "Previous track") { NowPlaying.previous() }.size(skipSize),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Previous, colors.text, Modifier.size(skipSize * 0.5f))
        }
        Box(
            Modifier
                .carClickable(CircleShape, if (track.playing) "Pause" else "Play") { NowPlaying.playPause() }
                .background(colors.album, CircleShape)
                .size(playSize),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(if (track.playing) CarIcons.Pause else CarIcons.Play, colors.onAlbum, Modifier.size(playSize * 0.4f))
        }
        Box(
            Modifier.carClickable(CircleShape, "Next track") { NowPlaying.next() }.size(skipSize),
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
