package com.simoneluconi.betterauto.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** One occurrence of a calendar event, as the calendar provider lists it. */
data class CalendarInstance(
    val title: String,
    /** Epoch ms; for all-day events, UTC midnight of the first day (the provider's convention). */
    val begin: Long,
    /** Epoch ms, exclusive; for all-day events, UTC midnight after the last day. */
    val end: Long,
    val allDay: Boolean,
    val location: String? = null,
    /** The calendar's (or event's) color, ARGB. */
    val color: Int? = null,
)

/** A calendar on the phone (one per account: personal, work, holidays...). */
data class PhoneCalendar(val id: Long, val name: String, val account: String, val color: Int?) {
    companion object {
        /** Chip labels: the name, with the account only where two calendars share a name. */
        fun labels(calendars: List<PhoneCalendar>): Map<Long, String> {
            val repeated = calendars.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            return calendars.associate { it.id to if (it.name in repeated && it.account.isNotBlank()) "${it.name} · ${it.account}" else it.name }
        }
    }
}

enum class AgendaDay { TODAY, TOMORROW }

data class AgendaEvent(val instance: CalendarInstance, val day: AgendaDay, val ongoing: Boolean)

/** Which events the calendar widget shows: what is still ahead today, then tomorrow. Pure, for tests. */
object Agenda {
    /**
     * The time span to ask the provider for: a day either side of today and tomorrow, so all-day events
     * (stored in UTC) are not cut off by the time zone; [select] then keeps the right ones.
     */
    fun queryWindow(now: ZonedDateTime): Pair<Long, Long> {
        val today = now.toLocalDate().atStartOfDay(now.zone)
        return today.minusDays(1).toInstant().toEpochMilli() to today.plusDays(3).toInstant().toEpochMilli()
    }

    /**
     * Events not over yet, today's and tomorrow's: by day, timed events first in order of start, the
     * day's all-day events after them (they rarely need the driver). The same event in two calendars
     * (shared ones) is shown once.
     */
    fun select(instances: List<CalendarInstance>, now: ZonedDateTime): List<AgendaEvent> {
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val nowMs = now.toInstant().toEpochMilli()
        val tomorrowStart = tomorrow.atStartOfDay(now.zone).toInstant().toEpochMilli()
        val afterTomorrow = tomorrow.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        return instances
            .distinctBy { listOf(it.title, it.begin, it.end, it.allDay) }
            .mapNotNull { event ->
                if (event.allDay) {
                    val first = utcDate(event.begin)
                    val last = utcDate(event.end).let { if (it > first) it.minusDays(1) else first }
                    when {
                        today in first..last -> AgendaEvent(event, AgendaDay.TODAY, ongoing = true)
                        tomorrow in first..last -> AgendaEvent(event, AgendaDay.TOMORROW, ongoing = false)
                        else -> null
                    }
                } else {
                    val over = event.end <= nowMs && event.begin < nowMs
                    when {
                        over -> null
                        event.begin < tomorrowStart -> AgendaEvent(event, AgendaDay.TODAY, ongoing = event.begin <= nowMs)
                        event.begin < afterTomorrow -> AgendaEvent(event, AgendaDay.TOMORROW, ongoing = false)
                        else -> null
                    }
                }
            }
            .sortedWith(compareBy({ it.day }, { it.instance.allDay }, { it.instance.begin }, { it.instance.title }))
    }

    private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
}
