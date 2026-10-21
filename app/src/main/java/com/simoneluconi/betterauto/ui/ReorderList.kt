package com.simoneluconi.betterauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

private val RowGap = 8.dp

/**
 * A short list the driver puts in order on the phone by dragging each row by its handle: the row follows
 * the finger and swaps with its neighbour past half of it. [onMoved] gets the new order once, on release.
 * The handle, not the whole row, takes the drag: the page around it still scrolls.
 */
@Composable
fun <T> ReorderList(
    items: List<T>,
    key: (T) -> Any,
    onMoved: (List<T>) -> Unit,
    row: @Composable (item: T, dragging: Boolean, handle: Modifier) -> Unit,
) {
    var list by remember(items.map(key)) { mutableStateOf(items) }
    var dragged by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val gap = with(LocalDensity.current) { RowGap.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        for (item in list) androidx.compose.runtime.key(key(item)) {
            // Keyed: a row keeps its gesture while it moves past its neighbours
            val k = key(item)
            val dragging = k == dragged
            val handle = Modifier.pointerInput(k) {
                detectDragGestures(
                    onDragStart = {
                        dragged = k
                        offset = 0f
                    },
                    onDragEnd = {
                        dragged = null
                        offset = 0f
                        onMoved(list)
                    },
                    onDragCancel = {
                        dragged = null
                        offset = 0f
                    },
                ) { change, drag ->
                    change.consume()
                    offset += drag.y
                    val i = list.indexOfFirst { key(it) == k }
                    if (offset > 0 && i < list.lastIndex) {
                        val step = (heights[key(list[i + 1])] ?: 0) + gap
                        if (offset > step / 2) {
                            list = list.toMutableList().apply { add(i + 1, removeAt(i)) }
                            offset -= step
                        }
                    } else if (offset < 0 && i > 0) {
                        val step = (heights[key(list[i - 1])] ?: 0) + gap
                        if (-offset > step / 2) {
                            list = list.toMutableList().apply { add(i - 1, removeAt(i)) }
                            offset += step
                        }
                    }
                }
            }
            Box(
                Modifier
                    .onSizeChanged { heights[k] = it.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f },
            ) {
                row(item, dragging, handle)
            }
        }
    }
}

/** One row of a [ReorderList]: its name and the handle, lifted while it is dragged. */
@Composable
fun DragRow(label: String, dragging: Boolean, handle: Modifier) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dragging) colors.surface else colors.surface2, shape)
            .border(1.dp, if (dragging) colors.focus else colors.line, shape)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarLabel(label, 16.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 2)
        // Big enough for a thumb
        Box(handle.size(52.dp), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Grip, colors.text2, Modifier.size(24.dp))
        }
    }
}
