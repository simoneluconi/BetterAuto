package com.simoneluconi.betterauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import java.text.Normalizer

/** Letter a text is filed under: accents removed, digits and symbols under '#'. */
fun indexLetter(text: String): Char {
    val first = text.trimStart().firstOrNull() ?: return '#'
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    return if (base.isLetter()) base.uppercaseChar() else '#'
}

/**
 * A–Z jump for long lists, made for the knob: the A–Z button opens the letters present in the list,
 * and picking one scrolls there and puts the focus on the first item of that letter.
 */
class AlphabetJump {
    var open by mutableStateOf(false)
    internal var target by mutableStateOf<Int?>(null)
    internal var requests by mutableIntStateOf(0)
    internal val requester = FocusRequester()

    /** Put on each list item, with its index in the list. */
    fun focusTarget(index: Int): Modifier = if (target == index) Modifier.focusRequester(requester) else Modifier

    fun jumpTo(index: Int) {
        open = false
        target = index
        requests++
    }
}

@Composable
fun rememberAlphabetJump(): AlphabetJump = remember { AlphabetJump() }

/** Scrolls the list to the picked item and gives it the focus. */
@Composable
fun AlphabetJumpEffect(jump: AlphabetJump, listState: LazyListState) {
    LaunchedEffect(jump.requests) {
        val index = jump.target ?: return@LaunchedEffect
        listState.scrollToItem(index)
        // The item exists only from the next frame
        withFrameNanos { }
        runCatching { jump.requester.requestFocus() }
    }
}

/** The A–Z button for a list header. */
@Composable
fun AlphabetButton(jump: AlphabetJump) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .carClickable(shape, stringResource(R.string.az_jump)) { jump.open = true }
            .background(colors.surface2, shape)
            .height(56.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel("A–Z", 21.sp, colors.text, weight = FontWeight.ExtraBold)
    }
}

/** The letters of the list as big keys; Back closes without jumping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlphabetPicker(letters: List<Char>, onPick: (Char) -> Unit, onClose: () -> Unit) {
    val colors = LocalCarColors.current
    val first = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            }
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CarLabel(stringResource(R.string.az_title), 22.sp, colors.text2, weight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            letters.forEachIndexed { i, letter ->
                val shape = RoundedCornerShape(18.dp)
                Box(
                    Modifier
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .carClickable(shape, letter.toString()) { onPick(letter) }
                        .background(colors.surface2, shape)
                        .size(76.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarLabel(letter.toString(), 30.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
