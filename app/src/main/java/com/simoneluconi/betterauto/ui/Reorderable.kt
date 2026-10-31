package com.simoneluconi.betterauto.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column whose rows are reordered by dragging their handle (touch): [row] gets the handle's modifier to put
 * on its own grip. A row passing half of its neighbour swaps with it ([onMove] from, to), and stays under the
 * finger. [items] must be distinct: they key the rows, which follow their item.
 */
@Composable
fun <T : Any> ReorderableColumn(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 10.dp,
    row: @Composable (item: T, index: Int, handle: Modifier) -> Unit,
) {
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    var dragged by remember { mutableStateOf<T?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val latestItems by rememberUpdatedState(items)
    val move by rememberUpdatedState(onMove)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        items.forEachIndexed { index, item ->
            key(item) {
                val moving = dragged == item
                var step by remember { mutableFloatStateOf(0f) }
                val handle = Modifier.pointerInput(item) {
                    // Where the row is, kept here as it moves: the list itself catches up a frame later
                    var at = 0
                    detectDragGestures(
                        onDragStart = {
                            dragged = item
                            offset = 0f
                            at = latestItems.indexOf(item)
                        },
                        onDragEnd = { dragged = null },
                        onDragCancel = { dragged = null },
                    ) { change, amount ->
                        change.consume()
                        offset += amount.y
                        if (step <= 0f || at < 0) return@detectDragGestures
                        if (offset > step / 2 && at < latestItems.size - 1) {
                            move(at, at + 1)
                            at++
                            offset -= step
                        } else if (offset < -step / 2 && at > 0) {
                            move(at, at - 1)
                            at--
                            offset += step
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { step = it.height + gapPx }
                        // The dragged row follows the finger, over the others
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer { translationY = if (moving) offset else 0f }
                        .then(if (moving) Modifier.shadow(8.dp, RoundedCornerShape(20.dp)) else Modifier),
                ) {
                    row(item, index, handle)
                }
            }
        }
    }
}
