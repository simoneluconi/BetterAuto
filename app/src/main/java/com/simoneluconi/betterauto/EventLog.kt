package com.simoneluconi.betterauto

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** Protocol log shown in the app and mirrored to logcat (tag "BetterAuto"). */
object EventLog {
    private const val TAG = "BetterAuto"
    private const val MAX_LINES = 500
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val lines = ArrayDeque<String>()
    private val lineListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String?) -> Unit>()

    /** Name of the connected head unit, null when idle. */
    @Volatile
    var status: String? = null
        set(value) {
            field = value
            statusListeners.forEach { it(value) }
        }

    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${LocalTime.now().format(timeFormat)} $message"
        synchronized(lines) {
            lines.addLast(line)
            if (lines.size > MAX_LINES) lines.removeFirst()
        }
        lineListeners.forEach { it(line) }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListeners(onLine: (String) -> Unit, onStatus: (String?) -> Unit) {
        lineListeners += onLine
        statusListeners += onStatus
    }

    fun removeListeners(onLine: (String) -> Unit, onStatus: (String?) -> Unit) {
        lineListeners -= onLine
        statusListeners -= onStatus
    }
}
