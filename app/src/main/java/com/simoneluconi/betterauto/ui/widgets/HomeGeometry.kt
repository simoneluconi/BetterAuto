package com.simoneluconi.betterauto.ui.widgets

import com.simoneluconi.betterauto.settings.HomeArrangement
import com.simoneluconi.betterauto.settings.HomeDrop

/** A rectangle in dp. */
internal data class Area(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2

    fun contains(px: Float, py: Float): Boolean = px in x..right && py in y..bottom
}

/**
 * A line between two neighbours that a finger drags: between lines [line] and the next ([place] null), or between
 * places [place] and the next of line [line]. [acrossX]: it moves along x (a vertical line). [travel] is how far
 * the two reach together, what a share is of; [reversed] when the share grows toward the start (mirrored).
 */
internal data class HomeDivider(
    val line: Int,
    val place: Int?,
    val area: Area,
    val acrossX: Boolean,
    val share: Float,
    val travel: Float,
    val reversed: Boolean,
)

/**
 * Where everything of [home] goes in a [width] by [height] area (dp): its places in reading order, the lines
 * between them, the drops of a place being moved. [mirrored] (right-hand drive) flips it left to right, so the
 * first place is on the driver's side. The same for the home, its editor and the phone's picture of it.
 *
 * While a place moves, a [gap] and an [inset] (a margin all round) as wide as the drop [bar]s keep the bars clear of
 * the places: they sit between them and around them, never over a widget's name.
 */
internal class HomeGeometry(
    val home: HomeArrangement,
    val width: Float,
    val height: Float,
    private val gap: Float,
    private val mirrored: Boolean = false,
    private val inset: Float = 0f,
    private val bar: Float = BAR,
) {
    private val along = (if (home.rows) height else width) - inset * 2
    private val across = (if (home.rows) width else height) - inset * 2

    /** Where a bar at an outer edge goes: in the margin when there is one, else just inside the edge. */
    private fun edge(start: Boolean, at: Float): Float = when {
        inset >= bar -> if (start) at - inset / 2 else at + inset / 2
        else -> if (start) at + bar / 2 else at - bar / 2
    }

    /** Each line's start and length along the screen. */
    private val lineSpans: List<Pair<Float, Float>> = spans(home.lines.map { it.weight }, along)

    /** Each line's places: start and length across the screen. */
    private val placeSpans: List<List<Pair<Float, Float>>> = home.lines.map { line -> spans(line.places.map { it.weight }, across) }

    val places: List<Area> = home.lines.indices.flatMap { l ->
        placeSpans[l].map { (start, length) -> area(lineSpans[l].first, lineSpans[l].second, start, length) }
    }

    val dividers: List<HomeDivider> = buildList {
        for (l in 0 until home.lines.size - 1) {
            val (a, la) = lineSpans[l]
            val lb = lineSpans[l + 1].second
            // Across the whole screen, in the middle of the gap
            add(divider(l, null, area(a + la, gap, 0f, across), lineAcross = true, share = la / (la + lb), travel = la + lb))
        }
        home.lines.indices.forEach { l ->
            val (a, la) = lineSpans[l]
            val spans = placeSpans[l]
            for (p in 0 until spans.size - 1) {
                val (c, pa) = spans[p]
                val pb = spans[p + 1].second
                add(divider(l, p, area(a, la, c + pa, gap), lineAcross = false, share = pa / (pa + pb), travel = pa + pb))
            }
        }
    }

    /** The place at a point, or null (a gap, outside). */
    fun placeAt(x: Float, y: Float): Int? = places.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }

    /**
     * Where place [index] can be dropped, each with the area that takes it, in reading order: the other places
     * (a swap), a bar in each gap of a line with room (beside), a bar across each gap between lines (own line).
     */
    fun drops(index: Int): List<Pair<HomeDrop, Area>> = home.drops(index).map { drop -> drop to dropArea(drop) }
        .sortedWith(compareBy({ it.second.centerY }, { it.second.centerX }))

    /** The drop at a point: a bar before the place under it. */
    fun dropAt(drops: List<Pair<HomeDrop, Area>>, x: Float, y: Float): HomeDrop? =
        (drops.firstOrNull { it.first !is HomeDrop.Swap && it.second.contains(x, y) } ?: drops.firstOrNull { it.second.contains(x, y) })?.first

    private fun dropArea(drop: HomeDrop): Area = when (drop) {
        is HomeDrop.Swap -> places[drop.place]
        is HomeDrop.Beside -> {
            val (a, la) = lineSpans[drop.line]
            val spans = placeSpans[drop.line]
            val at = when (drop.at) {
                0 -> edge(start = true, spans.first().first)
                spans.size -> edge(start = false, spans.last().let { it.first + it.second })
                else -> spans[drop.at].first - gap / 2
            }
            // Along the middle of the line, clear of the bars between lines
            area(a + la * (1 - BAR_LENGTH) / 2, la * BAR_LENGTH, at - bar / 2, bar)
        }
        is HomeDrop.NewLine -> {
            val at = when (drop.at) {
                0 -> edge(start = true, 0f)
                home.lines.size -> edge(start = false, along)
                else -> lineSpans[drop.at].first - gap / 2
            }
            area(at - bar / 2, bar, across * (1 - BAR_LENGTH) / 2, across * BAR_LENGTH)
        }
    }

    private fun divider(line: Int, place: Int?, area: Area, lineAcross: Boolean, share: Float, travel: Float): HomeDivider {
        // A line between rows lies across x; between the places of a row it moves along x; columns the other way
        val acrossX = home.rows != lineAcross
        val handle = if (acrossX) area.copy(x = area.centerX - HANDLE / 2, width = HANDLE) else area.copy(y = area.centerY - HANDLE / 2, height = HANDLE)
        return HomeDivider(line, place, handle, acrossX, share, travel, reversed = mirrored && acrossX)
    }

    /** A rectangle from a start and length along the lines, and a start and length across them. */
    private fun area(alongStart: Float, alongLength: Float, acrossStart: Float, acrossLength: Float): Area {
        val plain = if (home.rows) {
            Area(inset + acrossStart, inset + alongStart, acrossLength, alongLength)
        } else {
            Area(inset + alongStart, inset + acrossStart, alongLength, acrossLength)
        }
        return if (mirrored) plain.copy(x = width - plain.x - plain.width) else plain
    }

    /** Lengths in proportion to [weights] filling [total] with a [gap] between each two, with their starts. */
    private fun spans(weights: List<Float>, total: Float): List<Pair<Float, Float>> {
        val room = (total - gap * (weights.size - 1)).coerceAtLeast(0f)
        val sum = weights.sum().takeIf { it > 0f } ?: 1f
        var start = 0f
        return weights.map { w ->
            val length = room * w / sum
            (start to length).also { start += length + gap }
        }
    }

    companion object {
        /** How thick the drop bars are, and how much of their line they run along. */
        const val BAR = 48f
        const val BAR_LENGTH = 0.4f

        /** The gap and margin while a place moves, room for the bars. */
        const val MOVE_GAP = BAR + 12f
        const val MOVE_INSET = BAR + 8f

        /** A divider's grip, wider than the gap for the finger. */
        const val HANDLE = 44f
    }
}
