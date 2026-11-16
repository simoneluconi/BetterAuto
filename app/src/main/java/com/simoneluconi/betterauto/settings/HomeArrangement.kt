package com.simoneluconi.betterauto.settings

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a home place shows: a widget type and, for types that need one, a parameter (e.g. which smart-home
 * group). Types without a parameter leave it null.
 */
data class WidgetChoice(val type: WidgetType, val param: String? = null) {
    /** "TYPE" or "TYPE:param", the parameter URL-encoded so it never holds the separators of a saved home. */
    fun encode(): String = if (param == null) type.name else "${type.name}:${URLEncoder.encode(param, "UTF-8")}"

    companion object {
        fun decode(text: String): WidgetChoice? {
            val name = text.substringBefore(':').trim()
            val type = WidgetType.entries.firstOrNull { it.name == name } ?: return null
            val param = text.substringAfter(':', "").takeIf { ':' in text }?.let { URLDecoder.decode(it, "UTF-8") }
            return WidgetChoice(type, param)
        }
    }
}

/** The kinds of car screen the home keeps an arrangement for: a car with the other kind doesn't upset it. */
enum class ScreenShape {
    LANDSCAPE,
    PORTRAIT;

    companion object {
        // A square screen goes with landscape, as the launcher's own layout does
        fun of(width: Float, height: Float): ScreenShape = if (width < height) PORTRAIT else LANDSCAPE
    }
}

/** One place of the home: the widgets it holds (the first shown, the others a swipe away) and its share of its line. */
data class HomePlace(val pages: List<WidgetChoice>, val weight: Float = 1f) {
    val main: WidgetChoice get() = pages.first()
}

/** Places side by side, and the line's share of the screen. */
data class HomeLine(val places: List<HomePlace>, val weight: Float = 1f)

/** Where a place being moved can go. Line and position indices are those before the move. */
sealed interface HomeDrop {
    /** On another place: the two swap what they hold, the sizes stay. */
    data class Swap(val place: Int) : HomeDrop

    /** Into line [line], before its place [at] (its size for the end). */
    data class Beside(val line: Int, val at: Int) : HomeDrop

    /** On a line of its own, before line [at] (the count of lines for the end). */
    data class NewLine(val at: Int) : HomeDrop
}

/**
 * The home's widgets as the driver arranged them: [lines] one above the other with their places side by side
 * ([rows]), or side by side with their places stacked (columns). Places are numbered in reading order, line by
 * line. A widget is in one place at most. Immutable: every change returns a new arrangement.
 */
data class HomeArrangement(val rows: Boolean, val lines: List<HomeLine>) {
    val places: List<HomePlace> get() = lines.flatMap { it.places }
    val count: Int get() = lines.sumOf { it.places.size }
    val mains: List<WidgetChoice> get() = places.map { it.main }

    /** Every widget it holds, shown or a swipe away. */
    val widgets: List<WidgetChoice> get() = places.flatMap { it.pages }

    /** The line and position of place [index], or null. */
    fun locate(index: Int): Pair<Int, Int>? {
        var left = index
        lines.forEachIndexed { l, line ->
            if (left < line.places.size) return l to left
            left -= line.places.size
        }
        return null
    }

    /** The number of place [at] of line [line]. */
    fun indexOf(line: Int, at: Int): Int = lines.take(line).sumOf { it.places.size } + at

    fun place(index: Int): HomePlace? = locate(index)?.let { (l, p) -> lines[l].places[p] }

    /** The lines the other way: rows become columns and back, each place keeping its neighbours. */
    fun turned(): HomeArrangement = copy(rows = !rows)

    fun withRows(rows: Boolean): HomeArrangement = copy(rows = rows)

