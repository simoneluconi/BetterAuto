package com.simoneluconi.betterauto.ui.widgets

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import com.simoneluconi.betterauto.ui.formatTime
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.delay

/**
 * Knob nudges in the player: from the transport (previous, play, next) toward the secondary controls
 * (favorite, shuffle, repeat...) and back to play/pause, whichever side the secondary row is on.
 */
private class PlayerLinks {
    val play = FocusRequester()
    val secondary = FocusRequester()
    var secondaryAbove by mutableStateOf(true)
}

private val LocalPlayerLinks = staticCompositionLocalOf<PlayerLinks?> { null }

/** On the transport buttons. */
private fun Modifier.transportLinks(): Modifier = composed {
    val links = LocalPlayerLinks.current ?: return@composed Modifier
    focusProperties {
        if (links.secondaryAbove) up = links.secondary else down = links.secondary
    }
}

/** On every secondary control. */
private fun Modifier.secondaryLinks(): Modifier = composed {
    val links = LocalPlayerLinks.current ?: return@composed Modifier
    focusProperties {
        if (links.secondaryAbove) down = links.play else up = links.play
    }
}

/** Below this width/height ratio the player stacks its parts instead of putting the text beside the cover. */
private const val NARROW_RATIO = 1.45f

/** Progress bar refresh: as often as the knob moves a pixel, within these bounds. */
private const val MIN_PROGRESS_STEP_MS = 50L
private const val MAX_PROGRESS_STEP_MS = 500L

/** How long a seek is shown before the player reports it, and how close its report must be to count. */
private const val SEEK_HOLD_MS = 1500L
private const val SEEK_MATCH_MS = 1500L

/** A card at least this wide shows the player's own buttons too, not only in the music section. */
private val EXTRAS_MIN_WIDTH = 560.dp

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
    val links = remember { PlayerLinks() }

    CompositionLocalProvider(LocalPlayerLinks provides links) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(if (track != null) colors.albumCard else colors.surface)
            .padding(28.dp)
    ) {
        val current = track
        val ratio = maxWidth / maxHeight
        // The player's own buttons (favorite...) wherever there is room for them, the home card too
        val withExtras = extended || maxWidth >= EXTRAS_MIN_WIDTH
        // Only the narrow layout has its secondary controls under the transport
        links.secondaryAbove = !(ratio >= TALL_RATIO && ratio < NARROW_RATIO)
        when {
            current == null -> EmptyPlayer(hasAccess)
            ratio < TALL_RATIO -> TallPlayer(current, maxWidth, maxHeight, withExtras, onShowPanel)
            ratio < NARROW_RATIO -> NarrowPlayer(current, maxWidth, maxHeight, withExtras, onShowPanel)
            else -> WidePlayer(current, maxWidth, maxHeight, withExtras, onShowPanel)
        }
    }
    }
}

@Composable
private fun WidePlayer(track: Track, width: Dp, height: Dp, extended: Boolean, onShowPanel: (() -> Unit)?) {
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Cover(track, size = minOf(height * 0.34f, width * 0.34f), badge = true)
            TitleBlock(track, titleSize = 40.sp, maxLines = 2, modifier = Modifier.weight(1f))
        }
        Progress(track)
        Transport(track, playSize = (height * 0.19f).coerceIn(96.dp, 124.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { track.upNext?.let { UpNext(it) } }
            Row(Modifier.focusRequester(LocalPlayerLinks.current?.secondary ?: remember { FocusRequester() }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (extended) CustomActions(track)
                ShuffleRepeat(track)
                onShowPanel?.let { ShowPanelButton(it) }
            }
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
        Row(Modifier.focusRequester(LocalPlayerLinks.current?.secondary ?: remember { FocusRequester() }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (extended) CustomActions(track)
            ShuffleRepeat(track)
            onShowPanel?.let { ShowPanelButton(it) }
        }
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
                .secondaryLinks()
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
            .secondaryLinks()
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
    var barWidthPx by remember { mutableIntStateOf(0) }
    // Where the finger is while dragging the knob, as a fraction of the track
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // A seek the player has not reported back yet (target, when): shown so the knob does not jump back first
    var pendingSeek by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(track.playing, track.positionUpdatedAt, track.durationMs, barWidthPx) {
        now = SystemClock.elapsedRealtime()
        // About a pixel per step: smooth, without redrawing (and re-encoding for the car) every frame
        val step = if (barWidthPx > 0 && track.durationMs > 0) (track.durationMs / barWidthPx).coerceIn(MIN_PROGRESS_STEP_MS, MAX_PROGRESS_STEP_MS) else MAX_PROGRESS_STEP_MS
        while (track.playing) {
            delay(step)
            now = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_HOLD_MS)
        pendingSeek = null
    }
    val reported = track.positionAt(now)
    val pending = pendingSeek?.let { (target, at) -> target + if (track.playing) (now - at).coerceAtLeast(0) else 0 }
        ?.takeIf { kotlin.math.abs(it - reported) > SEEK_MATCH_MS }
    val position = dragFraction?.let { (it * track.durationMs).toLong() } ?: pending ?: reported
    val fraction = if (track.durationMs > 0) position.toFloat() / track.durationMs else 0f

    // Touchscreens: a tap along the bar (or on the times under it) seeks there, dragging the knob seeks on release
    val seek = if (track.canSeek && track.durationMs > 0) {
        fun commit(f: Float) {
            val target = (f * track.durationMs).toLong()
            pendingSeek = target to SystemClock.elapsedRealtime()
            NowPlaying.seekTo(target)
        }
        Modifier
            .pointerInput(track.durationMs) {
                detectTapGestures { offset -> commit((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(track.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let(::commit)
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    } else {
        Modifier
    }
    val knob = if (dragFraction != null) 28.dp else 20.dp
    Column(seek.onSizeChanged { barWidthPx = it.width }) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(colors.text.copy(alpha = 0.16f), RoundedCornerShape(50)))
            if (track.durationMs > 0) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(colors.album, RoundedCornerShape(50)))
                // Knob centred on the playback position, positioned on the full-width track so it never gets squeezed
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction.coerceIn(0f, 1f) - knob / 2)
                        .size(knob)
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
            Modifier.transportLinks().carClickable(CircleShape, stringResource(R.string.music_previous)) { NowPlaying.previous() }.size(skipSize),
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
                .contentFocus()
                .focusRequester(LocalPlayerLinks.current?.play ?: remember { FocusRequester() })
                .transportLinks()
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
            Modifier.transportLinks().carClickable(CircleShape, stringResource(R.string.music_next)) { NowPlaying.next() }.size(skipSize),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Next, colors.text, Modifier.size(skipSize * 0.5f))
        }
    }
}
