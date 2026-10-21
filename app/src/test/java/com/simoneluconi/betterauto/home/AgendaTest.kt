package com.simoneluconi.betterauto.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AgendaTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, zone)

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun timed(title: String, from: Long, to: Long) = CalendarInstance(title, from, to, allDay = false)

    private fun allDay(title: String, first: LocalDate, days: Long = 1) = CalendarInstance(
        title, first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        first.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true,
    )

    @Test
    fun keepsWhatIsAheadTodayAndTomorrowInOrder() {
        val events = listOf(
            timed("Later", at(21, 18), at(21, 19)),
            timed("Over", at(21, 9), at(21, 10)),
            timed("Ongoing", at(21, 13, 30), at(21, 14, 30)),
            timed("Tomorrow", at(22, 8), at(22, 9)),
            timed("Day after", at(23, 8), at(23, 9)),
            allDay("Birthday", LocalDate.of(2026, 10, 21)),
        )
        val selected = Agenda.select(events, now)
        assertEquals(listOf("Ongoing", "Later", "Birthday", "Tomorrow"), selected.map { it.instance.title })
        assertEquals(listOf(AgendaDay.TODAY, AgendaDay.TODAY, AgendaDay.TODAY, AgendaDay.TOMORROW), selected.map { it.day })
        assertTrue(selected.first().ongoing)
    }

    @Test
    fun allDayEventsUseTheirOwnDatesWhateverTheTimeZone() {
        // Stored at UTC midnight: in Rome tomorrow's holiday starts at 01:00 or 02:00, still tomorrow
        val holiday = allDay("Holiday", LocalDate.of(2026, 10, 22))
        assertEquals(AgendaDay.TOMORROW, Agenda.select(listOf(holiday), now).single().day)
        // Yesterday's ended, a three-day trip started yesterday is today's
        assertTrue(Agenda.select(listOf(allDay("Yesterday", LocalDate.of(2026, 10, 20))), now).isEmpty())
        assertEquals(AgendaDay.TODAY, Agenda.select(listOf(allDay("Trip", LocalDate.of(2026, 10, 20), 3)), now).single().day)
    }

    @Test
    fun theSameEventInTwoCalendarsIsShownOnce() {
        val event = timed("Shared", at(21, 16), at(21, 17))
        assertEquals(1, Agenda.select(listOf(event, event.copy(color = 1)), now).size)
    }

    @Test
    fun calendarsWithTheSameNameAreToldApartByAccount() {
        val labels = PhoneCalendar.labels(
            listOf(
                PhoneCalendar(1, "Personal", "mario@example.com", null),
                PhoneCalendar(2, "Personal", "work@example.com", null),
                PhoneCalendar(3, "Holidays", "mario@example.com", null),
            )
        )
        assertEquals("Personal · mario@example.com", labels[1])
        assertEquals("Personal · work@example.com", labels[2])
        assertEquals("Holidays", labels[3])
    }

    @Test
    fun theQueryCoversADayEitherSide() {
        val (from, to) = Agenda.queryWindow(now)
        assertEquals(at(20, 0), from)
        assertEquals(at(24, 0), to)
    }
}
