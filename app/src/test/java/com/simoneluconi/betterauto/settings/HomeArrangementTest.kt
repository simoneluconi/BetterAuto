package com.simoneluconi.betterauto.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArrangementTest {
    private val music = WidgetChoice(WidgetType.MUSIC)
    private val notifications = WidgetChoice(WidgetType.NOTIFICATIONS)
    private val favorites = WidgetChoice(WidgetType.FAVORITES)
    private val clock = WidgetChoice(WidgetType.CLOCK)
    private val weather = WidgetChoice(WidgetType.WEATHER)
    private val calendar = WidgetChoice(WidgetType.CALENDAR)
    private val map = WidgetChoice(WidgetType.MAP)

    /** Lines of places, each place its widgets; even sizes. */
    private fun home(rows: Boolean, vararg lines: List<List<WidgetChoice>>) =
        HomeArrangement(rows, lines.map { line -> HomeLine(line.map { HomePlace(it) }) })

    /** How many places each line has. */
    private fun shape(home: HomeArrangement) = home.lines.map { it.places.size }

    @Test
    fun thePlusFillsTheScreenLikeAGrid() {
        var home = home(false, listOf(listOf(music)))
        val sizes = mutableListOf(shape(home))
        for (choice in listOf(clock, notifications, favorites, calendar, weather)) {
            home = home.add(choice)
            sizes += shape(home)
        }
        assertEquals(listOf(listOf(1), listOf(1, 1), listOf(1, 2), listOf(2, 2), listOf(2, 2, 1), listOf(2, 2, 2)), sizes)
        assertEquals(listOf(music, favorites, clock, notifications, calendar, weather), home.mains)
        // Full: nothing more
        assertSame(home, home.add(map))
    }

    @Test
    fun addingAWidgetHeldASwipeAwayTakesItOutOfThere() {
        val home = home(false, listOf(listOf(music, map)), listOf(listOf(clock)))
        val added = home.add(map)
        assertEquals(listOf(music, clock, map), added.mains)
        assertEquals(listOf(music), added.place(0)!!.pages)
        // Already shown: nothing
        assertSame(home, home.add(clock))
    }

    @Test
    fun removingAPlaceDropsItsEmptyLineButNeverTheLastPlace() {
        val home = home(false, listOf(listOf(music)), listOf(listOf(clock), listOf(notifications)))
        assertEquals(listOf(1, 1), shape(home.remove(1)))
        assertEquals(listOf(clock, notifications), home.remove(0).mains)
        assertEquals(listOf(2), shape(home.remove(0)))
        val one = home(true, listOf(listOf(music)))
        assertSame(one, one.remove(0))
    }

    @Test
    fun swappingKeepsTheSizesWhereTheyAre() {
        val home = home(false, listOf(listOf(music)), listOf(listOf(clock), listOf(notifications))).withLineShare(0, 0.7f)
        val swapped = home.move(0, HomeDrop.Swap(2))
        assertEquals(listOf(notifications, clock, music), swapped.mains)
        assertEquals(home.lines.map { it.weight }, swapped.lines.map { it.weight })
    }

    @Test
    fun movingBesideAnotherOrOnItsOwnLine() {
        // Portrait: the map on top, the clock and the music below it
        val home = home(true, listOf(listOf(map)), listOf(listOf(clock), listOf(music)))
        // The music up beside the map
        val beside = home.move(2, HomeDrop.Beside(0, 1))
        assertEquals(listOf(2, 1), shape(beside))
        assertEquals(listOf(map, music, clock), beside.mains)
        // The clock on a row of its own at the bottom
        val ownLine = home.move(1, HomeDrop.NewLine(2))
        assertEquals(listOf(1, 1, 1), shape(ownLine))
        assertEquals(listOf(map, music, clock), ownLine.mains)
        // The map alone on its row moved to the end of the second: its empty row goes
        val joined = home.move(0, HomeDrop.Beside(1, 2))
        assertEquals(listOf(3), shape(joined))
        assertEquals(listOf(clock, music, map), joined.mains)
        // Within its own line, to the front
        assertEquals(listOf(map, music, clock), home.move(2, HomeDrop.Beside(1, 0)).mains)
    }

    @Test
    fun dropsLeaveOutWhereThePlaceAlreadyIsAndFullLines() {
        val home = home(false, listOf(listOf(music)), listOf(listOf(clock), listOf(notifications), listOf(favorites)))
        val drops = home.drops(0)
        // Alone on its line: no new line right before or after itself, nor beside itself
        assertFalse(HomeDrop.NewLine(0) in drops)
        assertFalse(HomeDrop.NewLine(1) in drops)
        assertTrue(HomeDrop.NewLine(2) in drops)
        assertFalse(HomeDrop.Beside(0, 0) in drops)
        // The second line is full
        assertFalse(drops.any { it is HomeDrop.Beside && it.line == 1 })
        assertEquals(3, drops.count { it is HomeDrop.Swap })
        // A drop it can't take changes nothing
        assertSame(home, home.move(0, HomeDrop.Beside(1, 0)))
    }

    @Test
    fun pickingAWidgetShownElsewhereSwapsTheTwo() {
        val home = home(false, listOf(listOf(music, map)), listOf(listOf(clock), listOf(notifications)))
        val picked = home.pick(0, clock)
        assertEquals(listOf(clock, music, notifications), picked.mains)
        // Its swipe-away pages stay with the place
        assertEquals(listOf(clock, map), picked.place(0)!!.pages)
        // One held a swipe away comes out of there
        val fromStack = home.pick(1, map)
        assertEquals(listOf(music), fromStack.place(0)!!.pages)
        assertEquals(listOf(music, map, notifications), fromStack.mains)
    }

    @Test
    fun pagesLeaveTheOtherPlacesButNotWhatTheyShow() {
        val home = home(false, listOf(listOf(music, map)), listOf(listOf(clock)))
        val changed = home.withPages(1, listOf(clock, map, music))
        assertEquals(listOf(clock, map), changed.place(1)!!.pages)
        assertEquals(listOf(music), changed.place(0)!!.pages)
        assertSame(home, home.withPages(1, emptyList()))
    }

    @Test
    fun sharesStayWithinBoundsAndCanBeEvenedOut() {
        val home = home(false, listOf(listOf(music)), listOf(listOf(clock), listOf(notifications)))
        val wide = home.withLineShare(0, 0.95f)
        assertEquals(1f - HomeArrangement.MIN_SHARE, wide.lines[0].weight / (wide.lines[0].weight + wide.lines[1].weight), 0.001f)
        val tall = home.withPlaceShare(1, 0, 0.3f)
        assertEquals(0.3f, tall.lines[1].places[0].weight / 2f, 0.001f)
        assertTrue(tall.resized)
        assertFalse(tall.evenSizes().resized)
        assertSame(home, home.withPlaceShare(0, 0, 0.5f))
    }

    @Test
    fun aSmallScreenStacksWhatDoesNotFitInsteadOfLosingIt() {
        val home = home(true, listOf(listOf(map), listOf(clock)), listOf(listOf(music), listOf(notifications), listOf(favorites)), listOf(listOf(weather)))
        val fitted = home.fit(maxLines = 2, maxPerLine = 2)
        assertEquals(listOf(2, 2), shape(fitted))
        assertEquals(listOf(map, clock, music, notifications), fitted.mains)
        assertEquals(listOf(notifications, favorites, weather), fitted.place(3)!!.pages)
        assertEquals(home.widgets.toSet(), fitted.widgets.toSet())
        assertSame(home, home.fit(3, 3))
    }

    @Test
    fun turningSwapsRowsAndColumns() {
        val landscape = HomeArrangement.default(ScreenShape.LANDSCAPE)
        assertFalse(landscape.rows)
        assertEquals(landscape.lines, landscape.turned().lines)
        assertTrue(HomeArrangement.default(ScreenShape.PORTRAIT).rows)
        assertEquals(listOf(music, clock, notifications), landscape.mains)
    }

    @Test
    fun savedAndReadBack() {
        val group = WidgetChoice(WidgetType.SMART_HOME, "ha/kitchen, upstairs;1=2/3")
        val home = home(true, listOf(listOf(map, group)), listOf(listOf(clock), listOf(music)))
            .withLineShare(0, 0.65f).withPlaceShare(1, 0, 0.4f)
        assertEquals(home, HomeArrangement.decode(home.encode()))
    }

    @Test
    fun aSavedHomeIsReadCarefully() {
        assertNull(HomeArrangement.decode(null))
        assertNull(HomeArrangement.decode(""))
        assertNull(HomeArrangement.decode("X/1=1=MUSIC"))
        // Unknown widgets and a widget twice are dropped, a place left empty goes, odd sizes become even
        val read = HomeArrangement.decode("C/abc=1=MUSIC,GONE;1=GONE/1=1=CLOCK;1=MUSIC")!!
        assertEquals(listOf(1, 1), shape(read))
        assertEquals(listOf(music, clock), read.mains)
        assertEquals(1f, read.lines[0].weight)
        // A smart-home group without its parameter means nothing
        assertNull(HomeArrangement.decode("R/1=1=SMART_HOME"))
        // Never more than the most places
        val many = "R" + List(5) { "/1=1=${listOf("MUSIC", "CLOCK", "MAP", "WEATHER", "CALENDAR")[it]};1=${listOf("NOTIFICATIONS", "FAVORITES", "WEATHER", "MAP", "CLOCK")[it]}" }.joinToString("")
        assertEquals(HomeArrangement.MAX_PLACES, HomeArrangement.decode(many)!!.count)
    }

    @Test
    fun roomForPlacesOnDifferentScreens() {
        // A usual landscape home area, a portrait one, a short and wide one, a small one
        assertEquals(6, HomeRoom.capacity(1130f, 610f, 20f))
        assertEquals(6, HomeRoom.capacity(680f, 1100f, 20f))
        assertEquals(2 to 4, HomeRoom.of(rows = true, width = 1130f, height = 450f, gap = 20f))
        assertEquals(4 to 2, HomeRoom.of(rows = false, width = 1130f, height = 450f, gap = 20f))
        assertEquals(1, HomeRoom.capacity(270f, 190f, 20f))
        assertEquals(ScreenShape.LANDSCAPE, ScreenShape.of(1000f, 1000f))
        assertEquals(ScreenShape.PORTRAIT, ScreenShape.of(700f, 1100f))
    }
}