    /**
     * One place more, showing [choice] (taken out of any place that holds it a swipe away): the screen fills
     * as a grid would, one line, two, then the lines past the first get a second place, then the first, then
     * a new line. Nothing changes at [MAX_PLACES] or when [choice] is already shown.
     */
    fun add(choice: WidgetChoice): HomeArrangement {
        if (count >= MAX_PLACES || choice in mains) return this
        val base = without(choice)
        val place = HomePlace(listOf(choice))
        val lines = base.lines.map { it.copy(places = it.places.toMutableList()) }.toMutableList()
        val target = when {
            lines.size == 1 && lines[0].places.size == 1 -> null
            else -> (lines.indices.drop(1).reversed().firstOrNull { lines[it].places.size < 2 })
                ?: (0.takeIf { lines[0].places.size < 2 })
        }
        if (target == null) {
            lines += HomeLine(listOf(place), lines.map { it.weight }.average().toFloat())
        } else {
            val line = lines[target]
            lines[target] = line.copy(places = line.places + place.copy(weight = line.places.map { it.weight }.average().toFloat()))
        }
        return copy(lines = lines)
    }

    /** Without place [index] and what it holds; the last place stays. */
    fun remove(index: Int): HomeArrangement {
        if (count <= 1) return this
        val (l, p) = locate(index) ?: return this
        return copy(lines = lines.mapIndexed { i, line -> if (i == l) line.copy(places = line.places.filterIndexed { j, _ -> j != p }) else line }
            .filter { it.places.isNotEmpty() })
    }

    /** The drops place [index] can take: every other place, every gap in a line with room, every gap between lines. */
    fun drops(index: Int): List<HomeDrop> {
        val (from, at) = locate(index) ?: return emptyList()
        val alone = lines[from].places.size == 1
        val result = mutableListOf<HomeDrop>()
        for (i in 0 until count) if (i != index) result += HomeDrop.Swap(i)
        lines.forEachIndexed { l, line ->
            val own = l == from
            if (!own && line.places.size >= MAX_PER_LINE) return@forEachIndexed
            for (gap in 0..line.places.size) {
                // Where it already is: either side of itself
                if (own && (gap == at || gap == at + 1)) continue
                result += HomeDrop.Beside(l, gap)
            }
        }
        for (gap in 0..lines.size) {
            if (alone && (gap == from || gap == from + 1)) continue
            result += HomeDrop.NewLine(gap)
        }
        return result
    }

    /** Place [index] moved to [drop]; lines left empty go. A drop it can't take ([drops]) changes nothing. */
    fun move(index: Int, drop: HomeDrop): HomeArrangement {
        if (drop !in drops(index)) return this
        val (from, at) = locate(index) ?: return this
        val moving = lines[from].places[at]
        if (drop is HomeDrop.Swap) {
            val (tl, tp) = locate(drop.place) ?: return this
            val other = lines[tl].places[tp]
            return copy(lines = lines.mapIndexed { l, line ->
                line.copy(places = line.places.mapIndexed { p, place ->
                    when {
                        l == from && p == at -> place.copy(pages = other.pages)
                        l == tl && p == tp -> place.copy(pages = moving.pages)
                        else -> place
                    }
                })
            })
        }
        // Null marks the place taken out until the empty lines are dropped, so the indices of the drop hold
        val work: MutableList<HomeLine?> = lines.mapIndexed { l, line ->
            if (l == from) line.copy(places = line.places.filterIndexed { p, _ -> p != at }) else line
        }.toMutableList()
        when (drop) {
            is HomeDrop.Beside -> {
                val line = work[drop.line]!!
                val gap = if (drop.line == from && drop.at > at) drop.at - 1 else drop.at
                val weight = (line.places.map { it.weight }.takeIf { it.isNotEmpty() }?.average()?.toFloat()) ?: moving.weight
                work[drop.line] = line.copy(places = line.places.toMutableList().apply { add(gap, moving.copy(weight = weight)) })
            }
            is HomeDrop.NewLine -> {
                val weight = lines.map { it.weight }.average().toFloat()
                work.add(drop.at, HomeLine(listOf(moving.copy(weight = 1f)), weight))
            }
            is HomeDrop.Swap -> Unit
        }
        return copy(lines = work.filterNotNull().filter { it.places.isNotEmpty() })
    }

