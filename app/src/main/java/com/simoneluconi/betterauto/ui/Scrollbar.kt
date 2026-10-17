package com.simoneluconi.betterauto.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlin.math.ceil

/**
 * A thin bar on the right edge of a list that scrolls, so the driver sees at a glance that there is more
 * and where they are. Shown only while the content is taller than the list. Put it before the scroll
 * modifier (or on the lazy list itself), so it is drawn over the visible part, not the scrolled content.
 */
@Composable
fun Modifier.scrollbar(state: ScrollState): Modifier =
    drawScrollbar(barColor()) { viewport ->
        if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) null
        else (state.value / (viewport + state.maxValue)) to (viewport / (viewport + state.maxValue))
    }

@Composable
fun Modifier.scrollbar(state: LazyListState): Modifier =
    drawScrollbar(barColor()) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || !(state.canScrollForward || state.canScrollBackward)) return@drawScrollbar null
        // Item sizes vary: estimate the whole list from the ones on screen
        val average = items.sumOf { it.size }.toFloat() / items.size + info.mainAxisItemSpacing
        val total = average * info.totalItemsCount
        val offset = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
        fractions(offset, viewportOf(info.viewportEndOffset - info.viewportStartOffset), total)
    }

@Composable
fun Modifier.scrollbar(state: LazyGridState): Modifier =
    drawScrollbar(barColor()) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || !(state.canScrollForward || state.canScrollBackward)) return@drawScrollbar null
        val columns = items.maxOf { it.column } + 1
        val rowHeight = items.sumOf { it.size.height }.toFloat() / items.size + info.mainAxisItemSpacing
        val total = rowHeight * ceil(info.totalItemsCount / columns.toFloat())
        val offset = state.firstVisibleItemIndex / columns * rowHeight + state.firstVisibleItemScrollOffset
        fractions(offset, viewportOf(info.viewportEndOffset - info.viewportStartOffset), total)
    }

/** verticalScroll with the bar. */
@Composable
fun Modifier.verticalScrollWithBar(state: ScrollState = rememberScrollState()): Modifier = scrollbar(state).verticalScroll(state)

/** A LazyColumn with the bar. */
@Composable
fun LazyColumnWithBar(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) = LazyColumn(modifier.scrollbar(state), state = state, verticalArrangement = verticalArrangement, content = content)

/** A LazyVerticalGrid with the bar. */
@Composable
fun LazyVerticalGridWithBar(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyGridScope.() -> Unit,
) = LazyVerticalGrid(
    columns, modifier.scrollbar(state), state = state,
    horizontalArrangement = horizontalArrangement, verticalArrangement = verticalArrangement, content = content,
)

@Composable
private fun barColor(): Color = LocalCarColors.current.text2.copy(alpha = 0.45f)

private fun viewportOf(px: Int) = px.toFloat()

/** Thumb start and length as fractions of the track. */
private fun fractions(offset: Float, viewport: Float, total: Float): Pair<Float, Float>? {
    if (total <= viewport || total <= 0f) return null
    return (offset / total).coerceIn(0f, 1f) to (viewport / total).coerceIn(0f, 1f)
}

/** [thumb] gets the visible height and returns the thumb's start and length as fractions, or null for no bar. */
private fun Modifier.drawScrollbar(color: Color, thumb: (viewport: Float) -> Pair<Float, Float>?): Modifier =
    drawWithContent {
        drawContent()
        val (start, length) = thumb(size.height) ?: return@drawWithContent
        val width = 4.dp.toPx()
        val inset = 2.dp.toPx()
        val track = size.height - 2 * inset
        val thumbLength = maxOf(track * length, 36.dp.toPx())
        val top = inset + (track - thumbLength) * (start / (1f - length).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        drawRoundRect(
            color,
            topLeft = Offset(size.width - width - inset, top),
            size = Size(width, thumbLength),
            cornerRadius = CornerRadius(width / 2),
        )
    }
