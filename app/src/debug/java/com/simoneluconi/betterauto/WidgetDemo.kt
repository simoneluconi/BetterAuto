package com.simoneluconi.betterauto

import com.simoneluconi.betterauto.home.CalendarEvents
import com.simoneluconi.betterauto.home.CalendarInstance
import com.simoneluconi.betterauto.home.FavoriteContacts
import com.simoneluconi.betterauto.phone.Contact
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Pretend calendar events and favorites for the home widgets (debug), for screenshots without personal data. */
object WidgetDemo {
    fun show() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val tomorrow = today.plusDays(1)
        CalendarEvents.demo.value = listOf(
            CalendarInstance("Team meeting", now - 20 * 60_000, now + 25 * 60_000, false, "Via Roma 12, Milano", 0xFF3B82C4.toInt()),
            CalendarInstance("Dentist", now + 2 * 3_600_000, now + 3 * 3_600_000, false, "Studio Bianchi", 0xFF2E9E8F.toInt()),
            CalendarInstance("Dinner with Anna", at(today, 20, 30), at(today, 22), false, "Trattoria da Mario", 0xFFC4568A.toInt()),
            CalendarInstance(
                "Holiday", tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                tomorrow.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, null, 0xFFD9822B.toInt(),
            ),
            CalendarInstance("Flight to Rome", at(tomorrow, 9, 15), at(tomorrow, 10, 30), false, "Linate Airport", 0xFF3B82C4.toInt()),
        )
        // Negative ids: tapping them logs instead of calling
        FavoriteContacts.demo.value = listOf("Anna Verdi", "Luca Bianchi", "Mamma", "Marco Neri", "Sara Gialli", "Ufficio")
            .mapIndexed { index, name -> Contact(-1L - index, name, photo = null, starred = true) }
        EventLog.log("Debug: pretend calendar events and favorites")
    }

    fun clear() {
        CalendarEvents.demo.value = null
        FavoriteContacts.demo.value = null
    }
}
