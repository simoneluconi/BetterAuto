package com.simoneluconi.betterauto.ui.voice

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState

/**
 * The voice panel at the bottom of the screen: a microphone that moves with the voice, the live
 * transcription word by word, then what was understood and done. Only the contact choice takes the knob.
 */
@Composable
fun VoiceOverlay(modifier: Modifier = Modifier) {
    val state by VoiceAssistant.state.collectAsStateWithLifecycle()
    // Kept while the panel slides away
    val shown = remember { Last() }
    if (state !is VoiceState.Idle) shown.value = state
    AnimatedVisibility(
        visible = state !is VoiceState.Idle,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val current = shown.value ?: return@AnimatedVisibility
        val colors = LocalCarColors.current
        val shape = RoundedCornerShape(32.dp)
        Column(
            Modifier
                .widthIn(min = 640.dp, max = 980.dp)
                .shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.line, shape)
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (current) {
                is VoiceState.Listening -> Listening(current)
                is VoiceState.Choosing -> Choosing(current)
                is VoiceState.Calling -> Calling(current)
                is VoiceState.Done -> Done(current)
                VoiceState.Idle -> Unit
            }
        }
    }
}

/** The last state shown, for the exit animation; not a state itself, it never triggers recomposition. */
private class Last { var value: VoiceState? = null }

@Composable
private fun Listening(state: VoiceState.Listening) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Microphone(state.level)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Where it listens from, as an icon: the car or the phone
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarLabel(stringResource(R.string.voice_listening), 19.sp, colors.text2, weight = FontWeight.Bold)
                CarIcon(if (state.carMicrophone) CarIcons.Car else CarIcons.Phone, colors.text2, Modifier.size(20.dp))
            }
            if (state.text.isBlank()) {
                CarLabel(stringResource(R.string.voice_hint), 24.sp, colors.text2, maxLines = 2)
            } else {
                CarLabel(state.text, 34.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 40.sp)
            }
        }
    }
}

/** The microphone in the album color, with a ring that grows with the voice and a slow breath when quiet. */
@Composable
private fun Microphone(level: Float) {
    val colors = LocalCarColors.current
    val voice by animateFloatAsState(level, spring(stiffness = 400f), label = "voiceLevel")
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathValue",
    )
    Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(108.dp).scale(0.78f + 0.22f * maxOf(voice, breath * 0.35f)).background(colors.album.copy(alpha = 0.22f), CircleShape))
        Box(Modifier.size(76.dp).background(colors.album, CircleShape), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Mic, colors.onAlbum, Modifier.size(36.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choosing(state: VoiceState.Choosing) {
    val colors = LocalCarColors.current
    val first = remember { FocusRequester() }
    // A similar name is only a guess: it is asked, never dialed on its own
    CarLabel(stringResource(if (state.confirm) R.string.voice_did_you_mean else R.string.voice_which), 24.sp, colors.text, weight = FontWeight.ExtraBold)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .focusProperties { onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.names.forEachIndexed { i, name ->
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .carClickable(shape, name) { VoiceAssistant.choose(name) }
                    .background(colors.surface2, shape)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CarIcon(CarIcons.CallAnswer, colors.text2, Modifier.size(22.dp))
                CarLabel(name, 22.sp, colors.text, weight = FontWeight.Bold)
            }
        }
    }
    LaunchedEffect(state) { runCatching { first.requestFocus() } }
}

/** About to call: who, which number, a bar running out, and Cancel under the knob. */
@Composable
private fun Calling(state: VoiceState.Calling) {
    val colors = LocalCarColors.current
    val cancel = remember { FocusRequester() }
    val total = remember(state) { (state.until - SystemClock.elapsedRealtime()).coerceAtLeast(1) }
    val progress = remember(state) { Animatable(1f) }
    LaunchedEffect(state) {
        runCatching { cancel.requestFocus() }
        progress.animateTo(0f, tween(total.toInt(), easing = LinearEasing))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(64.dp).background(Color(0xFF2E9E5B), CircleShape), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.CallAnswer, Color.White, Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CarLabel(stringResource(R.string.voice_calling, state.name, state.numberLabel), 28.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 2)
            Box(Modifier.fillMaxWidth().height(6.dp).background(colors.surface2, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(progress.value).height(6.dp).background(Color(0xFF2E9E5B), RoundedCornerShape(3.dp)))
            }
        }
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .focusRequester(cancel)
                .carClickable(shape, stringResource(R.string.voice_call_cancel)) { VoiceAssistant.cancel() }
                .background(colors.surface2, shape)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            CarLabel(stringResource(R.string.voice_call_cancel), 22.sp, colors.text, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Done(state: VoiceState.Done) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier.size(64.dp).background(if (state.understood) colors.album else colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(if (state.understood) CarIcons.Check2 else CarIcons.Close, if (state.understood) colors.onAlbum else colors.text2, Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.heard.isNotBlank() && state.understood) CarLabel("“${state.heard}”", 19.sp, colors.text2, maxLines = 1)
            CarLabel(state.message, 28.sp, colors.text, weight = FontWeight.ExtraBold, maxLines = 3, lineHeight = 34.sp)
        }
    }
}