    /**
     * Place [index] shows [choice] first: a widget another place shows swaps with this one's, one held a swipe
     * away anywhere comes out of there.
     */
    fun pick(index: Int, choice: WidgetChoice): HomeArrangement {
        val target = place(index) ?: return this
        if (target.main == choice) return this
        val elsewhere = mains.indexOf(choice).takeIf { it >= 0 && it != index }
        val updated = mapPlaces { i, place ->
            when {
                i == index -> place.copy(pages = listOf(choice) + place.pages.drop(1).filter { it != choice })
                i == elsewhere -> place.copy(pages = listOf(target.main) + place.pages.drop(1).filter { it != target.main })
                else -> place.copy(pages = place.pages.filterIndexed { p, it -> p == 0 || it != choice })
            }
        }
        return updated
    }

    /** Place [index] holds [pages] in this order; they leave the others' swipe-away pages. Never empty. */
    fun withPages(index: Int, pages: List<WidgetChoice>): HomeArrangement {
        val list = pages.distinct()
        if (list.isEmpty() || place(index) == null) return this
        // Another place's shown widget stays there
        val shownElsewhere = mains.filterIndexed { i, _ -> i != index }
        val own = list.filter { it !in shownElsewhere }.ifEmpty { return this }
        return mapPlaces { i, place ->
            if (i == index) place.copy(pages = own) else place.copy(pages = listOf(place.main) + place.pages.drop(1).filter { it !in own })
        }
    }

    /** The pair of lines [first] and the next one share their space [share] to [first]. */
    fun withLineShare(first: Int, share: Float): HomeArrangement {
        if (first !in 0 until lines.size - 1) return this
        val sum = lines[first].weight + lines[first + 1].weight
        val s = share.coerceIn(MIN_SHARE, 1f - MIN_SHARE)
        return copy(lines = lines.mapIndexed { i, line ->
            when (i) {
                first -> line.copy(weight = sum * s)
                first + 1 -> line.copy(weight = sum * (1f - s))
                else -> line
            }
        })
    }

    /** In line [line], places [first] and the next share their space [share] to [first]. */
    fun withPlaceShare(line: Int, first: Int, share: Float): HomeArrangement {
        val places = lines.getOrNull(line)?.places ?: return this
        if (first !in 0 until places.size - 1) return this
        val sum = places[first].weight + places[first + 1].weight
        val s = share.coerceIn(MIN_SHARE, 1f - MIN_SHARE)
        return copy(lines = lines.mapIndexed { l, ln ->
            if (l != line) ln else ln.copy(places = ln.places.mapIndexed { p, place ->
                when (p) {
                    first -> place.copy(weight = sum * s)
                    first + 1 -> place.copy(weight = sum * (1f - s))
                    else -> place
                }
            })
        })
    }

    /** Every line and place the same size. */
    fun evenSizes(): HomeArrangement = copy(lines = lines.map { line -> HomeLine(line.places.map { it.copy(weight = 1f) }, 1f) })

    /** Whether any size differs from [evenSizes]. */
    val resized: Boolean get() = this != evenSizes()

    /**
     * What a screen with room for [maxLines] lines of [maxPerLine] places shows: lines past the room join the last
     * one that fits, places past the room go a swipe away in the last place that fits. Never loses a widget.
     */
    fun fit(maxLines: Int, maxPerLine: Int): HomeArrangement {
        val keepLines = maxLines.coerceAtLeast(1)
        val keepPlaces = maxPerLine.coerceAtLeast(1)
        if (lines.size <= keepLines && lines.all { it.places.size <= keepPlaces }) return this
        val merged = lines.take(keepLines).toMutableList()
        if (lines.size > keepLines) {
            val last = merged.last()
            merged[merged.size - 1] = last.copy(places = last.places + lines.drop(keepLines).flatMap { it.places })
        }
        return copy(lines = merged.map { line ->
            if (line.places.size <= keepPlaces) line else {
                val kept = line.places.take(keepPlaces).toMutableList()
                val end = kept.last()
                kept[kept.size - 1] = end.copy(pages = (end.pages + line.places.drop(keepPlaces).flatMap { it.pages }).distinct())
                line.copy(places = kept)
            }
        })
    }

    /** "R" or "C", then the lines split by '/', each "weight=place;place", each place "weight=WIDGET,WIDGET". */
    fun encode(): String = (if (rows) "R" else "C") + lines.joinToString("") { line ->
        "/" + line.weight + "=" + line.places.joinToString(";") { place -> place.weight.toString() + "=" + place.pages.joinToString(",") { it.encode() } }
    }

