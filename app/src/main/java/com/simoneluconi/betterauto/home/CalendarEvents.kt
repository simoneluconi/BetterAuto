package com.simoneluconi.betterauto.home

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The phone's calendars, read through the calendar provider (every account the phone syncs). */
object CalendarEvents {
    /** Pretend events (debug), shown instead of the real ones for screenshots without personal data. */
    val demo = MutableStateFlow<List<CalendarInstance>?>(null)

    fun canRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The calendars the phone's calendar app shows, by name. */
    suspend fun calendars(context: Context): List<PhoneCalendar> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val result = ArrayList<PhoneCalendar>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
                ),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2) ?: continue
                    result += PhoneCalendar(c.getLong(0), name, c.getString(2).orEmpty(), if (c.isNull(3)) null else c.getInt(3))
                }
            }
        }.onFailure { EventLog.log("Calendar: calendars query failed: $it") }
        result
    }

    /**
     * Occurrences overlapping [from]..[to] (epoch ms) in visible calendars except [hidden] (ids), without the
     * ones declined or cancelled.
     */
    suspend fun instances(context: Context, from: Long, to: Long, hidden: List<String> = emptyList()): List<CalendarInstance> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val result = ArrayList<CalendarInstance>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION, Instances.DISPLAY_COLOR),
                "${Instances.VISIBLE} = 1 AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}" +
                    " AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})" +
                    // Ids only (numbers, from our own settings), so they can go in the query as they are
                    hidden.mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }
                        ?.joinToString(",", " AND ${Instances.CALENDAR_ID} NOT IN (", ")").orEmpty(),
                null, "${Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    result += CalendarInstance(
                        title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "",
                        begin = c.getLong(1),
                        end = c.getLong(2),
                        allDay = c.getInt(3) == 1,
                        location = c.getString(4)?.takeIf { it.isNotBlank() },
                        color = if (c.isNull(5)) null else c.getInt(5),
                    )
                }
            }
        }.onFailure { EventLog.log("Calendar: query failed: $it") }
        result
    }
}