    private fun without(choice: WidgetChoice): HomeArrangement =
        mapPlaces { _, place -> place.copy(pages = place.pages.filterIndexed { p, it -> p == 0 || it != choice }) }

    private fun mapPlaces(transform: (Int, HomePlace) -> HomePlace): HomeArrangement {
        var index = 0
        return copy(lines = lines.map { line -> line.copy(places = line.places.map { transform(index++, it) }) })
    }

    companion object {
        const val MAX_PLACES = 6
        const val MAX_PER_LINE = 3

        /** The smallest share of two neighbours: beyond it a widget gets too thin to show anything. */
        const val MIN_SHARE = 0.2f

        /**
         * Out of the box: music big, then the time (with the outside temperature) and the notifications, nothing to
         * set up on the phone first. Columns on a landscape screen, rows on a portrait one.
         */
        fun default(shape: ScreenShape): HomeArrangement = HomeArrangement(
            rows = shape == ScreenShape.PORTRAIT,
            lines = listOf(
                HomeLine(listOf(HomePlace(listOf(WidgetChoice(WidgetType.MUSIC)))), 0.6f),
                HomeLine(
                    listOf(HomePlace(listOf(WidgetChoice(WidgetType.CLOCK)), if (shape == ScreenShape.PORTRAIT) 1f else 0.6f),
                        HomePlace(listOf(WidgetChoice(WidgetType.NOTIFICATIONS)), if (shape == ScreenShape.PORTRAIT) 1f else 0.4f)),
                    0.4f,
                ),
            ),
        )

        /**
         * A saved arrangement, or null when there is none or nothing in it can be read. Unknown widgets are dropped,
         * a widget held twice keeps its first place, sizes out of range become even; never more than [MAX_PLACES]
         * places, [MAX_PER_LINE] a line.
         */
        fun decode(saved: String?): HomeArrangement? {
            if (saved.isNullOrEmpty()) return null
            val rows = when (saved.first()) {
                'R' -> true
                'C' -> false
                else -> return null
            }
            val seen = HashSet<WidgetChoice>()
            var placesLeft = MAX_PLACES
            val lines = saved.drop(1).split('/').filter { it.isNotEmpty() }.mapNotNull { lineText ->
                val lineWeight = weight(lineText.substringBefore('='))
                val places = lineText.substringAfter('=', "").split(';').mapNotNull { placeText ->
                    val placeWeight = weight(placeText.substringBefore('='))
                    val pages = placeText.substringAfter('=', "").split(',').mapNotNull { WidgetChoice.decode(it) }
                        .filter { (!it.type.takesParam || it.param != null) && seen.add(it) }
                    if (pages.isEmpty()) null else HomePlace(pages, placeWeight)
                }.take(MAX_PER_LINE).take(placesLeft)
                placesLeft -= places.size
                if (places.isEmpty()) null else HomeLine(places, lineWeight)
            }
            return if (lines.isEmpty()) null else HomeArrangement(rows, lines)
        }

        private fun weight(text: String): Float = text.toFloatOrNull()?.takeIf { it in 0.05f..20f } ?: 1f
    }
}

/**
 * How many lines and places a screen area has room for, in design units: a place is never smaller than
 * [MIN_WIDTH] by [MIN_HEIGHT], so a widget stays readable at a glance.
 */
object HomeRoom {
    const val MIN_WIDTH = 260f
    const val MIN_HEIGHT = 180f

    /** Lines that fit along the screen, places that fit along a line, for [rows] or columns. */
    fun of(rows: Boolean, width: Float, height: Float, gap: Float): Pair<Int, Int> {
        val across = fits(width, MIN_WIDTH, gap)
        val down = fits(height, MIN_HEIGHT, gap)
        return if (rows) down to across else across to down
    }

    /** How many places the screen holds at most, whatever their arrangement. */
    fun capacity(width: Float, height: Float, gap: Float): Int =
        (fits(width, MIN_WIDTH, gap) * fits(height, MIN_HEIGHT, gap)).coerceIn(1, HomeArrangement.MAX_PLACES)

    private fun fits(length: Float, min: Float, gap: Float): Int = ((length + gap) / (min + gap)).toInt().coerceAtLeast(1)
}
